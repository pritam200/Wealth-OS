import { apiClient } from './client';

export interface CardResponse {
  id: number;
  name: string; issuer: string; network: string; lastFour: string | null;
  annualFee: number; pointValue: number; pointsBalance: number;
  billingDay: number | null; dueDay: number | null;
  benefits: string | null; bestFor: string | null;
  rewardRates: Record<string, number>;
  pointsCashValue: number;
  currentDue: number | null;
  currentDueDate: string | null;
  creditLimit: number | null;
}

export interface CatalogEntry {
  name: string; issuer: string; network: string; bestFor: string;
  annualFee: number; pointValue: number;
  benefits: string[]; rewardRates: Record<string, number>;
}

export interface CardRequest {
  name?: string; issuer?: string; network?: string; lastFour?: string;
  annualFee?: number; pointValue?: number; pointsBalance?: number;
  billingDay?: number; dueDay?: number; creditLimit?: number; benefits?: string; bestFor?: string;
  rewardRates?: Record<string, number>;
  catalogName?: string;
}

export interface RecommendResult {
  cardId: number | null; cardName: string; issuer: string;
  network?: string;
  rewardRate: number; expectedReward: number; reason: string;
  best: boolean; fromCatalog: boolean;
  eligible: boolean; ineligibilityNote?: string;
}

export interface PointsTip {
  cardId: number; cardName: string; pointsBalance: number;
  cashValue: number; bestValue: number; recommendation: string;
}

export interface StatementFlag {
  type: string;
  severity: 'ALERT' | 'WARN' | 'INFO';
  title: string;
  detail: string | null;
  amount: number | null;
}

export interface CardStatement {
  id: number;
  statementDate: string | null; dueDate: string | null;
  totalDue: number; minimumDue: number | null; previousBalance: number | null;
  creditLimit: number | null; availableLimit: number | null; cardLastFour: string | null;
  arithmeticMismatch: boolean | null;
  flags: StatementFlag[];
  /** Null for a statement read before its lines were kept. */
  tally: TallySummary | null;
}

export interface TallySummary {
  lines: number; matched: number; added: number; missing: number; credits: number;
  debitTotal: number; creditTotal: number;
}

export type LineStatus = 'MATCHED' | 'ADDED' | 'MISSING' | 'IGNORED' | 'CREDIT';

export interface StatementLine {
  id: number; txnDate: string | null; merchant: string | null; amount: number; debit: boolean;
  currency: string | null; category: string | null; evidence: string | null;
  status: LineStatus; expenseId: number | null; expenseDate: string | null; expenseDescription: string | null;
}

export interface StatementDetail { statement: CardStatement; lines: StatementLine[]; }

export interface DetectedCard {
  id: number; issuer: string | null; lastFour: string | null; suggestedName: string | null;
  seenCount: number; pendingStatements: number; firstSeenAt: string; lastSeenAt: string;
  likelyCardIds: number[];
}

export interface CardVerdict {
  cardId: number | null; cardName: string;
  annualFee: number | null; projectedAnnualRewards: number | null; netValue: number | null;
  breakEvenSpend: number | null; feeWaiverSpend: number | null; verdict: string; verdictBasis: string | null;
}

export interface SpendProfile {
  days: number; observedSpend: number; projectedAnnualSpend: number; coverage: number; note: string | null;
  byCategory: { category: string; observedAmount: number; projectedAnnual: number; txnCount: number }[];
}

export interface CatalogRanking {
  spendWindow: SpendProfile; trustworthy: boolean; caveats: string[]; candidates: CardVerdict[];
}

export interface ChargesReport {
  statementsChecked: number;
  bankChargesTotal: number;
  unusualCount: number;
  statements: {
    cardId: number; cardName: string; cardLastFour: string | null; statementId: number;
    statementDate: string | null; dueDate: string | null; flags: StatementFlag[];
  }[];
}

export const cardApi = {
  catalog:    () => apiClient.get<CatalogEntry[]>('/api/cards/catalog'),
  categories: () => apiClient.get<string[]>('/api/cards/categories'),
  list:       () => apiClient.get<CardResponse[]>('/api/cards'),
  add:        (r: CardRequest) => apiClient.post<CardResponse>('/api/cards', r),
  update:     (id: number, r: CardRequest) => apiClient.put<CardResponse>(`/api/cards/${id}`, r),
  delete:     (id: number) => apiClient.delete(`/api/cards/${id}`),
  updatePoints: (id: number, pointsBalance: number) =>
    apiClient.put<CardResponse>(`/api/cards/${id}/points`, { pointsBalance }),
  recommend:  (category: string, amount: number, merchant?: string) =>
    apiClient.post<RecommendResult[]>('/api/cards/recommend', { category, amount, merchant }),
  statements: (id: number) => apiClient.get<CardStatement[]>(`/api/cards/${id}/statements`),
  applyStatement: (id: number, statementId?: number) =>
    apiClient.post<{ changes: string[] }>(`/api/cards/${id}/apply-statement`, null,
      statementId ? { params: { statementId } } : undefined),
  charges: () => apiClient.get<ChargesReport>('/api/cards/charges'),
  statement: (cardId: number, statementId: number) =>
    apiClient.get<StatementDetail>(`/api/cards/${cardId}/statements/${statementId}`),
  tally: (cardId: number, statementId: number) =>
    apiClient.post<StatementDetail>(`/api/cards/${cardId}/statements/${statementId}/tally`),
  lineAction: (cardId: number, lineId: number, action: 'add' | 'remove') =>
    apiClient.post<StatementDetail>(`/api/cards/${cardId}/statement-lines/${lineId}/${action}`),
  detected: () => apiClient.get<DetectedCard[]>('/api/cards/detected'),
  addDetected: (id: number, name?: string) =>
    apiClient.post<{ cardId: number; name: string }>(`/api/cards/detected/${id}/add`, name ? { name } : {}),
  linkDetected: (id: number, cardId: number) =>
    apiClient.post<{ cardId: number; name: string }>(`/api/cards/detected/${id}/link/${cardId}`),
  dismissDetected: (id: number) => apiClient.post(`/api/cards/detected/${id}/dismiss`),
  catalogRanking: () => apiClient.get<CatalogRanking>('/api/cards/optimizer/catalog-all'),
  pointsTips: () => apiClient.get<PointsTip[]>('/api/cards/points-tips'),
};
