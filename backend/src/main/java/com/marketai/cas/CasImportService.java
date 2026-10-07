package com.marketai.cas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketai.dataplatform.domain.ProviderMode;
import com.marketai.dataplatform.domain.RecordKind;
import com.marketai.dataplatform.domain.SourceType;
import com.marketai.dataplatform.pipeline.RawRecord;
import com.marketai.dataplatform.pipeline.StatementRowNormalizer;
import com.marketai.dataplatform.service.IngestionPipeline;
import com.marketai.dataplatform.service.RawDataStore;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Imports a mutual-fund CAS (CAMS / KFintech PDF) into the ledger: the PDF is unlocked in
 * memory with the password the user types (never stored or logged), read by {@link CasParser},
 * and every transaction goes through the same ingestion pipeline as any other statement row, so
 * duplicates against earlier imports are skipped and reconciliation applies as usual.
 */
@Service
@RequiredArgsConstructor
public class CasImportService {

    private final IngestionPipeline pipeline;
    private final ObjectMapper mapper;

    public record SchemeSummary(String amc, String folio, String scheme, String isin, BigDecimal closingUnits,
                                int transactions, boolean unitsReconcile) {}

    public record Summary(LocalDate periodFrom, LocalDate periodTo, int schemes, int rows, int created, int duplicated,
                          int rejected, List<SchemeSummary> holdings, List<String> warnings) {}

    public Summary importPdf(Long userId, byte[] pdf, String password, String institutionLabel) {
        String text = extractText(pdf, password);
        String head = text.length() > 4000 ? text.substring(0, 4000) : text;
        if (head.toUpperCase(Locale.ROOT).contains("NSDL") || head.toUpperCase(Locale.ROOT).contains("CDSL")) {
            throw bad("This looks like a demat (NSDL/CDSL) statement. Only the mutual-fund CAS from CAMS/KFintech is supported here for now.");
        }
        CasParser.Result parsed = CasParser.parse(text);
        if (parsed.schemes.isEmpty()) {
            throw bad("No mutual-fund schemes were found. Use the detailed CAS (with every transaction) from CAMS/KFintech.");
        }

        List<RawRecord> records = new ArrayList<>();
        Map<String, Integer> occurrence = new HashMap<>();
        List<SchemeSummary> holdings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (CasParser.Scheme s : parsed.schemes) {
            BigDecimal gap = s.unitMismatch();
            boolean reconciles = gap != null && gap.abs().compareTo(new BigDecimal("0.01")) <= 0;
            if (gap != null && !reconciles) {
                warnings.add(s.name + " (folio " + s.folio + "): units don't add up — the statement shows " + s.closingUnits
                    + " but its transactions give " + s.closingUnits.subtract(gap).toPlainString()
                    + ". Part of this scheme may not have been read.");
            }
            holdings.add(new SchemeSummary(s.amc, s.folio, s.name, s.isin, s.closingUnits, s.txns.size(), reconciles));
            for (CasParser.Txn t : s.txns) {
                ObjectNode n = mapper.createObjectNode();
                n.put("institution", s.amc != null ? s.amc : institutionLabel);
                if (s.folio != null) n.put("accountId", s.folio);
                n.put("assetClass", "MUTUAL_FUND");
                n.put("date", t.date.toString());
                n.put("type", t.type);
                n.put("name", s.name);
                n.put("isin", s.isin);
                if (t.units != null) n.put("quantity", t.units.abs().toPlainString());
                if (t.nav != null) n.put("price", t.nav.toPlainString());
                n.put("amount", t.amount.toPlainString());
                String json;
                try { json = mapper.writeValueAsString(n); } catch (Exception e) { throw new IllegalStateException(e); }
                String h = RawDataStore.sha256(json);
                int occ = occurrence.merge(h, 1, Integer::sum) - 1;
                records.add(new RawRecord(RecordKind.TRANSACTION, s.folio, "cas:" + h + ":" + occ, json, StatementRowNormalizer.SCHEMA));
            }
        }
        if (!parsed.unparsed.isEmpty()) {
            warnings.add(parsed.unparsed.size() + " line(s) looked like transactions but couldn't be read, e.g. \""
                + parsed.unparsed.get(0) + "\". They were not imported.");
        }
        if (records.isEmpty()) throw bad("The statement lists your schemes but no transactions in it could be read.");

        IngestionPipeline.Result res = pipeline.ingest(new IngestionPipeline.Request(userId, null, null, SourceType.CAS,
            "cas-import", ProviderMode.LIVE, records, null));
        if (res.rejected > 0 && !res.errors.isEmpty()) warnings.add(res.errors.get(0));
        return new Summary(parsed.periodFrom, parsed.periodTo, parsed.schemes.size(), records.size(),
            res.created, res.duplicated + res.updated, res.rejected, holdings, warnings);
    }

    private static String extractText(byte[] pdf, String password) {
        try (PDDocument doc = Loader.loadPDF(pdf, password == null ? "" : password.trim())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (InvalidPasswordException e) {
            throw bad(password == null || password.isBlank()
                ? "This PDF is password-protected. Enter the password you chose when requesting the CAS (often your PAN in capitals)."
                : "That password didn't open the PDF. Check it and try again.");
        } catch (IOException e) {
            throw bad("That file couldn't be read as a PDF.");
        }
    }

    private static ResponseStatusException bad(String msg) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg); }
}
