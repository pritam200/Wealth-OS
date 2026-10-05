package com.marketai.dataplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.pipeline.RawRecord;
import com.marketai.dataplatform.pipeline.StatementRowNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;

/**
 * Statement import from CSV (an Excel sheet saved as CSV takes the same path). The file is read
 * into rows, and from there it is exactly the pipeline every other source uses: same raw layer,
 * same normalisation, same deduplication and reconciliation. Importing the same file twice
 * creates nothing new.
 */
@Service
@RequiredArgsConstructor
public class CsvImportService {

    static final int MAX_ROWS = 5000;

    private final IngestionPipeline pipeline;
    private final ObjectMapper mapper;

    public record Options(String institution, String accountId, SourceType sourceType, AssetClass assetClass,
                          String provider, boolean completeStatement) {}

    public record Summary(int rows, int created, int duplicated, int rejected, List<String> errors) {}

    private static final Map<String, String[]> ALIASES = new LinkedHashMap<>();
    static {
        ALIASES.put("date", new String[]{"date", "transaction date", "txn date", "trade date", "value date", "nav date"});
        ALIASES.put("settlementDate", new String[]{"settlement date"});
        ALIASES.put("type", new String[]{"type", "transaction type", "txn type", "trade type", "buy/sell", "side", "description"});
        ALIASES.put("name", new String[]{"scheme", "scheme name", "fund", "fund name", "security", "security name", "instrument", "name", "company"});
        ALIASES.put("symbol", new String[]{"symbol", "ticker", "scrip"});
        ALIASES.put("isin", new String[]{"isin"});
        ALIASES.put("quantity", new String[]{"units", "quantity", "qty"});
        ALIASES.put("price", new String[]{"nav", "price", "rate", "avg price", "unit price"});
        ALIASES.put("amount", new String[]{"amount", "value", "transaction amount", "gross amount"});
        ALIASES.put("fees", new String[]{"fees", "charges", "brokerage", "stamp duty"});
        ALIASES.put("taxes", new String[]{"tax", "taxes", "stt"});
        ALIASES.put("reference", new String[]{"reference", "ref no", "txn id", "transaction id", "order no", "trade no", "utr"});
        ALIASES.put("ratioFrom", new String[]{"ratio from"});
        ALIASES.put("ratioTo", new String[]{"ratio to"});
        ALIASES.put("newSymbol", new String[]{"new symbol", "merged into", "surviving symbol"});
        ALIASES.put("interestRate", new String[]{"interest rate", "roi", "rate of interest"});
        ALIASES.put("maturityDate", new String[]{"maturity date"});
    }

    public Summary importCsv(Long userId, String csv, Options opt) {
        if (opt.institution() == null || opt.institution().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say which institution this statement is from");
        if (opt.sourceType() == null || !(opt.sourceType() == SourceType.STATEMENT || opt.sourceType() == SourceType.CAS
                || opt.sourceType() == SourceType.BROKER_API || opt.sourceType() == SourceType.DEPOSITORY))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A file import is a STATEMENT or CAS");
        List<List<String>> table = parse(csv);
        if (table.size() < 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file has no rows");
        if (table.size() - 1 > MAX_ROWS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many rows (limit " + MAX_ROWS + ")");

        List<String> header = table.get(0).stream().map(h -> h.trim().toLowerCase()).toList();
        Map<String, Integer> col = new HashMap<>();
        for (var e : ALIASES.entrySet())
            for (String alias : e.getValue()) { int i = header.indexOf(alias); if (i >= 0) { col.put(e.getKey(), i); break; } }
        if (!col.containsKey("date") || !col.containsKey("type"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file needs a date column and a transaction-type column");

        List<RawRecord> records = new ArrayList<>();
        Map<String, Integer> occurrence = new HashMap<>();
        LocalDate min = null, max = null;
        for (int r = 1; r < table.size(); r++) {
            List<String> row = table.get(r);
            if (row.stream().allMatch(String::isBlank)) continue;
            ObjectNode n = mapper.createObjectNode();
            n.put("institution", opt.institution().trim());
            if (opt.accountId() != null && !opt.accountId().isBlank()) n.put("accountId", opt.accountId().trim());
            n.put("assetClass", (opt.assetClass() == null ? AssetClass.MUTUAL_FUND : opt.assetClass()).name());
            for (var e : col.entrySet()) if (e.getValue() < row.size() && !row.get(e.getValue()).isBlank()) n.put(e.getKey(), clean(e.getKey(), row.get(e.getValue())));
            try {
                String d = n.path("date").asText();
                LocalDate ld = d.isEmpty() ? null : parseDate(d);
                if (ld != null) { min = min == null || ld.isBefore(min) ? ld : min; max = max == null || ld.isAfter(max) ? ld : max; }
            } catch (RuntimeException ignored) { /* the row is rejected by the pipeline with a reason */ }
            String json;
            try { json = mapper.writeValueAsString(n); } catch (Exception e) { throw new IllegalStateException(e); }
            String h = RawDataStore.sha256(json);
            int occ = occurrence.merge(h, 1, Integer::sum) - 1;
            records.add(new RawRecord(RecordKind.TRANSACTION, opt.accountId(), "csv:" + h + ":" + occ, json, StatementRowNormalizer.SCHEMA));
        }
        LedgerService.Coverage coverage = opt.completeStatement() && min != null ? new LedgerService.Coverage(min, max) : null;
        String provider = opt.provider() == null || opt.provider().isBlank() ? "csv-import" : opt.provider();
        IngestionPipeline.Result res = pipeline.ingest(new IngestionPipeline.Request(userId, null, null, opt.sourceType(), provider,
            ProviderMode.LIVE, records, coverage));
        return new Summary(records.size(), res.created, res.duplicated + res.updated, res.rejected, res.errors.stream().limit(25).toList());
    }

    private static final Set<String> TEXT_FIELDS = Set.of("name", "symbol", "reference", "type", "newSymbol");

    /**
     * Free-text cells that start with a spreadsheet formula trigger would become live formulas the
     * day the name is exported to CSV/Excel, so the trigger characters are stripped on the way in.
     * Numeric and date columns are left alone (a negative amount legitimately starts with '-').
     */
    static String clean(String field, String value) {
        String v = value.trim();
        if (!TEXT_FIELDS.contains(field)) return v;
        int i = 0;
        while (i < v.length() && "=+@-\t\r".indexOf(v.charAt(i)) >= 0) i++;
        return v.substring(i).trim();
    }

    private static LocalDate parseDate(String s) {
        if (s.matches("\\d{2}[-/]\\d{2}[-/]\\d{4}")) {
            String[] p = s.split("[-/]");
            return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
        }
        return LocalDate.parse(s);
    }

    /** RFC 4180-style: quoted fields, doubled quotes, commas and newlines inside quotes. */
    static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') { if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { f.append('"'); i++; } else quoted = false; }
                else f.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(f.toString()); f.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(f.toString()); f.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else f.append(c);
        }
        if (f.length() > 0 || !row.isEmpty()) { row.add(f.toString()); rows.add(row); }
        return rows;
    }
}
