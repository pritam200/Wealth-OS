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
/** The whole goal — an update replaces every field, so leaving targetDate out clears it. */
export interface GoalRequest {
  name: string; category?: string; targetAmount: number;
  currentSaved?: number; monthlyContribution?: number;
  expectedReturn?: number; inflationRate?: number; targetDate?: string;
  linkedHoldingIds?: number[]; linkedSipIds?: number[];
}
export interface GoalResponse {
  id: number; name: string; category: string;
  targetAmount: number; currentSaved: number; monthlyContribution: number;
  expectedReturn: number; targetDate: string | null;
  inflationRate: number | null;
  linkedHoldingIds: number[]; linkedSipIds: number[];
  /** Current value of linked holdings / monthly amount of active linked SIPs. */
  linkedValue: number; linkedMonthly: number;
  /** currentSaved + linkedValue; monthlyContribution + linkedMonthly. */
  totalSaved: number; totalMonthly: number;
  /** The target grown by inflation to the target date. */
  inflatedTarget: number; shortfallAmount: number;
  progressPercent: number; projectedValue: number; onTrack: boolean;
  monthsToTarget: number | null; requiredMonthly: number | null;
  status: 'ON_TRACK' | 'SHORTFALL' | 'ACHIEVED' | 'OVERDUE';
}
export interface GoalLinkOption { id: number; label: string; detail: string | null; amount: number }
export interface GoalLinkOptions { holdings: GoalLinkOption[]; sips: GoalLinkOption[] }
export const GOAL_CATEGORIES = ['Retirement', 'Home', 'Car', 'Education', 'Travel', 'Emergency', 'Other'];
export const goalApi = {
  list: () => apiClient.get<GoalResponse[]>('/api/goals'),
  add: (r: GoalRequest) => apiClient.post<GoalResponse>('/api/goals', r),
  linkOptions: () => apiClient.get<GoalLinkOptions>('/api/goals/link-options'),
  update: (id: number, r: GoalRequest) => apiClient.put<GoalResponse>(`/api/goals/${id}`, r),
  delete: (id: number) => apiClient.delete(`/api/goals/${id}`),
  whatIf: (id: number, r: GoalWhatIfRequest) => apiClient.post<GoalWhatIfResponse>(`/api/goals/${id}/what-if`, r),
};

/* ── Goal what-if scenarios (pure simulation — never mutates the real goal/SIP) ── */
export interface GoalWhatIfRequest {
  adjustmentType: 'PAUSE_SIP' | 'LUMP_SUM';
  startMonth?: number;      // months from now the adjustment takes effect (1-based)
  pauseMonths?: number;     // PAUSE_SIP only
  lumpSumAmount?: number;   // LUMP_SUM only
}
export interface GoalWhatIfProjection {
  projectedValue: number;
  completionMonth: number | null;
  completionDate: string | null;
  onTrack: boolean;
  shortfall: number;
}
export interface GoalWhatIfResponse {
  goalId: number;
  baseline: GoalWhatIfProjection;
  scenario: GoalWhatIfProjection;
  completionDelayMonths: number | null;
  /** The baseline reaches the target within 50 years; with this change it doesn't. */
  scenarioNeverCompletes: boolean;
  projectedValueDelta: number;
}

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
  /** TDS recorded on dividends/interest this year — already paid. */
  tdsDeducted?: number;
  notes: string[];
}
export const taxApi = {
  summary: (fyStartYear?: number) =>
    apiClient.get<TaxResponse>(`/api/tax/summary${fyStartYear ? `?fyStartYear=${fyStartYear}` : ''}`),
  // Blob response type so axios doesn't try to JSON-parse the CSV body.
  exportCsv: (financialYear?: string) =>
    apiClient.get<Blob>(`/api/tax/export`, {
      params: { financialYear, format: 'csv' },
      responseType: 'blob',
    }),
};
