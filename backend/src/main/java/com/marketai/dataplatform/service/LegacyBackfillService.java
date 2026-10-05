package com.marketai.dataplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.LegacyTransactionNormalizer;
import com.marketai.dataplatform.pipeline.RawRecord;
import com.marketai.dataplatform.repo.CanonicalTransactionRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Migrates the existing {@code transactions} ledger into the canonical ledger without touching
 * it. Rows that came from an email or an attachment of one are recorded with source EMAIL; the
 * rest are MANUAL. Idempotent: a row already migrated, or already recorded live from the same
 * email line, is recognised and skipped, so it can be re-run safely.
 *
 * <p>The legacy table is not modified or deleted — it stays what the existing screens read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LegacyBackfillService {

    private static final Set<String> EMAIL_METHODS = Set.of("EMAIL_LLM", "PDF_LLM", "OCR_LLM", "ATTACHMENT_LLM");

    private final TransactionRepository legacy;
    private final CanonicalTransactionRepository canonical;
    private final IngestionPipeline pipeline;
    private final ObjectMapper mapper;

    public record Summary(int examined, int created, int alreadyPresent, int rejected, int email, int manual, List<String> errors) {}

    public Summary backfill(Long userId) {
        List<Transaction> rows = legacy.findForUserBetween(userId, LocalDate.of(1990, 1, 1), LocalDate.of(2100, 1, 1));
        int created = 0, present = 0, rejected = 0, email = 0, manual = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (Transaction t : rows) {
            if (canonical.findByUserIdAndLegacyTransactionId(userId, t.getId()).isPresent()) { present++; continue; }
            boolean fromEmail = isEmail(t);
            try {
                RawRecord rec = new RawRecord(RecordKind.TRANSACTION, null, recordId(t), payload(t, fromEmail), LegacyTransactionNormalizer.SCHEMA);
                IngestionPipeline.Result res = pipeline.ingest(new IngestionPipeline.Request(userId, null, null,
                    fromEmail ? SourceType.EMAIL : SourceType.MANUAL, fromEmail ? EmailSignalRecorder.PROVIDER : "manual",
                    ProviderMode.LIVE, List.of(rec), null, true));
                res.touchedEntryIds.stream().findFirst().flatMap(canonical::findById).ifPresent(e -> {
                    if (e.getLegacyTransactionId() == null) { e.setLegacyTransactionId(t.getId()); canonical.save(e); }
                });
                if (res.created > 0) created++; else if (res.rejected > 0) { rejected++; errors.addAll(res.errors); } else present++;
                if (fromEmail) email++; else manual++;
            } catch (Exception e) {
                rejected++;
                errors.add("transaction " + t.getId() + ": " + e.getClass().getSimpleName());
            }
        }
        log.info("event=backfill_completed userId={} examined={} created={} alreadyPresent={} rejected={}", userId, rows.size(), created, present, rejected);
        return new Summary(rows.size(), created, present, rejected, email, manual, errors.stream().limit(20).toList());
    }

    private static boolean isEmail(Transaction t) {
        var p = t.getProvenance();
        return p != null && (p.getSourceEmailId() != null || (p.getExtractionMethod() != null && EMAIL_METHODS.contains(p.getExtractionMethod())));
    }

    /** The importer's line fingerprint when there is one — the same id the live email path uses — else a stable id for the row. */
    private static String recordId(Transaction t) {
        var p = t.getProvenance();
        return p != null && p.getSourceFingerprint() != null && isEmail(t) ? p.getSourceFingerprint() : "legacy-txn-" + t.getId();
    }

    private String payload(Transaction t, boolean fromEmail) throws Exception {
        Holding h = t.getHolding();
        ObjectNode n = mapper.createObjectNode();
        n.put("symbol", h.getSymbol()); n.put("name", h.getName());
        if (h.getIsin() != null) n.put("isin", h.getIsin());
        if (h.getBroker() != null) n.put("broker", h.getBroker());
        if (h.getFolio() != null) n.put("folio", h.getFolio());
        if (h.getDpId() != null) n.put("dpId", h.getDpId());
        if (h.getClientId() != null) n.put("clientId", h.getClientId());
        n.put("type", t.getType().name()); n.put("date", t.getTransactionDate().toString());
        n.put("quantity", t.getQuantity().toPlainString()); n.put("price", t.getPrice().toPlainString());
        if (t.getCharges() != null) n.put("charges", t.getCharges().toPlainString());
        if (t.getRatioFrom() != null) n.put("ratioFrom", t.getRatioFrom().toPlainString());
        if (t.getRatioTo() != null) n.put("ratioTo", t.getRatioTo().toPlainString());
        var p = t.getProvenance();
        if (p != null && p.getSourceReference() != null) n.put("reference", p.getSourceReference());
        if (p != null && p.getExtractionConfidence() != null) n.put("confidence", p.getExtractionConfidence());
        n.put("legacyTransactionId", t.getId());
        return mapper.writeValueAsString(n);
    }
}
