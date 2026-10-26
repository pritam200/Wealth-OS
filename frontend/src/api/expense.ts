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
  confirmSeparate?: boolean;
  /** On an edit: use this category for the merchant's other and future expenses too. */
  rememberForMerchant?: boolean;
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
  alreadyRecorded?: boolean;
  note: string | null;
  createdAt: string;
  /** The purchase this refund reverses; the amount is then negative. */
  refundOfExpenseId: number | null;
  /** False for transfers, card-bill payments and investment debits — listed, not counted. */
  spending: boolean;
  /** After an edit with rememberForMerchant: how many of the merchant's other expenses moved. */
  alsoRecategorised?: number | null;
}

export interface ExpenseSummary {
  total: number;
  byCategory: Record<string, number>;
  /** Card-bill payments, own-account transfers and investment debits: not counted in total. */
  transfers: number;
}

export interface MerchantCategoryRule {
  id: number; merchant: string; category: string; updatedAt: string;
}

// Matches backend ExpenseCategory enum labels. Account Transfer is offered so an imported
// card-bill payment or own-account transfer can be taken out of spending; Investment stays
// system-only (such debits are booked from the fund or broker confirmation instead).
export const EXPENSE_CATEGORIES = [
  'Food', 'Food Delivery', 'Groceries', 'Restaurant / Outing', 'Shopping', 'Travel', 'Fuel',
  'Bills', 'Medical', 'Entertainment', 'EMI', 'UPI', 'Bank Charges', 'Account Transfer', 'Uncategorized',
];
/** Categories that are not spending: shown with their rows but left out of totals. */
export const NOT_SPENDING = new Set(['Account Transfer', 'Investment']);
export const categoryLabel = (c: string) => (c === 'Account Transfer' ? 'Transfer / card bill (not spending)' : c);

export const expenseApi = {
  list: (year: number, month: number) =>
    apiClient.get<ExpenseResponse[]>(`/api/expenses?year=${year}&month=${month}`),
  search: (p: { from?: string; to?: string; category?: string; q?: string }) =>
    apiClient.get<ExpenseResponse[]>('/api/expenses', { params: p }),
  merchantRules: () => apiClient.get<MerchantCategoryRule[]>('/api/expenses/merchant-rules'),
  forgetMerchantRule: (id: number) => apiClient.delete(`/api/expenses/merchant-rules/${id}`),
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
