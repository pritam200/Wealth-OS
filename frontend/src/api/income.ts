import { apiClient } from './client';

export interface IncomeRequest {
  description: string;
  amount: number;
  source: string;
  incomeDate: string;
  note?: string;
}

export interface IncomeResponse {
  id: number;
  description: string;
  amount: number;
  source: string;
  incomeDate: string;
  payer: string | null;
  paymentMethod: string | null;
  sourceEmailId: string | null;
  note: string | null;
  createdAt: string;
}

export interface IncomeSummary {
  total: number;
  bySource: Record<string, number>;
}

export const INCOME_SOURCES = [
  'Salary', 'Freelance', 'Dividend', 'Interest', 'Rental', 'Business', 'Other',
];

export const incomeApi = {
  list: (year: number, month: number) =>
    apiClient.get<IncomeResponse[]>(`/api/income?year=${year}&month=${month}`),
  add: (r: IncomeRequest) =>
    apiClient.post<IncomeResponse>('/api/income', r),
  update: (id: number, r: IncomeRequest) =>
    apiClient.put<IncomeResponse>(`/api/income/${id}`, r),
  delete: (id: number) =>
    apiClient.delete(`/api/income/${id}`),
  summary: (year: number, month: number) =>
    apiClient.get<IncomeSummary>(`/api/income/summary?year=${year}&month=${month}`),
  bySource: (source: string, year: number) =>
    apiClient.get<IncomeResponse[]>(`/api/income/by-source?source=${encodeURIComponent(source)}&year=${year}`),
};
