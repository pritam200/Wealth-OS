package com.marketai.ledger.controller;

import com.marketai.auth.entity.User;
import com.marketai.ledger.dto.CashAccountResponse;
import com.marketai.ledger.dto.LedgerTransferResponse;
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
import java.util.stream.Collectors;

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
    public ResponseEntity<List<CashAccountResponse>> accounts(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(accountRepo.findByUser_IdAndActiveTrueOrderByNameAsc(user.getId())
            .stream().map(this::toResponse).collect(Collectors.toList()));
    }

    @PostMapping("/accounts")
    public ResponseEntity<CashAccountResponse> addAccount(@AuthenticationPrincipal User user,
                                                  @RequestBody CashAccountRequest req) {
        return ResponseEntity.ok(toResponse(accountRepo.save(CashAccount.builder()
            .user(user)
            .name(req.getName())
            .bank(req.getBank())
            .lastFour(req.getLastFour())
            .accountType(req.getAccountType() != null ? req.getAccountType() : "SAVINGS")
            .balance(req.getBalance() != null ? req.getBalance() : BigDecimal.ZERO)
            .asOf(req.getAsOf() != null ? req.getAsOf() : LocalDate.now())
            .active(true)
            .build())));
    }

    /** Explicit balance correction — reconciling against a statement, not inferred from email. */
    @PutMapping("/accounts/{id}/balance")
    public ResponseEntity<CashAccountResponse> setBalance(@AuthenticationPrincipal User user,
                                                  @PathVariable Long id,
                                                  @RequestBody Map<String, BigDecimal> body) {
        CashAccount account = accountRepo.findByIdAndUser_Id(id, user.getId())
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Account not found"));
        account.setBalance(body.get("balance"));
        account.setAsOf(LocalDate.now());
        return ResponseEntity.ok(toResponse(accountRepo.save(account)));
    }

    @GetMapping("/transfers")
    public ResponseEntity<List<LedgerTransferResponse>> transfers(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(transferService.list(user.getId())
            .stream().map(this::toResponse).collect(Collectors.toList()));
    }

    @PostMapping("/transfers")
    public ResponseEntity<LedgerTransferResponse> transfer(@AuthenticationPrincipal User user,
                                                   @RequestBody TransferRequest req) {
        return ResponseEntity.ok(toResponse(transferService.record(user,
            req.getSourceAccountId(), req.getDestinationAccountId(),
            req.getDestinationType(), req.getDestinationRef(),
            req.getAmount(), req.getTransferDate(), req.getNote(), null)));
    }

    // Never return CashAccount/LedgerTransfer entities directly — both carry a `User user` FK,
    // and serializing that risks either leaking sensitive User fields or throwing on an
    // uninitialized Hibernate proxy once the request's persistence context is gone (reproduced
    // live as a 500 on this exact endpoint before this fix).
    private CashAccountResponse toResponse(CashAccount a) {
        return CashAccountResponse.builder()
            .id(a.getId()).name(a.getName()).bank(a.getBank()).lastFour(a.getLastFour())
            .accountType(a.getAccountType()).balance(a.getBalance()).asOf(a.getAsOf())
            .active(Boolean.TRUE.equals(a.getActive()))
            .build();
    }

    private LedgerTransferResponse toResponse(LedgerTransfer t) {
        return LedgerTransferResponse.builder()
            .id(t.getId())
            .sourceAccountId(t.getSourceAccount() != null ? t.getSourceAccount().getId() : null)
            .sourceAccountName(t.getSourceAccount() != null ? t.getSourceAccount().getName() : null)
            .destinationAccountId(t.getDestinationAccount() != null ? t.getDestinationAccount().getId() : null)
            .destinationAccountName(t.getDestinationAccount() != null ? t.getDestinationAccount().getName() : null)
            .destinationType(t.getDestinationType()).destinationRef(t.getDestinationRef())
            .amount(t.getAmount()).transferDate(t.getTransferDate()).note(t.getNote())
            .applied(Boolean.TRUE.equals(t.getApplied())).createdAt(t.getCreatedAt())
            .build();
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
