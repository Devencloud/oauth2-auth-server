package com.dev.auth_server.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    @Async
    public void log(String eventType, String principalName, String clientId, String details) {
        try {
            AuditEvent event = AuditEvent.builder()
                    .eventType(eventType)
                    .principalName(principalName)
                    .clientId(clientId)
                    .details(details)
                    .createdAt(Instant.now())
                    .build();
            auditEventRepository.save(event);
            log.info("AUDIT: {} | user={} | client={} | details={}",
                    eventType, principalName, clientId, details);
        } catch (Exception e) {
            log.error("Failed to save audit event", e);
        }
    }
}