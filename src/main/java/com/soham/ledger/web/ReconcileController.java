package com.soham.ledger.web;

import com.soham.ledger.service.ReconciliationService;
import com.soham.ledger.web.dto.ReconciliationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconcileController {

    private final ReconciliationService reconciliationService;

    public ReconcileController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/reconcile")
    public ReconciliationResponse reconcile() {
        return reconciliationService.reconcile();
    }
}
