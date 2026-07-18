package com.soham.ledger.web;

import com.soham.ledger.service.TransferExecutionResult;
import com.soham.ledger.service.TransferService;
import com.soham.ledger.web.dto.TransferRequest;
import com.soham.ledger.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        TransferExecutionResult result = transferService.transfer(
                request.fromAccountId(), request.toAccountId(), request.amount(), request.idempotencyKey());

        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TransferResponse.from(result.transaction(), result.replayed()));
    }

    @GetMapping("/transfers/{id}")
    public TransferResponse getTransfer(@PathVariable UUID id) {
        return TransferResponse.from(transferService.getTransaction(id), false);
    }
}
