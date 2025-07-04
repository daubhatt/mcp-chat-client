package com.example.mcpchat.controller;

import com.example.mcpchat.dto.ChatRequest;
import com.example.mcpchat.dto.ChatResponse;
import com.example.mcpchat.dto.CustomerSession;
import com.example.mcpchat.dto.MessageDTO;
import com.example.mcpchat.service.ChatService;
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

    @PostMapping("/message")
    public ResponseEntity<ChatResponse> sendMessage(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        String customerId = jwt.getSubject();
        log.debug("Received chat message from customer: {}", customerId);

        try {
            // Update customer activity
            chatService.updateCustomerActivity(customerId);

            // Set the customer ID from JWT
            request.setCustomerId(customerId);

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

    @GetMapping("/session")
    public ResponseEntity<CustomerSession> getCustomerSession(@AuthenticationPrincipal Jwt jwt) {
        String customerId = jwt.getSubject();
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
            @AuthenticationPrincipal Jwt jwt) {
        String customerId = jwt.getSubject();
        log.debug("Deleting conversation: {} for customer: {}", conversationId, customerId);

        try {
            chatService.deleteConversation(customerId, conversationId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getCustomerSummary(@AuthenticationPrincipal Jwt jwt) {
        String customerId = jwt.getSubject();
        log.debug("Getting customer summary for: {}", customerId);

        try {
            Map<String, Object> summary = chatService.getCustomerSummary(customerId, jwt.getTokenValue());
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Error getting customer summary", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}