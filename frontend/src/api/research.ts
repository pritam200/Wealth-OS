import { apiClient } from './client';

/** What a statement rests on, derived by the backend from what it cites — never set by the model. */
export type Basis = 'DATA' | 'FILING' | 'NEWS' | 'CALCULATION' | 'PORTFOLIO' | 'MODEL' | 'LLM_INTERPRETATION';

export interface Claim {
  text: string;
  /** Ids of the facts (F#) and evidence items (E#) the statement cites. */
  evidence: string[];
  /** One or more of {@link Basis}, joined with "+" (e.g. "FILING+DATA"). */
  basis: string;
}

export interface Fact {
  id: string; category: string; basis: string; label: string;
  value: string | null; numeric: number | null; unit: string | null;
  asOf: string | null; source: string | null; available: boolean;
}

export interface Evidence {
  id: string; kind: string; tier: 'PRIMARY' | 'OFFICIAL' | 'RELIABLE' | 'NEWS' | 'UNVERIFIED' | string;
  source: string | null; title: string; publishedAt: string | null; url: string | null;
  retrievedAt: string | null; relevance: string | null; detail: string | null;
}

export interface SourceStatus { source: string; status: 'OK' | 'UNAVAILABLE' | 'NOT_SUPPORTED' | string; items: number; detail: string | null }

export interface CrossCheck { question: string; label: string; answer: 'SUPPORTS' | 'CONTRADICTS' | 'MIXED' | 'UNKNOWN'; explanation: Claim | null }

export interface QuantAssessment {
  rating: string; ruleOutput: string | null; validated: boolean; hitRatePct: number | null;
  signalSummary: string | null; trend: string | null; trendSummary: string | null;
  forecastSummary: string[]; calibrationSummary: string | null; seriesStatus: string | null;
  priceDate: string | null; summary: string;
}

export interface ResearchReport {
  executiveSummary: Claim; fundamentalAssessment: Claim | null; technicalAssessment: Claim | null;
  marketContext: Claim | null; sectorContext: Claim | null; newsAssessment: Claim | null;
  valuationAssessment: Claim | null; portfolioImpact: Claim | null; forecastInterpretation: Claim | null;
  bullCase: Claim | null; baseCase: Claim | null; bearCase: Claim | null;
  crossChecks: CrossCheck[]; contradictingEvidence: Claim[]; keyRisks: Claim[]; catalysts: Claim[];
  missingInformation: string[]; evidenceQuality: 'HIGH' | 'MEDIUM' | 'LOW';
  researchConclusion: Claim; actionability: Actionability; sources: string[];
}

export interface DevilsAdvocate {
  contradictoryEvidence: Claim[]; overlookedRisks: Claim[]; dataQualityProblems: Claim[];
  upcomingCatalysts: Claim[]; technicalSignalFailure: Claim[]; fundamentalThesisFailure: Claim[];
  forecastRangeReliability: Claim[]; thesisRisk: 'LOW' | 'MEDIUM' | 'HIGH'; verdict: Claim | null;
}

export type Actionability = 'BUY' | 'SELL' | 'HOLD' | 'NO_ACTIONABLE_SIGNAL' | 'INSUFFICIENT_DATA' | 'CONFLICTING_EVIDENCE' | 'RESEARCH_REQUIRED';

export interface FinalView { actionability: Actionability; reason: string; rule: string; quantRating: string | null; researchLean: string | null }

export interface GuardReport { removedFigures: string[]; droppedCitations: string[]; notes: string[] }

export interface ResearchResult {
  subjectType: 'STOCK' | 'INDEX' | 'MUTUAL_FUND';
  symbol: string; displayName: string;
  /** OK | PARTIAL (review failed) | UNAVAILABLE (model failed) | DISABLED (no model) | NOT_RUN (cached mode, nothing stored) */
  status: 'OK' | 'PARTIAL' | 'UNAVAILABLE' | 'DISABLED' | 'NOT_RUN';
  statusReason: string | null;
  researchTimestamp: string | null; marketDate: string | null;
  stale: boolean; staleReason: string | null; fromCache: boolean;
  provider: string | null; model: string | null; fallbackUsed: boolean;
  analystPromptVersion: string | null; reviewPromptVersion: string | null; webPromptVersion: string | null;
  dataSnapshotHash: string;
  quant: QuantAssessment | null;
  facts: Fact[]; evidence: Evidence[]; sources: SourceStatus[]; missingData: string[];
  report: ResearchReport | null; devilsAdvocate: DevilsAdvocate | null;
  finalView: FinalView | null; guard: GuardReport | null;
  webSearchQueries: string[]; latencyMs: number | null;
  /** When there is no current research: the latest research, made on older data. */
  previous: ResearchResult | null;
}

export type ResearchSubject = { kind: 'stock'; symbol: string; name?: string } | { kind: 'market'; symbol: string } | { kind: 'fund'; symbol: string };

// Two model passes on a local model can take minutes.
const LONG = { timeout: 600_000 };

export const researchApi = {
  get: (s: ResearchSubject, opts: { cached?: boolean; refresh?: boolean } = {}) =>
    apiClient.get<ResearchResult>(`/api/research/${s.kind}/${encodeURIComponent(s.symbol)}`, {
      ...LONG,
      params: {
        ...(s.kind === 'stock' && s.name ? { name: s.name } : {}),
        ...(opts.cached ? { mode: 'cached' } : {}),
        ...(opts.refresh ? { refresh: true } : {}),
      },
    }),
};

export const ACTIONABILITY_LABEL: Record<string, string> = {
  BUY: 'Buy', SELL: 'Sell', HOLD: 'Hold',
  NO_ACTIONABLE_SIGNAL: 'No actionable signal', INSUFFICIENT_DATA: 'Insufficient data',
  CONFLICTING_EVIDENCE: 'Conflicting evidence', RESEARCH_REQUIRED: 'Research required',
};

export const BASIS_LABEL: Record<string, string> = {
  DATA: 'Data', FILING: 'Filing', NEWS: 'News', CALCULATION: 'Calculation',
  PORTFOLIO: 'Portfolio', MODEL: 'Model', LLM_INTERPRETATION: 'LLM interpretation',
};
