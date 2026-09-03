import { apiClient } from './client';

export interface AmfiNavResult {
  schemeName: string;
  schemeCode: string;
  nav: number;
  asOf: string;
}

// Free official AMFI daily NAV lookup by fuzzy scheme-name match — real data source for
// mutual fund current price, instead of manual-only entry.
export const amfiNavApi = {
  lookup: (name: string) => apiClient.get<AmfiNavResult>('/api/mf-nav', { params: { name } }),
};
