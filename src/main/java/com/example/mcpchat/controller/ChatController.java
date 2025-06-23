package com.example.mcpchat.controller;

import com.example.mcpchat.dto.ChatRequest;
import com.example.mcpchat.dto.ChatResponse;
import com.example.mcpchat.dto.CustomerSession;
import com.example.mcpchat.dto.MessageDTO;
import com.example.mcpchat.service.ChatService;
import com.example.mcpchat.service.McpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Validated
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
public class ChatController {

    private final ChatService chatService;
    private final McpService mcpService;

    @PostMapping("/message")
    public ResponseEntity<ChatResponse> sendMessage(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        log.debug("Received chat message from customer: {}", request.getCustomerId());

        try {
            // Update customer activity
            chatService.updateCustomerActivity(request.getCustomerId());

            // Process the message
            ChatResponse response = chatService.processMessage(request, jwt.getTokenValue());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing chat message", e);
            return ResponseEntity.internalServerError()
                    .body(ChatResponse.builder()
                            .response("I'm sorry, I encountered an error processing your message. Please try again.")
                            .build());
        }
    }

    @GetMapping("/session/{customerId}")
    public ResponseEntity<CustomerSession> getCustomerSession(@PathVariable String customerId, @AuthenticationPrincipal Jwt jwt) {
        log.debug("Getting session for customer: {}", customerId);

        CustomerSession session = chatService.getCustomerSession(customerId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(session);
    }

    @GetMapping("/conversation/{conversationId}/messages")
    public ResponseEntity<List<MessageDTO>> getConversationMessages(@PathVariable String conversationId, @AuthenticationPrincipal Jwt jwt) {
        log.debug("Getting messages for conversation: {}", conversationId);

        List<MessageDTO> messages = chatService.getConversationMessages(conversationId);
        return ResponseEntity.ok(messages);
    }

    @DeleteMapping("/conversation/{conversationId}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable String conversationId,
            @RequestParam String customerId) {
        log.debug("Deleting conversation: {} for customer: {}", conversationId, customerId);

        try {
            chatService.deleteConversation(customerId, conversationId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/mcp/status")
    public ResponseEntity<Map<String, Object>> getMcpStatus(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(mcpService.getMcpStatus());
    }

    @PostMapping("/mcp/reconnect")
    public ResponseEntity<Map<String, String>> reconnectMcp(@AuthenticationPrincipal Jwt jwt) {
        try {
            mcpService.reconnect();
            return ResponseEntity.ok(Map.of("status", "reconnection initiated"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to reconnect: " + e.getMessage()));
        }
    }

    @PostMapping("/mcp/refresh-tools")
    public ResponseEntity<Map<String, Object>> refreshMcpTools(@AuthenticationPrincipal Jwt jwt) {
        try {
            mcpService.refreshTools();
            return ResponseEntity.ok(mcpService.getMcpStatus());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to refresh tools: " + e.getMessage()));
        }
    }
}