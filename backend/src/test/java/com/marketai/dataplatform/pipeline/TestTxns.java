package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Builders shared by the pipeline tests. */
final class TestTxns {
    private TestTxns() {}

    static final AccountRef ACCT = new AccountRef("HDFC Mutual Fund", "FOLIO1234", AccountType.MF_FOLIO);
    static final AccountRef PLACEHOLDER = new AccountRef("HDFC Mutual Fund", null, AccountType.MF_FOLIO);
    static final AssetRef FUND_A = new AssetRef(AssetClass.MUTUAL_FUND, null, "INF179K01XX1", "HDFC Flexi Cap", null);

    static NormalizedTransaction.NormalizedTransactionBuilder txn(SourceType src, TransactionType type, String date, String amount) {
        BigDecimal a = new BigDecimal(amount);
        return NormalizedTransaction.builder().account(ACCT).asset(FUND_A).type(type).transactionDate(LocalDate.parse(date))
            .grossAmount(a).netAmount(a).sourceType(src).sourceProvider(src.name().toLowerCase());
    }

    static LedgerEntryView entry(long id, long assetId, TransactionType type, String date, String amount, SourceType src, String ref) {
        BigDecimal a = new BigDecimal(amount);
        return new LedgerEntryView(id, 1L, true, assetId, type, TxnStatus.PENDING_RECONCILIATION, LocalDate.parse(date), null, a, a, null, null,
            List.of(new LedgerEntryView.Source(src, src.name().toLowerCase(), ref)));
    }

    static SourceObservation obs(SourceType t, String qty, String net) {
        return new SourceObservation(t, t.name().toLowerCase(), null, null, LocalDate.of(2026, 8, 15),
            qty == null ? null : new BigDecimal(qty), null, net == null ? null : new BigDecimal(net), null, null,
            net == null ? null : new BigDecimal(net), 1.0);
    }
}
