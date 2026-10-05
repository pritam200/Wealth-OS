package com.marketai.dataplatform.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.dataplatform.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Schema {@code legacy-txn-v1}: a row of the pre-canonical {@code transactions} table with its
 * holding's identity, used when existing data is migrated into the canonical ledger. The source
 * type is not read from here; the migration declares it from each row's provenance.
 */
@Component
public class LegacyTransactionNormalizer implements RecordNormalizer {

    public static final String SCHEMA = "legacy-txn-v1";
    private final ObjectMapper mapper;

    public LegacyTransactionNormalizer(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public boolean supports(String schemaVersion) { return SCHEMA.equals(schemaVersion); }

    @Override
    public List<NormalizedTransaction> transactions(RawRecord raw, SourceType sourceType, String provider) {
        JsonNode n;
        try { n = mapper.readTree(raw.payload()); }
        catch (Exception e) { throw new IllegalArgumentException("payload is not valid JSON"); }
        String symbol = Parse.text(n, "symbol");
        boolean mf = symbol != null && symbol.toUpperCase().endsWith(".MF");
        String legacyType = Parse.text(n, "type");
        TransactionType type = switch (legacyType == null ? "" : legacyType) {
            case "BUY" -> TransactionType.BUY;
            case "SELL" -> mf ? TransactionType.REDEMPTION : TransactionType.SELL;
            case "BONUS" -> TransactionType.BONUS;
            case "SPLIT" -> TransactionType.SPLIT;
            default -> throw new IllegalArgumentException("unknown legacy transaction type '" + legacyType + "'");
        };
        String broker = Parse.text(n, "broker");
        String folio = Parse.text(n, "folio");
        String dp = Parse.text(n, "dpId"), client = Parse.text(n, "clientId");
        // Without a recorded institution a folio/client id cannot be tied to one (folio numbers repeat
        // across AMCs), so the row goes to an institution-less placeholder account. The matcher treats a
        // placeholder as compatible with any specific account, and an institution source that reports
        // the same transaction then refines it.
        AccountRef account = mf
            ? new AccountRef(broker != null ? broker : "Mutual fund", broker != null ? folio : null, AccountType.MF_FOLIO)
            : new AccountRef(broker != null ? broker : "Broker",
                broker != null && client != null ? (dp == null ? "" : dp) + client : null, AccountType.DEMAT);
        String name = Parse.text(n, "name");
        String bare = symbol == null ? null : symbol.replaceAll("(?i)\\.(NS|BO|MF)$", "");
        AssetRef asset = mf
            ? new AssetRef(AssetClass.MUTUAL_FUND, null, Parse.text(n, "isin"), name != null ? name : bare, null)
            : new AssetRef(AssetClass.STOCK, bare == null ? null : bare.toUpperCase(), Parse.text(n, "isin"), name, null);
        BigDecimal conf = Parse.decimal(n, "confidence");
        NormalizedTransaction t = NormalizedTransaction.builder()
            .account(account).asset(asset).type(type).transactionDate(Parse.date(n, "date"))
            .quantity(type == TransactionType.SPLIT ? null : Parse.decimal(n, "quantity"))
            .unitPrice(type == TransactionType.BONUS || type == TransactionType.SPLIT ? null : Parse.decimal(n, "price"))
            .fees(Parse.decimal(n, "charges"))
            .ratioFrom(Parse.decimal(n, "ratioFrom")).ratioTo(Parse.decimal(n, "ratioTo"))
            .sourceType(sourceType).sourceProvider(provider).sourceReference(Parse.text(n, "reference") != null ? Parse.text(n, "reference") : Parse.text(n, "legacyTransactionId") == null ? null : "legacy-txn-" + Parse.text(n, "legacyTransactionId"))
            .recordConfidence(conf == null ? 0.8 : Math.max(0, Math.min(1, conf.doubleValue())))
            .notes(Parse.text(n, "notes")).build();
        return List.of(AmountMath.complete(t));
    }
}
