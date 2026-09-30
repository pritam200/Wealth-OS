import { apiClient } from './client';
import type { PortfolioExposure, PortfolioFlag } from './todaysActions';

/* ── Mirrors backend RebalancingSuggestionsResponse — a read-only concentration report, never
   a target-allocation rebalancer and never an executed trade. ─────────────────────────────── */

export interface AssetClassExposure {
  label: string;
  value: number;
  percentOfTotalAssets: number | null;
}

export interface TaxImpact {
  unitsSold: number;
  grossProceeds: number;
  exitLoad: number;
  shortTermGain: number;
  longTermGain: number;
  exemptionUsed: number;
  tax: number;
  netProceeds: number;
  deferralAdvice: string | null;
  caveats: string[];
}

export interface TrimSuggestion {
  triggerType: 'SINGLE_STOCK' | 'SECTOR';
  triggerLabel: string;
  symbol: string;
  name: string;
  currentValue: number;
  currentPercent: number | null;
  basis: string;
  suggestedSellUnits: number;
  suggestedSellValue: number;
  selectionReason: string | null;
  reason: string;
  taxImpact: TaxImpact | null;
  taxImpactGap: string | null;
}

export interface RebalancingSuggestionsResponse {
  generatedAt: string;
  totalAssets: number;
  assetClassBreakdown: AssetClassExposure[];
  sectorBreakdown: PortfolioExposure[];
  concentrationFlags: PortfolioFlag[];
  trimSuggestions: TrimSuggestion[];
  dataGaps: string[];
  scopeNote: string;
}

export const rebalancingApi = {
  getSuggestions: () => apiClient.get<RebalancingSuggestionsResponse>('/api/rebalancing/suggestions'),
};
