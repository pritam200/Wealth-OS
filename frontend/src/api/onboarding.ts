import { apiClient } from './client';
import type { ImportSummary } from './dataPlatform';

export type SourceKind = 'STOCKS' | 'MUTUAL_FUNDS' | 'FIXED_DEPOSITS' | 'RECURRING_DEPOSITS' | 'CREDIT_CARDS';
export type SourceStatus = 'NOT_STARTED' | 'CURRENT' | 'STALE';

export interface SourceView {
  id: number;
  kind: SourceKind;
  name: string;
  /** The latest date the imported data covers; null until something has been imported. */
  syncedThrough: string | null;
  lastImportAt: string | null;
  lastImportNote: string | null;
  status: SourceStatus;
  daysBehind: number | null;
  nextUpdateDue: string | null;
  fileImportable: boolean;
}

export interface Guide {
  kind: SourceKind;
  title: string;
  best: string;
  steps: string[];
  ongoing: string;
  fileImportable: boolean;
}

export interface Checklist {
  sources: SourceView[];
  guides: Guide[];
  gmail: { connected: boolean; email: string | null; lastSyncAt: string | null };
  total: number;
  current: number;
}

export interface CasSchemeSummary {
  amc: string | null; folio: string | null; scheme: string; isin: string;
  closingUnits: number | null; transactions: number; unitsReconcile: boolean;
}
export interface CasSummary {
  periodFrom: string | null; periodTo: string | null; schemes: number; rows: number;
  created: number; duplicated: number; rejected: number;
  holdings: CasSchemeSummary[]; warnings: string[];
}

export const onboardingApi = {
  checklist: () => apiClient.get<Checklist>('/api/onboarding/checklist'),
  addSource: (kind: SourceKind, name: string) => apiClient.post<SourceView>('/api/onboarding/sources', { kind, name }),
  removeSource: (id: number) => apiClient.delete(`/api/onboarding/sources/${id}`),
  setSyncedThrough: (id: number, date: string) =>
    apiClient.put<SourceView>(`/api/onboarding/sources/${id}/synced-through`, { date }),
  importCas: (id: number, file: File, password: string) => {
    const f = new FormData();
    f.append('file', file);
    if (password) f.append('password', password);
    return apiClient.post<{ source: SourceView; summary: CasSummary }>(`/api/onboarding/sources/${id}/import-cas`, f);
  },
  importFile: (id: number, file: File, completeStatement: boolean) => {
    const f = new FormData();
    f.append('file', file);
    f.append('completeStatement', String(completeStatement));
    return apiClient.post<{ source: SourceView; summary: ImportSummary }>(`/api/onboarding/sources/${id}/import`, f);
  },
};

export interface InboundItem {
  id: number; receivedAt: string; from: string | null; subject: string | null; filename: string | null;
  status: 'IMPORTED' | 'NEEDS_PASSWORD' | 'FAILED' | 'IGNORED'; note: string | null;
}
export interface InboundView { enabled: boolean; address: string | null; hasCasPassword: boolean; items: InboundItem[] }

export const inboundApi = {
  view: () => apiClient.get<InboundView>('/api/onboarding/inbound'),
  rotate: () => apiClient.post<InboundView>('/api/onboarding/inbound/rotate'),
  savePassword: (password: string) => apiClient.put('/api/onboarding/inbound/cas-password', { password }),
  clearPassword: () => apiClient.delete('/api/onboarding/inbound/cas-password'),
  unlock: (id: number, password: string, remember: boolean) =>
    apiClient.post<InboundItem>(`/api/onboarding/inbound/items/${id}/unlock`, { password, remember }),
  dismiss: (id: number) => apiClient.delete(`/api/onboarding/inbound/items/${id}`),
};
