package com.soham.ledger.web;

import com.soham.ledger.service.AuditLogService;
import com.soham.ledger.web.dto.AuditLogResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/audit-logs/{accountId}")
    public List<AuditLogResponse> getAuditLogs(@PathVariable UUID accountId) {
        return auditLogService.getLogsForAccount(accountId).stream().map(AuditLogResponse::from).toList();
    }
}
