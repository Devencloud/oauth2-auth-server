package com.dev.auth_server.config;

import com.dev.auth_server.audit.AuditService;
import com.dev.auth_server.repository.UserRepository;

import com.dev.auth_server.security.ReuseDetectingOAuth2AuthorizationService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.stream.Collectors;

import org.springframework.security.authentication.dao.DaoAuthenticationProvider;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.core.io.Resource;

@Configuration
public class OAuth2AuthorizationServerConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            DaoAuthenticationProvider daoAuthProvider) throws Exception {

        http.securityMatcher("/oauth2/**");

        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults());

        http
                .authenticationProvider(daoAuthProvider)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }
    // No RegisteredClientRepository bean – use properties only

    @Bean
    public JWKSource<SecurityContext> jwkSource(
            @Value("${spring.security.oauth2.authorizationserver.keystore.location}") String keystoreLocation,
            @Value("${spring.security.oauth2.authorizationserver.keystore.password}") String keystorePassword,
            @Value("${spring.security.oauth2.authorizationserver.keystore.alias}") String keystoreAlias) {
        try {
            Resource resource = new ClassPathResource(keystoreLocation.replace("classpath:", ""));
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(resource.getInputStream(), keystorePassword.toCharArray());

            RSAPrivateKey privateKey = (RSAPrivateKey) keyStore.getKey(keystoreAlias, keystorePassword.toCharArray());
            RSAPublicKey publicKey = (RSAPublicKey) ((java.security.cert.X509Certificate) keyStore
                    .getCertificate(keystoreAlias)).getPublicKey();

            RSAKey rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keystoreAlias)
                    .build();

            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load keystore", ex);
        }
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer("http://localhost:9000")
                .build();
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(
            UserRepository userRepository) {
        return context -> {
            if (context.getTokenType().equals(OAuth2TokenType.ACCESS_TOKEN)) {
                // 1. ADD THIS LINE: Generate a unique ID for every token
                context.getClaims().claim("jti", java.util.UUID.randomUUID().toString());

                String username = context.getPrincipal().getName();
                System.out.println(">>> tokenCustomizer invoked for user: " + username);
                userRepository.findByEmail(username).ifPresent(user -> {
                    List<String> roles = user.getRoles().stream()
                            .map(role -> "ROLE_" + role)
                            .collect(Collectors.toList());

                    context.getClaims()
                            .claim("roles", roles)
                            .claim("email", username);
                });
            }
        };
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository,
            RedisTemplate<String, String> redisTemplate,
            AuditService auditService) {

        return new ReuseDetectingOAuth2AuthorizationService(
                jdbcTemplate,
                registeredClientRepository,
                redisTemplate,
                auditService);
    }
}