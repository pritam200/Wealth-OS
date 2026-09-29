import { apiClient } from './client';

export interface InstallmentStatus {
  dueDate: string;
  /** PARTIAL = bought less than scheduled; FAILED = debit recorded as failed; PAUSED = schedule paused then. */
  status: 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'MISSED' | 'PAUSED' | 'UPCOMING';
  /** The amount the schedule asked for on that date. */
  expectedAmount?: number | null;
  actualAmount: number | null;
  note?: string | null;
}

export interface RecurringInvestment {
  id: number;
  type: 'SIP' | 'PPF' | 'NPS';
  label: string;
  linkedSymbol: string | null;
  amount: number;
  startDate: string;
  tenureMonths: number | null;
  status: 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
  installments: InstallmentStatus[];
  completedCount: number;
  missedCount: number;
  partialCount?: number;
  failedCount?: number;
  pausedCount?: number;
}

export interface RecurringInvestmentRequest {
  type: 'SIP' | 'PPF' | 'NPS';
  label: string;
  linkedSymbol?: string;
  amount: number;
  startDate?: string;
  tenureMonths?: number;
}

export const scheduledInvestmentApi = {
  list: () => apiClient.get<RecurringInvestment[]>('/api/recurring-investments'),
  add: (req: RecurringInvestmentRequest) => apiClient.post<RecurringInvestment>('/api/recurring-investments', req),
  delete: (id: number) => apiClient.delete(`/api/recurring-investments/${id}`),
};
