import { apiClient } from './client';

export interface ScheduledInvestmentSummary {
  sourceKind: 'RECURRING_INVESTMENT' | 'RECURRING_DEPOSIT';
  sourceId: number;
  investmentType: string;
  label: string;
  amount: number;
  frequency: string;
  dueDayOfMonth: number | null;
  sourceAccountId: number | null;
  destination: string | null;
  status: string;
  startDate: string;
}

export const scheduledInvestmentsApi = {
  listAll: () => apiClient.get<ScheduledInvestmentSummary[]>('/api/scheduled-investments'),
};
