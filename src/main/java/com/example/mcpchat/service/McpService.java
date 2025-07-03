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
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpService {

    @Value("${app.mcp.custom-server.reconnect-attempts:3}")
    private int reconnectAttempts;

    @Value("${app.mcp.custom-server.reconnect-delay:5}")
    private int reconnectDelaySeconds;

    @Value("${app.mcp.session.cleanup-interval:300}")
    private int sessionCleanupIntervalSeconds;

    @Value("${app.mcp.session.max-idle-time:300}")
    private int maxIdleTimeSeconds;

    @Value("${app.mcp.global-tools.enabled:false}")
    private boolean globalToolsEnabled;

    @Value("${spring.ai.mcp.clients.banking-server.url}")
    private String bankingServerUrl;

    private final McpClientConnectionFactory mcpClientConnectionFactory;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    // User-specific MCP sessions
    private final Map<String, UserMcpSession> userSessions = new ConcurrentHashMap<>();

    // Global tools cache (shared across users if tools are the same)
    private volatile List<McpSchema.Tool> globalAvailableTools = new ArrayList<>();
    private volatile boolean globalToolsLoaded = false;

    @PostConstruct
    public void initialize() {
        startSessionCleanup();
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
     * Get available tools (uses global cache if enabled, otherwise gets from user's client)
     */
    public List<McpSchema.Tool> getAvailableTools() {
        if (globalToolsEnabled && globalToolsLoaded) {
            return new ArrayList<>(globalAvailableTools);
        }
        // Return empty list if global tools disabled and no specific user context
        return new ArrayList<>();
    }

    /**
     * Get available tools for a specific user
     */
    public List<McpSchema.Tool> getAvailableToolsForUser(String userId, String jwtToken) {
        if (globalToolsEnabled && globalToolsLoaded) {
            return new ArrayList<>(globalAvailableTools);
        }

        // Get tools from user's specific client
        UserMcpSession session = userSessions.get(userId);
        if (session == null) {
            log.warn("No MCP session found for user: {}", userId);
            session = createUserSession(userId, jwtToken);
        }
        if (session.isConnected() && session.getClient() != null) {
            return getSessionTools(session);
        }
        return new ArrayList<>();
    }

    private ArrayList<McpSchema.Tool> getSessionTools(UserMcpSession session) {
        return session.getClient().listTools()
                .map(McpSchema.ListToolsResult::tools)
                .map(ArrayList::new)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .doBeforeRetry(retrySignal -> {
                            log.warn("Retry attempt {} for listTools", retrySignal.totalRetries() + 1);
                            session.setConnected(false);
                            reconnectUserSession(session);
                        })
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                                new RuntimeException("Failed to get session tools after retries", retrySignal.failure())
                        )
                )
                .block();
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
    private void reconnectUserSession(UserMcpSession session) {
        int attempts = 0;
        while (attempts < reconnectAttempts && !session.isConnected()) {
            try {
                log.debug("Attempting to connect MCP client for user: {} (attempt {})",
                        session.getUserId(), attempts + 1);

                McpAsyncClient client = mcpClientConnectionFactory.createNewConnection(bankingServerUrl, session.getJwtToken());
                session.setClient(client);
                session.setConnected(true);
                session.updateLastAccessed();

                log.info("Successfully connected MCP client for user: {}", session.getUserId());
                break;

            } catch (Exception e) {
                attempts++;
                log.warn("Failed to connect MCP client for user: {} (attempt {}): {}",
                        session.getUserId(), attempts, e.getMessage());

                if (attempts < reconnectAttempts) {
                    try {
                        Thread.sleep(reconnectDelaySeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!session.isConnected()) {
            log.error("Failed to connect MCP client for user: {} after {} attempts",
                    session.getUserId(), reconnectAttempts);
        }
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