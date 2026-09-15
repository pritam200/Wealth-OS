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
}

/** Emails the classifier could not book confidently. Nothing here has been written to a
 *  financial table yet — accepting one imports it through the normal fingerprint-gated path. */
export const reviewApi = {
  list: (all = false) => apiClient.get<EmailReviewItem[]>('/api/review', { params: { all } }),
  count: () => apiClient.get<{ pending: number }>('/api/review/count'),
  decide: (id: number, decision: ReviewDecision) =>
    apiClient.post<EmailReviewItem>(`/api/review/${id}/decision`, decision),
};
