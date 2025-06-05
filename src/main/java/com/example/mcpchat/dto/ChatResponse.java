package com.example.mcpchat.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatResponse {
    private String conversationId;
    private String response;
    private LocalDateTime timestamp;
    private List<ToolCallResult> toolCalls;
    private String messageId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ToolCallResult {
        private String toolName;
        private String parameters;
        private String result;
        private boolean success;
        private String error;
    }
}