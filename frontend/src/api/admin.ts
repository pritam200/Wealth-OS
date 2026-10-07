import { apiClient } from './client';

/** Set once /api/admin/me answers; every change then carries it so a UI pointed at the wrong server is refused. */
let confirmEnvironment: string | null = null;
export const setAdminEnvironment = (env: string | null) => { confirmEnvironment = env; };

const cfg = (mutating: boolean) =>
  mutating && confirmEnvironment ? { headers: { 'X-Confirm-Environment': confirmEnvironment } } : {};

const get = <T,>(url: string, params?: object) => apiClient.get<T>(`/api/admin${url}`, { params }).then(r => r.data);
const post = <T,>(url: string, body?: object) => apiClient.post<T>(`/api/admin${url}`, body ?? {}, cfg(true)).then(r => r.data);
const put = <T,>(url: string, body?: object) => apiClient.put<T>(`/api/admin${url}`, body ?? {}, cfg(true)).then(r => r.data);
const del = <T,>(url: string, params?: object) => apiClient.delete<T>(`/api/admin${url}`, { params, ...cfg(true) }).then(r => r.data);

export type Role = 'READ_ONLY' | 'DEVELOPER' | 'ADMIN';
export interface Me { email: string; role: Role; environment: 'DEV' | 'STAGE' | 'PRODUCTION'; clientIp: string; bootstrap: boolean }
export interface Page<T> { content: T[]; totalElements: number; number: number; size: number }
export interface IpEntry { id: number; cidr: string; description?: string; status: string; expiresAt?: string; createdBy?: string }
export interface EmailEntry { id: number; emailOrDomain: string; kind: 'EMAIL' | 'DOMAIN'; role: Role; status: string; expiresAt?: string }
export interface RequestLog { id: number; occurredAt: string; sourceIp: string; actorEmail?: string; method: string; path: string; status: number; durationMs: number; decision: string; denyReason?: string }
export interface AuditEvent { id: number; occurredAt: string; actorEmail?: string; actorIp?: string; action: string; targetType?: string; targetId?: string; reason?: string; beforeValue?: string; afterValue?: string }
export interface TableInfo { table: string; label: string; idColumn: string; columns: { name: string; type: string; editable: boolean; sensitive: boolean; searchable: boolean }[] }
export interface ConfigItem { name: string; category: string; description: string; secret: boolean; configured: boolean; display: string; lastRotatedAt?: string; rotatedBy?: string }
export interface ChangeRequest { id: number; tableName: string; recordId: string; changesJson: string; reason?: string; status: string; requestedBy: string; decidedBy?: string }

export const adminApi = {
  me: () => get<Me>('/me'),
  dashboard: () => get<Record<string, any>>('/dashboard'),
  ips: () => get<IpEntry[]>('/ip-allowlist'),
  addIp: (b: object) => post<IpEntry>('/ip-allowlist', b),
  deleteIp: (id: number, reason: string) => del(`/ip-allowlist/${id}`, { reason }),
  emails: () => get<EmailEntry[]>('/email-allowlist'),
  addEmail: (b: object) => post<EmailEntry>('/email-allowlist', b),
  updateEmail: (id: number, b: object) => put<EmailEntry>(`/email-allowlist/${id}`, b),
  deleteEmail: (id: number, reason: string) => del(`/email-allowlist/${id}`, { reason }),
  requestLogs: (p: object) => get<Page<RequestLog>>('/request-logs', p),
  auditLogs: (page: number) => get<Page<AuditEvent>>('/audit-logs', { page, size: 25 }),
  verifyAudit: () => get<{ valid: boolean; checked: number; firstBadId?: number; message: string }>('/audit-logs/verify'),
  tables: () => get<TableInfo[]>('/data/tables'),
  search: (table: string, q: string, page: number) => get<{ rows: Record<string, any>[]; total: number; page: number; size: number }>(`/data/${table}`, { q, page }),
  preview: (table: string, id: string, changes: object) => post<{ before: object; after: object; needsApproval: boolean }>(`/data/${table}/${id}/preview`, { changes }),
  update: (table: string, id: string, changes: object, reason: string) => put<Record<string, any>>(`/data/${table}/${id}`, { changes, reason }),
  changes: () => get<ChangeRequest[]>('/data-changes'),
  decide: (id: number, approve: boolean, reason: string) => post(`/data-changes/${id}/${approve ? 'approve' : 'reject'}`, { reason }),
  config: () => get<{ items: ConfigItem[]; flags: { name: string; enabled: boolean }[]; rotationAvailable: boolean }>('/config'),
  rotate: (name: string, value: string, reason: string) => put(`/config/secrets/${name}/rotate`, { value, reason }),
  setFlag: (name: string, enabled: boolean, reason: string) => put(`/config/flags/${name}`, { enabled, reason }),
  deployment: () => get<Record<string, any>>('/deployment'),
  sessions: () => get<{ email: string; activeSessions: number }[]>('/security/sessions'),
  lockdown: () => get<{ engaged: boolean; engagedBy?: string; reason?: string }>('/security/lockdown'),
  engage: (reason: string) => post('/security/lockdown', { reason }),
  release: (reason: string) => post('/security/lockdown/release', { reason }),
  disableIp: (id: number, reason: string) => post(`/security/ip-allowlist/${id}/disable`, { reason }),
  disableAccount: (email: string, reason: string) => post(`/security/accounts/${encodeURIComponent(email)}/disable`, { reason }),
  mfa: () => get<{ required: boolean; enrolled: boolean; fresh: boolean }>('/mfa'),
  mfaEnroll: () => post<{ secret: string; otpauthUri: string }>('/mfa/enroll'),
  mfaVerify: (code: string) => post('/mfa/verify', { code }),
};

export const adminError = (e: any): string =>
  e?.response?.data?.message ?? e?.response?.data?.error ?? e?.message ?? 'Something went wrong';
