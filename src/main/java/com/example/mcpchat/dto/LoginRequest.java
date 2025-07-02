package com.example.mcpchat.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {
    // Username will be extracted from JWT, no longer required in request
    private String displayName;
}