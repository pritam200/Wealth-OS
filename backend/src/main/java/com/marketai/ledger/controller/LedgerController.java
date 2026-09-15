package com.marketai.ledger.controller;

import com.marketai.auth.entity.User;
import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.ledger.repository.CashAccountRepository;
import com.marketai.ledger.service.LedgerTransferService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerTransferService transferService;
    private final CashAccountRepository accountRepo;

    @Data
    public static class CashAccountRequest {
        private String name;
        private String bank;
        private String lastFour;
        private String accountType;
        private BigDecimal balance;
        private LocalDate asOf;
    }

    @Data
    public static class TransferRequest {
        private Long sourceAccountId;
        private Long destinationAccountId;
        private String destinationType;   // CASH_ACCOUNT | MUTUAL_FUND | STOCK | FD | RD | EPF | EXTERNAL
        private String destinationRef;
        private BigDecimal amount;
        private LocalDate transferDate;
        private String note;
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<CashAccount>> accounts(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(accountRepo.findByUser_IdAndActiveTrueOrderByNameAsc(user.getId()));
    }

    @PostMapping("/accounts")
    public ResponseEntity<CashAccount> addAccount(@AuthenticationPrincipal User user,
                                                  @RequestBody CashAccountRequest req) {
        return ResponseEntity.ok(accountRepo.save(CashAccount.builder()
            .user(user)
            .name(req.getName())
            .bank(req.getBank())
            .lastFour(req.getLastFour())
            .accountType(req.getAccountType() != null ? req.getAccountType() : "SAVINGS")
            .balance(req.getBalance() != null ? req.getBalance() : BigDecimal.ZERO)
            .asOf(req.getAsOf() != null ? req.getAsOf() : LocalDate.now())
            .active(true)
            .build()));
    }

    /** Explicit balance correction — reconciling against a statement, not inferred from email. */
    @PutMapping("/accounts/{id}/balance")
    public ResponseEntity<CashAccount> setBalance(@AuthenticationPrincipal User user,
                                                  @PathVariable Long id,
                                                  @RequestBody Map<String, BigDecimal> body) {
        CashAccount account = accountRepo.findByIdAndUser_Id(id, user.getId())
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Account not found"));
        account.setBalance(body.get("balance"));
        account.setAsOf(LocalDate.now());
        return ResponseEntity.ok(accountRepo.save(account));
    }

    @GetMapping("/transfers")
    public ResponseEntity<List<LedgerTransfer>> transfers(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(transferService.list(user.getId()));
    }

    @PostMapping("/transfers")
    public ResponseEntity<LedgerTransfer> transfer(@AuthenticationPrincipal User user,
                                                   @RequestBody TransferRequest req) {
        return ResponseEntity.ok(transferService.record(user,
            req.getSourceAccountId(), req.getDestinationAccountId(),
            req.getDestinationType(), req.getDestinationRef(),
            req.getAmount(), req.getTransferDate(), req.getNote(), null));
    }

    @DeleteMapping("/transfers/{id}")
    public ResponseEntity<Void> deleteTransfer(@AuthenticationPrincipal User user, @PathVariable Long id) {
        transferService.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cash-total")
    public ResponseEntity<Map<String, Object>> cashTotal(@AuthenticationPrincipal User user) {
        Map<String, Object> body = new HashMap<>();
        body.put("totalCash", transferService.totalCash(user.getId()));
        return ResponseEntity.ok(body);
    }
}
