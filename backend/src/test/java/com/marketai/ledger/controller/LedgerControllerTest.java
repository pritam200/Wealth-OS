package com.marketai.ledger.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.auth.entity.User;
import com.marketai.ledger.dto.CashAccountResponse;
import com.marketai.ledger.dto.LedgerTransferResponse;
import com.marketai.ledger.entity.CashAccount;
import com.marketai.ledger.entity.LedgerTransfer;
import com.marketai.ledger.repository.CashAccountRepository;
import com.marketai.ledger.service.LedgerTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for a live-verified bug: this controller used to return CashAccount /
 * LedgerTransfer JPA entities directly. Each entity carries a {@code User user} FK, which (a)
 * serialized the user's bcrypt password hash straight into the response body, and (b) could
 * throw a 500 (HttpMessageConversionException on a ByteBuddy Hibernate proxy) once the entity
 * came back from a query rather than being the exact object handed in earlier in the request.
 * Now the controller only ever returns response DTOs built from scalar fields.
 */
class LedgerControllerTest {

    private LedgerTransferService transferService;
    private CashAccountRepository accountRepo;
    private LedgerController controller;
    private User user;

    @BeforeEach
    void setUp() {
        transferService = mock(LedgerTransferService.class);
        accountRepo = mock(CashAccountRepository.class);
        controller = new LedgerController(transferService, accountRepo);

        user = User.builder().id(1L).name("Test User").email("test@example.com")
            .password("$2a$12$verysecrethash").build();
    }

    private CashAccount account() {
        return CashAccount.builder().id(10L).user(user).name("HDFC Savings").bank("HDFC")
            .accountType("SAVINGS").balance(new BigDecimal("50000")).asOf(LocalDate.of(2026, 9, 23))
            .active(true).build();
    }

    @Test
    void accountsResponseNeverContainsTheUserOrItsPassword() throws Exception {
        when(accountRepo.findByUser_IdAndActiveTrueOrderByNameAsc(1L)).thenReturn(List.of(account()));

        List<CashAccountResponse> body = controller.accounts(user).getBody();

        assertThat(body).hasSize(1);
        assertThat(body.get(0).getName()).isEqualTo("HDFC Savings");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(body);
        assertThat(json).doesNotContain("verysecrethash").doesNotContain("\"user\"");
    }

    @Test
    void addAccountReturnsADtoNotTheEntity() {
        LedgerController.CashAccountRequest req = new LedgerController.CashAccountRequest();
        req.setName("ICICI Savings");
        req.setBalance(new BigDecimal("20000"));
        when(accountRepo.save(any())).thenAnswer(i -> {
            CashAccount a = i.getArgument(0);
            a.setId(11L);
            return a;
        });

        CashAccountResponse resp = controller.addAccount(user, req).getBody();

        assertThat(resp.getId()).isEqualTo(11L);
        assertThat(resp.getName()).isEqualTo("ICICI Savings");
    }

    @Test
    void transferResponseMapsAccountNamesWithoutExposingEntities() throws Exception {
        CashAccount source = account();
        CashAccount destination = CashAccount.builder().id(20L).user(user).name("m.Stock Wallet")
            .accountType("WALLET").balance(BigDecimal.ZERO).active(true).build();
        LedgerTransfer transfer = LedgerTransfer.builder()
            .id(99L).user(user).sourceAccount(source).destinationAccount(destination)
            .destinationType("STOCK").destinationRef("m.Stock")
            .amount(new BigDecimal("15000")).transferDate(LocalDate.of(2026, 9, 23))
            .applied(true).createdAt(LocalDateTime.of(2026, 9, 23, 12, 0))
            .build();
        when(transferService.record(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(transfer);

        LedgerController.TransferRequest req = new LedgerController.TransferRequest();
        req.setDestinationType("STOCK");
        req.setDestinationRef("m.Stock");
        req.setAmount(new BigDecimal("15000"));

        LedgerTransferResponse resp = controller.transfer(user, req).getBody();

        assertThat(resp.getSourceAccountName()).isEqualTo("HDFC Savings");
        assertThat(resp.getDestinationAccountName()).isEqualTo("m.Stock Wallet");
        assertThat(resp.getAmount()).isEqualByComparingTo("15000");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(resp);
        assertThat(json).doesNotContain("verysecrethash").doesNotContain("\"user\"");
    }
}
