package com.dev.auth_server.security;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.annotation.Transactional;

import com.dev.auth_server.audit.AuditService;

import java.time.Duration;
import java.util.List;

@Slf4j
@Transactional
public class ReuseDetectingOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private static final String USED_REFRESH_TOKEN_PREFIX = "used_rt:";
    private static final Duration USED_TOKEN_TTL = Duration.ofDays(8);

    private final JdbcOAuth2AuthorizationService delegate;
    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final AuditService auditService;

    public ReuseDetectingOAuth2AuthorizationService(
            JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository,
            RedisTemplate<String, String> redisTemplate,
            AuditService auditService) {

        this.delegate = new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.auditService = auditService;
    }

    @Override
    public void save(OAuth2Authorization authorization) {

        OAuth2Authorization existing = delegate.findById(authorization.getId());

        if (existing != null) {
            OAuth2Authorization.Token<?> oldRefreshToken = existing.getRefreshToken();
            OAuth2Authorization.Token<?> newRefreshToken = authorization.getRefreshToken();

            if (newRefreshToken != null) {
                log.warn("REFRESH TOKEN = {} | LENGTH = {}",
                        newRefreshToken.getToken().getTokenValue(),
                        newRefreshToken.getToken().getTokenValue().length());
            }

            // 🔥 ROTATION DETECTION
            if (oldRefreshToken != null && newRefreshToken != null) {

                String oldTokenValue = oldRefreshToken.getToken().getTokenValue();
                String newTokenValue = newRefreshToken.getToken().getTokenValue();

                if (!oldTokenValue.equals(newTokenValue)) {

                    String redisKey = USED_REFRESH_TOKEN_PREFIX + hashToken(oldTokenValue);

                    redisTemplate.opsForValue().set(
                            redisKey,
                            authorization.getPrincipalName(),
                            USED_TOKEN_TTL);

                    log.info("Marked refresh token as used for user '{}'",
                            authorization.getPrincipalName());

                    // AUDIT: token rotation
                    auditService.log(
                            "TOKEN_ROTATION",
                            authorization.getPrincipalName(),
                            authorization.getRegisteredClientId(),
                            "Refresh token rotated");
                }
            }
        }

        // AUDIT: token issued (covers login + refresh)
        auditService.log(
                "TOKEN_ISSUED",
                authorization.getPrincipalName(),
                authorization.getRegisteredClientId(),
                "Access + refresh token issued");

        delegate.save(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    @Nullable
    public OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {

        // REUSE DETECTION
        if (tokenType != null && OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {

            String redisKey = USED_REFRESH_TOKEN_PREFIX + hashToken(token);
            String principalName = redisTemplate.opsForValue().get(redisKey);

            if (principalName != null) {

                log.warn(" REFRESH TOKEN REUSE DETECTED for user '{}'", principalName);

                // AUDIT: reuse attack
                auditService.log(
                        "REFRESH_TOKEN_REUSE",
                        principalName,
                        "unknown-client",
                        "Reuse detected → all sessions revoked");

                revokeAllUserTokens(principalName);

                return null;
            }
        }

        return delegate.findByToken(token, tokenType);
    }

    private void revokeAllUserTokens(String principalName) {
        log.warn("STARTING REVOCATION FOR USER: {}", principalName);

        // 1. SET THE USER-LEVEL KILL SWITCH IMMEDIATELY
        String userKey = "revoked_user:" + principalName;
        redisTemplate.opsForValue().set(userKey, "revoked", Duration.ofMinutes(30));
        log.warn("USER REVOKED IN REDIS: {}", userKey);

        // 2. NOW BLACKLIST INDIVIDUAL JTIs
        try {
            List<String> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM oauth2_authorization WHERE principal_name = ?",
                    String.class,
                    principalName);

            for (String id : ids) {
                OAuth2Authorization auth = delegate.findById(id);
                if (auth != null) {
                    var accessToken = auth.getAccessToken();
                    if (accessToken != null) {
                        Object jtiObj = accessToken.getClaims().get("jti");
                        if (jtiObj != null) {
                            String jti = jtiObj.toString();
                            redisTemplate.opsForValue().set("blacklisted_jti:" + jti, "revoked",
                                    Duration.ofMinutes(30));
                            log.warn("BLACKLISTED JTI: {}", jti);
                        }
                    }
                    delegate.remove(auth);
                }
            }
        } catch (Exception e) {
            log.error("Batch JTI blacklisting failed, but user-level revocation is active: {}", e.getMessage());
        }
    }

    private String hashToken(String token) {
        return DigestUtils.sha256Hex(token);
    }
}