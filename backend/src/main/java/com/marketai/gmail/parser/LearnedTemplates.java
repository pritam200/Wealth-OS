package com.marketai.gmail.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Learns a sender's alert wording from one read the model got right, so the next alert worded
 * the same way is read without the model. The sentence the model quoted is kept, with the
 * amount, date, payee, card digits and payment reference as blanks and every other number
 * generalised; a learned shape must read the email it came from back to the same figures, or
 * it is not kept.
 *
 * <p>Bank and UPI alerts are learned by the sentence the model quoted — the bulk of the model's
 * calls, and the case where a sentence's wording fixes its meaning. A fund house's or registrar's
 * confirmation, or a broker's trade or dividend email, is a form: the figures sit in a table
 * the quote doesn't cover. Those are learned as the whole email with every figure the model read
 * as a blank, and only from an email with one transaction, so a learned form never reads one of
 * two. Statements and contract notes, which list many, have their own readers.
 */
public final class LearnedTemplates {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LearnedTemplates() {}

    /** A learned shape: the pattern, the fields it always carries, and the sentence it came from. */
    public record Shape(String pattern, String constants, String sample) {}

    // Fields whose value is fixed by the wording. sub_type OWN_TRANSFER is left out: it depends on
    // the payee, and is worked out again on every read.
    private static final Set<String> CONSTANTS = Set.of("instrument_type", "transaction_type", "sub_type", "status", "currency", "payment_method");
    private static final Set<String> LEARNABLE_INSTRUMENTS = Set.of("BANK", "UPI");
    private static final Set<String> FORM_INSTRUMENTS = Set.of("MF", "EQUITY");
    /** Longer than this, an email is a statement or a newsletter, not one confirmation. */
    static final int MAX_FORM_CHARS = 8000;
    /** The ledger keeps 500 characters of evidence. */
    static final int MAX_EVIDENCE = 400;

    /** A figure a form carries: the extractor's field, its blank's name, and what it matches. */
    private record Slot(String field, String group, Kind kind) {}
    private enum Kind { NUMBER, TEXT, CODE }
    private static final List<Slot> FORM_SLOTS = List.of(
        new Slot("amount_inr", "amt", Kind.NUMBER),
        new Slot("units", "units", Kind.NUMBER),
        new Slot("nav", "nav", Kind.NUMBER),
        new Slot("quantity", "qty", Kind.NUMBER),
        new Slot("price", "px", Kind.NUMBER),
        new Slot("charges_inr", "chg", Kind.NUMBER),
        new Slot("tds_inr", "tds", Kind.NUMBER),
        new Slot("isin", "isin", Kind.CODE),
        new Slot("folio_number", "fol", Kind.CODE),
        new Slot("trade_number", "ref", Kind.CODE),
        new Slot("symbol", "sym", Kind.CODE),
        new Slot("scheme_name", "sch", Kind.TEXT));
    /** What the model must have read, and the form must carry, for a fund or a share. */
    private static final Map<String, List<String>> FORM_REQUIRED = Map.of(
        "MF", List.of("amount_inr", "scheme_name"),
        "EQUITY", List.of("amount_inr"));
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    /** Words of the sender's own wording a shape must keep, so it can't match just any sentence. */
    static final int MIN_LITERAL_WORDS = 4;
    /** Words after the quoted sentence kept as context, so a payee at its end has a boundary. */
    private static final int TRAILING_WORDS = 3;

    private record DateForm(Pattern pattern, String formatPattern) {
        DateTimeFormatter format() { return f(formatPattern); }
    }

    private static DateTimeFormatter f(String p) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH);
    }

    private static final List<DateForm> DATES = List.of(
        new DateForm(Pattern.compile("\\d{2}-\\d{2}-\\d{4}"), "dd-MM-uuuu"),
        new DateForm(Pattern.compile("\\d{2}/\\d{2}/\\d{4}"), "dd/MM/uuuu"),
        new DateForm(Pattern.compile("\\d{4}-\\d{2}-\\d{2}"), "uuuu-MM-dd"),
        new DateForm(Pattern.compile("\\d{2}-\\d{2}-\\d{2}"), "dd-MM-uu"),
        new DateForm(Pattern.compile("\\d{2}/\\d{2}/\\d{2}"), "dd/MM/uu"),
        new DateForm(Pattern.compile("\\d{1,2}-[A-Za-z]{3}-\\d{4}"), "d-MMM-uuuu"),
        new DateForm(Pattern.compile("\\d{1,2}-[A-Za-z]{3}-\\d{2}"), "d-MMM-uu"),
        new DateForm(Pattern.compile("\\d{1,2} [A-Za-z]{3} \\d{4}"), "d MMM uuuu"),
        new DateForm(Pattern.compile("[A-Za-z]{3} \\d{1,2}, \\d{4}"), "MMM d, uuuu"));

    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d,]*(?:\\.\\d{1,2})?");

    private record Span(int start, int end, String group, String regex) {}

    /** Learns the shape of {@code t}'s sentence in {@code sourceText}, or empty when it can't be done safely. */
    public static Optional<Shape> learn(String sourceText, JsonNode t) {
        return learn(sourceText, t, 1);
    }

    /** As {@link #learn(String, JsonNode)}, for one of {@code transactionsInEmail} the model read from the email. */
    public static Optional<Shape> learn(String sourceText, JsonNode t, int transactionsInEmail) {
        if (sourceText == null || t == null) return Optional.empty();
        String instrument = t.path("instrument_type").asText("").toUpperCase(Locale.ROOT);
        if (FORM_INSTRUMENTS.contains(instrument)) {
            return transactionsInEmail == 1 ? learnForm(sourceText, t) : Optional.empty();
        }
        if (!LEARNABLE_INSTRUMENTS.contains(instrument)) return Optional.empty();
        String evidence = norm(t.path("evidence").asText(""));
        String text = norm(sourceText);
        int at = evidence.isEmpty() ? -1 : text.indexOf(evidence);
        if (at < 0) return Optional.empty();
        String sentence = withTrailingWords(text, at, at + evidence.length());

        BigDecimal amount = decimal(t.path("amount_inr"));
        LocalDate date = date(t.path("transaction_date").asText(null));
        if (amount == null || date == null) return Optional.empty();

        List<Span> spans = new ArrayList<>();
        Span dateSpan = findDate(sentence, date);
        if (dateSpan == null) return Optional.empty();
        spans.add(dateSpan);
        Span amountSpan = find(sentence, AMOUNT, m -> sameAmount(m, amount), "amt", "(?<amt>\\d[\\d,]*(?:\\.\\d{1,2})?)", spans);
        if (amountSpan == null) return Optional.empty();
        spans.add(amountSpan);
        String merchant = t.path("merchant").asText(null);
        if (merchant != null && !merchant.isBlank()) {
            int m = sentence.toLowerCase(Locale.ROOT).indexOf(norm(merchant).toLowerCase(Locale.ROOT));
            if (m >= 0 && free(spans, m, m + norm(merchant).length())) {
                spans.add(new Span(m, m + norm(merchant).length(), "mer", "(?<mer>.+?)"));
            }
        }
        for (String[] opt : new String[][]{{"card_last4", "card", "(?<card>\\d{4})"}, {"trade_number", "ref", "(?<ref>[A-Za-z0-9]+)"}}) {
            String v = t.path(opt[0]).asText(null);
            if (v == null || v.isBlank()) continue;
            int i = sentence.indexOf(v.trim());
            if (i >= 0 && free(spans, i, i + v.trim().length())) spans.add(new Span(i, i + v.trim().length(), opt[1], opt[2]));
        }
        spans.sort(Comparator.comparingInt(Span::start));

        StringBuilder regex = new StringBuilder();
        int cursor = 0, literalWords = 0;
        for (Span s : spans) {
            literalWords += literal(sentence.substring(cursor, s.start()), regex);
            regex.append(s.regex());
            cursor = s.end();
        }
        String tail = sentence.substring(cursor);
        if (spans.get(spans.size() - 1).group().equals("mer") && tail.isBlank()) return Optional.empty(); // no boundary after the payee
        literalWords += literal(tail, regex);
        if (literalWords < MIN_LITERAL_WORDS) return Optional.empty();

        ObjectNode constants = JSON.createObjectNode();
        for (String k : CONSTANTS) {
            if (!t.hasNonNull(k)) continue;
            if (k.equals("sub_type") && "OWN_TRANSFER".equalsIgnoreCase(t.get(k).asText())) continue;
            constants.put(k, t.get(k).asText());
        }
        constants.put("date_format", DATES.get(dateFormIndex(dateSpan)).formatPattern());
        Shape shape = new Shape(regex.toString(), constants.toString(), evidence);

        // It must read its own email back to the same figures.
        Optional<JsonNode> back = read(shape, sourceText);
        if (back.isEmpty()) return Optional.empty();
        JsonNode r = back.get();
        if (decimal(r.path("amount_inr")) == null || decimal(r.path("amount_inr")).compareTo(amount) != 0
                || !date.toString().equals(r.path("transaction_date").asText())) return Optional.empty();
        return Optional.of(shape);
    }

    /**
     * Reads one transaction from {@code sourceText} with a learned shape, as the extractor's JSON
     * for one transaction, or empty when the shape doesn't match exactly once.
     */
    public static Optional<JsonNode> read(Shape shape, String sourceText) {
        if (shape == null || sourceText == null) return Optional.empty();
        Matcher m;
        try {
            m = Pattern.compile(shape.pattern(), Pattern.CASE_INSENSITIVE).matcher(norm(sourceText));
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!m.find()) return Optional.empty();
        int start = m.start(), end = m.end();
        int amtStart = m.start("amt"), amtEnd = m.end("amt");
        String amt = m.group("amt"), dt = m.group("dt");
        String mer = group(m, "mer"), card = group(m, "card"), ref = group(m, "ref");
        Map<String, String> form = new java.util.LinkedHashMap<>();
        for (Slot sl : FORM_SLOTS) {
            if (sl.group().equals("amt") || sl.group().equals("ref")) continue;
            String v = group(m, sl.group());
            if (v != null) form.put(sl.field(), v);
        }
        if (m.find()) return Optional.empty(); // twice in one email: a statement, not an alert
        try {
            JsonNode constants = JSON.readTree(shape.constants());
            ObjectNode t = JSON.createObjectNode();
            constants.fields().forEachRemaining(e -> { if (!e.getKey().equals("date_format")) t.set(e.getKey(), e.getValue()); });
            LocalDate date = LocalDate.parse(dt.replaceAll("\\s+", " "), f(constants.path("date_format").asText()));
            t.put("amount_inr", new BigDecimal(amt.replace(",", "")));
            t.put("transaction_date", date.toString());
            if (mer != null) t.put("merchant", mer.trim());
            if (card != null) t.put("card_last4", card);
            if (ref != null) t.put("trade_number", ref);
            for (Map.Entry<String, String> e : form.entrySet()) {
                Slot sl = FORM_SLOTS.stream().filter(x -> x.field().equals(e.getKey())).findFirst().orElseThrow();
                if (sl.kind() == Kind.NUMBER) t.put(e.getKey(), new BigDecimal(e.getValue().replace(",", "")));
                else t.put(e.getKey(), e.getValue().trim());
            }
            // A form matches the whole email; its evidence is the stretch around the amount.
            String text = norm(sourceText);
            if (end - start > MAX_EVIDENCE) {
                start = Math.max(start, amtStart - MAX_EVIDENCE / 2);
                end = Math.min(end, Math.max(amtEnd, start + MAX_EVIDENCE));
                start = Math.max(start, end - MAX_EVIDENCE);
            }
            t.put("evidence", text.substring(start, end).trim());
            return Optional.of(t);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Learns a fund or share confirmation as a form: the whole email, with the date and every
     * figure the model read as blanks (a figure the email repeats must repeat), its other numbers
     * and dates generalised, and its wording kept. Every figure the model read must be in the
     * email, or the form would read the next one with a figure missing.
     */
    static Optional<Shape> learnForm(String sourceText, JsonNode t) {
        String text = norm(sourceText);
        if (text.length() > MAX_FORM_CHARS) return Optional.empty();
        String instrument = t.path("instrument_type").asText("").toUpperCase(Locale.ROOT);
        for (String f : FORM_REQUIRED.getOrDefault(instrument, List.of())) {
            if (!t.hasNonNull(f) || t.get(f).asText().isBlank()) return Optional.empty();
        }
        LocalDate date = date(t.path("transaction_date").asText(null));
        if (date == null) return Optional.empty();

        String quote = norm(t.path("evidence").asText(""));
        int quoted = quote.isEmpty() ? -1 : text.indexOf(quote);
        List<Span> spans = new ArrayList<>();
        Map<String, String> wanted = new java.util.LinkedHashMap<>();
        // Text first, longest first: a scheme name holds numbers ("Nifty 50") a figure must not take.
        List<Slot> order = new ArrayList<>(FORM_SLOTS);
        order.sort(Comparator.comparingInt((Slot x) -> x.kind() == Kind.TEXT ? 0 : x.kind() == Kind.CODE ? 1 : 2));
        for (Slot sl : order) {
            if (!t.hasNonNull(sl.field()) || t.get(sl.field()).asText().isBlank()) continue;
            String v = t.get(sl.field()).asText().trim();
            List<int[]> at = sl.kind() == Kind.NUMBER ? numberAt(text, v) : textAt(text, sl.kind() == Kind.CODE ? v : norm(v));
            at.removeIf(r -> !free(spans, r[0], r[1]));
            if (at.isEmpty()) return Optional.empty();   // a figure the model read that the email doesn't show
            if (sl.kind() == Kind.NUMBER) {
                // A figure is one blank, the one in the line the model quoted when it's there: a
                // quantity of 4 is also in the address, and every other number is generalised anyway.
                int[] pick = at.stream().filter(r -> quoted >= 0 && r[0] >= quoted && r[1] <= quoted + quote.length())
                    .findFirst().orElse(at.get(0));
                at = new ArrayList<>(List.of(pick));
            }
            String regex = switch (sl.kind()) {
                case NUMBER -> "\\d[\\d,]*(?:\\.\\d+)?";
                case CODE -> "[A-Za-z0-9/\\-]+";
                case TEXT -> ".{3,200}?";
            };
            for (int i = 0; i < at.size(); i++) {
                spans.add(new Span(at.get(i)[0], at.get(i)[1], sl.group(), i == 0 ? "(?<" + sl.group() + ">" + regex + ")" : "\\k<" + sl.group() + ">"));
            }
            wanted.put(sl.field(), v);
        }
        Span dateSpan = null;
        for (Span d : allDates(text, date)) {
            if (!free(spans, d.start(), d.end())) continue;
            boolean inQuote = quoted >= 0 && d.start() >= quoted && d.end() <= quoted + quote.length();
            if (dateSpan == null || inQuote) dateSpan = d;
            if (inQuote) break;
        }
        if (dateSpan == null) return Optional.empty();
        spans.add(dateSpan);
        spans.sort(Comparator.comparingInt(Span::start));
        for (int i = 1; i < spans.size(); i++) {
            // Two blanks with nothing between them can't be told apart.
            if (text.substring(spans.get(i - 1).end(), spans.get(i).start()).isBlank()
                    && (spans.get(i).regex().contains(".{") || spans.get(i - 1).regex().contains(".{"))) return Optional.empty();
        }

        StringBuilder regex = new StringBuilder();
        int cursor = 0, literalWords = 0;
        for (Span sp : spans) {
            literalWords += formLiteral(text.substring(cursor, sp.start()), regex);
            regex.append(sp.regex());
            cursor = sp.end();
        }
        literalWords += formLiteral(text.substring(cursor), regex);
        if (literalWords < MIN_LITERAL_WORDS * 3) return Optional.empty();

        ObjectNode constants = JSON.createObjectNode();
        for (String k : CONSTANTS) if (t.hasNonNull(k)) constants.put(k, t.get(k).asText());
        constants.put("date_format", DATES.get(dateFormIndex(dateSpan)).formatPattern());
        Shape shape = new Shape(regex.toString(), constants.toString(), norm(t.path("evidence").asText("")));

        Optional<JsonNode> back = read(shape, sourceText);
        if (back.isEmpty() || !date.toString().equals(back.get().path("transaction_date").asText())) return Optional.empty();
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            JsonNode got = back.get().get(e.getKey());
            if (got == null) return Optional.empty();
            boolean number = FORM_SLOTS.stream().anyMatch(x -> x.field().equals(e.getKey()) && x.kind() == Kind.NUMBER);
            boolean same = number ? decimal(got) != null && decimal(got).compareTo(new BigDecimal(e.getValue().replace(",", ""))) == 0
                : norm(got.asText()).equalsIgnoreCase(norm(e.getValue()));
            if (!same) return Optional.empty();
        }
        return Optional.of(shape);
    }

    /** Where a figure is written, in any of the ways it may be ("1,250.00" for 1250). */
    private static List<int[]> numberAt(String text, String value) {
        BigDecimal want;
        try {
            want = new BigDecimal(value.replace(",", ""));
        } catch (Exception e) {
            return new ArrayList<>();
        }
        List<int[]> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            boolean digitBefore = m.start() > 0 && (Character.isLetterOrDigit(text.charAt(m.start() - 1)) || text.charAt(m.start() - 1) == '.');
            if (digitBefore) continue;
            String raw = m.group().replaceAll("[,.]+$", "");
            if (sameAmount(raw, want)) out.add(new int[]{m.start(), m.start() + raw.length()});
        }
        return out;
    }

    /** Where a name or code is written, ignoring case, as a whole word. */
    private static List<int[]> textAt(String text, String value) {
        List<int[]> out = new ArrayList<>();
        if (value.length() < 2) return out;
        Matcher m = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(value) + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) out.add(new int[]{m.start(), m.end()});
        return out;
    }

    private static List<Span> allDates(String text, LocalDate date) {
        List<Span> out = new ArrayList<>();
        for (int i = 0; i < DATES.size(); i++) {
            Matcher m = DATES.get(i).pattern().matcher(text);
            while (m.find()) {
                try {
                    if (LocalDate.parse(m.group(), DATES.get(i).format()).equals(date)) {
                        out.add(new Span(m.start(), m.end(), "dt#" + i, "(?<dt>" + DATES.get(i).pattern().pattern() + ")"));
                    }
                } catch (Exception ignored) {
                    // not this form
                }
            }
        }
        out.sort(Comparator.comparingInt(Span::start));
        return out;
    }

    /** As {@link #literal}, with every other number and date in a form generalised as a whole. */
    private static int formLiteral(String s, StringBuilder out) {
        int words = 0;
        Matcher m = Pattern.compile("\\s+|(?<![A-Za-z0-9])[A-Za-z]{5}\\d{4}[A-Za-z](?![A-Za-z0-9])|\\S+@\\S+|\\d{1,2}-[A-Za-z]{3}-\\d{2,4}|\\d{1,2} [A-Za-z]{3} \\d{4}|\\d[\\d,]*(?:\\.\\d+)?|[^\\s\\d]+").matcher(s);
        while (m.find()) {
            String tok = m.group();
            if (tok.isBlank()) out.append("\\s*");
            // A PAN or an email address is not kept in the form, only its shape.
            else if (tok.matches("[A-Za-z]{5}\\d{4}[A-Za-z]")) out.append("[A-Za-z]{5}\\d{4}[A-Za-z]");
            else if (tok.contains("@")) out.append("\\S+@\\S+");
            else if (Character.isDigit(tok.charAt(0)) && tok.matches(".*[A-Za-z].*")) out.append("\\d{1,2}[- ][A-Za-z]{3}[- ]\\d{2,4}");
            else if (Character.isDigit(tok.charAt(0))) out.append("\\d[\\d,]*(?:\\.\\d+)?");
            else {
                out.append(Pattern.quote(tok));
                if (tok.chars().filter(Character::isLetter).count() >= 2) words++;
            }
        }
        return words;
    }

    /** The extractor's JSON for a learned read, as the fixed parsers return it. */
    public static String asExtraction(JsonNode t) {
        ObjectNode root = JSON.createObjectNode();
        root.set("transactions", JSON.createArrayNode().add(t));
        root.set("closing_balances", JSON.createArrayNode());
        root.put("confidence", 1.0);
        return root.toString();
    }

    /** "Alert : Rs 500 debited 12-10-26" and "alert : rs 900 debited 1-11-26" share a shape. */
    public static String subjectShape(String subject) {
        return subject == null ? "" : subject.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
    }

    // ── helpers ──

    static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String withTrailingWords(String text, int start, int end) {
        int e = end, words = 0;
        while (e < text.length() && words < TRAILING_WORDS) {
            int next = text.indexOf(' ', e + 1);
            e = next < 0 ? text.length() : next;
            words++;
        }
        return text.substring(start, e);
    }

    /** Appends a literal stretch: words quoted, numbers generalised, spaces flexible. Returns its word count. */
    private static int literal(String s, StringBuilder out) {
        int words = 0;
        Matcher m = Pattern.compile("\\s+|\\d+|[^\\s\\d]+").matcher(s);
        while (m.find()) {
            String tok = m.group();
            if (tok.isBlank()) out.append("\\s*");
            else if (Character.isDigit(tok.charAt(0))) out.append("\\d+");
            else {
                out.append(Pattern.quote(tok));
                if (tok.chars().filter(Character::isLetter).count() >= 2) words++;
            }
        }
        return words;
    }

    private static Span findDate(String sentence, LocalDate date) {
        for (int i = 0; i < DATES.size(); i++) {
            DateForm d = DATES.get(i);
            Matcher m = d.pattern().matcher(sentence);
            while (m.find()) {
                try {
                    if (LocalDate.parse(m.group(), d.format()).equals(date)) {
                        return new Span(m.start(), m.end(), "dt#" + i, "(?<dt>" + d.pattern().pattern() + ")");
                    }
                } catch (Exception ignored) {
                    // not this form
                }
            }
        }
        return null;
    }

    private static int dateFormIndex(Span s) {
        return Integer.parseInt(s.group().substring(3));
    }

    private static Span find(String sentence, Pattern p, java.util.function.Predicate<String> ok, String group, String regex, List<Span> taken) {
        Matcher m = p.matcher(sentence);
        while (m.find()) {
            if (ok.test(m.group()) && free(taken, m.start(), m.end())) return new Span(m.start(), m.end(), group, regex);
        }
        return null;
    }

    private static boolean sameAmount(String raw, BigDecimal amount) {
        try {
            return new BigDecimal(raw.replace(",", "")).compareTo(amount) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean free(List<Span> spans, int start, int end) {
        for (Span s : spans) if (start < s.end() && s.start() < end) return false;
        return true;
    }

    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        try {
            return new BigDecimal(n.asText().replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
