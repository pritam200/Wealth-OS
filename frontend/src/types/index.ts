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

export interface TechnicalAnalysis {
  symbol: string;
  price: number;
  // Every indicator below is null when it could not be computed from real stored history.
  // They used to be unconditionally populated — sometimes with values fabricated from a
  // single day's change — so they must all be null-checked before being displayed.
  rsi: number | null;
  macd: number | null;
  macdSignal: number | null;
  macdHistogram: number | null;
  sma20: number | null;
  sma50: number | null;
  sma200: number | null;
  ema20: number | null;
  bollingerUpper: number | null;
  bollingerMiddle: number | null;
  bollingerLower: number | null;
  atr: number | null;
  support: number | null;
  resistance: number | null;
  trend: TrendValue;
  signal: 'BUY' | 'SELL' | 'HOLD' | 'INSUFFICIENT_DATA';
  signalStrength: 'STRONG' | 'MODERATE' | 'WEAK';
  dataQuality?: DataQuality | null;
  barsAvailable?: number | null;
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
  type: 'BUY' | 'SELL';
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
