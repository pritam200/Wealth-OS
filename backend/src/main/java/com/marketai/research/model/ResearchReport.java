package com.marketai.research.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The research analyst pass, validated: every claim carries its citations and basis. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ResearchReport {
    private Claim executiveSummary;
    private Claim fundamentalAssessment;
    private Claim technicalAssessment;
    private Claim marketContext;
    private Claim sectorContext;
    private Claim newsAssessment;
    private Claim valuationAssessment;
    private Claim portfolioImpact;
    private Claim forecastInterpretation;
    private Claim bullCase;
    private Claim baseCase;
    private Claim bearCase;
    private List<CrossCheck> crossChecks;
    private List<Claim> contradictingEvidence;
    private List<Claim> keyRisks;
    private List<Claim> catalysts;
    private List<String> missingInformation;
    /** HIGH | MEDIUM | LOW | INSUFFICIENT */
    private String evidenceQuality;
    private Claim researchConclusion;
    /** BUY | SELL | HOLD | NO_ACTIONABLE_SIGNAL | CONFLICTING_EVIDENCE | RESEARCH_REQUIRED | INSUFFICIENT_DATA */
    private String actionability;
    /** Fact and evidence ids the report cites anywhere. */
    private List<String> sources;
}
