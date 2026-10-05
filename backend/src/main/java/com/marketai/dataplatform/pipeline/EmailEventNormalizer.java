package com.marketai.dataplatform.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.dataplatform.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Schema {@code email-parsed-v1}: what the email parsers extracted from a notification. An email
 * is a signal, so everything here becomes a candidate, never a fact. Only investment events are
 * mapped; spending, card and cash-flow items are outside the ledger's scope and yield nothing.
 */
@Component
public class EmailEventNormalizer implements RecordNormalizer {

    public static final String SCHEMA = "email-parsed-v1";
    /** The parsed-email types that describe an investment event the canonical ledger records. */
    public static final Set<String> SUPPORTED = Set.of("TRADE_BUY", "TRADE_SELL", "MF_SIP", "MF_REDEEM", "DIVIDEND",
        "FD_OPEN", "RD_OPEN", "DEPOSIT_INTEREST", "DEPOSIT_CLOSE", "CORPORATE_ACTION");

    private final ObjectMapper mapper;

    public EmailEventNormalizer(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public boolean supports(String schemaVersion) { return SCHEMA.equals(schemaVersion); }

    @Override
    public List<NormalizedTransaction> transactions(RawRecord raw, SourceType sourceType, String provider) {
        JsonNode n;
        try { n = mapper.readTree(raw.payload()); }
        catch (Exception e) { throw new IllegalArgumentException("payload is not valid JSON"); }
        String kind = Parse.text(n, "type");
        if (kind == null || !SUPPORTED.contains(kind)) return List.of();

        NormalizedTransaction.NormalizedTransactionBuilder b = NormalizedTransaction.builder()
            .sourceType(sourceType).sourceProvider(provider)
            .sourceReference(Parse.text(n, "tradeReference"))
            .recordConfidence(conf(n))
            .linkGroup(Parse.text(n, "linkGroup"))
            .notes(trunc(Parse.text(n, "sourceDescription"), 480));
        LocalDate tradeDate = Parse.date(n, "tradeDate", "startDate");

        switch (kind) {
            case "TRADE_BUY", "TRADE_SELL" -> {
                String exch = Parse.text(n, "exchange");
                String sym = Parse.text(n, "symbol");
                b.type(kind.equals("TRADE_BUY") ? TransactionType.BUY : TransactionType.SELL)
                    .account(new AccountRef(firstNonNull(Parse.text(n, "provider"), exch, "Broker"),
                        dematId(n), AccountType.DEMAT))
                    .asset(new AssetRef(AssetClass.STOCK, sym == null ? null : sym.trim().toUpperCase(), Parse.text(n, "isin"), sym, null))
                    .transactionDate(tradeDate).quantity(Parse.decimal(n, "quantity")).unitPrice(Parse.decimal(n, "price"))
                    .fees(Parse.decimal(n, "charges"));
            }
            case "MF_SIP", "MF_REDEEM" -> {
                boolean redeem = kind.equals("MF_REDEEM");
                boolean legged = Parse.text(n, "linkGroup") != null;
                String desc = Parse.text(n, "sourceDescription");
                BigDecimal units = Parse.decimal(n, "units");
                BigDecimal nav = Parse.decimal(n, "nav");
                BigDecimal amount = Parse.decimal(n, "amount");
                if (units == null && nav != null && amount != null && nav.signum() > 0)
                    units = amount.divide(nav, 4, RoundingMode.HALF_UP);
                TransactionType t = redeem ? (legged ? TransactionType.SWITCH_OUT : TransactionType.REDEMPTION)
                    : legged ? TransactionType.SWITCH_IN
                    : desc != null && desc.toLowerCase().contains("sip") ? TransactionType.SIP : TransactionType.BUY;
                b.type(t)
                    .account(new AccountRef(firstNonNull(Parse.text(n, "provider"), "Mutual fund"), Parse.text(n, "folio"), AccountType.MF_FOLIO))
                    .asset(new AssetRef(AssetClass.MUTUAL_FUND, null, Parse.text(n, "isin"), Parse.text(n, "fundName"), null))
                    .transactionDate(tradeDate).quantity(units).unitPrice(nav).grossAmount(amount);
            }
            case "DIVIDEND" -> {
                BigDecimal paid = Parse.decimal(n, "amount");
                BigDecimal tds = Parse.decimal(n, "tds");
                String sym = Parse.text(n, "symbol");
                b.type(TransactionType.DIVIDEND)
                    .account(new AccountRef(firstNonNull(Parse.text(n, "provider"), "Broker"), dematId(n), AccountType.DEMAT))
                    .asset(sym == null ? null : new AssetRef(AssetClass.STOCK, sym.trim().toUpperCase(), Parse.text(n, "isin"), sym, null))
                    .transactionDate(tradeDate).netAmount(paid)
                    .grossAmount(paid == null ? null : tds == null ? paid : paid.add(tds)).taxes(tds);
            }
            case "FD_OPEN", "RD_OPEN" -> {
                boolean rd = kind.equals("RD_OPEN");
                String bank = Parse.text(n, "bank");
                BigDecimal amt = rd ? Parse.decimal(n, "monthlyAmount") : Parse.decimal(n, "principal");
                String start = tradeDate == null ? "" : tradeDate.toString();
                String key = (rd ? "RD:" : "FD:") + (bank == null ? "" : bank.trim().toUpperCase()) + ":" + (amt == null ? "" : amt.stripTrailingZeros().toPlainString()) + ":" + start;
                b.type(TransactionType.FD_CREATION)
                    .account(new AccountRef(bank, null, rd ? AccountType.RD_ACCOUNT : AccountType.FD_ACCOUNT))
                    .asset(new AssetRef(rd ? AssetClass.RD : AssetClass.FD, key, null, (bank == null ? "" : bank + " ") + (rd ? "RD" : "FD"), null))
                    .transactionDate(tradeDate).grossAmount(amt).netAmount(amt)
                    .interestRate(Parse.decimal(n, "rate")).maturityDate(Parse.date(n, "maturityDate"));
            }
            case "DEPOSIT_INTEREST", "DEPOSIT_CLOSE" -> {
                boolean close = kind.equals("DEPOSIT_CLOSE");
                BigDecimal paid = Parse.decimal(n, "amount");
                BigDecimal tds = Parse.decimal(n, "tds");
                String bank = Parse.text(n, "bank");
                b.type(close ? TransactionType.FD_MATURITY : TransactionType.INTEREST)
                    .account(new AccountRef(bank, null, "RD".equals(Parse.text(n, "instrumentKind")) ? AccountType.RD_ACCOUNT : AccountType.FD_ACCOUNT))
                    .transactionDate(tradeDate).netAmount(paid).taxes(tds)
                    .grossAmount(paid == null ? null : tds == null || close ? paid : paid.add(tds));
            }
            case "CORPORATE_ACTION" -> {
                String action = Parse.text(n, "corporateAction");
                String sym = Parse.text(n, "symbol");
                TransactionType t = "BONUS".equals(action) ? TransactionType.BONUS
                    : "SPLIT".equals(action) ? TransactionType.SPLIT : TransactionType.MERGER;
                b.type(t)
                    .account(new AccountRef(firstNonNull(Parse.text(n, "provider"), "Broker"), dematId(n), AccountType.DEMAT))
                    .asset(new AssetRef(AssetClass.STOCK, sym == null ? null : sym.trim().toUpperCase(), Parse.text(n, "isin"), sym, null))
                    .transactionDate(tradeDate).ratioFrom(Parse.decimal(n, "ratioFrom")).ratioTo(Parse.decimal(n, "ratioTo"))
                    .newSymbol(Parse.text(n, "newSymbol") == null ? null : Parse.text(n, "newSymbol").trim().toUpperCase())
                    .quantity(Parse.decimal(n, "quantity"));
            }
            default -> { return List.of(); }
        }
        return List.of(AmountMath.complete(b.build()));
    }

    /** The demat account identity, when the email states one: DP id plus client id. */
    private static String dematId(JsonNode n) {
        String dp = Parse.text(n, "dpId"), client = Parse.text(n, "clientId");
        return client == null ? null : (dp == null ? "" : dp) + client;
    }

    private static double conf(JsonNode n) {
        BigDecimal c = Parse.decimal(n, "extractionConfidence");
        return c == null ? 0.8 : Math.max(0, Math.min(1, c.doubleValue()));
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... v) {
        for (T t : v) if (t != null) return t;
        return null;
    }

    private static String trunc(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }
}
