package com.dev.auth_server.service;

import com.dev.auth_server.dto.RegisterRequest;
import com.dev.auth_server.dto.UserResponse;

import com.dev.auth_server.model.User;
import com.dev.auth_server.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public void register(RegisterRequest request) {

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .roles(Set.of("USER"))
                .build();

        userRepository.save(user);
    }

    public List<UserResponse> getAllUsers() {

        return userRepository.findAll().stream()
                .map(user -> UserResponse.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .roles(user.getRoles())
                        .build())
                .toList();
    }
}