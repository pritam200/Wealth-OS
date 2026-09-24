import { apiClient } from './client';

export interface ExpenseRequest {
  description: string;
  amount: number;
  category: string;
  expenseDate: string;
  merchant?: string;
  paymentMethod?: string;
  cashAccountId?: number;
  note?: string;
}

export interface ExpenseResponse {
  id: number;
  description: string;
  amount: number;
  category: string;
  expenseDate: string;
  merchant: string | null;
  paymentMethod: string | null;
  cashAccountId: number | null;
  sourceEmailId: string | null;
  note: string | null;
  createdAt: string;
}

export interface ExpenseSummary {
  total: number;
  byCategory: Record<string, number>;
}

// Matches backend ExpenseCategory enum labels. Investment and Account Transfer are
// deliberately excluded here — both are system-derived-only classifications (Gmail parsing
// flags/skips investment-flavoured debits and CRED/CC-bill-payment debits respectively); neither
// should ever be a category a user picks for a manually-entered real expense.
export const EXPENSE_CATEGORIES = [
  'Food', 'Food Delivery', 'Groceries', 'Restaurant / Outing', 'Shopping', 'Travel', 'Fuel',
  'Bills', 'Medical', 'Entertainment', 'EMI', 'UPI', 'Uncategorized',
];

export const expenseApi = {
  list: (year: number, month: number) =>
    apiClient.get<ExpenseResponse[]>(`/api/expenses?year=${year}&month=${month}`),
  add: (r: ExpenseRequest) =>
    apiClient.post<ExpenseResponse>('/api/expenses', r),
  update: (id: number, r: ExpenseRequest) =>
    apiClient.put<ExpenseResponse>(`/api/expenses/${id}`, r),
  delete: (id: number) =>
    apiClient.delete(`/api/expenses/${id}`),
  summary: (year: number, month: number) =>
    apiClient.get<ExpenseSummary>(`/api/expenses/summary?year=${year}&month=${month}`),
  // One-time cleanup: pre-fix rows where a SIP/mutual-fund/broker debit got mislabeled
  // as an Expense before Gmail parsing was fixed to keep investments out of this table.
  listMiscategorizedInvestments: () =>
    apiClient.get<ExpenseResponse[]>('/api/expenses/miscategorized-investments'),
  purgeMiscategorizedInvestments: () =>
    apiClient.delete<{ removed: number }>('/api/expenses/miscategorized-investments'),
};
