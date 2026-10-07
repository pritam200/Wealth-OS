package com.marketai.dataplatform.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.dataplatform.domain.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Schema {@code statement-row-v1}: one row of a statement or CAS (CSV / Excel / PDF-table) as a
 * flat JSON object. The importer reads files into rows; everything after that is this schema, so
 * every import path shares one normalisation and reconciliation route.
 * <pre>{ "institution", "accountId", "assetClass", "date", "settlementDate", "type", "symbol",
 *        "isin", "name", "quantity", "price", "amount", "fees", "taxes", "reference", "linkGroup" }</pre>
 */
@Component
public class StatementRowNormalizer implements RecordNormalizer {

    public static final String SCHEMA = "statement-row-v1";
    private final ObjectMapper mapper;

    public StatementRowNormalizer(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public boolean supports(String schemaVersion) { return SCHEMA.equals(schemaVersion); }

    /** A HOLDING-kind row: {@code institution, accountId, assetClass, isin/symbol/name, quantity, price, value, asOf}. */
    @Override
    public List<NormalizedHolding> holdings(RawRecord raw, SourceType sourceType, String provider) {
        JsonNode n;
        try { n = mapper.readTree(raw.payload()); }
        catch (Exception e) { throw new IllegalArgumentException("payload is not valid JSON"); }
        AssetClass cls = Parse.assetClass(Parse.text(n, "assetClass"), AssetClass.OTHER);
        AccountType at = switch (cls) {
            case MUTUAL_FUND -> AccountType.MF_FOLIO;
            case STOCK, ETF, US_STOCK -> AccountType.DEMAT;
            default -> AccountType.OTHER;
        };
        return List.of(NormalizedHolding.builder()
            .account(new AccountRef(Parse.text(n, "institution"), Parse.text(n, "accountId"), at))
            .asset(new AssetRef(cls, Parse.text(n, "symbol"), Parse.text(n, "isin"), Parse.text(n, "name"), null))
            .quantity(Parse.decimal(n, "quantity"))
            .currentPrice(Parse.decimal(n, "price"))
            .currentValue(Parse.decimal(n, "value"))
            .asOfDate(Parse.date(n, "asOf"))
            .sourceType(sourceType).sourceProvider(provider).build());
    }

    @Override
    public List<NormalizedTransaction> transactions(RawRecord raw, SourceType sourceType, String provider) {
        JsonNode n;
        try { n = mapper.readTree(raw.payload()); }
        catch (Exception e) { throw new IllegalArgumentException("payload is not valid JSON"); }
        TransactionType type = Parse.type(Parse.text(n, "type"));
        if (type == null) throw new IllegalArgumentException("unknown transaction type '" + Parse.text(n, "type") + "'");
        AssetClass cls = Parse.assetClass(Parse.text(n, "assetClass"), AssetClass.OTHER);
        AccountType at = switch (cls) {
            case MUTUAL_FUND -> AccountType.MF_FOLIO;
            case STOCK, ETF, US_STOCK -> AccountType.DEMAT;
            case CASH -> AccountType.BANK_SAVINGS;
            case FD -> AccountType.FD_ACCOUNT;
            default -> AccountType.OTHER;
        };
        boolean hasAsset = Parse.text(n, "isin", "symbol", "name") != null;
        NormalizedTransaction t = NormalizedTransaction.builder()
            .account(new AccountRef(Parse.text(n, "institution"), Parse.text(n, "accountId"), at))
            .asset(hasAsset ? new AssetRef(cls, Parse.text(n, "symbol"), Parse.text(n, "isin"), Parse.text(n, "name"), null) : null)
            .type(type)
            .transactionDate(Parse.date(n, "date"))
            .settlementDate(Parse.date(n, "settlementDate"))
            .quantity(Parse.decimal(n, "quantity")).unitPrice(Parse.decimal(n, "price"))
            .grossAmount(Parse.decimal(n, "amount")).fees(Parse.decimal(n, "fees")).taxes(Parse.decimal(n, "taxes"))
            .ratioFrom(Parse.decimal(n, "ratioFrom")).ratioTo(Parse.decimal(n, "ratioTo"))
            .newSymbol(Parse.text(n, "newSymbol") == null ? null : Parse.text(n, "newSymbol").trim().toUpperCase())
            .interestRate(Parse.decimal(n, "interestRate")).maturityDate(Parse.date(n, "maturityDate"))
            .sourceType(sourceType).sourceProvider(provider)
            .sourceReference(Parse.text(n, "reference")).linkGroup(Parse.text(n, "linkGroup"))
            .reversal("true".equalsIgnoreCase(Parse.text(n, "reversal")))
            .build();
        return List.of(AmountMath.complete(t));
    }
}
