import { apiClient } from './client';

export interface ProjectedEvent {
  label: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE' | 'SIP' | 'FD_MATURITY' | 'RD_MATURITY';
  // False for events on an explicit schedule already stored (SIP debit date, FD/RD maturity
  // date) — true for anything inferred from historical averages (recurring income, expense
  // run-rate), where the date and/or amount is a best guess, not a fact on record.
  estimated: boolean;
}

export interface DailyProjection {
  date: string;
  projectedBalance: number;
  events: ProjectedEvent[];
  hasEstimatedComponent: boolean;
}

export const cashflowApi = {
  forecast: (days = 90) => apiClient.get<DailyProjection[]>(`/api/cashflow/forecast?days=${days}`),
};
