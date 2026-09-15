package com.marketai.reconciliation.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ReconciliationReportDto {
    private int issueCount;
    private List<ReconciliationIssue> issues;
}
