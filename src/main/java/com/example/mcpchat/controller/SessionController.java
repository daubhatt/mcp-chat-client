package com.example.mcpchat.controller;

import com.example.mcpchat.service.McpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
@Validated
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
public class SessionController {

    private final McpService mcpService;

    @GetMapping("/mcp/status/{customerId}")
    public ResponseEntity<Map<String, Object>> getUserMcpStatus(@PathVariable String customerId) {
        log.debug("Getting MCP status for customer: {}", customerId);

        Map<String, Object> globalStatus = mcpService.getMcpStatus();

        // Add user-specific status
        globalStatus.put("userConnected", mcpService.isConnectedForUser(customerId));
        globalStatus.put("customerId", customerId);

        return ResponseEntity.ok(globalStatus);
    }

    @PostMapping("/mcp/connect/{customerId}")
    public ResponseEntity<Map<String, String>> connectUserMcp(@PathVariable String customerId , @AuthenticationPrincipal Jwt jwt) {
        log.debug("Connecting MCP for customer: {}", customerId);

        try {
            // This will create or reconnect the user's MCP session
            mcpService.getClientForUser(customerId, jwt.getTokenValue());

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "MCP session connected for user: " + customerId
            ));
        } catch (Exception e) {
            log.error("Failed to connect MCP for customer: {}", customerId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "error",
                            "message", "Failed to connect MCP: " + e.getMessage()
                    ));
        }
    }

    @PostMapping("/mcp/disconnect/{customerId}")
    public ResponseEntity<Map<String, String>> disconnectUserMcp(@PathVariable String customerId) {
        log.debug("Disconnecting MCP for customer: {}", customerId);

        try {
            mcpService.closeUserSession(customerId);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "MCP session disconnected for user: " + customerId
            ));
        } catch (Exception e) {
            log.error("Failed to disconnect MCP for customer: {}", customerId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "status", "error",
                            "message", "Failed to disconnect MCP: " + e.getMessage()
                    ));
        }
    }
}