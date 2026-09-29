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

/** A finding kept until it goes away: OPEN → (ACKNOWLEDGED) → RESOLVED automatically. */
export interface StoredIssue {
  id: number;
  domain: string;
  type: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  description: string;
  referenceId: number | null;
  status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';
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

export interface ReconciliationCenter {
  ingestion: IngestionTotals;
  openIssues: StoredIssue[];
  recentlyResolved: StoredIssue[];
  failedEmails: { gmailMessageId: string; sender: string | null; subject: string | null; reason: string | null; processedAt: string | null }[];
  backgroundJobs: BackgroundJob[];
  lastSync: { id: number; status: string; resultSummary: string | null; finishedAt: string | null } | null;
  checkedAt: string;
}

export type AuditClass = 'VERIFIED' | 'DUPLICATE' | 'CONFLICT' | 'CORRUPTED' | 'MISSING' | 'REQUIRES_RECONCILIATION';
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

export const reconciliationApi = {
  getReport: () => apiClient.get<ReconciliationReport>('/api/reconciliation/report'),
  center: (refresh = true) => apiClient.get<ReconciliationCenter>('/api/reconciliation/center', { params: { refresh } }),
  acknowledge: (id: number, note?: string) =>
    apiClient.post<StoredIssue>(`/api/reconciliation/issues/${id}/acknowledge`, { note }),
  dataAudit: () => apiClient.get<DataAuditReport>('/api/reconciliation/data-audit'),
  backups: () => apiClient.get<BackupInfo[]>('/api/reconciliation/backups'),
  createBackup: () => apiClient.post<BackupInfo>('/api/reconciliation/backups'),
  downloadBackup: (id: number) =>
    apiClient.get<Blob>(`/api/reconciliation/backups/${id}/download`, { responseType: 'blob' }),
  rebuild: (backupId: number, remove: { entity: AuditEntity; id: number }[]) =>
    apiClient.post<RebuildResult>('/api/reconciliation/rebuild', { backupId, confirm: 'REBUILD', remove }),
};
