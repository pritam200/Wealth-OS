package com.marketai.gmail.service;

import com.marketai.auth.entity.User;
import com.marketai.document.identity.ReferenceHarvester;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.portfolio.dto.AddHoldingRequest;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.service.PortfolioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Two defects in the mutual-fund import path.
 *
 * <b>The crash:</b> units were derived as {@code amount ÷ nav} with only a null check on the
 * NAV. A document carrying "NAV: 0.00" — a malformed statement, a mis-parsed column — divided by
 * zero and threw {@code ArithmeticException} out of the middle of the import, aborting the whole
 * message rather than skipping one transaction.
 *
 * <b>The fabrication:</b> when units could not be derived at all, the code fell back to
 * {@code BigDecimal.ONE} and used the transaction amount as the NAV. The total value came out
 * right, which is why it looked harmless — but both the quantity and the cost basis were
 * invented, and {@code recomputeFromLedger} replays them as fact. One fabricated "1 unit @
 * ₹5,000" mixed with real units yields a nonsense average cost, and nothing downstream can tell
 * which number was made up.
 */
class MfImportGuardTest {

    private PortfolioService portfolioService;
    private com.marketai.tracking.service.TrackingService trackingService;
    private ParsedEmailImporter importer;

    private static final Long USER = 9L;

    @BeforeEach
    void setUp() {
        portfolioService = mock(PortfolioService.class);
        trackingService = mock(com.marketai.tracking.service.TrackingService.class);
        ImportedTransactionFingerprintRepository fpRepo =
            mock(ImportedTransactionFingerprintRepository.class);

        when(fpRepo.existsByUserIdAndFingerprint(any(), anyString())).thenReturn(false);
        when(fpRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(fpRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Portfolio p = new Portfolio();
        p.setId(1L);
        when(portfolioService.getUserPortfolios(USER)).thenReturn(java.util.List.of(p));
        when(portfolioService.isDuplicateTrade(any(), any(), any(), any(), any())).thenReturn(false);

        importer = new ParsedEmailImporter(
            trackingService,
            portfolioService,
            mock(com.marketai.income.repository.IncomeRepository.class),
            mock(com.marketai.expense.repository.ExpenseRepository.class),
            mock(com.marketai.card.repository.CreditCardRepository.class),
            mock(com.marketai.card.repository.CardStatementRepository.class),
            mock(com.marketai.card.repository.CardPaymentRepository.class),
            mock(com.marketai.tracking.repository.FixedDepositRepository.class),
            mock(com.marketai.tracking.repository.RecurringDepositRepository.class),
            new TransactionFingerprinter(), fpRepo, new ReferenceHarvester(),
            mock(TransactionMatchScorer.class), mock(com.marketai.rent.service.RentService.class));
    }

    private ParsedEmail mf(BigDecimal units, BigDecimal nav, BigDecimal amount) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.MF_SIP)
            .fundName("HDFC Flexi Cap Fund")
            .units(units).nav(nav).amount(amount)
            .tradeDate(LocalDate.of(2026, 9, 1))
            .sourceDescription("SIP confirmation")
            .build();
    }

    @Test
    @DisplayName("a zero NAV no longer divides by zero and aborts the import")
    void zeroNavDoesNotThrow() {
        assertThatCode(() -> importer.importParsedEmail(
            USER, new User(), mf(null, BigDecimal.ZERO, new BigDecimal("5000")), "msg-1", null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a zero NAV is rejected rather than imported with invented units")
    void zeroNavIsRejected() throws Exception {
        importer.importParsedEmail(
            USER, new User(), mf(null, BigDecimal.ZERO, new BigDecimal("5000")), "msg-2", null);

        verify(portfolioService, never()).addHolding(any(), any(), any());
    }

    @Test
    @DisplayName("no units and no NAV is rejected, not booked as one unit")
    void underivableUnitsAreRejected() throws Exception {
        importer.importParsedEmail(
            USER, new User(), mf(null, null, new BigDecimal("5000")), "msg-3", null);

        verify(portfolioService, never()).addHolding(any(), any(), any());
    }

    @Test
    @DisplayName("units derived from amount ÷ NAV are imported correctly")
    void derivedUnitsAreImported() throws Exception {
        importer.importParsedEmail(
            USER, new User(), mf(null, new BigDecimal("50.00"), new BigDecimal("5000")), "msg-4", null);

        ArgumentCaptor<AddHoldingRequest> req = ArgumentCaptor.forClass(AddHoldingRequest.class);
        verify(portfolioService).addHolding(any(), any(), req.capture());

        assertThat(req.getValue().getQuantity()).isEqualByComparingTo("100.0000");
        assertThat(req.getValue().getPrice()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("explicit units with no NAV derive a real per-unit price, not the whole amount")
    void navIsDerivedRatherThanBorrowedFromTheAmount() throws Exception {
        // Previously nav fell back to the transaction amount, recording ₹5,000 in a field that
        // means "price per unit" — so the holding's cost basis was 100× its true value.
        importer.importParsedEmail(
            USER, new User(), mf(new BigDecimal("100"), null, new BigDecimal("5000")), "msg-5", null);

        ArgumentCaptor<AddHoldingRequest> req = ArgumentCaptor.forClass(AddHoldingRequest.class);
        verify(portfolioService).addHolding(any(), any(), req.capture());

        assertThat(req.getValue().getPrice()).isEqualByComparingTo("50.0000");
    }

    // --- Trade import: the same class of defect on the equity path ---

    private ParsedEmail trade(String symbol, Integer qty, BigDecimal price) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.TRADE_BUY)
            .symbol(symbol).quantity(qty).price(price)
            .tradeDate(LocalDate.of(2026, 9, 1))
            .sourceDescription("contract note")
            .build();
    }

    @Test
    @DisplayName("a null quantity no longer throws NullPointerException on unboxing")
    void nullQuantityDoesNotThrow() {
        assertThatCode(() -> importer.importParsedEmail(
            USER, new User(), trade("RELIANCE", null, new BigDecimal("1400")), "t-1", null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null symbol is rejected, not booked under the literal string \"null.NS\"")
    void nullSymbolIsRejected() throws Exception {
        // String concatenation on a null symbol produced "null.NS" and created a holding under
        // it, which then took part in ledger replay like any real position.
        importer.importParsedEmail(
            USER, new User(), trade(null, 25, new BigDecimal("1400")), "t-2", null);

        verify(portfolioService, never()).addHolding(any(), any(), any());
    }

    @Test
    @DisplayName("a missing price is rejected rather than dereferenced")
    void missingPriceIsRejected() throws Exception {
        importer.importParsedEmail(
            USER, new User(), trade("RELIANCE", 25, null), "t-3", null);

        verify(portfolioService, never()).addHolding(any(), any(), any());
    }

    @Test
    void aCompleteTradeStillImports() throws Exception {
        importer.importParsedEmail(
            USER, new User(), trade("RELIANCE", 25, new BigDecimal("1400")), "t-4", null);

        ArgumentCaptor<AddHoldingRequest> req = ArgumentCaptor.forClass(AddHoldingRequest.class);
        verify(portfolioService).addHolding(any(), any(), req.capture());

        assertThat(req.getValue().getSymbol()).isEqualTo("RELIANCE.NS");
        assertThat(req.getValue().getQuantity()).isEqualByComparingTo("25");
    }

    @Test
    void zeroOrNegativeQuantityIsRejected() throws Exception {
        importer.importParsedEmail(USER, new User(), trade("RELIANCE", 0, new BigDecimal("1400")), "t-5", null);
        importer.importParsedEmail(USER, new User(), trade("RELIANCE", -5, new BigDecimal("1400")), "t-6", null);

        verify(portfolioService, never()).addHolding(any(), any(), any());
    }

    // --- Routing and deposit guards ---

    @Test
    @DisplayName("a parsed item with no type is skipped, not routed into a null switch")
    void nullTypeDoesNotThrow() {
        // The fingerprinter called immediately before routeImport already handles a null type,
        // so such an item reaches the switch — where switching on a null enum throws NPE and
        // abandons the entire message.
        ParsedEmail typeless = ParsedEmail.builder()
            .amount(new BigDecimal("100")).sourceDescription("unclassifiable").build();

        assertThatCode(() -> importer.importParsedEmail(USER, new User(), typeless, "n-1", null))
            .doesNotThrowAnyException();
        verifyNoInteractions(portfolioService);
    }

    @Test
    @DisplayName("an FD with no principal is rejected rather than persisted null")
    void fdWithoutPrincipalIsRejected() throws Exception {
        // @NotNull on FdRequest only fires at the controller's @Valid boundary; this path calls
        // the service directly, so a null principal would persist and break every interest and
        // maturity calculation that reads it.
        ParsedEmail fd = ParsedEmail.builder()
            .type(ParsedEmail.Type.FD_OPEN).bank("HDFC Bank")
            .principal(null).startDate(LocalDate.of(2026, 9, 1))
            .sourceDescription("FD advice").build();

        importer.importParsedEmail(USER, new User(), fd, "fd-1", null);

        verify(trackingService, never()).addFd(any(), any(), any());
    }

    @Test
    void rdWithoutMonthlyAmountIsRejected() throws Exception {
        ParsedEmail rd = ParsedEmail.builder()
            .type(ParsedEmail.Type.RD_OPEN).bank("HDFC Bank")
            .monthlyAmount(null).startDate(LocalDate.of(2026, 9, 1))
            .sourceDescription("RD advice").build();

        importer.importParsedEmail(USER, new User(), rd, "rd-1", null);

        verify(trackingService, never()).addRd(any(), any(), any());
    }

    @Test
    void negativeUnitsAreRejected() throws Exception {
        importer.importParsedEmail(
            USER, new User(), mf(new BigDecimal("-5"), new BigDecimal("50"), new BigDecimal("5000")),
            "msg-6", null);

        verify(portfolioService, never()).addHolding(any(), any(), any());
    }

    private ParsedEmail redemption(BigDecimal units, BigDecimal nav) {
        return ParsedEmail.builder()
            .type(ParsedEmail.Type.MF_REDEEM)
            .fundName("HDFC Flexi Cap Fund")
            .units(units).nav(nav).amount(units.multiply(nav))
            .tradeDate(LocalDate.of(2026, 9, 1))
            .sourceDescription("CAMS redemption statement")
            .build();
    }

    /**
     * Regression: MF_SIP and MF_REDEEM were routed to the same handler, which only ever writes a
     * BUY. A 500-unit redemption of a 1,000-unit position therefore left the holding reading
     * 1,500 units, blended the cost basis against the redemption NAV, added the withdrawn money
     * to net worth instead of removing it, and recorded no capital gain at all.
     */
    @Test
    @DisplayName("a redemption reduces the position — it is never imported as a purchase")
    void redemptionSellsRatherThanBuys() throws Exception {
        when(portfolioService.findHoldingId(eq(1L), anyString())).thenReturn(42L);

        importer.importParsedEmail(USER, new User(),
            redemption(new BigDecimal("500"), new BigDecimal("42.50")), "redeem-1", null);

        verify(portfolioService, never()).addHolding(any(), any(), any());
        verify(portfolioService).sellHolding(eq(1L), eq(42L), eq(USER),
            argThat(q -> q.compareTo(new BigDecimal("500")) == 0),
            argThat(p -> p.compareTo(new BigDecimal("42.50")) == 0),
            any());
    }

    @Test
    @DisplayName("a redemption of an untracked fund is refused, not turned into a purchase")
    void redemptionWithNoMatchingHoldingIsRefused() throws Exception {
        when(portfolioService.findHoldingId(eq(1L), anyString())).thenReturn(null);

        importer.importParsedEmail(USER, new User(),
            redemption(new BigDecimal("500"), new BigDecimal("42.50")), "redeem-2", null);

        // Neither side: no invented purchase, and no sale against a position we cannot identify
        // (there would be no cost basis to compute a gain from).
        verify(portfolioService, never()).addHolding(any(), any(), any());
        verify(portfolioService, never()).sellHolding(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aSipIsStillImportedAsAPurchase() throws Exception {
        importer.importParsedEmail(USER, new User(),
            mf(new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("5000")), "sip-1", null);

        verify(portfolioService).addHolding(any(), any(), any());
        verify(portfolioService, never()).sellHolding(any(), any(), any(), any(), any(), any());
    }
}
