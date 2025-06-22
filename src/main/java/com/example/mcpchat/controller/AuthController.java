package com.example.mcpchat.controller;

import com.example.mcpchat.dto.CustomerSession;
import com.example.mcpchat.dto.LoginRequest;
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

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
public class AuthController {

    private final ChatService chatService;

    @PostMapping("/login")
    public ResponseEntity<CustomerSession> login(@Valid @RequestBody LoginRequest request, @AuthenticationPrincipal Jwt jwt) {
        log.info("Login attempt for username: {}, {}", request.getUsername(), jwt.getSubject());
        try {
            // Get or create customer session
            CustomerSession session = chatService.getCustomerSession(request.getUsername());

            if (session == null) {
                // Create new customer by sending a dummy message
                log.debug("Creating new customer: {}", request.getUsername());
                // This will create the customer record
                chatService.updateCustomerActivity(request.getUsername());
                session = chatService.getCustomerSession(request.getUsername());
            }

            if (session == null) {
                // Create minimal session for new customer
                session = CustomerSession.builder()
                        .customerId(request.getUsername())
                        .displayName(request.getDisplayName() != null ? request.getDisplayName() : request.getUsername())
                        .conversations(List.of())
                        .build();
            }

            log.debug("Login successful for customer: {}", request.getUsername());
            return ResponseEntity.ok(session);

        } catch (Exception e) {
            log.error("Login failed for username: {}", request.getUsername(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestParam String customerId) {
        log.debug("Logout for customer: {}", customerId);

        try {
            // Update last active time
            chatService.updateCustomerActivity(customerId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Error during logout for customer: {}", customerId, e);
            return ResponseEntity.ok().build(); // Don't fail logout on error
        }
    }
}