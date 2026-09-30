// ─── Auth ────────────────────────────────────────────────────────────────────

export interface AuthResponse {
  userId: number;
  name: string;
  email: string;
  roles: string[];
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

// ─── Market ──────────────────────────────────────────────────────────────────

export interface QuoteDto {
  symbol: string;
  name: string;
  currentPrice: number;
  previousClose: number;
  open: number;
  high: number;
  low: number;
  volume: number;
  change: number;
  changePercent: number;
  weekHigh52: number;
  weekLow52: number;
  marketCap: number;
  pe: number;
  sector: string;
  lastUpdated: string;
  /** Exchange timestamp of the price, when the provider gave one. */
  marketTime?: string | null;
  /** DELAYED_INTRADAY | LAST_TRADED | UNKNOWN */
  priceType?: string | null;
  source?: string | null;
}

export interface IndexQuote {
  symbol: string;
  name: string;
  value: number;
  change: number;
  changePercent: number;
  open: number;
  high: number;
  low: number;
}

export interface MarketOverview {
  nifty50: IndexQuote;
  bankNifty: IndexQuote;
  sensex: IndexQuote;
  niftyMidcap: IndexQuote;
  sectors: SectorPerformance[];
  lastUpdated: string;
}

export interface SectorPerformance {
  sector: string;
  changePercent: number;
  trend: string;
}

export interface PriceHistory {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

// ─── Technical ───────────────────────────────────────────────────────────────

/**
 * How much real price history an indicator set was computed from.
 *   FULL         — >=200 daily bars; every indicator including SMA200 is genuine.
 *   PARTIAL      — >=20 but <200 bars; sma200 (and the long-term trend filter) is null.
 *   INSUFFICIENT — <5 bars; no indicator could be computed, every numeric field is null
 *                  and `signal` is INSUFFICIENT_DATA. Never render these as values.
 */
export type DataQuality = 'FULL' | 'PARTIAL' | 'INSUFFICIENT';

/** The only trend values the backend emits. BULLISH/BEARISH are NOT among them. */
export type TrendValue =
  | 'STRONG_UPTREND' | 'UPTREND' | 'SIDEWAYS' | 'DOWNTREND' | 'STRONG_DOWNTREND' | 'UNKNOWN';

export interface PriceLevel {
  price: number;
  /** SWING_CLUSTER | 52W_HIGH | 52W_LOW | SMA50 | SMA200 | GAP */
  source: string;
  touches: number | null;
  lastTouched: string | null;
  distancePct: number;
  reason: string;
}

export interface SupportResistance {
  nearestSupport: PriceLevel | null;
  nextSupport: PriceLevel | null;
  nearestResistance: PriceLevel | null;
  nextResistance: PriceLevel | null;
  /** OK | NO_RELIABLE_LEVEL */
  supportStatus: string;
  resistanceStatus: string;
  method: string;
}

export interface TrendEvidence { name: string; reading: string; detail: string; vote: boolean }

export interface TrendAssessment {
  label: TrendValue | 'INSUFFICIENT_DATA';
  bullishVotes: number;
  bearishVotes: number;
  votesAvailable: number;
  evidence: TrendEvidence[];
  rule: string;
}

export interface IndicatorValue {
  key: string; name: string; value: number | null; unit: string; formula: string;
  period: number | null; timeframe: string; asOf: string | null;
  barsRequired: number; barsAvailable: number; available: boolean; reason: string | null;
}

export interface VolumeProfile {
  latestVolume: number | null; averageVolume20: number | null; relativeVolume: number | null;
  volumeTrend: string | null; volumeTrendRatio: number | null; unusual: boolean | null;
  barsWithVolume: number; reason: string | null; zscore: number | null;
}

export interface SeriesIssue { code: string; severity: string; date: string | null; detail: string; warning: boolean }

/** OK | DATA_QUALITY_WARNING | STALE_DATA | INSUFFICIENT_DATA */
export type SeriesStatus = 'OK' | 'DATA_QUALITY_WARNING' | 'STALE_DATA' | 'INSUFFICIENT_DATA';

/**
 * The canonical technical read. Every value is null when it could not be computed from
 * validated history; never substitute 0 or 50. The frontend must not recompute any of these.
 */
export interface TechnicalAnalysis {
  symbol: string;
  price: number | null;
  rsi: number | null;
  macd: number | null;
  macdSignal: number | null;
  macdHistogram: number | null;
  sma20: number | null;
  sma50: number | null;
  sma100: number | null;
  sma200: number | null;
  ema20: number | null;
  ema50: number | null;
  ema200: number | null;
  bollingerUpper: number | null;
  bollingerMiddle: number | null;
  bollingerLower: number | null;
  /** Price units (₹), not a percentage. */
  atr: number | null;
  atrPct: number | null;
  adx: number | null;
  plusDi: number | null;
  minusDi: number | null;
  dailyVolatilityPct: number | null;
  annualizedVolatilityPct: number | null;
  volatilityBars: number | null;
  volume: VolumeProfile | null;
  high52w: number | null;
  low52w: number | null;
  range52wSessions: number | null;
  rangePosition52wPct: number | null;
  support: number | null;
  resistance: number | null;
  levels: SupportResistance | null;
  trend: TrendValue;
  trendAssessment: TrendAssessment | null;
  indicators: IndicatorValue[] | null;
  dataQuality?: DataQuality | null;
  seriesStatus: SeriesStatus;
  dataIssues: SeriesIssue[] | null;
  barsAvailable?: number | null;
  barsRejected: number | null;
  firstBarDate: string | null;
  lastBarDate: string | null;
  expectedSession: string | null;
  stale: boolean;
  source: string | null;
  dataUpdatedAt: string | null;
  timeframe: string;
}

// ─── Portfolio ───────────────────────────────────────────────────────────────

export interface Portfolio {
  id: number;
  name: string;
  description: string;
  createdAt: string;
}

export interface HoldingDto {
  id: number;
  portfolioId: number;
  symbol: string;
  name: string;
  quantity: number;
  averageCost: number;
  currentPrice: number;
  investedValue: number;
  currentValue: number;
  pnl: number;
  pnlPercent: number;
  weightPercent: number;
  broker?: string | null;
  folio?: string | null;
  buyDate?: string | null;
  xirr?: number | null;
  /** Day the price is from; null when unknown. */
  priceAsOf?: string | null;
  /** MARKET = current price; STALE = price older than a few days; COST = no price, valued at cost. */
  valuationBasis?: 'MARKET' | 'STALE' | 'COST';
}

export interface AllocationDto {
  label: string;
  value: number;
  percent: number;
}

export interface TransactionDto {
  id: number;
  holdingId: number;
  symbol: string;
  fundName: string;
  /** BONUS = nil-cost shares allotted; SPLIT = a split or merger ratio, carries no units or money. */
  type: 'BUY' | 'SELL' | 'BONUS' | 'SPLIT';
  quantity: number;
  price: number;
  totalAmount: number;
  charges: number | null;
  transactionDate: string;
  notes: string | null;
  broker: string | null;
  folio: string | null;
  createdAt: string;
}

export interface PortfolioSummary {
  portfolioId: number;
  name: string;
  totalInvested: number;
  currentValue: number;
  totalPnl: number;
  totalPnlPercent: number;
  stocksInvested?: number;
  stocksCurrentValue?: number;
  mfInvested?: number;
  mfCurrentValue?: number;
  holdingsAtCost?: number;
  valueAtCost?: number;
  holdingsStale?: number;
  valueStale?: number;
  holdings: HoldingDto[];
  allocation: AllocationDto[];
  lastUpdated: string;
}

// ─── News ────────────────────────────────────────────────────────────────────

export interface NewsItem {
  id: number;
  title: string;
  description: string;
  source: string;
  url: string;
  publishedAt: string;
  sentiment: 'POSITIVE' | 'NEGATIVE' | 'NEUTRAL';
  imageUrl: string;
}

// ─── AI ──────────────────────────────────────────────────────────────────────

export interface AiResponse {
  summary: string;
  risks: string[];
  opportunities: string[];
  signal: string;
  rawResponse: string;
  generatedAt: string;
}
