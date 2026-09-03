import { apiClient } from './client';

/* ── Portfolio context (mirrors backend PortfolioContext) ──────────────── */

export interface PortfolioExposure {
  label: string;
  value: number;
  percentOfTotalAssets: number | null;
  percentOfEquity: number | null;
  holdingCount: number;
  sector: string | null;
}

export interface PortfolioFlag {
  type: 'SINGLE_STOCK' | 'SECTOR' | 'ASSET_ALLOCATION' | 'LEVERAGE';
  label: string;
  percent: number | null;
  severity: 'HIGH' | 'MODERATE';
  message: string;
}

export interface PortfolioContext {
  stocksValue: number; mfValue: number; fdValue: number; rdValue: number;
  epfValue: number; otherAssetsValue: number; loansOutstanding: number;
  totalAssets: number; netWorth: number;
  equityPercent: number | null; debtPercent: number | null; otherPercent: number | null;
  equityInvested: number; equityCurrent: number; equityPnl: number; equityPnlPercent: number | null;
  stockCount: number; mfCount: number;
  topStockExposures: PortfolioExposure[];
  sectorExposures: PortfolioExposure[];
  concentrationFlags: PortfolioFlag[];
  sectorCoveragePercent: number | null;
  sectorScopeNote: string;
  dataQuality: 'FULL' | 'PARTIAL';
  dataGaps: string[];
}

/* ── Action buckets ──────────────────────────────────────────────────────
   Mirrors backend TodaysActionsResponse exactly — see that file for the "why" behind each
   field (e.g. maxAddWithoutBreachingGuideline is a ceiling, never a suggested buy amount). */

export type AssetType = 'STOCK' | 'MF' | 'PORTFOLIO';

export interface BuyAction {
  symbol: string; name: string; assetType: AssetType;
  currentValue: number; currentPercentOfEquity: number | null;
  maxAddWithoutBreachingGuideline: number | null;
  why: string; risk: string; confidence: number;
}

export interface SellReduceAction {
  symbol: string; name: string; assetType: AssetType;
  currentValue: number; pnlPercent: number | null;
  action: 'FULL_EXIT' | 'SWITCH' | 'REDUCE';
  why: string; riskReward: string; taxImpact: string | null;
}

export interface ReinvestmentTranche {
  label: string; amount: number; percentOfTotal: number; condition: string;
}
export interface ReinvestmentPlan {
  totalToRedeploy: number; tranches: ReinvestmentTranche[]; basis: string;
}

export interface BookProfitAction {
  symbol: string; name: string; assetType: AssetType;
  currentValue: number; pnlPercent: number | null; currentProfitAmount: number | null;
  suggestedBookPercent: number; suggestedBookAmount: number;
  why: string; taxImpact: string | null;
  reinvestmentPlan: ReinvestmentPlan;
}

export interface HoldAction {
  symbol: string; name: string; assetType: AssetType;
  currentValue: number; pnlPercent: number | null; why: string;
}

export interface WatchAction {
  symbol: string | null; name: string; assetType: AssetType;
  condition: string; why: string;
}

export interface NotAnalysed {
  symbol: string; name: string; assetType: AssetType; reason: string;
}

export interface TodaysActionsResponse {
  generatedAt: string;
  scopeNote: string;
  portfolioContext: PortfolioContext;
  buy: BuyAction[];
  sellReduce: SellReduceAction[];
  bookProfit: BookProfitAction[];
  hold: HoldAction[];
  watch: WatchAction[];
  notAnalysed: NotAnalysed[];
}

export const todaysActionsApi = {
  get: () => apiClient.get<TodaysActionsResponse>('/api/todays-actions'),
};
