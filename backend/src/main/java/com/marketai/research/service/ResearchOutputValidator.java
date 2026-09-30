package com.marketai.research.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketai.research.model.*;

import java.util.*;

/**
 * Turns model JSON into a report only after checking it: required sections present, enums from
 * the allowed sets, and every citation an id that exists in the context. A claim's basis
 * (DATA, FILING, NEWS …) is derived from what it cites — never taken from the model — and a
 * claim that cites nothing valid is labelled LLM_INTERPRETATION.
 */
public final class ResearchOutputValidator {

    public static final List<String> ACTIONABILITY = List.of("BUY", "SELL", "HOLD", "NO_ACTIONABLE_SIGNAL",
            "INSUFFICIENT_DATA", "CONFLICTING_EVIDENCE", "RESEARCH_REQUIRED");
    static final Set<String> QUALITY = Set.of("HIGH", "MEDIUM", "LOW");
    static final Set<String> RISK = Set.of("LOW", "MEDIUM", "HIGH");
    static final int MAX_LIST = 6, MAX_TEXT = 700;
    /** A statement citing more than this is listing, not evidencing; the first ones are kept. */
    static final int MAX_CITATIONS = 6;

    /** Precedence when a claim cites several kinds of source: the most authoritative is named first. */
    static final List<String> BASIS_ORDER = List.of("FILING", "DATA", "CALCULATION", "MODEL", "PORTFOLIO", "NEWS");

    public static class Invalid extends RuntimeException {
        public Invalid(String message) { super(message); }
    }

    private final Map<String, Fact> facts = new HashMap<>();
    private final Map<String, Evidence> evidence = new HashMap<>();
    private final Set<String> dropped = new LinkedHashSet<>();
    private final Set<String> citedEvidence = new LinkedHashSet<>();
    private final List<String> notes = new ArrayList<>();
    private int truncated;

    public ResearchOutputValidator(List<Fact> facts, List<Evidence> evidence) {
        facts.forEach(f -> this.facts.put(f.id(), f));
        evidence.forEach(e -> this.evidence.put(e.id(), e));
    }

    public ResearchReport report(JsonNode n) {
        if (n == null || !n.isObject()) throw new Invalid("reply is not a JSON object");
        Claim summary = claim(n.get("executiveSummary"));
        Claim conclusion = claim(n.get("researchConclusion"));
        if (summary == null) throw new Invalid("executiveSummary is missing");
        if (conclusion == null) throw new Invalid("researchConclusion is missing");
        String act = upper(n.path("actionability").asText(null));
        if (act == null) throw new Invalid("actionability is missing");
        act = act.replace(' ', '_');
        if (!ACTIONABILITY.contains(act)) {
            notes.add("Model gave actionability '" + act + "', which is not an allowed value; treated as RESEARCH_REQUIRED");
            act = "RESEARCH_REQUIRED";
        }
        String quality = upper(n.path("evidenceQuality").asText(null));
        if (quality == null || !QUALITY.contains(quality)) {
            if (quality != null) notes.add("Model gave evidenceQuality '" + quality + "'; treated as LOW");
            quality = "LOW";
        }
        return ResearchReport.builder()
                .executiveSummary(summary)
                .fundamentalAssessment(claim(n.get("fundamentalAssessment")))
                .technicalAssessment(claim(n.get("technicalAssessment")))
                .marketContext(claim(n.get("marketContext")))
                .sectorContext(claim(n.get("sectorContext")))
                .newsAssessment(claim(n.get("newsAssessment")))
                .valuationAssessment(claim(n.get("valuationAssessment")))
                .portfolioImpact(claim(n.get("portfolioImpact")))
                .forecastInterpretation(claim(n.get("forecastInterpretation")))
                .bullCase(claim(n.get("bullCase")))
                .baseCase(claim(n.get("baseCase")))
                .bearCase(claim(n.get("bearCase")))
                .crossChecks(crossChecks(n.get("crossChecks")))
                .contradictingEvidence(claims(n.get("contradictingEvidence")))
                .keyRisks(claims(n.get("keyRisks")))
                .catalysts(claims(n.get("catalysts")))
                .missingInformation(strings(n.get("missingInformation")))
                .evidenceQuality(quality)
                .researchConclusion(conclusion)
                .actionability(act)
                .sources(citedSources())
                .build();
    }

    public DevilsAdvocate devilsAdvocate(JsonNode n) {
        if (n == null || !n.isObject()) throw new Invalid("reply is not a JSON object");
        String risk = upper(n.path("thesisRisk").asText(null));
        if (risk == null) throw new Invalid("thesisRisk is missing");
        if (!RISK.contains(risk)) {
            notes.add("Review gave thesisRisk '" + risk + "'; treated as HIGH");
            risk = "HIGH";
        }
        return DevilsAdvocate.builder()
                .contradictoryEvidence(claims(n.get("contradictoryEvidence")))
                .overlookedRisks(claims(n.get("overlookedRisks")))
                .dataQualityProblems(claims(n.get("dataQualityProblems")))
                .upcomingCatalysts(claims(n.get("upcomingCatalysts")))
                .technicalSignalFailure(claims(n.get("technicalSignalFailure")))
                .fundamentalThesisFailure(claims(n.get("fundamentalThesisFailure")))
                .forecastRangeReliability(claims(n.get("forecastRangeReliability")))
                .thesisRisk(risk)
                .verdict(claim(n.get("verdict")))
                .build();
    }

    public List<String> droppedCitations() { return List.copyOf(dropped); }
    public List<String> notes() {
        List<String> out = new ArrayList<>(notes);
        if (truncated > 0) out.add(truncated + " statement(s) cited more than " + MAX_CITATIONS + " ids; only the first " + MAX_CITATIONS + " were kept.");
        return out;
    }

    Claim claim(JsonNode n) {
        if (n == null || n.isNull()) return null;
        String text;
        List<String> cites = new ArrayList<>();
        if (n.isTextual()) text = n.asText();
        else if (n.isObject()) {
            text = n.path("text").asText(null);
            JsonNode ev = n.get("evidence");
            if (ev != null && ev.isArray()) ev.forEach(x -> cites.add(x.asText()));
            else if (ev != null && ev.isTextual()) cites.addAll(Arrays.asList(ev.asText().split("[,\\s]+")));
        } else return null;
        if (text == null || text.isBlank()) return null;
        text = text.trim();
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT - 1) + "…";
        List<String> valid = new ArrayList<>();
        for (String c : cites) {
            String id = c.trim().replaceAll("[\\[\\]]", "").toUpperCase(Locale.ROOT);
            if (id.isEmpty()) continue;
            if (facts.containsKey(id) || evidence.containsKey(id)) {
                if (!valid.contains(id)) valid.add(id);
            }
            else dropped.add(id);
        }
        if (valid.size() > MAX_CITATIONS) {
            truncated++;
            valid = new ArrayList<>(valid.subList(0, MAX_CITATIONS));
        }
        valid.stream().filter(evidence::containsKey).forEach(citedEvidence::add);
        return new Claim(text, valid, basis(valid));
    }

    String basis(List<String> ids) {
        Set<String> kinds = new HashSet<>();
        for (String id : ids) {
            Fact f = facts.get(id);
            if (f != null) {
                if ("PORTFOLIO".equals(f.category()) || "PORTFOLIO".equals(f.basis())) kinds.add("PORTFOLIO");
                else kinds.add(f.basis());
                continue;
            }
            Evidence e = evidence.get(id);
            if (e != null) kinds.add(switch (e.kind()) {
                case "FILING", "CORPORATE_ACTION", "EVENT", "RESULTS_FILING" -> "FILING";
                default -> "NEWS";
            });
        }
        if (kinds.isEmpty()) return "LLM_INTERPRETATION";
        List<String> out = new ArrayList<>();
        for (String b : BASIS_ORDER) if (kinds.contains(b)) out.add(b);
        return String.join("+", out);
    }

    private List<Claim> claims(JsonNode n) {
        List<Claim> out = new ArrayList<>();
        if (n == null || !n.isArray()) return out;
        for (JsonNode x : n) {
            Claim c = claim(x);
            if (c != null && !isNone(c.text())) out.add(c);
            if (out.size() >= MAX_LIST) break;
        }
        return out;
    }

    private List<CrossCheck> crossChecks(JsonNode n) {
        Map<String, CrossCheck> got = new LinkedHashMap<>();
        if (n != null && n.isArray()) for (JsonNode x : n) {
            String q = upper(x.path("question").asText(null));
            if (q == null || !CrossCheck.QUESTIONS.containsKey(q) || got.containsKey(q)) continue;
            String a = upper(x.path("answer").asText(null));
            if (a == null || !CrossCheck.ANSWERS.contains(a)) a = "UNKNOWN";
            got.put(q, new CrossCheck(q, CrossCheck.QUESTIONS.get(q), a, claim(x)));
        }
        List<CrossCheck> out = new ArrayList<>();
        for (Map.Entry<String, String> q : CrossCheck.QUESTIONS.entrySet()) {
            out.add(got.getOrDefault(q.getKey(), new CrossCheck(q.getKey(), q.getValue(), "UNKNOWN",
                    new Claim("Not answered by the model.", List.of(), "LLM_INTERPRETATION"))));
        }
        return out;
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) return out;
        for (JsonNode x : n) {
            String s = x.isTextual() ? x.asText() : x.path("text").asText(null);
            if (s != null && !s.isBlank() && !isNone(s)) out.add(s.trim().length() > 300 ? s.trim().substring(0, 299) + "…" : s.trim());
            if (out.size() >= 10) break;
        }
        return out;
    }

    /** Ids of the evidence items the report cited, in order of first citation. */
    private List<String> citedSources() {
        return List.copyOf(citedEvidence);
    }

    private static boolean isNone(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT).replaceAll("[.!]$", "");
        return t.equals("none") || t.equals("none found") || t.equals("n/a");
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
