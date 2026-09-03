package com.marketai.gmail.service;

import com.marketai.auth.entity.User;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.portfolio.dto.AddHoldingRequest;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.tracking.dto.FdRequest;
import com.marketai.tracking.dto.RdRequest;
import com.marketai.tracking.service.TrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The single place a {@link ParsedEmail} — regardless of whether it came from a
 * deterministic regex parser, the AI fallback extractor, or a decrypted PDF attachment —
 * gets booked into the right table (Portfolio/FD/RD/Income/Expense/Card). Extracted out of
 * GmailSyncService so the AI and PDF import paths reuse this routing instead of duplicating it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParsedEmailImporter {

    private final TrackingService trackingService;
    private final PortfolioService portfolioService;
    private final com.marketai.income.repository.IncomeRepository incomeRepo;
    private final com.marketai.expense.repository.ExpenseRepository expenseRepo;
    private final com.marketai.card.repository.CreditCardRepository cardRepo;
    private final com.marketai.tracking.repository.FixedDepositRepository fdRepo;
    private final com.marketai.tracking.repository.RecurringDepositRepository rdRepo;

    public void importParsedEmail(Long userId, User user, ParsedEmail pe) throws Exception {
        importParsedEmail(userId, user, pe, null);
    }

    public void importParsedEmail(Long userId, User user, ParsedEmail pe, String gmailMessageId) throws Exception {
        switch (pe.getType()) {
            case TRADE_BUY:
            case TRADE_SELL:
                importTrade(userId, user, pe);
                break;
            case FD_OPEN:
                String fdBank = pe.getBank() != null ? pe.getBank() : "Bank";
                LocalDate fdStart = pe.getStartDate() != null ? pe.getStartDate() : LocalDate.now();
                if (pe.getPrincipal() != null && fdRepo.existsByUser_IdAndBankAndPrincipalAndStartDate(userId, fdBank, pe.getPrincipal(), fdStart)) {
                    log.debug("Skipping duplicate FD: {} ₹{} on {}", fdBank, pe.getPrincipal(), fdStart);
                    break;
                }
                FdRequest fdReq = new FdRequest();
                fdReq.setBank(fdBank);
                fdReq.setPrincipal(pe.getPrincipal());
                fdReq.setRate(pe.getRate() != null ? pe.getRate() : BigDecimal.valueOf(7.0));
                fdReq.setCompounding(pe.getCompounding() != null ? pe.getCompounding() : "quarterly");
                fdReq.setAutoRenew(false);
                fdReq.setStartDate(fdStart);
                fdReq.setMaturityDate(pe.getMaturityDate());
                com.marketai.tracking.dto.FdResponse newFd = trackingService.addFd(userId, fdReq, user);
                // A bank "new FD opened" email is exactly what a renewal looks like from the
                // mailbox's point of view — there is no separate "renewed" notification to key
                // off, so every FD_OPEN is checked against the user's other FDs at the same
                // bank for a maturity/amount match. See TrackingService.detectAndLinkRenewal
                // for the tolerances; an unmatched FD is simply left as a fresh one.
                trackingService.detectAndLinkRenewal(userId, newFd.getId());
                break;
            case RD_OPEN:
                String rdBank = pe.getBank() != null ? pe.getBank() : "Bank";
                LocalDate rdStart = pe.getStartDate() != null ? pe.getStartDate() : LocalDate.now();
                if (pe.getMonthlyAmount() != null && rdRepo.existsByUser_IdAndBankAndMonthlyAmountAndStartDate(userId, rdBank, pe.getMonthlyAmount(), rdStart)) {
                    log.debug("Skipping duplicate RD: {} ₹{}/mo on {}", rdBank, pe.getMonthlyAmount(), rdStart);
                    break;
                }
                RdRequest rdReq = new RdRequest();
                rdReq.setBank(rdBank);
                rdReq.setMonthlyAmount(pe.getMonthlyAmount());
                rdReq.setRate(pe.getRate() != null ? pe.getRate() : BigDecimal.valueOf(7.0));
                rdReq.setStartDate(rdStart);
                rdReq.setTenureMonths(pe.getTenureMonths() != null ? pe.getTenureMonths() : 12);
                trackingService.addRd(userId, rdReq, user);
                break;
            case MF_SIP:
            case MF_REDEEM:
                importMf(userId, user, pe);
                break;
            case DIVIDEND:
                importDividend(userId, pe, gmailMessageId);
                break;
            case INCOME:
                importIncome(userId, pe, gmailMessageId);
                break;
            case EXPENSE:
                importExpense(userId, pe, gmailMessageId);
                break;
            case CARD_BILL:
                applyCardBill(userId, pe);
                break;
            default:
                break;
        }
    }

    private void importDividend(Long userId, ParsedEmail pe, String gmailMessageId) {
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();
        String company = pe.getSymbol();
        String shortName = company != null ? company.replaceAll("(?i)\\s*(Limited|Ltd\\.?|Industries|Corporation)\\s*", " ").trim() : null;
        String desc = shortName != null ? shortName + " Dividend" : "Dividend";

        if (isDuplicateIncome(userId, pe.getAmount(), date, desc, gmailMessageId)) {
            log.debug("Skipping duplicate dividend: {} on {} for ₹{}", desc, date, pe.getAmount());
            return;
        }
        incomeRepo.save(com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description(desc)
            .amount(pe.getAmount())
            .source("Dividend")
            .incomeDate(date)
            .payer(company)
            .paymentMethod(pe.getPaymentMethod())
            .sourceEmailId(gmailMessageId)
            .note("Auto-imported from email")
            .build());
    }

    private void importIncome(Long userId, ParsedEmail pe, String gmailMessageId) {
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();
        String payer = pe.getMerchant();
        String source = pe.getIncomeSource() != null ? pe.getIncomeSource() : "Other";
        String desc = payer != null ? source + " from " + payer : source;
        if (pe.getSourceDescription() != null && pe.getSourceDescription().length() > desc.length()) {
            desc = pe.getSourceDescription();
        }

        if (isDuplicateIncome(userId, pe.getAmount(), date, desc, gmailMessageId)) {
            log.debug("Skipping duplicate income: {} on {} for ₹{}", desc, date, pe.getAmount());
            return;
        }
        incomeRepo.save(com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description(desc)
            .amount(pe.getAmount())
            .source(source)
            .incomeDate(date)
            .payer(payer)
            .paymentMethod(pe.getPaymentMethod())
            .sourceEmailId(gmailMessageId)
            .note("Auto-imported from email")
            .build());
    }

    private void importExpense(Long userId, ParsedEmail pe, String gmailMessageId) {
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();
        String merchant = pe.getMerchant();
        String desc = merchant != null ? merchant
            : (pe.getCategory() != null ? pe.getCategory() + " spend" : "Expense");

        if (isDuplicateExpense(userId, pe.getAmount(), date, merchant, gmailMessageId)) {
            log.debug("Skipping duplicate expense: {} on {} for ₹{}", desc, date, pe.getAmount());
            return;
        }
        expenseRepo.save(com.marketai.expense.entity.Expense.builder()
            .userId(userId)
            .description(desc)
            .amount(pe.getAmount())
            .category(com.marketai.expense.entity.ExpenseCategory.fromLabel(pe.getCategory()))
            .expenseDate(date)
            .merchant(merchant)
            .paymentMethod(pe.getPaymentMethod())
            .sourceEmailId(gmailMessageId)
            .note("Auto-imported from email")
            .build());
    }

    private boolean isDuplicateExpense(Long userId, BigDecimal amount, LocalDate date, String merchant, String gmailMessageId) {
        if (amount == null || date == null) return false;
        List<com.marketai.expense.entity.Expense> existing =
            expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, date, date);
        for (com.marketai.expense.entity.Expense e : existing) {
            if (gmailMessageId != null && gmailMessageId.equals(e.getSourceEmailId())) return true;
            if (e.getAmount().compareTo(amount) == 0) {
                if (merchant != null && merchant.equalsIgnoreCase(e.getMerchant())) return true;
                if (merchant != null && merchant.equalsIgnoreCase(e.getDescription())) return true;
            }
        }
        return false;
    }

    private boolean isDuplicateIncome(Long userId, BigDecimal amount, LocalDate date, String desc, String gmailMessageId) {
        if (amount == null || date == null) return false;
        List<com.marketai.income.entity.Income> existing =
            incomeRepo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(userId, date, date);
        for (com.marketai.income.entity.Income i : existing) {
            if (gmailMessageId != null && gmailMessageId.equals(i.getSourceEmailId())) return true;
            if (i.getAmount().compareTo(amount) == 0 && desc != null && desc.equalsIgnoreCase(i.getDescription())) return true;
            if (i.getAmount().compareTo(amount) == 0 && "Dividend".equals(i.getSource())
                    && desc != null && desc.toLowerCase().contains("dividend")) return true;
        }
        return false;
    }

    private void applyCardBill(Long userId, ParsedEmail pe) {
        List<com.marketai.card.entity.CreditCard> matches = new ArrayList<>();
        if (pe.getCardLast4() != null) {
            matches = cardRepo.findByUserIdAndLastFour(userId, pe.getCardLast4());
        }
        if (matches.isEmpty() && pe.getBank() != null) {
            matches = cardRepo.findByUserIdAndIssuerIgnoreCase(userId, pe.getBank());
        }
        if (matches.isEmpty()) return; // no saved card to attach the bill to
        com.marketai.card.entity.CreditCard card = matches.get(0);
        card.setCurrentDue(pe.getAmount());
        card.setCurrentDueDate(pe.getDueDate());
        cardRepo.save(card);
    }

    private Portfolio getOrCreatePortfolio(Long userId, User user) throws Exception {
        List<Portfolio> portfolios = portfolioService.getUserPortfolios(userId);
        if (!portfolios.isEmpty()) return portfolios.get(0);
        return portfolioService.createPortfolio(userId, "My Portfolio", "Auto-created");
    }

    private void importTrade(Long userId, User user, ParsedEmail pe) throws Exception {
        Portfolio portfolio = getOrCreatePortfolio(userId, user);
        String exchange = pe.getExchange() != null ? pe.getExchange() : "NSE";
        String symbol = pe.getSymbol() + ("BSE".equalsIgnoreCase(exchange) ? ".BO" : ".NS");
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();
        BigDecimal quantity = BigDecimal.valueOf(pe.getQuantity());

        if (portfolioService.isDuplicateTrade(portfolio.getId(), symbol, date, quantity, pe.getPrice())) {
            log.debug("Skipping duplicate trade: {} {} @ {} on {}", symbol, pe.getQuantity(), pe.getPrice(), date);
            return;
        }

        if (pe.getType() == ParsedEmail.Type.TRADE_SELL) {
            Long holdingId = portfolioService.findHoldingId(portfolio.getId(), symbol);
            if (holdingId != null) {
                portfolioService.sellHolding(portfolio.getId(), holdingId, userId,
                    quantity, pe.getPrice(), incomeRepo);
                log.info("Imported SELL: {} x {} @ ₹{}", symbol, quantity, pe.getPrice());
            } else {
                log.warn("SELL trade for {} but no holding found — recording as transaction only", symbol);
                importSellAsTransaction(portfolio.getId(), userId, symbol, pe, date, quantity);
            }
            return;
        }

        AddHoldingRequest req = new AddHoldingRequest();
        req.setSymbol(symbol);
        req.setName(pe.getSymbol());
        req.setQuantity(quantity);
        req.setPrice(pe.getPrice());
        req.setTransactionDate(date);
        req.setCharges(BigDecimal.ZERO);
        req.setBroker(pe.getExchange());

        portfolioService.addHolding(portfolio.getId(), userId, req);
    }

    private void importSellAsTransaction(Long portfolioId, Long userId, String symbol,
            ParsedEmail pe, LocalDate date, BigDecimal quantity) {
        BigDecimal saleValue = pe.getPrice().multiply(quantity);
        String desc = "Sale of " + symbol.replace(".NS", "").replace(".BO", "");
        if (isDuplicateIncome(userId, saleValue, date, desc, null)) {
            log.debug("Skipping duplicate sell-as-income: {} on {} for ₹{}", desc, date, saleValue);
            return;
        }
        incomeRepo.save(com.marketai.income.entity.Income.builder()
            .userId(userId)
            .description(desc)
            .amount(saleValue)
            .source("Capital Gain")
            .incomeDate(date)
            .note("Sold " + quantity.stripTrailingZeros().toPlainString() + " units @ ₹" + pe.getPrice() + " (holding not found)")
            .build());
    }

    private void importMf(Long userId, User user, ParsedEmail pe) throws Exception {
        if (com.marketai.common.util.FinancialDataValidator.looksLikeUnverifiableFundName(pe.getFundName())) {
            log.warn("REJECTED MF import — fund name '{}' looks like mis-parsed header/boilerplate text, not a real scheme. " +
                "Source: {}. Refusing to create unverified holding.", pe.getFundName(), pe.getSourceDescription());
            return;
        }

        Portfolio portfolio = getOrCreatePortfolio(userId, user);

        BigDecimal units = pe.getUnits() != null ? pe.getUnits() :
                (pe.getNav() != null && pe.getAmount() != null ? pe.getAmount().divide(pe.getNav(), 4, java.math.RoundingMode.HALF_UP) : BigDecimal.ONE);
        BigDecimal nav = pe.getNav() != null ? pe.getNav() : pe.getAmount();

        String symbol;
        if (pe.getFundName() != null) {
            String clean = pe.getFundName().toUpperCase().replaceAll("[^A-Z0-9]", "");
            symbol = clean.substring(0, Math.min(30, clean.length())) + ".MF";
        } else {
            symbol = "MFSIP.MF";
        }
        LocalDate date = pe.getTradeDate() != null ? pe.getTradeDate() : LocalDate.now();

        if (portfolioService.isDuplicateTrade(portfolio.getId(), symbol, date, units, nav != null ? nav : BigDecimal.ONE)) {
            log.debug("Skipping duplicate MF import: {} {} units @ {} on {}", symbol, units, nav, date);
            return;
        }

        AddHoldingRequest req = new AddHoldingRequest();
        req.setSymbol(symbol);
        req.setName(pe.getFundName() != null ? pe.getFundName() : "Mutual Fund SIP");
        req.setQuantity(units);
        req.setPrice(nav != null ? nav : BigDecimal.ONE);
        req.setTransactionDate(date);
        req.setCharges(BigDecimal.ZERO);
        req.setBroker(pe.getProvider());
        req.setFolio(pe.getFolio());

        portfolioService.addHolding(portfolio.getId(), userId, req);
    }
}
