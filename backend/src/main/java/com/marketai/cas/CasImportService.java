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
                          int rejected, List<SchemeSummary> holdings, List<String> warnings, Kind kind) {}

    /** Which kind of CAS a PDF is, so the caller can refuse one uploaded under the wrong section. */
    public enum Kind { MUTUAL_FUND, DEMAT }

    public Summary importPdf(Long userId, byte[] pdf, String password, String institutionLabel) {
        return importPdf(userId, pdf, password, institutionLabel, null);
    }

    /** {@code expected}: when set, a CAS of the other kind is refused with a pointer to the right section. */
    public Summary importPdf(Long userId, byte[] pdf, String password, String institutionLabel, Kind expected) {
        String text = extractText(pdf, password);
        Kind kind = DematCasParser.looksLikeDemat(text) ? Kind.DEMAT : Kind.MUTUAL_FUND;
        if (expected == Kind.MUTUAL_FUND && kind == Kind.DEMAT)
            throw bad("This is a demat (NSDL/CDSL) statement. Upload it under Stocks & demat holdings.");
        if (expected == Kind.DEMAT && kind == Kind.MUTUAL_FUND)
            throw bad("This is a mutual-fund (CAMS/KFintech) statement. Upload it under Mutual funds.");
        if (kind == Kind.DEMAT) return importDemat(userId, text, institutionLabel);
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
        // What the statement says each scheme holds at its end: reconciled against the ledger the
        // transactions above build, so a missing transaction shows up as a holding mismatch.
        for (CasParser.Scheme s : parsed.schemes) {
            if (s.closingUnits == null || s.closingUnits.signum() < 0) continue;
            LocalDate asOf = s.navDate != null ? s.navDate : parsed.periodTo;
            if (asOf == null) continue;
            ObjectNode n = mapper.createObjectNode();
            n.put("institution", s.amc != null ? s.amc : institutionLabel);
            if (s.folio != null) n.put("accountId", s.folio);
            n.put("assetClass", "MUTUAL_FUND");
            n.put("name", s.name);
            n.put("isin", s.isin);
            n.put("quantity", s.closingUnits.toPlainString());
            if (s.closingNav != null) {
                n.put("price", s.closingNav.toPlainString());
                n.put("value", s.closingUnits.multiply(s.closingNav).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            }
            n.put("asOf", asOf.toString());
            records.add(holdingRecord(n, s.folio));
        }
        if (!parsed.unparsed.isEmpty()) {
            warnings.add(parsed.unparsed.size() + " line(s) looked like transactions but couldn't be read, e.g. \""
                + parsed.unparsed.get(0) + "\". They were not imported.");
        }
        if (records.size() == holdingCount(parsed)) throw bad("The statement lists your schemes but no transactions in it could be read.");

        IngestionPipeline.Result res = pipeline.ingest(new IngestionPipeline.Request(userId, null, null, SourceType.CAS,
            "cas-import", ProviderMode.LIVE, records, null));
        if (res.rejected > 0 && !res.errors.isEmpty()) warnings.add(res.errors.get(0));
        return new Summary(parsed.periodFrom, parsed.periodTo, parsed.schemes.size(), records.size() - holdingCount(parsed),
            res.created, res.duplicated + res.updated, res.rejected, holdings, warnings, Kind.MUTUAL_FUND);
    }

    static final String DEMAT_INSTITUTION = "Demat (NSDL/CDSL)";

    /** True when the PDF can't be opened without a password. */
    public boolean needsPassword(byte[] pdf) {
        try (PDDocument ignored = Loader.loadPDF(pdf, "")) {
            return false;
        } catch (InvalidPasswordException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int holdingCount(CasParser.Result parsed) {
        int c = 0;
        for (CasParser.Scheme s : parsed.schemes)
            if (s.closingUnits != null && s.closingUnits.signum() >= 0 && (s.navDate != null || parsed.periodTo != null)) c++;
        return c;
    }

    private RawRecord holdingRecord(ObjectNode n, String account) {
        String json;
        try { json = mapper.writeValueAsString(n); } catch (Exception e) { throw new IllegalStateException(e); }
        return new RawRecord(RecordKind.HOLDING, account, "cas-holding:" + RawDataStore.sha256(json), json, StatementRowNormalizer.SCHEMA);
    }

    private Summary importDemat(Long userId, String text, String institutionLabel) {
        DematCasParser.Result parsed = DematCasParser.parse(text);
        if (parsed.holdings.isEmpty())
            throw bad("No holdings were found in this demat statement. Use the monthly NSDL/CDSL CAS that lists your securities.");
        if (parsed.asOf == null)
            throw bad("Couldn't find the statement date in this file, so the holdings can't be dated.");

        List<RawRecord> records = new ArrayList<>();
        List<SchemeSummary> holdings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (DematCasParser.Holding h : parsed.holdings) {
            ObjectNode n = mapper.createObjectNode();
            // The demat account is identified by its DP/Client id, not by whichever broker the user named
            // the source after, so one account is never split in two by how the file reached us.
            n.put("institution", DEMAT_INSTITUTION);
            n.put("accountId", h.account);
            n.put("assetClass", h.isin.startsWith("INF") ? "MUTUAL_FUND" : "STOCK");
            n.put("name", h.name);
            n.put("isin", h.isin);
            n.put("quantity", h.quantity.toPlainString());
            if (h.price != null) n.put("price", h.price.toPlainString());
            n.put("value", h.value.toPlainString());
            n.put("asOf", parsed.asOf.toString());
            records.add(holdingRecord(n, h.account));
            holdings.add(new SchemeSummary(h.account, h.account, h.name, h.isin, h.quantity, 0, h.valueChecks));
            if (!h.valueChecks)
                warnings.add(h.name + ": quantity \u00d7 price doesn't match the printed value, so a column may have been misread.");
        }
        if (!parsed.unparsed.isEmpty())
            warnings.add(parsed.unparsed.size() + " security line(s) couldn't be read, e.g. \"" + parsed.unparsed.get(0) + "\". They were not imported.");
        warnings.add("A demat statement shows what you hold, not what you paid. Add buy prices from your broker's export if you want returns.");

        IngestionPipeline.Result res = pipeline.ingest(new IngestionPipeline.Request(userId, null, null, SourceType.CAS,
            "cas-import", ProviderMode.LIVE, records, null));
        if (res.rejected > 0 && !res.errors.isEmpty()) warnings.add(res.errors.get(0));
        return new Summary(parsed.asOf, parsed.asOf, holdings.size(), records.size(), res.created,
            res.duplicated + res.updated, res.rejected, holdings, warnings, Kind.DEMAT);
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
