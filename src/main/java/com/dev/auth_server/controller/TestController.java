package com.dev.auth_server.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TestController {

    @GetMapping("/test")
    public String test(Authentication authentication) {
        return "Hello " + authentication.getPrincipal().toString();
    }

    @GetMapping("/user")
    @PreAuthorize("hasRole('USER')")
    public String userEndpoint(Authentication auth) {
        return "Hello USER " + auth.getName();
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminEndpoint(Authentication auth) {
        return "Hello ADMIN " + auth.getName();
    }

}