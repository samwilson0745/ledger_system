package com.soham.ledger.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReconciliationResponse(
        boolean passed,
        BigDecimal totalCurrentBalance,
        BigDecimal totalExpectedBalance,
        BigDecimal globalDiscrepancy,
        List<AccountDiscrepancy> accountDiscrepancies,
        Instant checkedAt
) {
    public record AccountDiscrepancy(
            java.util.UUID accountId,
            BigDecimal actualBalance,
            BigDecimal expectedBalance,
            BigDecimal discrepancy
    ) {
    }
}
