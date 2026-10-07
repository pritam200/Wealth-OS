import { apiClient } from './client';

/** The canonical financial data platform: institution-verified ledger, reconciliation and data health. */

export type SourceType = 'ACCOUNT_AGGREGATOR' | 'BROKER_API' | 'DEPOSITORY' | 'CAS' | 'STATEMENT' | 'EMAIL' | 'MANUAL' | 'AI_INFERENCE';
export type ReconStatus = 'VERIFIED' | 'MATCHED' | 'PARTIALLY_MATCHED' | 'PENDING' | 'MISSING' | 'DUPLICATE' | 'CONFLICT' | 'UNCONFIRMED';
export type ResolutionAction = 'REVIEW' | 'CONFIRM' | 'REJECT' | 'MERGE' | 'IGNORE' | 'MARK_MANUAL_ADJUSTMENT';
export type IssueSeverity = 'HIGH' | 'MEDIUM' | 'LOW';

export interface ConnectionView {
  id: number | null;
  providerId: string;
  displayName: string;
  institution: string | null;
  sourceType: SourceType;
  mode: 'LIVE' | 'TEST' | 'MOCK';
  status: 'CONNECTED' | 'PENDING_CONSENT' | 'DISCONNECTED' | 'ERROR';
  consentStatus: string | null;
  consentId: number | null;
  lastSyncAt: string | null;
  lastSuccessfulSyncAt: string | null;
  syncStatus: 'IDLE' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED';
  lastError: string | null;
  authoritative: boolean;
  note: string | null;
}

export interface ProviderView {
  providerId: string;
  displayName: string;
  sourceType: SourceType;
  mode: 'LIVE' | 'TEST' | 'MOCK';
  requiresConsent: boolean;
}

export interface ConnectionsOverview {
  connections: ConnectionView[];
  availableProviders: ProviderView[];
  institutionSourceConnected: boolean;
  message: string | null;
}

export interface SyncRun {
  id: number;
  connectionId: number | null;
  kind: 'INITIAL' | 'INCREMENTAL' | 'FULL_RECONCILIATION' | 'RETRY_FAILED';
  status: string;
  startedAt: string;
  finishedAt: string | null;
  recordsFetched: number;
  recordsCreated: number;
  recordsUpdated: number;
  recordsDuplicated: number;
  recordsRejected: number;
  recordsReconciled: number;
  errors: string | null;
}

export interface AccountHealth {
  accountId: number;
  name: string;
  institution: string;
  ownership: string;
  scorePercent: number | null;
  transactions: number;
  verified: number;
  unverified: number;
}

export interface HoldingHealth {
  accountId: number;
  assetId: number;
  assetName: string | null;
  symbol: string | null;
  isin: string | null;
  calculatedQuantity: number | null;
  reportedQuantity: number | null;
  state: string;
  source: string | null;
  asOf: string | null;
  lastVerifiedAt: string | null;
  openIssueId: number | null;
}

export interface DataHealth {
  scorePercent: number | null;
  formula: string;
  counts: Record<string, number>;
  considered: number;
  verified: number;
  pending: number;
  unconfirmed: number;
  missing: number;
  conflicts: number;
  possibleDuplicates: number;
  duplicatesPrevented: number;
  holdingMismatches: number;
  awaitingConfirmation: number;
  bySource: Record<string, number>;
  hasAuthoritativeSource: boolean;
  lastVerifiedAt: string | null;
  accounts: AccountHealth[];
  holdings: HoldingHealth[];
  warnings: string[];
}

export interface LedgerIssue {
  id: number;
  accountId: number | null;
  assetId: number | null;
  type: string;
  severity: IssueSeverity;
  status: 'OPEN' | 'IN_REVIEW' | 'RESOLVED' | 'AUTO_RESOLVED' | 'IGNORED';
  title: string | null;
  description: string | null;
  expectedValue: string | null;
  observedValue: string | null;
  difference: number | null;
  suspectedCauses: string | null;
  transactionId: number | null;
  otherTransactionId: number | null;
  detectedAt: string;
  resolvedAt: string | null;
  resolutionAction: ResolutionAction | null;
  resolutionNote: string | null;
}

export interface TxnRow {
  id: number;
  accountId: number;
  account: string | null;
  assetId: number | null;
  asset: string | null;
  type: string;
  date: string;
  quantity: number | null;
  unitPrice: number | null;
  netAmount: number | null;
  status: string;
  reconciliationStatus: ReconStatus;
  confidence: number;
  sourceType: SourceType;
  sourceCount: number;
}

export interface TxnSource {
  sourceType: SourceType;
  provider: string | null;
  reference: string | null;
  timestamp: string | null;
  reportedType: string | null;
  reportedDate: string | null;
  quantity: number | null;
  unitPrice: number | null;
  grossAmount: number | null;
  netAmount: number | null;
  recordConfidence: number;
  matchKind: string | null;
  matchExplanation: string | null;
  linkedAt: string | null;
}

export interface TxnDetail {
  summary: TxnRow;
  assetSymbol: string | null;
  assetIsin: string | null;
  accountInstitution: string | null;
  ownership: string | null;
  grossAmount: number | null;
  fees: number | null;
  taxes: number | null;
  currency: string;
  settlementDate: string | null;
  sources: TxnSource[];
  externalReferences: string[];
  issues: { id: number; type: string; severity: IssueSeverity; status: string; title: string | null; description: string | null }[];
  history: { actor: string; action: string; before: string | null; after: string | null; note: string | null; at: string }[];
  createdAt: string | null;
  lastVerifiedAt: string | null;
  ingestedAt: string | null;
  notes: string | null;
  confidenceExplanation: string | null;
}

export interface ImportSummary { rows: number; created: number; duplicated: number; rejected: number; errors: string[]; earliest?: string | null; latest?: string | null }
export interface BackfillSummary { examined: number; created: number; alreadyPresent: number; rejected: number; email: number; manual: number; errors: string[] }

export interface FamilyMemberView { userId: number; name?: string; role: string; sharesData: boolean }
export interface FamilyView { id: number; name: string; owner: boolean; role: string; sharesData: boolean; members: FamilyMemberView[] }
export type Ownership = 'INDIVIDUAL' | 'JOINT' | 'FAMILY';

export const dataPlatformApi = {
  families: () => apiClient.get<FamilyView[]>('/api/data/families'),
  createFamily: (name: string) => apiClient.post('/api/data/families', { name }),
  addFamilyMember: (id: number, email: string) => apiClient.post(`/api/data/families/${id}/members`, { email, role: 'ADULT' }),
  setSharing: (id: number, shares: boolean) => apiClient.put(`/api/data/families/${id}/sharing`, { shares }),
  removeFamilyMember: (id: number, userId: number) => apiClient.delete(`/api/data/families/${id}/members/${userId}`),
  setOwnership: (accountId: number, ownership: Ownership, familyId?: number, coOwnerEmails?: string[]) =>
    apiClient.put(`/api/data/accounts/${accountId}/ownership`, { ownership, familyId, coOwnerEmails }),
  connections: () => apiClient.get<ConnectionsOverview>('/api/data/connections'),
  startConnection: (providerId: string, institution: string) =>
    apiClient.post('/api/data/connections', { providerId, institution }),
  refreshConsent: (consentId: number) => apiClient.post(`/api/data/consents/${consentId}/refresh`),
  disconnect: (id: number) => apiClient.delete(`/api/data/connections/${id}`),
  sync: (id: number, kind: SyncRun['kind']) => apiClient.post<SyncRun>(`/api/data/connections/${id}/sync`, { kind }),
  syncRuns: () => apiClient.get<SyncRun[]>('/api/data/sync-runs'),
  health: (scope: 'self' | 'family' = 'self') => apiClient.get<DataHealth>('/api/data/health', { params: { scope } }),
  issues: (status: 'open' | 'all' = 'open') => apiClient.get<LedgerIssue[]>('/api/data/issues', { params: { status } }),
  act: (id: number, action: ResolutionAction, note?: string, mergeTargetId?: number) =>
    apiClient.post<LedgerIssue>(`/api/data/issues/${id}/action`, { action, note, mergeTargetId }),
  transactions: (opts: { assetId?: number; accountId?: number; scope?: 'self' | 'family'; limit?: number } = {}) =>
    apiClient.get<TxnRow[]>('/api/data/transactions', { params: opts }),
  transaction: (id: number) => apiClient.get<TxnDetail>(`/api/data/transactions/${id}`),
  addToPortfolio: (id: number) => apiClient.post<{ applied: boolean; message: string }>(`/api/data/transactions/${id}/add-to-portfolio`),
  importCsv: (body: { csv: string; institution: string; accountId?: string; sourceType: SourceType; assetClass: string; completeStatement: boolean }) =>
    apiClient.post<ImportSummary>('/api/data/import/csv', body),
  importFile: (file: File, p: { institution: string; accountId?: string; sourceType: SourceType; assetClass: string; completeStatement: boolean }) => {
    const f = new FormData();
    f.append('file', file);
    f.append('institution', p.institution);
    if (p.accountId) f.append('accountId', p.accountId);
    f.append('sourceType', p.sourceType);
    f.append('assetClass', p.assetClass);
    f.append('completeStatement', String(p.completeStatement));
    return apiClient.post<ImportSummary>('/api/data/import/file', f);
  },
  backfill: () => apiClient.post<BackfillSummary>('/api/data/backfill'),
};

export const SOURCE_LABEL: Record<SourceType, string> = {
  ACCOUNT_AGGREGATOR: 'Account Aggregator',
  BROKER_API: 'Broker',
  DEPOSITORY: 'Depository',
  CAS: 'CAS statement',
  STATEMENT: 'Statement',
  EMAIL: 'Email',
  MANUAL: 'Manual entry',
  AI_INFERENCE: 'AI inference',
};

export const RECON_LABEL: Record<ReconStatus, string> = {
  VERIFIED: 'Verified',
  MATCHED: 'Matched',
  PARTIALLY_MATCHED: 'Partly matched',
  PENDING: 'Awaiting confirmation',
  MISSING: 'Missing from your records',
  DUPLICATE: 'Duplicate',
  CONFLICT: 'Sources disagree',
  UNCONFIRMED: 'Not confirmed',
};
