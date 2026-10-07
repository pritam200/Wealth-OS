package com.marketai.dataplatform.provider.setu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.dataplatform.domain.AssetClass;
import com.marketai.dataplatform.domain.RecordKind;
import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.domain.TransactionType;
import com.marketai.dataplatform.pipeline.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the mapper emits must be accepted by the pipeline's real normalizer and validator, not just look right. */
class SetuFiMapperPipelineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AaFiNormalizer normalizer = new AaFiNormalizer(mapper);
    private final RecordValidator validator = new RecordValidator();

    @Test
    void everyRecordTheMapperEmitsNormalizesAndValidates() throws Exception {
        var session = mapper.readTree(SetuAaProviderTest.SESSION);
        SetuFiMapper.Result r = SetuFiMapper.map(session, mapper, LocalDate.of(2024, 3, 31));
        assertThat(r.records).isNotEmpty();
        assertThat(r.accountsSkipped).isEqualTo(2); // insurance + the denied account

        int txns = 0, holdings = 0;
        for (RawRecord rec : r.records) {
            assertThat(normalizer.supports(rec.schemaVersion())).isTrue();
            if (rec.kind() == RecordKind.TRANSACTION) {
                for (NormalizedTransaction t : normalizer.transactions(rec, SourceType.ACCOUNT_AGGREGATOR, "setu-aa")) {
                    assertThat(validator.validate(t, LocalDate.of(2024, 4, 1))).as(rec.payload()).isEmpty();
                    txns++;
                }
            } else {
                for (NormalizedHolding h : normalizer.holdings(rec, SourceType.ACCOUNT_AGGREGATOR, "setu-aa")) {
                    assertThat(validator.validate(h)).as(rec.payload()).isEmpty();
                    holdings++;
                }
            }
        }
        assertThat(txns).isEqualTo(3);
        assertThat(holdings).isEqualTo(3);
    }

    @Test
    void bankCreditsAndDebitsBecomeCashTransactionsAndFundsKeepTheirFolio() throws Exception {
        var session = mapper.readTree(SetuAaProviderTest.SESSION);
        List<RawRecord> recs = SetuFiMapper.map(session, mapper, LocalDate.of(2024, 3, 31)).records;
        RawRecord credit = recs.stream().filter(x -> x.payload().contains("\"T1\"")).findFirst().orElseThrow();
        NormalizedTransaction t = normalizer.transactions(credit, SourceType.ACCOUNT_AGGREGATOR, "setu-aa").get(0);
        assertThat(t.getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(t.getAsset()).isNull();
        assertThat(t.getTransactionDate()).isEqualTo(LocalDate.of(2024, 3, 2));

        RawRecord buy = recs.stream().filter(x -> x.kind() == RecordKind.TRANSACTION && x.payload().contains("INF179K01YZ4")).findFirst().orElseThrow();
        NormalizedTransaction f = normalizer.transactions(buy, SourceType.ACCOUNT_AGGREGATOR, "setu-aa").get(0);
        assertThat(f.getAccount().externalAccountId()).isEqualTo("12345678");
        assertThat(f.getAsset().assetClass()).isEqualTo(AssetClass.MUTUAL_FUND);
        assertThat(f.getType()).isEqualTo(TransactionType.BUY);
    }
}
