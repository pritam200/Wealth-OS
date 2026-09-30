package com.marketai.research.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The second pass: assume the thesis is wrong and look for what would show it. Stored separately. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DevilsAdvocate {
    private List<Claim> contradictoryEvidence;
    private List<Claim> overlookedRisks;
    private List<Claim> dataQualityProblems;
    private List<Claim> upcomingCatalysts;
    private List<Claim> technicalSignalFailure;
    private List<Claim> fundamentalThesisFailure;
    private List<Claim> forecastRangeReliability;
    /** HIGH | MEDIUM | LOW — how seriously the challenge undermines the initial thesis. */
    private String thesisRisk;
    private Claim verdict;
}
