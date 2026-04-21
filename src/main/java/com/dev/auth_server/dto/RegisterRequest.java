package com.dev.auth_server.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    private String email;
    private String password;
}