import { apiClient } from './client';

import type { RecordKind } from './provenance';

export type AdvisorTool =
  | 'NET_WORTH' | 'RECENT_EXPENSES' | 'UPCOMING_REMINDERS' | 'PORTFOLIO_HOLDINGS'
  | 'NET_WORTH_CHANGE' | 'INVESTMENT_PLAN' | 'IMPORTED_TRANSACTIONS' | 'VALUE_CHANGE'
  | 'DUPLICATES' | 'MISSING_TRANSACTIONS' | 'HOLDING_SOURCES' | 'UNKNOWN';

/** A ledger record an answer rests on; kind + id open its source document. */
export interface AdvisorEvidence {
  kind: RecordKind | null;
  id: number | null;
  date: string | null;
  label: string;
  amount: number | null;
  source: string | null;
}

export interface AdvisorAskResponse {
  answer: string;
  tool: AdvisorTool;
  groundedData: Record<string, unknown>;
  evidence: AdvisorEvidence[];
  available: boolean;
  generatedAt: string;
}

export const advisorApi = {
  ask: (question: string) =>
    apiClient.post<AdvisorAskResponse>('/api/advisor/ask', { question }),
};
