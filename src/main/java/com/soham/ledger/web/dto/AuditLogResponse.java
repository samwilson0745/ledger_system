package com.soham.ledger.web.dto;

import com.soham.ledger.domain.AuditLog;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        String entityType,
        UUID entityId,
        String action,
        String beforeState,
        String afterState,
        String actor,
        Instant timestamp
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getEntityType(),
                log.getEntityId(),
                log.getAction(),
                log.getBeforeState(),
                log.getAfterState(),
                log.getActor(),
                log.getTimestamp()
        );
    }
}
