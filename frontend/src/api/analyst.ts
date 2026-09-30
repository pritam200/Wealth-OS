import { apiClient } from './client';
import type { DataQuality } from '../types';

export type { DataQuality } from '../types';

import type { TrendAssessment } from '../types';

export interface AnalystFactor {
  name: string;
  /** TECHNICAL_SIGNAL | MARKET_TREND | MOMENTUM | VALUATION | NEWS_SENTIMENT | … */
  category?: string | null;
  /** Null for descriptive readings that have no defined score. */
  score: number | null;
  weightPct: number;
  reading?: string | null;
  note: string;
  /** True only for the inputs the rating was actually derived from. */
  contributesToRating?: boolean;
}

export interface SignalValidation {
  horizonSessions: number; observations: number; buyCalls: number; sellCalls: number;
  buyHitRate: number | null; buyHitRateCiLow: number | null;
  sellHitRate: number | null; sellHitRateCiLow: number | null;
  baseUpRate: number | null;
  meanForwardReturnBuyPct: number | null; meanForwardReturnSellPct: number | null; meanForwardReturnAllPct: number | null;
  currentCallValidated: boolean; summary: string;
}

export interface FundamentalFact {
  name: string; value: number | null; unit: string; period: string | null;
  source: string | null; fetchedAt: string | null; note: string | null;
}
export interface AnalystFundamentals {
  pe: number | null; marketCap: number | null;
  weekHigh52: number | null; weekLow52: number | null;
  pctOf52wRange: number | null; sector: string | null; trend: string; rsi: number | null;
}
export interface AnalystArticle {
  title: string; url: string | null; source: string | null; publishedAt: string | null;
  sentiment?: string | null; matchedTerms?: string[] | null; event?: string | null; ageDays?: number | null;
}
/** Reported separately from the rating; never folded into it. */
export interface AnalystNews {
  category?: string; status?: 'OK' | 'INSUFFICIENT' | string;
  score: number | null; label?: string | null; confidence?: string | null;
  windowDays?: number; excluded?: number; method?: string | null;
  positive: number; negative: number; total: number; headlines: string[]; articles: AnalystArticle[];
}
export interface AnalystTechnicals {
  sma20: number | null; sma50: number | null; sma200: number | null;
  support: number | null; resistance: number | null;
  macd: number | null; macdSignal: number | null; atr: number | null;
  bollingerUpper: number | null; bollingerLower: number | null;
  dayChangePercent: number | null; volume: number | null;
}
// INSUFFICIENT_DATA is not a recommendation — it's the engine explicitly declining to make
// one because there wasn't enough stored price history. It must never be rendered like HOLD.
export type NextAction =
  | 'ACCUMULATE' | 'CONTINUE' | 'BOOK_PROFIT' | 'EXIT' | 'REVIEW' | 'HOLD' | 'AVOID' | 'REBALANCE'
  | 'NO_ACTIONABLE_SIGNAL' | 'STALE_DATA' | 'INSUFFICIENT_DATA';

/** BUY/SELL only for a call validated on the instrument's own history; otherwise one of the non-ratings. */
export type Rating = 'BUY' | 'SELL' | 'NO_ACTIONABLE_SIGNAL' | 'STALE_DATA' | 'INSUFFICIENT_DATA' | 'NOT_RATED';

export const INSUFFICIENT_DATA = 'INSUFFICIENT_DATA';
export const NO_ACTIONABLE_SIGNAL = 'NO_ACTIONABLE_SIGNAL';
export const STALE_DATA = 'STALE_DATA';

/** Human label for a rating or next action that is not a trade call. */
export const NON_CALL_LABEL: Record<string, string> = {
  NO_ACTIONABLE_SIGNAL: 'No actionable signal',
  STALE_DATA: 'Stale data',
  INSUFFICIENT_DATA: 'Insufficient data',
  NOT_RATED: 'Not rated',
};

/** Historical hit rate label — this is what `confidenceScore` is, not a probability of profit. */
export const hitRateLabel = (a: Pick<AnalystAssessment, 'confidenceScore'> | null | undefined) =>
  a?.confidenceScore != null ? `${a.confidenceScore}% historical hit rate` : null;

/** True when the engine declined to produce a verdict for lack of verifiable data. */
export const isInsufficient = (a: Pick<AnalystAssessment, 'dataQuality' | 'nextAction'> | null | undefined) =>
  !!a && (a.dataQuality === 'INSUFFICIENT' || a.nextAction === INSUFFICIENT_DATA);

export interface AnalystAssessment {
  symbol: string; displayName: string; price: number;
  // null when dataQuality === 'INSUFFICIENT' — the backend deliberately emits no rating
  // rather than one that isn't based on verifiable data.
  rating: Rating | string | null;
  conviction: 'HIGH' | 'MEDIUM' | 'LOW' | null;
  /** Factor agreement of a validated call (−100..100); null otherwise. Not a probability. */
  compositeScore: number | null;
  ruleOutput?: string | null;
  signalValidation?: SignalValidation | null;
  trendAssessment?: TrendAssessment | null;
  fundamentalFacts?: FundamentalFact[] | null;
  priceDate?: string | null;
  priceSource?: string | null;
  seriesStatus?: string | null;
  factorBreakdown: AnalystFactor[];
  fundamentals: AnalystFundamentals;
  technicals: AnalystTechnicals | null; // null on the INSUFFICIENT path
  news: AnalystNews | null;             // null on the INSUFFICIENT path
  positives: string[]; risks: string[];
  basis: string;

  // The research engine's final view, read from stored research (see /api/research). Code-generated
  // text only; null when no research exists for the current session.
  researchActionability?: string | null;
  researchReason?: string | null;
  researchLean?: string | null;
  researchAt?: string | null;
  researchModel?: string | null;

  // Set by the backend's RecommendationEngine — the same for every consumer of a given symbol.
  // Widened to string (not the narrower NextAction/MfNextAction unions) because this one DTO
  // shape is shared by both the stock and MF recommendation endpoints, which use different
  // value sets — each consumer narrows/casts to the union it expects.
  /** Historical hit rate (%) of the current validated call; null when there is none. */
  confidenceScore: number | null;
  nextAction: string;
  nextActionReason: string | null; // plain-English explanation of why THIS action, not just the rating
  taxImpact: string | null;  // MF only; null for stocks

  // Truthfulness marker for the inputs behind this assessment.
  // 'INSUFFICIENT' → nextAction is INSUFFICIENT_DATA, rating is INSUFFICIENT_DATA, confidenceScore is null,
  // and risks[] explains why. The UI must show "no data" rather than a verdict.
  dataQuality?: DataQuality | null;
  barsAvailable?: number | null;   // daily bars of real price history behind this assessment
}

export type MfNextAction = 'CONTINUE_SIP' | 'INCREASE_SIP' | 'PAUSE_SIP' | 'HOLD'
  | 'PARTIAL_PROFIT_BOOKING' | 'FULL_REDEMPTION' | 'REBALANCE' | 'SWITCH_FUND';

export const analystApi = {
  assess: (symbol: string, name?: string) =>
    apiClient.get<AnalystAssessment>(`/api/analyst/${encodeURIComponent(symbol)}${name ? `?name=${encodeURIComponent(name)}` : ''}`),
};

// The one endpoint every UI surface should call for BUY/HOLD/SELL + next action —
// same underlying engine as analystApi.assess, with optional portfolio (P&L) context.
export const recommendationApi = {
  get: (symbol: string, opts?: { name?: string; pnlPercent?: number; holdingValue?: number; totalPortfolioValue?: number }) => {
    const params = new URLSearchParams();
    if (opts?.name) params.set('name', opts.name);
    if (opts?.pnlPercent !== undefined && opts.pnlPercent !== null) params.set('pnlPercent', String(opts.pnlPercent));
    if (opts?.holdingValue !== undefined && opts.holdingValue !== null) params.set('holdingValue', String(opts.holdingValue));
    if (opts?.totalPortfolioValue !== undefined && opts.totalPortfolioValue !== null) params.set('totalPortfolioValue', String(opts.totalPortfolioValue));
    const qs = params.toString();
    return apiClient.get<AnalystAssessment>(`/api/recommendation/${encodeURIComponent(symbol)}${qs ? `?${qs}` : ''}`);
  },
};

export interface MfRecommendationParams {
  symbol: string;
  fundName?: string;
  buyDate?: string;
  xirr?: number;
  investedValue: number;
  currentValue: number;
  quantity?: number;
  totalMfPortfolioValue?: number;
}

// Dedicated MF recommendation engine — tax-aware (STCG/LTCG), holding-period-aware,
// portfolio-concentration-aware. Not the stock engine; MFs shouldn't use stock-shaped logic.
export const recommendationMfApi = {
  get: (params: MfRecommendationParams) => apiClient.post<AnalystAssessment>('/api/recommendation/mf', params),
};

export interface IndexBenchmarks { nifty50: number | null; sensex: number | null; bankNifty: number | null }

// Real trailing index returns — replaces hardcoded benchmark strings on the Mutual Funds tab.
export const benchmarksApi = {
  get: () => apiClient.get<IndexBenchmarks>('/api/recommendation/benchmarks'),
};
