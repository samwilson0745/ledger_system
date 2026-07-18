package com.soham.ledger.service;

import com.soham.ledger.domain.Account;
import com.soham.ledger.repository.AccountRepository;
import com.soham.ledger.repository.TransactionRepository;
import com.soham.ledger.web.dto.ReconciliationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the ledger's conservation invariant: nothing is created or
 * destroyed by transfers, only moved. Checks both a global sum and, for
 * defense in depth, a per-account recomputation from completed transaction
 * history — a bug that corrupts one account while accidentally balancing
 * the books globally would still be caught here.
 */
@Service
public class ReconciliationService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public ReconciliationService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile() {
        List<Account> accounts = accountRepository.findAll();

        BigDecimal totalCurrent = BigDecimal.ZERO;
        BigDecimal totalExpected = BigDecimal.ZERO;
        List<ReconciliationResponse.AccountDiscrepancy> discrepancies = new ArrayList<>();

        for (Account account : accounts) {
            totalCurrent = totalCurrent.add(account.getBalance());
            totalExpected = totalExpected.add(account.getInitialBalance());

            BigDecimal outgoing = transactionRepository.sumCompletedOutgoing(account.getId());
            BigDecimal incoming = transactionRepository.sumCompletedIncoming(account.getId());
            BigDecimal expectedBalance = account.getInitialBalance().subtract(outgoing).add(incoming);

            BigDecimal diff = account.getBalance().subtract(expectedBalance);
            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                discrepancies.add(new ReconciliationResponse.AccountDiscrepancy(
                        account.getId(), account.getBalance(), expectedBalance, diff));
            }
        }

        BigDecimal globalDiscrepancy = totalCurrent.subtract(totalExpected);
        boolean passed = globalDiscrepancy.compareTo(BigDecimal.ZERO) == 0 && discrepancies.isEmpty();

        return new ReconciliationResponse(
                passed,
                totalCurrent,
                totalExpected,
                globalDiscrepancy,
                discrepancies,
                Instant.now()
        );
    }
}
