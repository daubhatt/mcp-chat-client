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
public class CustomerSession {
    private String customerId;
    private String displayName;
    private LocalDateTime lastActiveAt;
    private List<ConversationSummary> conversations;
    private String currentConversationId;
}