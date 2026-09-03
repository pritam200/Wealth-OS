import { apiClient } from './client';

export interface InstallmentStatus {
  dueDate: string;
  status: 'COMPLETED' | 'MISSED' | 'UPCOMING';
  actualAmount: number | null;
}

export interface RecurringInvestment {
  id: number;
  type: 'SIP' | 'PPF' | 'NPS';
  label: string;
  linkedSymbol: string | null;
  amount: number;
  startDate: string;
  tenureMonths: number | null;
  status: 'ACTIVE' | 'PAUSED' | 'COMPLETED';
  installments: InstallmentStatus[];
  completedCount: number;
  missedCount: number;
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
