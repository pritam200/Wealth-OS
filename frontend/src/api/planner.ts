import { apiClient } from './client';

export interface PlanTransaction {
  expenseId: number;
  date: string;
  merchant: string | null;
  description: string | null;
  amount: number;
  paymentMethod: string | null;
  sourceEmailId: string | null;
  aiCategoryKey: string | null;
  finalCategoryKey: string | null;
  overridden: boolean;
}

export interface CategoryPlanLine {
  key: string;
  name: string;
  groupName: string | null;
  planned: number;
  actual: number;
  remaining: number;
  linkedToSinkingFund: boolean;
  transactions: PlanTransaction[];
}

export interface GroupSummary {
  groupName: string;
  planned: number;
  actual: number;
  difference: number;
}

export type PlanStatus = 'WITHIN_PLAN' | 'NEAR_LIMIT' | 'LIMIT_REACHED' | 'OVER_LIMIT';

export interface MonthlyPlanResponse {
  year: number;
  month: number;
  monthlyLimit: number;
  plannedTotal: number;
  actualTotal: number;
  remaining: number;
  buffer: number;
  status: PlanStatus;
  categories: CategoryPlanLine[];
  groupSummaries: GroupSummary[];
  biggestExpenseCategory: string | null;
}

export interface CategoryResponse {
  id: number;
  key: string;
  name: string;
  groupName: string | null;
  plannedAmount: number;
  sortOrder: number | null;
  keywords: string | null;
  fallback: boolean;
  linkedSinkingFundName: string | null;
  active: boolean;
}

export interface CategoryRequest {
  name?: string;
  groupName?: string;
  plannedAmount?: number;
  sortOrder?: number;
  keywords?: string;
  active?: boolean;
}

export interface SettingsResponse { monthlyLimit: number }

export interface ReflectionResponse {
  yearMonth: string;
  biggestExpenseNote: string | null;
  overspendNote: string | null;
  underspendNote: string | null;
  oneOffNote: string | null;
  adjustmentsNote: string | null;
  finalStatus: 'UNDER' | 'EXACT' | 'OVER' | null;
  suggestedStatus: 'UNDER' | 'EXACT' | 'OVER';
}

export interface ReflectionRequest {
  biggestExpenseNote?: string;
  overspendNote?: string;
  underspendNote?: string;
  oneOffNote?: string;
  adjustmentsNote?: string;
  finalStatus?: string;
}

export interface SinkingFundResponse {
  id: number;
  name: string;
  monthlyPlanned: number;
  annualTarget: number | null;
  active: boolean;
}

export interface SinkingFundLedgerRow {
  yearMonth: string;
  planned: number;
  added: number;
  used: number;
  balance: number;
}

export interface SinkingFundLedgerResponse {
  fundId: number;
  fundName: string;
  year: number;
  rows: SinkingFundLedgerRow[];
  totalPlanned: number;
  totalAdded: number;
  totalUsed: number;
  endingBalance: number;
}

export const plannerApi = {
  getPlan: (year: number, month: number) =>
    apiClient.get<MonthlyPlanResponse>(`/api/planner/plan?year=${year}&month=${month}`),
  getCategories: () => apiClient.get<CategoryResponse[]>('/api/planner/categories'),
  addCategory: (r: CategoryRequest) => apiClient.post<CategoryResponse>('/api/planner/categories', r),
  updateCategory: (id: number, r: CategoryRequest) => apiClient.put<CategoryResponse>(`/api/planner/categories/${id}`, r),
  deactivateCategory: (id: number) => apiClient.delete(`/api/planner/categories/${id}`),
  getSettings: () => apiClient.get<SettingsResponse>('/api/planner/settings'),
  updateSettings: (monthlyLimit: number) => apiClient.put<SettingsResponse>('/api/planner/settings', { monthlyLimit }),
  getReflection: (year: number, month: number) =>
    apiClient.get<ReflectionResponse>(`/api/planner/reflection?year=${year}&month=${month}`),
  updateReflection: (year: number, month: number, r: ReflectionRequest) =>
    apiClient.put<ReflectionResponse>(`/api/planner/reflection?year=${year}&month=${month}`, r),
};

export const sinkingFundApi = {
  list: () => apiClient.get<SinkingFundResponse[]>('/api/planner/sinking-funds'),
  add: (r: { name: string; monthlyPlanned: number; annualTarget?: number }) =>
    apiClient.post<SinkingFundResponse>('/api/planner/sinking-funds', r),
  update: (id: number, r: Partial<{ name: string; monthlyPlanned: number; annualTarget: number; active: boolean }>) =>
    apiClient.put<SinkingFundResponse>(`/api/planner/sinking-funds/${id}`, r),
  ledger: (id: number, year: number) =>
    apiClient.get<SinkingFundLedgerResponse>(`/api/planner/sinking-funds/${id}/ledger?year=${year}`),
  upsertLedgerEntry: (id: number, yearMonth: string, added?: number, used?: number) =>
    apiClient.put<SinkingFundLedgerResponse>(`/api/planner/sinking-funds/${id}/ledger`, { yearMonth, added, used }),
};

// Lives on the expense API surface (it edits an Expense row) but is the planner's "move to a
// different section" action — kept here alongside the rest of the planner types for discoverability.
export const moveExpenseToCategory = (expenseId: number, planCategoryKey: string | null) =>
  apiClient.put(`/api/expenses/${expenseId}/plan-category`, { planCategoryKey });
