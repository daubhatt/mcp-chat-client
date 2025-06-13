package com.example.mcpchat.service;

import com.example.mcpchat.config.McpClientConnectionFactory;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpService {

    @Value("${app.mcp.custom-server.enabled:true}")
    private boolean mcpEnabled;

    @Value("${app.mcp.custom-server.reconnect-attempts:3}")
    private int reconnectAttempts;

    @Value("${app.mcp.custom-server.reconnect-delay:5}")
    private int reconnectDelaySeconds;

    @Value("${spring.ai.mcp.clients.banking-server.url}")
    private String bankingServerUrl;

    private final McpClientConnectionFactory mcpClientConnectionFactory;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile boolean isConnected = false;
    private volatile List<McpSchema.Tool> availableTools = new ArrayList<>();
    @Getter
    private McpAsyncClient bankingClient;

    @PostConstruct
    public void initialize() {
        if (mcpEnabled) {
            log.info("Initializing MCP service");
            bankingClient = mcpClientConnectionFactory.createNewConnection(bankingServerUrl);
            connectToMcpServer();
            startHealthCheck();
            //startHeartbeatCall();
        } else {
            log.info("MCP service is disabled");
        }
    }

    @PreDestroy
    public void cleanup() {
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

    public List<McpSchema.Tool> getAvailableTools() {
        return new ArrayList<>(availableTools);
    }

    private void connectToMcpServer() {
        int attempts = 0;
        while (attempts < reconnectAttempts && !isConnected) {
            try {
                log.debug("Attempting to connect to MCP server (attempt {})", attempts + 1);

                loadAvailableTools();

                isConnected = true;
                log.info("Successfully connected to MCP server");
                break;

            } catch (Exception e) {
                attempts++;
                log.warn("Failed to connect to MCP server (attempt {}): {}", attempts, e.getMessage());

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

        if (!isConnected) {
            log.error("Failed to connect to MCP server after {} attempts", reconnectAttempts);
        }
    }

    private void loadAvailableTools() {
        bankingClient.listTools()
                .map(McpSchema.ListToolsResult::tools)
                .doOnNext(toolLists -> {
                    synchronized (this) {
                        availableTools = new ArrayList<>(toolLists);
                    }
                    log.info("Loaded {} tools from MCP server", availableTools.size());
                }).doOnError(e -> {
                    log.error("Failed to load MCP tools", e);
                    bankingClient = mcpClientConnectionFactory.createNewConnection(bankingServerUrl);
                    connectToMcpServer();
                }).subscribe();
    }

    private void startHealthCheck() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (isConnected) {
                // Perform a simple health check by listing tools
                bankingClient.listTools()
                        .doOnError(ex -> {
                            log.warn("MCP health check failed: {}", ex.getMessage());
                            isConnected = false;
                        })
                        .doOnSuccess(result -> log.debug("MCP health check passed"))
                        .subscribe();
            } else {
                log.debug("MCP not connected, attempting reconnection");
                connectToMcpServer();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void startHeartbeatCall() {
        WebClient.builder().baseUrl(bankingServerUrl)
                .build().get()
                .uri("/heartbeat")
                .exchangeToFlux(response -> response.bodyToFlux(String.class))
                .doOnNext(msg -> {
                    log.info("Received heartbeat message: {}", msg);
                    this.isConnected = true;
                })
                .doOnError(err -> {
                    log.error("Failed to connect to MCP server", err);
                    this.isConnected = false;
                })
                .subscribe();
    }

    public boolean isConnected() {
        return isConnected && mcpEnabled;
    }

    public Map<String, Object> getMcpStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("connected", isConnected);
        status.put("enabled", mcpEnabled);
        status.put("toolCount", availableTools.size());
        status.put("tools", availableTools.stream()
                .map(tool -> Map.of(
                        "name", tool.name(),
                        "description", tool.description()
                ))
                .toList());
        return status;
    }

    public void refreshTools() {
        if (isConnected()) {
            log.info("Refreshing MCP tools");
            loadAvailableTools();
        }
    }

    public void reconnect() {
        log.info("Manual reconnection requested");
        isConnected = false;
        availableTools.clear();
        connectToMcpServer();
    }

}