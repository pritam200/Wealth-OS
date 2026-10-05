package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.*;
import com.marketai.dataplatform.repo.*;
import com.marketai.gmail.parser.ParsedEmail;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Wires the whole data platform over an in-memory database. Transactions are real (each
 * pipeline step commits), as in production, so the tests exercise what a sync actually does.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(DataPlatformTestSupport.Wiring.class)
@TestPropertySource(properties = {
    "wealthos.data.providers.mock.enabled=true",
    "wealthos.data.backfill-on-startup=false",
    // 32 zero bytes, base64: a fixed key so the cipher never writes a key file during tests.
    "app.security.pdf-password-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
abstract class DataPlatformTestSupport {

    @Configuration
    @ComponentScan("com.marketai.dataplatform")
    @Import({JacksonAutoConfiguration.class, com.marketai.gmail.security.PasswordCipher.class})
    static class Wiring {}

    static final Long USER = 1L;
    static final String ISIN_A = "INF179K01XX1";
    static final String ISIN_B = "INF179K01YY2";

    @Autowired IngestionPipeline pipeline;
    @Autowired EmailSignalRecorder emailRecorder;
    @Autowired CanonicalTransactionRepository txns;
    @Autowired TransactionSourceRepository sources;
    @Autowired TransactionCandidateRepository candidates;
    @Autowired RawFinancialDataRepository raws;
    @Autowired HoldingSnapshotRepository snapshots;
    @Autowired LedgerIssueRepository issueRepo;
    @Autowired LedgerAuditEventRepository auditRepo;
    @Autowired FinancialAccountRepository accountRepo;
    @Autowired FinancialAssetRepository assetRepo;
    @Autowired DataConnectionRepository connectionRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired FinancialSyncRunRepository runRepo;
    @Autowired AccountMemberRepository memberRepo;
    @Autowired FamilyMemberRepository familyMemberRepo;
    @Autowired FamilyRepository familyRepo;

    @BeforeEach
    void clean() {
        sources.deleteAllInBatch(); candidates.deleteAllInBatch(); txns.deleteAllInBatch(); raws.deleteAllInBatch();
        snapshots.deleteAllInBatch(); issueRepo.deleteAllInBatch(); auditRepo.deleteAllInBatch();
        consentRepo.deleteAllInBatch(); connectionRepo.deleteAllInBatch(); runRepo.deleteAllInBatch();
        memberRepo.deleteAllInBatch(); familyMemberRepo.deleteAllInBatch(); familyRepo.deleteAllInBatch();
        accountRepo.deleteAllInBatch(); assetRepo.deleteAllInBatch();
    }

    /* ───────── record builders ───────── */

    static RawRecord aaTxn(String id, String type, String date, String isin, String name, String units, String amount) {
        String json = "{\"fiType\":\"MUTUAL_FUNDS\",\"institution\":\"HDFC Mutual Fund\",\"accountId\":\"FOLIO1234\",\"txn\":{"
            + "\"txnId\":\"" + id + "\",\"type\":\"" + type + "\",\"date\":\"" + date + "\",\"isin\":\"" + isin + "\",\"name\":\"" + name + "\","
            + (units == null ? "" : "\"units\":\"" + units + "\",") + "\"amount\":\"" + amount + "\"}}";
        return new RawRecord(RecordKind.TRANSACTION, "FOLIO1234", id, json, AaFiNormalizer.SCHEMA);
    }

    static RawRecord aaHolding(String isin, String name, String units, String asOf) {
        String json = "{\"fiType\":\"MUTUAL_FUNDS\",\"institution\":\"HDFC Mutual Fund\",\"accountId\":\"FOLIO1234\",\"holding\":{"
            + "\"isin\":\"" + isin + "\",\"name\":\"" + name + "\",\"units\":\"" + units + "\",\"price\":\"200\",\"asOf\":\"" + asOf + "\"}}";
        return new RawRecord(RecordKind.HOLDING, "FOLIO1234", "H-" + isin + "-" + asOf, json, AaFiNormalizer.SCHEMA);
    }

    static RawRecord statementRow(String ref, String type, String date, String isin, String name, String qty, String price, String amount) {
        StringBuilder j = new StringBuilder("{\"institution\":\"HDFC Mutual Fund\",\"accountId\":\"FOLIO1234\",\"assetClass\":\"MUTUAL_FUND\"");
        j.append(",\"type\":\"").append(type).append("\",\"date\":\"").append(date).append("\"");
        if (isin != null) j.append(",\"isin\":\"").append(isin).append("\"");
        if (name != null) j.append(",\"name\":\"").append(name).append("\"");
        if (qty != null) j.append(",\"quantity\":\"").append(qty).append("\"");
        if (price != null) j.append(",\"price\":\"").append(price).append("\"");
        if (amount != null) j.append(",\"amount\":\"").append(amount).append("\"");
        if (ref != null) j.append(",\"reference\":\"").append(ref).append("\"");
        return new RawRecord(RecordKind.TRANSACTION, "FOLIO1234", ref, j.append("}").toString(), StatementRowNormalizer.SCHEMA);
    }

    static ParsedEmail emailSip(String date, String isin, String fundName, String amount) {
        return ParsedEmail.builder().type(ParsedEmail.Type.MF_SIP).fundName(fundName).isin(isin).amount(new BigDecimal(amount))
            .tradeDate(LocalDate.parse(date)).provider("HDFC Mutual Fund").sourceDescription("Your SIP was processed")
            .extractionConfidence(0.85).build();
    }

    IngestionPipeline.Result ingest(SourceType type, String provider, RawRecord... records) {
        return pipeline.ingest(new IngestionPipeline.Request(USER, null, null, type, provider, ProviderMode.LIVE, List.of(records), null));
    }

    IngestionPipeline.Result ingestWithCoverage(SourceType type, String provider, LocalDate from, LocalDate to, RawRecord... records) {
        return pipeline.ingest(new IngestionPipeline.Request(USER, null, null, type, provider, ProviderMode.LIVE, List.of(records),
            new LedgerService.Coverage(from, to)));
    }

    List<CanonicalTransaction> all() { return txns.findByUserIdAndStatusIn(USER, List.of(TxnStatus.values())); }
}
