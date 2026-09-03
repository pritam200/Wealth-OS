import { apiClient } from './client';

export interface ForecastScenario {
  label: string;         // Bull | Base | Bear
  direction: string;     // up | flat | down
  probability: number;
  low: number;
  high: number;
  movePctLow: number;
  movePctHigh: number;
}

export interface ForecastResponse {
  symbol: string;
  displayName: string;
  horizon: string;
  currentPrice: number;
  // Empty when signal === 'INSUFFICIENT_DATA'. A forecast is purely a function of measured
  // volatility (ATR) and trend; with no stored history there is neither, so the backend
  // returns no scenarios at all instead of a manufactured band. Callers MUST check for the
  // empty array before indexing into it — the reference levels below are 0, not real.
  scenarios: ForecastScenario[];
  atr: number;
  expectedMove: number;
  rsi: number;
  sma50: number;
  sma200: number;
  support: number;
  resistance: number;
  trend: string;
  // BUY | HOLD | SELL | INSUFFICIENT_DATA. This is the raw pass-through technical signal,
  // NOT the centralized RecommendationEngine verdict — do not render it as a BUY/SELL/HOLD
  // chip anywhere, or the Forecast page will contradict every other surface for the same
  // symbol. Use it only to detect the INSUFFICIENT_DATA state.
  signal: string;
  dataPoints: number;
  basis: string;   // human-readable method, or the explanation when there's no forecast
}

export const forecastApi = {
  indices: () => apiClient.get<Record<string, string>>('/api/forecast/indices'),
  get: (symbol: string, horizon: string, name?: string) =>
    apiClient.get<ForecastResponse>(
      `/api/forecast?symbol=${encodeURIComponent(symbol)}&horizon=${horizon}${name ? `&name=${encodeURIComponent(name)}` : ''}`,
    ),
};
