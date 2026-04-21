package com.dev.auth_server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class FormLoginConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain formLoginFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/login", "/login/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .permitAll()
            )
            .csrf(csrf -> csrf.disable());
        return http.build();
    }
}