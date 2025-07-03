package com.example.mcpchat.service;

import com.example.mcpchat.config.McpClientConnectionFactory;
import com.example.mcpchat.dto.UserMcpSession;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpService {

    @Value("${app.mcp.session.cleanup-interval:300}")
    private int sessionCleanupIntervalSeconds;

    @Value("${app.mcp.session.max-idle-time:300}")
    private int maxIdleTimeSeconds;

    @Value("${spring.ai.mcp.clients.banking-server.url}")
    private String bankingServerUrl;

    private final McpClientConnectionFactory mcpClientConnectionFactory;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    // User-specific MCP sessions
    private final Map<String, UserMcpSession> userSessions = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        startSessionCleanup();
        startHeartbeatCall();
    }

    private void startHeartbeatCall() {
        WebClient.builder().baseUrl(bankingServerUrl)
                .build().get()
                .uri("/heartbeat")
                .exchangeToFlux(response -> response.bodyToFlux(String.class))
                .subscribe();
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up MCP service");

        // Close all user sessions
        userSessions.values().forEach(this::closeUserSession);
        userSessions.clear();

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get or create MCP client for a specific user
     */
    public McpAsyncClient getClientForUser(String userId, String jwtToken) {

        UserMcpSession session = userSessions.computeIfAbsent(userId, key -> createUserSession(key, jwtToken));
        session.updateLastAccessed();

        if (session.getClient() == null || !session.isConnected()) {
            log.info("Creating new MCP connection for user: {}", userId);
            reconnectUserSession(session);
        }

        return session.getClient();
    }

    /**
     * Get available tools for a specific user
     */
    public Mono<List<McpSchema.Tool>> getAvailableToolsForUser(String userId, String jwtToken) {

        // Get tools from user's specific client
        UserMcpSession session = userSessions.get(userId);
        if (session == null) {
            log.warn("No MCP session found for user: {}", userId);
            session = createUserSession(userId, jwtToken);
        }
        if (session.isConnected() && session.getClient() != null) {
            return getSessionTools(session);
        }
        return Mono.empty();
    }

    private Mono<List<McpSchema.Tool>> getSessionTools(UserMcpSession session) {
        AtomicReference<UserMcpSession> sessionRef = new AtomicReference<>(session);

        return Mono.defer(() -> {
                    // Client is resolved fresh on each attempt
                    return sessionRef.get().getClient().listTools()
                            .map(McpSchema.ListToolsResult::tools);
                })
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .doBeforeRetry(retrySignal -> {
                            log.warn("Retry attempt {} for listTools", retrySignal.totalRetries() + 1);
                            UserMcpSession currentSession = sessionRef.get();
                            currentSession.setConnected(false);
                            UserMcpSession newSession = reconnectUserSession(currentSession);
                            sessionRef.set(newSession);
                        })
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                                new RuntimeException("Failed to get session tools after retries", retrySignal.failure())
                        )
                );
    }

    /**
     * Check if MCP is connected for a specific user
     */
    public boolean isConnectedForUser(String userId) {
        UserMcpSession session = userSessions.get(userId);
        return session != null && session.isConnected();
    }

    /**
     * Close session for a specific user
     */
    public void closeUserSession(String userId) {
        UserMcpSession session = userSessions.remove(userId);
        if (session != null) {
            closeUserSession(session);
            log.info("Closed MCP session for user: {}", userId);
        }
    }

    /**
     * Close a user session
     */
    private void closeUserSession(UserMcpSession session) {
        try {
            if (session.getClient() != null) {
                session.getClient().close();
            }
        } catch (Exception e) {
            log.warn("Error closing MCP client for user: {}", session.getUserId(), e);
        }
        session.setConnected(false);
        session.setClient(null);
    }

    /**
     * Create a new user session
     */
    private UserMcpSession createUserSession(String userId, String jwtToken) {
        log.debug("Creating new MCP session for user: {}", userId);
        UserMcpSession session = new UserMcpSession(userId, jwtToken);
        reconnectUserSession(session);
        return session;
    }

    /**
     * Reconnect a user session
     */
    private UserMcpSession reconnectUserSession(UserMcpSession session) {
        if (!session.isConnected()) {
            try {
                log.debug("Attempting to connect MCP client for user: {}",
                        session.getUserId());

                McpAsyncClient client = mcpClientConnectionFactory.createNewConnection(bankingServerUrl, session.getJwtToken());
                session.setClient(client);
                session.setConnected(true);
                session.updateLastAccessed();
                log.info("Successfully connected MCP client for user: {}", session.getUserId());
            } catch (Exception e) {
                log.warn("Failed to connect MCP client for user: {}: {}",
                        session.getUserId(), e.getMessage());
            }
        }
        return session;
    }

    /**
     * Start periodic cleanup of idle sessions
     */
    private void startSessionCleanup() {
        scheduler.scheduleWithFixedDelay(() -> {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(maxIdleTimeSeconds);

            List<String> toRemove = userSessions.entrySet().stream()
                    .filter(entry -> entry.getValue().getLastAccessed().isBefore(cutoff))
                    .map(Map.Entry::getKey)
                    .toList();

            if (!toRemove.isEmpty()) {
                log.info("Cleaning up {} idle MCP sessions", toRemove.size());
                toRemove.forEach(this::closeUserSession);
            }

            // Health check for remaining sessions
            userSessions.values().forEach(session -> {
                if (session.isConnected() && session.getClient() != null) {
                    session.getClient().listTools()
                            .doOnError(ex -> {
                                log.warn("Health check failed for user: {}", session.getUserId());
                                session.setConnected(false);
                            })
                            .subscribe();
                }
            });

        }, sessionCleanupIntervalSeconds, sessionCleanupIntervalSeconds, TimeUnit.SECONDS);
    }
}