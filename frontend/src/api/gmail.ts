import { apiClient } from './client';

export interface GmailStatus {
  connected: boolean;
  connectedEmail: string | null;
  lastSyncAt: string | null;
  importedCount: number;
}

export interface SyncLogEntry {
  gmailMessageId: string;
  sender: string;
  subject: string;
  matchedParser: string | null;
  status: string;
  type: string | null;
  detail: string;
  itemsImported: number;
  pipelineSteps: string[] | null;
}

export interface ReconciliationReport {
  emailsProcessed: number;
  attachmentsProcessed: number;
  transactionsFound: number;
  transactionsImported: number;
  duplicatesSkipped: number;
  failedImports: number;
  pdfsPending: number;
  status: 'OK' | 'ACTION_REQUIRED';
  actionItems: string[];
}

/** The one shape describing what a sync actually did — previously these figures were split
 *  across the token's lastSyncAt and the nested reconciliation report. */
export interface GmailSyncSummary {
  lastSync: string | null;
  scanned: number;
  newlyImported: number;
  duplicatesSkipped: number;
  failed: number;
  extractedTransactions: number;
  queuedForReview: number;
}

export interface GmailSyncResult {
  imported: number;
  skipped: number;
  failed: number;
  summaries: string[];
  logEntries: SyncLogEntry[];
  error: string | null;
  reconciliation: ReconciliationReport | null;
  stats: GmailSyncSummary | null;
}

export interface ProcessedEmailEntry {
  id: number;
  gmailMessageId: string;
  processedAt: string;
  type: string;
  status: 'IMPORTED' | 'SKIPPED' | 'FAILED' | 'REVIEW_REQUIRED';
  matchedParser: string | null;
  sender: string | null;
  resultSummary: string | null;
}

export interface PipelineStep {
  step: string;
  status: 'OK' | 'FAIL' | 'EMPTY' | 'ERROR' | 'SKIP';
  detail: string;
}

export interface PendingPdf {
  id: number;
  filename: string;
  sender: string;
  subject: string;
  providerKey: string | null;
  status: 'NEEDS_PASSWORD' | 'PASSWORD_FAILED' | 'IMPORTED' | 'FAILED' | 'DISMISSED';
  resultSummary: string | null;
  passwordHint: string | null;
  pipelineSteps: string | null;
  tradesExtracted: number | null;
  tradesImported: number | null;
  textSnippet: string | null;
  createdAt: string;
  unlockedAt: string | null;
}

export interface PdfUnlockResult {
  unlocked: boolean;
  message: string;
}

export interface SavedPassword {
  id: number;
  providerKey: string;
  passwordHint: string | null;
  createdAt: string;
  updatedAt: string | null;
  lastUsedAt: string | null;
}

export interface ExcludedSender {
  id: number;
  pattern: string;
  label: string | null;
  createdAt: string;
}

// The persistent, queryable counterpart to ReconciliationReport above (which is only a
// one-shot snapshot from the last sync run). This is built from the full ProcessedEmail +
// PendingPdf history for the user, so it survives across page loads. `duplicates` and
// `reconciled` are `null` (not `0`) whenever the backend can't honestly compute them from
// what's persisted — see their *Note fields for exactly why — so treat null and 0 as
// different claims: null means "unknown", 0 means "confirmed zero".
export interface ReconciliationDetailRow {
  gmailMessageId: string;
  subject: string | null;
  sender: string | null;
  matchedParser: string | null;
  status: 'Failed' | 'Unparsed';
  reason: string | null;
  processedAt: string | null;
  source: 'EMAIL' | 'PDF';
}

export interface ReconciliationReportDto {
  imported: number;
  updatedNote: string;
  duplicates: number | null;
  duplicatesNote: string;
  failed: number;
  unparsed: number;
  reconciled: number | null;
  reconciledNote: string;
  excluded: number;
  pdfsAwaitingPassword: number;
  details: ReconciliationDetailRow[];
  generatedAt: string;
}

export const gmailApi = {
  // Returns {error} instead of {url} when Gmail OAuth credentials aren't configured.
  getAuthUrl: () => apiClient.get<{ url: string; error?: string }>('/api/gmail/auth-url'),
  getStatus:  () => apiClient.get<GmailStatus>('/api/gmail/status'),
  triggerSync: () => apiClient.post<GmailSyncResult>('/api/gmail/sync', {}),
  fullResync: () => apiClient.post<GmailSyncResult>('/api/gmail/resync', {}),
  retryFailed: () => apiClient.post<GmailSyncResult>('/api/gmail/retry-failed', {}),
  disconnect: () => apiClient.delete('/api/gmail/disconnect'),
  getHistory: (limit = 100) => apiClient.get<ProcessedEmailEntry[]>('/api/gmail/history', { params: { limit } }),
  // "Locked statements" — financial emails whose PDF attachment (contract note, margin
  // statement) couldn't be read automatically and no saved password (see below) unlocked
  // it yet. A successful manual unlock saves the password (encrypted) for this sender's
  // provider, so future statements from the same institution unlock automatically.
  getPendingPdfs: () => apiClient.get<PendingPdf[]>('/api/gmail/pending-pdfs'),
  unlockPdf: (id: number, password: string) =>
    apiClient.post<PdfUnlockResult>(`/api/gmail/pending-pdfs/${id}/unlock`, { password }),
  dismissPdf: (id: number) => apiClient.delete(`/api/gmail/pending-pdfs/${id}`),
  // "Manage Saved Passwords" — view/update/delete the encrypted per-provider passwords
  // saved from past unlocks. The password value itself is never returned by the API.
  getSavedPasswords: () => apiClient.get<SavedPassword[]>('/api/gmail/saved-passwords'),
  updateSavedPassword: (id: number, password: string) =>
    apiClient.put(`/api/gmail/saved-passwords/${id}`, { password }),
  deleteSavedPassword: (id: number) => apiClient.delete(`/api/gmail/saved-passwords/${id}`),
  // Patterns (sender address, client code, folio number, or account-holder name) that should
  // never be auto-imported — e.g. a shared inbox that also receives a parent's account emails.
  getExcludedSenders: () => apiClient.get<ExcludedSender[]>('/api/gmail/excluded-senders'),
  addExcludedSender: (pattern: string, label?: string) =>
    apiClient.post<ExcludedSender>('/api/gmail/excluded-senders', { pattern, label }),
  removeExcludedSender: (id: number) => apiClient.delete(`/api/gmail/excluded-senders/${id}`),
  getContractNoteDebug: () => apiClient.get<PendingPdf[]>('/api/gmail/contract-note-debug'),
  // Persistent, queryable reconciliation report — see ReconciliationReportDto for why
  // `duplicates`/`reconciled` are nullable rather than defaulting to 0.
  getReconciliationReport: () => apiClient.get<ReconciliationReportDto>('/api/gmail/reconciliation-report'),
};

/* ── Async sync jobs ──────────────────────────────────────────────────────────────────────
   A full scan is minutes of work and outlives an HTTP request, so syncing now queues a job
   and the UI polls it. The old blocking POST /api/gmail/sync is kept for compatibility but
   should not be used for large windows.                                                   */

export type SyncJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface SyncJob {
  id: number;
  type: 'GMAIL_FULL_SYNC' | 'GMAIL_INCREMENTAL_SYNC' | 'GMAIL_RETRY_FAILED';
  status: SyncJobStatus;
  trigger: 'MANUAL' | 'SCHEDULED' | 'PUSH' | 'RECOVERY';
  itemsTotal?: number | null;
  itemsProcessed?: number | null;
  attempts?: number;
  resultSummary?: string | null;
  lastError?: string | null;
  createdAt?: string;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export const syncJobApi = {
  /** Queues a sync. Returns 202 with the job; incremental unless `full` is set. */
  queueGmail: (opts?: { full?: boolean; lookback?: string }) =>
    apiClient.post<SyncJob>('/api/sync/gmail', {}, {
      params: { full: opts?.full ?? false, lookback: opts?.lookback },
    }),
  get: (id: number) => apiClient.get<SyncJob>(`/api/sync/jobs/${id}`),
  recent: (limit = 10) => apiClient.get<SyncJob[]>('/api/sync/jobs', { params: { limit } }),
};
