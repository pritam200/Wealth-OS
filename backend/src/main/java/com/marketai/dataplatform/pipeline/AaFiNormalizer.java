package com.marketai.dataplatform.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.dataplatform.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Schema {@code aa-fi-v1}: a financial-information record as an aggregator delivers it, reduced to
 * the fields the platform needs:
 * <pre>
 * { "fiType": "MUTUAL_FUNDS|EQUITIES|DEPOSIT", "institution": "...", "accountId": "...",
 *   "txn":     { "txnId", "type", "date", "valueDate", "isin", "symbol", "name", "units", "nav",
 *                "amount", "charges", "tax", "narrative", "reversal" },
 *   "holding": { "isin", "symbol", "name", "units", "avgCost", "invested", "price", "value", "asOf" } }
 * </pre>
 * A real provider whose payloads differ adds its own {@link RecordNormalizer}; this one is the
 * reference implementation and the one the mock provider produces.
 */
@Component
public class AaFiNormalizer implements RecordNormalizer {

    public static final String SCHEMA = "aa-fi-v1";
    private final ObjectMapper mapper;

    public AaFiNormalizer(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public boolean supports(String schemaVersion) { return SCHEMA.equals(schemaVersion); }

    @Override
    public List<NormalizedTransaction> transactions(RawRecord raw, SourceType sourceType, String provider) {
        JsonNode root = read(raw.payload());
        JsonNode t = root.get("txn");
        if (t == null) return List.of();
        AssetClass cls = Parse.assetClass(Parse.text(root, "fiType"), AssetClass.OTHER);
        AccountRef acct = account(root, cls);
        TransactionType type = Parse.type(Parse.text(t, "type"));
        if (type == null) throw new IllegalArgumentException("unknown transaction type '" + Parse.text(t, "type") + "'");
        boolean cash = cls == AssetClass.CASH;
        NormalizedTransaction nt = NormalizedTransaction.builder()
            .account(acct)
            .asset(Parse.text(t, "isin", "symbol", "name") == null || cash ? null
                : new AssetRef(cls, Parse.text(t, "symbol"), Parse.text(t, "isin"), Parse.text(t, "name"), null))
            .type(type)
            .transactionDate(Parse.date(t, "date", "transactionDate"))
            .settlementDate(Parse.date(t, "valueDate", "settlementDate"))
            .quantity(Parse.decimal(t, "units", "quantity"))
            .unitPrice(Parse.decimal(t, "nav", "price"))
            .grossAmount(Parse.decimal(t, "amount"))
            .fees(Parse.decimal(t, "charges"))
            .taxes(Parse.decimal(t, "tax"))
            .sourceType(sourceType).sourceProvider(provider)
            .sourceReference(Parse.text(t, "txnId", "reference"))
            .sourceTimestamp(Parse.timestamp(t, "timestamp"))
            .linkGroup(Parse.text(t, "linkGroup"))
            .notes(Parse.text(t, "narrative"))
            .reversal("true".equalsIgnoreCase(Parse.text(t, "reversal")))
            .build();
        return List.of(AmountMath.complete(nt));
    }

    @Override
    public List<NormalizedHolding> holdings(RawRecord raw, SourceType sourceType, String provider) {
        JsonNode root = read(raw.payload());
        JsonNode h = root.get("holding");
        if (h == null) return List.of();
        AssetClass cls = Parse.assetClass(Parse.text(root, "fiType"), AssetClass.OTHER);
        BigDecimal qty = Parse.decimal(h, "units", "quantity");
        return List.of(NormalizedHolding.builder()
            .account(account(root, cls))
            .asset(new AssetRef(cls, Parse.text(h, "symbol"), Parse.text(h, "isin"), Parse.text(h, "name"), null))
            .quantity(qty)
            .averageCost(Parse.decimal(h, "avgCost"))
            .investedValue(Parse.decimal(h, "invested"))
            .currentPrice(Parse.decimal(h, "price", "nav"))
            .currentValue(Parse.decimal(h, "value"))
            .xirr(Parse.decimal(h, "xirr"))
            .asOfDate(Parse.date(h, "asOf"))
            .sourceType(sourceType).sourceProvider(provider).build());
    }

    private AccountRef account(JsonNode root, AssetClass cls) {
        AccountType at = switch (cls) {
            case MUTUAL_FUND -> AccountType.MF_FOLIO;
            case STOCK, ETF, US_STOCK -> AccountType.DEMAT;
            case CASH -> AccountType.BANK_SAVINGS;
            case FD -> AccountType.FD_ACCOUNT;
            case RD -> AccountType.RD_ACCOUNT;
            default -> AccountType.OTHER;
        };
        return new AccountRef(Parse.text(root, "institution"), Parse.text(root, "accountId"), at);
    }

    private JsonNode read(String payload) {
        try { return mapper.readTree(payload); }
        catch (Exception e) { throw new IllegalArgumentException("payload is not valid JSON"); }
    }
}
