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
 * <p>Only bank and UPI alerts with one transaction are learned — the bulk of the model's calls,
 * and the case where a sentence's wording fixes its meaning. Statements, contract notes and
 * fund mail have their own readers.
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
        if (sourceText == null || t == null) return Optional.empty();
        String instrument = t.path("instrument_type").asText("").toUpperCase(Locale.ROOT);
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
        String amt = m.group("amt"), dt = m.group("dt");
        String mer = group(m, "mer"), card = group(m, "card"), ref = group(m, "ref");
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
            t.put("evidence", norm(sourceText).substring(start, end).trim());
            return Optional.of(t);
        } catch (Exception e) {
            return Optional.empty();
        }
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
