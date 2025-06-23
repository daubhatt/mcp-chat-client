package com.example.mcpchat.controller;

import com.example.mcpchat.dto.CustomerSession;
import com.example.mcpchat.dto.LoginRequest;
import com.example.mcpchat.service.ChatService;
import com.example.mcpchat.service.McpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
public class AuthController {

    private final ChatService chatService;
    private final McpService mcpService;

    @PostMapping("/login")
    public ResponseEntity<CustomerSession> login(@Valid @RequestBody LoginRequest request, @AuthenticationPrincipal Jwt jwt) {
        log.info("Login request: {}", jwt.getTokenValue());
        String authenticatedUsername = jwt.getSubject();
        try {
            // Get or create customer session
            CustomerSession session = chatService.getCustomerSession(authenticatedUsername);

            if (session == null) {
                // Create new customer
                log.debug("Creating new customer: {}", authenticatedUsername);
                chatService.updateCustomerActivity(authenticatedUsername);
                session = chatService.getCustomerSession(authenticatedUsername);
            }

            if (session == null) {
                // Create minimal session for new customer
                session = CustomerSession.builder()
                        .customerId(authenticatedUsername)
                        .displayName(request.getDisplayName() != null ? request.getDisplayName() : authenticatedUsername)
                        .conversations(List.of())
                        .build();
            }

            // Initialize MCP session for the user
            try {
                mcpService.getClientForUser(authenticatedUsername, jwt.getTokenValue());
                log.debug("MCP session initialized for user: {}", authenticatedUsername);
            } catch (Exception e) {
                log.warn("Failed to initialize MCP session for user: {}", authenticatedUsername, e);
                // Don't fail login if MCP initialization fails
            }

            log.debug("Login successful for customer: {}", authenticatedUsername);
            return ResponseEntity.ok(session);

        } catch (Exception e) {
            log.error("Login failed for username: {}", authenticatedUsername, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestParam(required = false) String customerId, @AuthenticationPrincipal Jwt jwt) {
        // Get the authenticated user from Spring Security context
        String userToLogout = jwt.getSubject();

        log.debug("Logout for customer: {}", userToLogout);

        try {
            // Update last active time
            chatService.updateCustomerActivity(userToLogout);

            // Close MCP session for the user
            try {
                mcpService.closeUserSession(userToLogout);
                log.debug("MCP session closed for user: {}", userToLogout);
            } catch (Exception e) {
                log.warn("Failed to close MCP session for user: {}", userToLogout, e);
                // Don't fail logout if MCP cleanup fails
            }

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Error during logout for customer: {}", userToLogout, e);
            return ResponseEntity.ok().build(); // Don't fail logout on error
        }
    }
}