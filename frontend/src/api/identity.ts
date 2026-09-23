import { apiClient } from './client';

export interface FinancialIdentityStatus {
  panSaved: boolean;
  dobSaved: boolean;
  canDerivePasswords: boolean;
  message: string;
}

export const identityApi = {
  status: () => apiClient.get<FinancialIdentityStatus>('/api/identity'),

  // Either field may be omitted to update just the other — the server never echoes back
  // what was stored, only whether it was.
  save: (pan?: string, dateOfBirth?: string) =>
    apiClient.put<FinancialIdentityStatus>('/api/identity', { pan, dateOfBirth }),

  clear: () => apiClient.delete<void>('/api/identity'),
};
