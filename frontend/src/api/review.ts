import { apiClient } from './client';

export type ReviewStatus = 'PENDING' | 'ACCEPTED' | 'EDITED' | 'REJECTED';

export interface EmailReviewItem {
  id: number;
  gmailMessageId?: string;
  sender?: string;
  subject?: string;
  proposedType?: string;
  confidence?: number;
  reviewReason?: string;
  reasoning?: string;
  evidence?: string;
  extractedFields?: string;
  amount?: number;
  transactionDate?: string;
  counterparty?: string;
  status: ReviewStatus;
  resolvedAt?: string;
  resolutionNote?: string;
  createdAt?: string;
}

export interface ReviewDecision {
  decision: 'ACCEPT' | 'EDIT' | 'REJECT';
  correctedType?: string;
  correctedAmount?: number;
  correctedDate?: string;
  correctedCounterparty?: string;
  correctedCategory?: string;
  note?: string;
  /** ACCEPT and REJECT apply to every item of the same kind, now and later, unless false. */
  rememberForSimilar?: boolean;
}

/** A remembered accept/reject, applied to every later item of the same kind. */
export interface ReviewRule {
  id: number;
  decision: 'ACCEPT' | 'REJECT';
  sender?: string;
  subject?: string;
  reviewReason?: string;
  proposedType?: string;
  createdAt?: string;
}

/** Emails the classifier could not book confidently. Nothing here has been written to a
 *  financial table yet — accepting one imports it through the normal fingerprint-gated path. */
export const reviewApi = {
  list: (all = false) => apiClient.get<EmailReviewItem[]>('/api/review', { params: { all } }),
  count: () => apiClient.get<{ pending: number }>('/api/review/count'),
  decide: (id: number, decision: ReviewDecision) =>
    apiClient.post<EmailReviewItem>(`/api/review/${id}/decision`, decision),
  /** Item id → kind; items sharing a kind are the same question from the same sender. */
  kinds: () => apiClient.get<Record<string, string>>('/api/review/kinds'),
  rules: () => apiClient.get<ReviewRule[]>('/api/review/rules'),
  forgetRule: (id: number) => apiClient.delete(`/api/review/rules/${id}`),
};
