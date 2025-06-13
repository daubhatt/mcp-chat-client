package com.example.mcpchat.dto;

import com.example.mcpchat.entity.ChatMessage;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageDTO {
    private Long id;
    private ChatMessage.MessageType messageType;
    private String content;
    private LocalDateTime createdAt;
    private String metadata;
    private String toolCalls;

    public static MessageDTO fromEntity(ChatMessage message) {
        return MessageDTO.builder()
                .id(message.getId())
                .messageType(message.getMessageType())
                .content(message.getContent())
                .createdAt(message.getCreatedAt())
                .metadata(message.getMetadata())
                .toolCalls(message.getToolCalls())
                .build();
    }
}