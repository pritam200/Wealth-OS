import { apiClient } from './client';

export interface ReconciliationIssue {
  domain: 'PORTFOLIO' | 'FD' | 'RD' | 'NET_WORTH';
  type: string;
  description: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  referenceId: number | null;
}

export interface ReconciliationReport {
  issueCount: number;
  issues: ReconciliationIssue[];
}

// The generalized "flag it, never silently show a wrong number" check across every financial
// domain (FD/RD lifecycle problems, net-worth drift) — portfolio holding issues are also
// included here but already surface via portfolioApi.integrityCheck()'s own banner.
export const reconciliationApi = {
  getReport: () => apiClient.get<ReconciliationReport>('/api/reconciliation/report'),
};
