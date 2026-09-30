import { apiClient } from './client';

/** One band of the model distribution. `probability` is a measured historical frequency, or null. */
export interface ForecastScenario {
  label: 'Bull' | 'Base' | 'Bear' | string;
  direction: string;
  quantileLow: number;
  quantileHigh: number;
  low: number;
  high: number;
  reference: number;
  movePctLow: number;
  movePctHigh: number;
  /** Share of the model distribution in the band, by construction (20/50/20). */
  nominalProbability: number;
  /** Measured share of this instrument's past outcomes in the band (%), null when unavailable. */
  probability: number | null;
  probabilityCiLow: number | null;
  probabilityCiHigh: number | null;
}

export interface ForecastRange { low: number; high: number; nominalCoverage: number; historicalCoverage: number | null }

export interface DataIssue { code: string; severity: string; date: string | null; detail: string; warning: boolean }

export interface CurvePoint { nominal: number; empirical: number }

/**
 * A model-estimated range, never a price target. When `status` is STALE_DATA or
 * INSUFFICIENT_DATA there is no range and `scenarios` is empty — render `statusReason`.
 */
export interface ForecastResponse {
  symbol: string;
  displayName: string;
  horizon: string;
  tradingDays: number;
  status: 'OK' | 'DATA_QUALITY_WARNING' | 'STALE_DATA' | 'INSUFFICIENT_DATA';
  statusReason: string | null;
  currentPrice: number | null;
  priceDate: string | null;
  horizonEndsAround: string | null;
  volatility: { dailyPct: number; annualizedPct: number; horizonPct: number; window: number; method: string } | null;
  range50: ForecastRange | null;
  range90: ForecastRange | null;
  scenarios: ForecastScenario[];
  probabilityStatus: 'EMPIRICAL' | 'UNAVAILABLE' | null;
  probabilityNote: string | null;
  calibration: {
    label: string; summary: string; observations: number; effectiveSample: number;
    from: string | null; to: string | null; curve: CurvePoint[]; tailFrequency: number | null;
  } | null;
  directional: {
    modelUpProbability: number; historicalUpFrequency: number | null; movingAverageBaselineHitRate: number | null;
    brierModel: number | null; brierTrailingFrequency: number | null; summary: string;
  } | null;
  pointErrors: {
    maeRandomWalkPct: number | null; rmseRandomWalkPct: number | null; maeDriftPct: number | null;
    rmseDriftPct: number | null; maeMaReversionPct: number | null; rmseMaReversionPct: number | null; summary: string;
  } | null;
  trend: string | null;
  rsi: number | null;
  sma50: number | null;
  sma200: number | null;
  support: number | null;
  resistance: number | null;
  keyDrivers: string[] | null;
  keyRisks: string[] | null;
  dataIssues: DataIssue[] | null;
  dataPoints: number;
  firstBarDate: string | null;
  source: string | null;
  methodology: string | null;
  basis: string | null;
}

export const FORECAST_HORIZONS = ['1D', '5D', '20D', '60D'] as const;
export type ForecastHorizon = typeof FORECAST_HORIZONS[number];

export const forecastApi = {
  indices: () => apiClient.get<Record<string, string>>('/api/forecast/indices'),
  get: (symbol: string, horizon: string, name?: string) =>
    apiClient.get<ForecastResponse>(
      `/api/forecast?symbol=${encodeURIComponent(symbol)}&horizon=${horizon}${name ? `&name=${encodeURIComponent(name)}` : ''}`,
    ),
};
