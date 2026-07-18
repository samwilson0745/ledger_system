package com.soham.ledger.service;

import com.soham.ledger.domain.Account;
import com.soham.ledger.repository.AccountRepository;
import com.soham.ledger.web.exception.AccountNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;

    public AccountService(AccountRepository accountRepository, AuditLogService auditLogService) {
        this.accountRepository = accountRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public Account createAccount(String ownerName, String currency, BigDecimal startingBalance) {
        Account account = new Account(ownerName, currency, startingBalance);
        Account saved = accountRepository.save(account);
        auditLogService.recordCreation(saved.getId(), startingBalance);
        return saved;
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    @Transactional(readOnly = true)
    public List<Account> listAccounts() {
        return accountRepository.findAll();
    }
}
