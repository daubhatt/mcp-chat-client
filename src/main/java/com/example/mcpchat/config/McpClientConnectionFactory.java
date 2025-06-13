package com.example.mcpchat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
@Slf4j
@RequiredArgsConstructor
public class McpClientConnectionFactory {

    private final ObjectMapper objectMapper;

    public McpAsyncClient createNewConnection(String serverUrl) {
        // Logic to create and return an MCP client
        log.info("Configuring MCP client for server: {}", serverUrl);
        try {
            WebFluxSseClientTransport transport = WebFluxSseClientTransport.builder(WebClient.builder().baseUrl(serverUrl))
                    .objectMapper(objectMapper)
                    .build();
            McpAsyncClient asyncClient = McpClient.async(transport)
                    .requestTimeout(Duration.ofSeconds(120))
                    .build();
            asyncClient.initialize().subscribe();
            return asyncClient;

        } catch (Exception e) {
            log.error("Failed to configure MCP client", e);
            throw new RuntimeException("MCP client configuration failed", e);
        }
    }
}
