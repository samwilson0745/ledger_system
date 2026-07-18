package com.soham.ledger.service;

import com.soham.ledger.domain.AuditLog;
import com.soham.ledger.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AuditLogService {

    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void recordBalanceChange(UUID accountId, String action, BigDecimal before, BigDecimal after) {
        auditLogRepository.save(new AuditLog(
                "ACCOUNT",
                accountId,
                action,
                "{\"balance\":\"" + before + "\"}",
                "{\"balance\":\"" + after + "\"}",
                SYSTEM_ACTOR
        ));
    }

    public void recordCreation(UUID accountId, BigDecimal startingBalance) {
        auditLogRepository.save(new AuditLog(
                "ACCOUNT",
                accountId,
                "CREATE",
                "{}",
                "{\"balance\":\"" + startingBalance + "\"}",
                SYSTEM_ACTOR
        ));
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getLogsForAccount(UUID accountId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampAsc("ACCOUNT", accountId);
    }
}
