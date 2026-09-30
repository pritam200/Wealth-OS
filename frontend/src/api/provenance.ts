import { apiClient } from './client';

export type RecordKind = 'expense' | 'income' | 'rent' | 'transaction' | 'fd' | 'rd';

/** Where a ledger record came from — GET /api/provenance/{kind}/{id}. */
export interface SourceView {
  kind: RecordKind;
  id: number;
  origin: 'MANUAL' | 'EMAIL_LLM' | 'PDF_LLM' | 'UNKNOWN' | string;
  confidence: number | null;
  gmailMessageId: string | null;
  gmailLink: string | null;
  emailSender: string | null;
  emailProcessedAt: string | null;
  attachmentId: string | null;
  documentHash: string | null;
  extractedAs: string | null;
  duplicateState: string | null;
  conflictDetail: string | null;
  importedAt: string | null;
  extractionVersion: string | null;
}

export const provenanceApi = {
  source: (kind: RecordKind, id: number) => apiClient.get<SourceView>(`/api/provenance/${kind}/${id}`),
};
