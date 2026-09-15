import { apiClient } from './client';
import type { PortfolioContext } from './todaysActions';

export type { PortfolioContext, PortfolioExposure, PortfolioFlag } from './todaysActions';

/**
 * The single source of truth for net worth, asset allocation, and total invested/current/P&L.
 * Every screen (Dashboard, My Wealth, Stocks, Mutual Funds) must read these numbers from here
 * instead of re-deriving them from raw holdings/FD/RD data — that duplication was the direct
 * cause of different tabs showing different figures for the same underlying portfolio.
 */
export const wealthApi = {
  getSummary: () => apiClient.get<PortfolioContext>('/api/wealth/summary'),
};
