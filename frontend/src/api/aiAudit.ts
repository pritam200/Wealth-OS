import { apiClient } from './client';

export interface AiAuditTrailEntry {
  id: number;
  task: string;
  provider: string | null;
  model: string | null;
  promptVersion: string | null;
  fallbackUsed: boolean;
  referenceId: string | null;
  systemInstruction: string | null;
  prompt: string | null;
  rawOutput: string | null;
  confidence: number | null;
  status: string;
  note: string | null;
  latencyMs: number | null;
  createdAt: string;
}

export const aiAuditApi = {
  // referenceId is the gmailMessageId stored as sourceEmailId on the record the extraction
  // produced (Expense/Income/Rent/etc.) — see backend AiAuditService.getByReference.
  byReference: (referenceId: string) =>
    apiClient.get<AiAuditTrailEntry[]>(`/api/ai-audit/by-reference/${encodeURIComponent(referenceId)}`),
};
