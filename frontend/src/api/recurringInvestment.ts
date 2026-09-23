import { apiClient } from './client';

export type RecurringInvestmentType = 'SIP' | 'PPF' | 'NPS' | 'STOCK_SIP' | 'ETF_SIP' | 'BROKER_RECURRING';

export interface RecurringInvestmentRequest {
  type: RecurringInvestmentType;
  label: string;
  linkedSymbol?: string;
  sourceAccountId?: number;
  amount: number;
  startDate?: string;
  tenureMonths?: number;
  status?: string;
}

export interface RecurringInvestmentUpdateRequest {
  amount?: number;
  status?: 'ACTIVE' | 'PAUSED' | 'COMPLETED';
  sourceAccountId?: number;
  linkedSymbol?: string;
  label?: string;
}

export interface InstallmentStatus {
  dueDate: string;
  status: 'COMPLETED' | 'MISSED' | 'UPCOMING';
  actualAmount: number | null;
}

export interface AmountChange {
  changedAt: string;
  field: string;
  oldValue: string | null;
  newValue: string | null;
}

export interface RecurringInvestmentResponse {
  id: number;
  type: RecurringInvestmentType;
  label: string;
  linkedSymbol: string | null;
  sourceAccountId: number | null;
  amount: number;
  startDate: string;
  tenureMonths: number | null;
  status: string;
  installments: InstallmentStatus[];
  completedCount: number;
  missedCount: number;
  amountHistory: AmountChange[];
}

export const recurringInvestmentApi = {
  list: () => apiClient.get<RecurringInvestmentResponse[]>('/api/recurring-investments'),
  add: (r: RecurringInvestmentRequest) => apiClient.post<RecurringInvestmentResponse>('/api/recurring-investments', r),
  update: (id: number, r: RecurringInvestmentUpdateRequest) =>
    apiClient.patch<RecurringInvestmentResponse>(`/api/recurring-investments/${id}`, r),
  delete: (id: number) => apiClient.delete(`/api/recurring-investments/${id}`),
};
