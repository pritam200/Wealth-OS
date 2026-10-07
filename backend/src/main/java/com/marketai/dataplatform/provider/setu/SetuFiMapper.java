package com.marketai.dataplatform.provider.setu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketai.dataplatform.domain.RecordKind;
import com.marketai.dataplatform.pipeline.AaFiNormalizer;
import com.marketai.dataplatform.pipeline.RawRecord;
import com.marketai.dataplatform.service.RawDataStore;

import java.time.LocalDate;
import java.util.*;

/**
 * Turns the decrypted financial information a Setu data session returns (the standard AA/ReBIT
 * shape: {@code fips[].accounts[].data.account.{profile,summary,transactions}}) into
 * {@code aa-fi-v1} records the pipeline already understands.
 *
 * <p>AA payloads are loosely typed — names differ in case between providers, numbers arrive as
 * strings, timestamps as dates or date-times — so every lookup is case-insensitive and tolerant. A
 * record that can't be understood is skipped and counted, never invented. Deposits yield
 * transactions; fixed/recurring deposits, mutual funds and equities yield holdings, plus
 * transactions where the provider lists them.
 */
public final class SetuFiMapper {

    private SetuFiMapper() {}

    public static final class Result {
        public final List<RawRecord> records = new ArrayList<>();
        public int accountsRead, accountsSkipped;
    }

    public static Result map(JsonNode session, ObjectMapper mapper, LocalDate today) {
        Result out = new Result();
        Map<String, Integer> occurrence = new HashMap<>();
        for (JsonNode fip : array(get(session, "fips"))) {
            String fipId = text(fip, "fipID", "fipId");
            for (JsonNode acct : array(get(fip, "accounts"))) {
                JsonNode account = get(get(acct, "data"), "account");
                if (account == null || account.isMissingNode() || account.isNull()) { out.accountsSkipped++; continue; }
                String masked = text(acct, "maskedAccNumber", "maskedAccountNumber");
                String linkRef = text(acct, "linkRefNumber");
                String type = fiType(account, acct);
                if (type == null) { out.accountsSkipped++; continue; }
                out.accountsRead++;
                String accountId = masked != null ? masked : linkRef;
                Ctx c = new Ctx(mapper, out, occurrence, type, fipId != null ? fipId : "AA", accountId, today);
                JsonNode summary = get(account, "summary");
                JsonNode txns = get(get(account, "transactions"), "transaction");
                switch (type) {
                    case "DEPOSIT" -> c.depositTransactions(txns);
                    case "TERM_DEPOSIT", "RECURRING_DEPOSIT" -> c.deposit(summary, masked, type);
                    case "MUTUAL_FUNDS" -> { c.fundHoldings(get(get(get(summary, "investment"), "holdings"), "holding")); c.investmentTransactions(txns, true); }
                    case "EQUITIES" -> { c.equityHoldings(get(get(get(summary, "investment"), "holdings"), "holding")); c.investmentTransactions(txns, false); }
                    default -> out.accountsSkipped++;
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- per-record mapping

    private record Ctx(ObjectMapper mapper, Result out, Map<String, Integer> occurrence, String fiType, String institution,
                       String accountId, LocalDate today) {

        void depositTransactions(JsonNode txns) {
            for (JsonNode t : array(txns)) {
                String kind = text(t, "type");
                String date = date(text(t, "transactionTimestamp", "valueDate", "txnDate"));
                String amount = text(t, "amount");
                if (kind == null || date == null || amount == null) continue;
                ObjectNode txn = mapper.createObjectNode();
                put(txn, "txnId", text(t, "txnId", "reference"));
                txn.put("type", kind);
                txn.put("date", date);
                put(txn, "valueDate", date(text(t, "valueDate")));
                txn.put("amount", amount);
                put(txn, "narrative", text(t, "narration"));
                put(txn, "timestamp", text(t, "transactionTimestamp"));
                emit(root("DEPOSIT").set("txn", txn), RecordKind.TRANSACTION, text(t, "txnId") + "|" + date + "|" + amount + "|" + kind);
            }
        }

        void deposit(JsonNode summary, String masked, String type) {
            String value = text(summary, "currentValue", "currentBalance", "principalAmount");
            if (value == null) { out.accountsSkipped++; return; }
            ObjectNode h = mapper.createObjectNode();
            h.put("name", (type.equals("RECURRING_DEPOSIT") ? "RD " : "FD ") + (masked != null ? masked : accountId) + " · " + institution);
            h.put("units", "1");
            h.put("price", value);
            h.put("value", value);
            put(h, "invested", text(summary, "principalAmount", "balance"));
            h.put("asOf", today.toString());
            emit(root(type.equals("RECURRING_DEPOSIT") ? "RECURRING_DEPOSIT" : "TERM_DEPOSIT").set("holding", h), RecordKind.HOLDING, "dep|" + today);
        }

        void fundHoldings(JsonNode holdings) {
            for (JsonNode x : array(holdings)) {
                String isin = text(x, "isin");
                String units = text(x, "closingUnits", "units");
                if (isin == null || units == null) continue;
                String nav = text(x, "nav");
                ObjectNode h = mapper.createObjectNode();
                h.put("isin", isin);
                put(h, "name", text(x, "isinDescription", "schemeName", "amcName"));
                h.put("units", units);
                put(h, "nav", nav);
                h.put("asOf", orToday(date(text(x, "navDate", "lastNavDate"))));
                ObjectNode r = root("MUTUAL_FUNDS");
                put(r, "accountId", text(x, "folioNo"));   // an MF account is the folio, not the masked link
                emit(r.set("holding", h), RecordKind.HOLDING, "mf|" + isin + "|" + text(x, "folioNo") + "|" + h.get("asOf").asText());
            }
        }

        void equityHoldings(JsonNode holdings) {
            for (JsonNode x : array(holdings)) {
                String isin = text(x, "isin");
                String units = text(x, "units");
                if (isin == null || units == null) continue;
                ObjectNode h = mapper.createObjectNode();
                h.put("isin", isin);
                put(h, "name", text(x, "issuerName", "isinDescription"));
                h.put("units", units);
                put(h, "price", text(x, "lastTradedPrice"));
                h.put("asOf", today.toString());
                emit(root("EQUITIES").set("holding", h), RecordKind.HOLDING, "eq|" + isin + "|" + today);
            }
        }

        void investmentTransactions(JsonNode txns, boolean fund) {
            for (JsonNode t : array(txns)) {
                String kind = text(t, "type");
                String date = date(text(t, "txnDate", "tradeDate", "orderDate", "transactionDate"));
                String isin = text(t, "isin");
                if (kind == null || date == null || isin == null) continue;
                ObjectNode txn = mapper.createObjectNode();
                put(txn, "txnId", text(t, "txnId", "orderId", "reference"));
                txn.put("type", kind);
                txn.put("date", date);
                txn.put("isin", isin);
                put(txn, "name", text(t, "schemeName", "companyName", "isinDescription"));
                put(txn, "units", text(t, "units"));
                put(txn, fund ? "nav" : "price", text(t, "nav", "rate"));
                put(txn, "amount", text(t, "amount", "tradeValue"));
                ObjectNode r = root(fund ? "MUTUAL_FUNDS" : "EQUITIES");
                if (fund) put(r, "accountId", text(t, "folioNo"));
                emit(r.set("txn", txn), RecordKind.TRANSACTION, text(t, "txnId", "orderId") + "|" + isin + "|" + date + "|" + text(t, "units") + "|" + kind);
            }
        }

        private ObjectNode root(String type) {
            ObjectNode r = mapper.createObjectNode();
            r.put("fiType", type);
            r.put("institution", institution);
            if (accountId != null) r.put("accountId", accountId);
            return r;
        }

        /** Idempotent: the same source record always gets the same id, so re-fetching an overlapping window dedups. */
        private void emit(ObjectNode node, RecordKind kind, String identity) {
            String acct = node.path("accountId").asText(accountId);
            String json;
            try { json = mapper.writeValueAsString(node); } catch (Exception e) { throw new IllegalStateException(e); }
            String h = RawDataStore.sha256(institution + "|" + acct + "|" + identity);
            int occ = occurrence.merge(h, 1, Integer::sum) - 1;
            out.records.add(new RawRecord(kind, acct, "setu:" + h + ":" + occ, json, AaFiNormalizer.SCHEMA));
        }

        private String orToday(String d) { return d != null ? d : today.toString(); }
        private static void put(ObjectNode n, String k, String v) { if (v != null) n.put(k, v); }
    }

    // ---------------------------------------------------------------- tolerant JSON helpers

    private static String fiType(JsonNode account, JsonNode acct) {
        String t = text(account, "type");
        if (t == null) t = text(acct, "fiType");
        if (t == null) return null;
        String k = t.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        if (k.contains("mutual")) return "MUTUAL_FUNDS";
        if (k.contains("equit")) return "EQUITIES";
        if (k.contains("term")) return "TERM_DEPOSIT";
        if (k.contains("recurring")) return "RECURRING_DEPOSIT";
        if (k.contains("deposit")) return "DEPOSIT";
        return null;
    }

    /** Case-insensitive child lookup; never returns null. */
    static JsonNode get(JsonNode n, String name) {
        if (n == null || !n.isObject()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    static String text(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = get(n, name);
            if (!v.isMissingNode() && !v.isNull() && !v.isContainerNode() && !v.asText().isBlank()) return v.asText().trim();
        }
        return null;
    }

    /** A JSON array, or a single object treated as a one-element array (XML-derived JSON does this). */
    static List<JsonNode> array(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return List.of();
        if (n.isArray()) { List<JsonNode> l = new ArrayList<>(); n.forEach(l::add); return l; }
        return n.isObject() ? List.of(n) : List.of();
    }

    /** "2024-03-31T10:15:00.000Z" or "2024-03-31" to "2024-03-31"; null if it isn't a date. */
    static String date(String s) {
        if (s == null || s.length() < 10) return null;
        String d = s.substring(0, 10);
        try { LocalDate.parse(d); return d; } catch (Exception e) { return null; }
    }
}
