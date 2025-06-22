package com.example.mcpchat.dto;

import io.modelcontextprotocol.client.McpAsyncClient;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
public class UserMcpSession {

    private final String userId;
    @Setter
    private McpAsyncClient client;
    @Setter
    private volatile boolean connected = false;
    private LocalDateTime lastAccessed;

    public UserMcpSession(String userId) {
        this.userId = userId;
        this.lastAccessed = LocalDateTime.now();
    }

    public void updateLastAccessed() {
        this.lastAccessed = LocalDateTime.now();
    }
}
