import { apiClient } from './client';

export interface ReconciliationIssue {
  domain: 'PORTFOLIO' | 'FD' | 'RD' | 'NET_WORTH' | 'INGESTION' | 'LEDGER' | string;
  type: string;
  description: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  referenceId: number | null;
}

export interface ReconciliationReport {
  issueCount: number;
  issues: ReconciliationIssue[];
}

/**
 * A finding kept until it goes away: OPEN → (ACKNOWLEDGED | IGNORED) → RESOLVED automatically.
 * An ignored finding stays out of the open list while the check keeps finding it.
 */
export interface StoredIssue {
  id: number;
  domain: string;
  type: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  description: string;
  /** The record it is about — for a PORTFOLIO issue, the holding id. */
  referenceId: number | null;
  /** The check that found it. */
  checkId: string | null;
  status: 'OPEN' | 'ACKNOWLEDGED' | 'IGNORED' | 'RESOLVED';
  firstSeenAt: string;
  lastSeenAt: string;
  resolvedAt: string | null;
  note: string | null;
}

export interface IngestionTotals {
  emailsScanned: number;
  emailsFailed: number;
  attachments: number;
  eventsExtracted: number;
  imported: number;
  duplicatesPrevented: number;
  conflicts: number;
  failedEvents: number;
  needsReview: number;
  passwordFailures: number;
  awaitingPassword: number;
  unreadableScans: number;
  documentsByOutcome: Record<string, number>;
  documentsWithoutCounts: number;
}

export interface BackgroundJob {
  jobName: string;
  lastStartedAt: string | null;
  lastFinishedAt: string | null;
  lastStatus: 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | null;
  lastFailureAt: string | null;
  lastError: string | null;
  consecutiveFailures: number;
}

/** One check and how it came out on the last run: passed when none of its findings is open. */
export interface CheckStatus {
  id: string;
  domain: string;
  description: string;
  openIssues: number;
  ignoredIssues: number;
  failedToRun: boolean;
}

export interface ReconciliationCenter {
  ingestion: IngestionTotals;
  /** Open and acknowledged. */
  openIssues: StoredIssue[];
  ignoredIssues: StoredIssue[];
  recentlyResolved: StoredIssue[];
  failedEmails: { gmailMessageId: string; sender: string | null; subject: string | null; reason: string | null; processedAt: string | null }[];
  backgroundJobs: BackgroundJob[];
  lastSync: { id: number; status: string; resultSummary: string | null; finishedAt: string | null } | null;
  checks: CheckStatus[];
  /** When the checks last actually ran; null if they never have. */
  checkedAt: string | null;
  /** The checks are running now; ask again shortly for their results. */
  refreshing?: boolean;
}

export type AuditClass = 'VERIFIED' | 'DUPLICATE' | 'MISREAD' | 'CONFLICT' | 'CORRUPTED' | 'MISSING' | 'REQUIRES_RECONCILIATION';
export type AuditEntity = 'EXPENSE' | 'INCOME' | 'TRANSACTION' | 'HOLDING' | 'FIXED_DEPOSIT' | 'RECURRING_DEPOSIT';

export interface AuditFinding {
  entity: AuditEntity;
  id: number;
  classification: AuditClass;
  reason: string;
  duplicateOf: number | null;
  amount: number | null;
  date: string | null;
  label: string | null;
  /** A DUPLICATE or MISREAD a rebuild will remove; otherwise refusalReason says why it won't. */
  removable: boolean;
  refusalReason: string | null;
}

export interface DataAuditReport {
  generatedAt: string;
  counts: Record<AuditEntity, Record<AuditClass, number>>;
  duplicateExpenseAmount: number;
  duplicateIncomeAmount: number;
  findings: AuditFinding[];
  totalRecords: number;
  verifiedRecords: number;
}

export interface BackupInfo {
  id: number;
  createdAt: string;
  rowCounts: Record<string, number>;
  appliedAt: string | null;
  appliedSummary: string | null;
  usable: boolean;
}

export interface RebuildResult {
  backupId: number;
  expensesRemoved: number;
  incomesRemoved: number;
  transactionsRemoved: number;
  refundsRelinked: number;
  holdingsRederived: number;
  summary: string;
}

export interface StatementResetLine {
  symbol: string;
  appQuantity: number;
  appInvested: number;
  statementQuantity: number;
  statementInvested: number;
  action: 'KEEP' | 'ADJUST' | 'ADD' | 'REMOVE';
  merged: string[];
  laterTrades: number;
}

export interface StatementResetPreview {
  portfolioId: number;
  portfolioName: string;
  asOf: string;
  lines: StatementResetLine[];
  appInvested: number;
  statementInvested: number;
  holdingsBefore: number;
  holdingsAfter: number;
  tradesReplaced: number;
  reviewsClosed: number;
  summary: string | null;
}

const statementForm = (file: File, portfolioId: number, asOf: string, extra: Record<string, string> = {}) => {
  const f = new FormData();
  f.append('file', file);
  f.append('portfolioId', String(portfolioId));
  f.append('asOf', asOf);
  Object.entries(extra).forEach(([k, v]) => f.append(k, v));
  return f;
};

export const reconciliationApi = {
  getReport: () => apiClient.get<ReconciliationReport>('/api/reconciliation/report'),
  center: (refresh = true) => apiClient.get<ReconciliationCenter>('/api/reconciliation/center', { params: { refresh } }),
  acknowledge: (id: number, note?: string) =>
    apiClient.post<StoredIssue>(`/api/reconciliation/issues/${id}/acknowledge`, { note }),
  ignore: (id: number, note?: string) =>
    apiClient.post<StoredIssue>(`/api/reconciliation/issues/${id}/ignore`, { note }),
  reopen: (id: number) => apiClient.post<StoredIssue>(`/api/reconciliation/issues/${id}/reopen`, {}),
  checks: () => apiClient.get<{ id: string; domain: string; description: string }[]>('/api/reconciliation/checks'),
  dataAudit: () => apiClient.get<DataAuditReport>('/api/reconciliation/data-audit'),
  backups: () => apiClient.get<BackupInfo[]>('/api/reconciliation/backups'),
  createBackup: () => apiClient.post<BackupInfo>('/api/reconciliation/backups'),
  downloadBackup: (id: number) =>
    apiClient.get<Blob>(`/api/reconciliation/backups/${id}/download`, { responseType: 'blob' }),
  rebuild: (backupId: number, remove: { entity: AuditEntity; id: number }[]) =>
    apiClient.post<RebuildResult>('/api/reconciliation/rebuild', { backupId, confirm: 'REBUILD', remove }),
  statementResetPreview: (file: File, portfolioId: number, asOf: string) =>
    apiClient.post<StatementResetPreview>('/api/reconciliation/statement-reset/preview', statementForm(file, portfolioId, asOf)),
  statementResetApply: (file: File, portfolioId: number, asOf: string, backupId: number) =>
    apiClient.post<StatementResetPreview>('/api/reconciliation/statement-reset/apply',
      statementForm(file, portfolioId, asOf, { backupId: String(backupId), confirm: 'RESET' })),
};
