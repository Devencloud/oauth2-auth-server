package com.dev.auth_server.controller;

import com.dev.auth_server.dto.RegisterRequest;
import com.dev.auth_server.dto.UserResponse;

import com.dev.auth_server.service.AuthService;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;



@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @GetMapping("/api/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserResponse> getAllUsers() {
        return authService.getAllUsers();
    }

    @PostMapping("/auth/register")
    public String register(@RequestBody RegisterRequest request) {
        authService.register(request);
        return "User registered successfully";
    }
}