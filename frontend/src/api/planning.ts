import { apiClient } from './client';

/* ── Reminders ── */
export interface Reminder {
  type: string; title: string; subtitle: string;
  dueDate: string; daysUntil: number; amount: number;
  severity: 'OVERDUE' | 'DUE_SOON' | 'UPCOMING';
}
export const reminderApi = {
  list: () => apiClient.get<Reminder[]>('/api/reminders'),
};

/* ── Goals ── */
export interface GoalRequest {
  name: string; category?: string; targetAmount: number;
  currentSaved?: number; monthlyContribution?: number;
  expectedReturn?: number; targetDate?: string;
}
export interface GoalResponse {
  id: number; name: string; category: string;
  targetAmount: number; currentSaved: number; monthlyContribution: number;
  expectedReturn: number; targetDate: string | null;
  progressPercent: number; projectedValue: number; onTrack: boolean;
  monthsToTarget: number | null; requiredMonthly: number | null;
  status: 'ON_TRACK' | 'SHORTFALL' | 'ACHIEVED';
}
export const GOAL_CATEGORIES = ['Retirement', 'Home', 'Car', 'Education', 'Travel', 'Emergency', 'Other'];
export const goalApi = {
  list: () => apiClient.get<GoalResponse[]>('/api/goals'),
  add: (r: GoalRequest) => apiClient.post<GoalResponse>('/api/goals', r),
  update: (id: number, r: Partial<GoalRequest>) => apiClient.put<GoalResponse>(`/api/goals/${id}`, r),
  delete: (id: number) => apiClient.delete(`/api/goals/${id}`),
};

/* ── Net worth trend ── */
export interface NetWorthSnapshot {
  id: number; snapshotDate: string; totalAssets: number; netWorth: number;
}
export const netWorthApi = {
  series: () => apiClient.get<NetWorthSnapshot[]>('/api/networth/series'),
  // Always computed server-side from the same canonical summary as wealthApi.getSummary() —
  // never pass a client-computed total here, that's the exact bug this was rewritten to fix.
  snapshot: () => apiClient.post<NetWorthSnapshot>('/api/networth/snapshot', {}),
};

/* ── Tax ── */
export interface TaxResponse {
  fyLabel: string;
  capitalGains: number; dividendIncome: number; interestIncome: number; salaryIncome: number;
  totalTaxableInvestmentIncome: number;
  estimatedTaxLow: number; estimatedTaxHigh: number;
  notes: string[];
}
export const taxApi = {
  summary: (fyStartYear?: number) =>
    apiClient.get<TaxResponse>(`/api/tax/summary${fyStartYear ? `?fyStartYear=${fyStartYear}` : ''}`),
};
