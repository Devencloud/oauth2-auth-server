package com.dev.auth_server.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @PostMapping("/login")
    public String loginPost() {
        // Spring Security intercepts this before it reaches here
        // but the mapping must exist to prevent 405
        return "redirect:/login?error";
    }
}