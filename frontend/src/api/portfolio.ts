import { apiClient } from './client';
import type { Portfolio, PortfolioSummary, TransactionDto } from '../types';

export interface IntegrityIssue {
  holdingId: number;
  symbol: string;
  name: string;
  type: 'UNVERIFIABLE_NAME' | 'DUPLICATE_FOLIO' | 'DUPLICATE_DISPLAY_NAME' | 'DUPLICATE_SYMBOL';
  description: string;
  currentValue: number;
}

export interface IntegrityReport {
  totalHoldings: number;
  issueCount: number;
  issues: IntegrityIssue[];
}

export interface MergedGroup {
  symbol: string;
  keptHoldingId: number;
  removedHoldingIds: string[];
}

export interface MergeSummary {
  groupsMerged: number;
  holdingsMerged: number;
  groups: MergedGroup[];
}

export const portfolioApi = {
  list: () =>
    apiClient.get<Portfolio[]>('/api/portfolios'),

  create: (name: string, description?: string) =>
    apiClient.post<Portfolio>('/api/portfolios', { name, description }),

  getSummary: (id: number) =>
    apiClient.get<PortfolioSummary>(`/api/portfolios/${id}/summary`),

  addHolding: (portfolioId: number, data: {
    symbol: string; name: string; quantity: number;
    price: number; transactionDate: string; charges?: number;
  }) => apiClient.post(`/api/portfolios/${portfolioId}/holdings`, data),

  removeHolding: (portfolioId: number, holdingId: number) =>
    apiClient.delete(`/api/portfolios/${portfolioId}/holdings/${holdingId}`),

  sellHolding: (portfolioId: number, holdingId: number, quantity: number, salePrice: number) =>
    apiClient.post(`/api/portfolios/${portfolioId}/holdings/${holdingId}/sell`, { quantity, salePrice }),

  updateHolding: (portfolioId: number, holdingId: number,
    patch: { quantity?: number; averageCost?: number; currentPrice?: number; investedAmount?: number; broker?: string; folio?: string; buyDate?: string; xirr?: number }) =>
    apiClient.put(`/api/portfolios/${portfolioId}/holdings/${holdingId}`, patch),

  recalculate: () => apiClient.post('/api/portfolios/recalculate', {}),
  rebuild: () => apiClient.post<{ status: string; holdingsFixed: number }>('/api/portfolios/rebuild', {}),

  getTransactions: (portfolioId: number, holdingId: number) =>
    apiClient.get<TransactionDto[]>(`/api/portfolios/${portfolioId}/holdings/${holdingId}/transactions`),

  getRecentMfTransactions: (days = 7) =>
    apiClient.get<TransactionDto[]>('/api/portfolios/mf-transactions', { params: { days } }),

  integrityCheck: () =>
    apiClient.get<IntegrityReport>('/api/portfolios/integrity-check'),

  // Safely merges holdings that hold the exact same symbol across more than one portfolio
  // (the portfolio-fragmentation bug) into one, combining quantity/average cost and
  // preserving every transaction — never touches merely similarly-named holdings.
  mergeDuplicateSymbols: () =>
    apiClient.post<MergeSummary>('/api/portfolios/merge-duplicate-symbols', {}),
};
