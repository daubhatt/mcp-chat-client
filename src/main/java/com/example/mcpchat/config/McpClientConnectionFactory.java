package com.example.mcpchat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.time.Duration;

@Component
@Slf4j
@RequiredArgsConstructor
public class McpClientConnectionFactory {

    private final ObjectMapper objectMapper;

    public McpSyncClient createNewConnection(String serverUrl, String jwtToken) {
        // Logic to create and return an MCP client
        log.info("Configuring MCP client for server: {}", serverUrl);
        try {
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(serverUrl)
                    .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + jwtToken))
                    .objectMapper(objectMapper)
                    .build();
            McpSyncClient asyncClient = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(120))
                    .build();
            asyncClient.initialize();
            return asyncClient;

        } catch (Exception e) {
            log.error("Failed to configure MCP client", e);
            throw new RuntimeException("MCP client configuration failed", e);
        }
    }
}
