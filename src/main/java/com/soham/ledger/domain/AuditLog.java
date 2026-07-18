package com.soham.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Insert-only audit trail entry. No setters, no update paths — rows are
 * immutable once persisted, matching the append-only ledger guarantee.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String entityType;

    @Column(nullable = false)
    private UUID entityId;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String beforeState;

    @Column(nullable = false)
    private String afterState;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    protected AuditLog() {
    }

    public AuditLog(String entityType, UUID entityId, String action, String beforeState, String afterState, String actor) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.actor = actor;
        this.timestamp = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getAction() {
        return action;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public String getActor() {
        return actor;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
