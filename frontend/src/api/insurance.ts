import { apiClient } from './client';

export type PolicyType = 'TERM' | 'HEALTH' | 'MOTOR' | 'OTHER';
export type PremiumFrequency = 'MONTHLY' | 'QUARTERLY' | 'ANNUAL';

export interface InsurancePolicyRequest {
  policyType: PolicyType;
  insurer: string;
  policyNumber?: string;
  sumAssured?: number;
  premiumAmount: number;
  premiumFrequency: PremiumFrequency;
  nextPremiumDueDate?: string;
  startDate?: string;
  endDate?: string;
  notes?: string;
}

export interface InsurancePolicyResponse {
  id: number;
  policyType: PolicyType;
  insurer: string;
  policyNumber?: string;
  sumAssured?: number;
  premiumAmount: number;
  premiumFrequency: PremiumFrequency;
  nextPremiumDueDate?: string;
  startDate?: string;
  endDate?: string;
  notes?: string;
  status?: 'ACTIVE' | 'LAPSED' | 'CLOSED';
  daysToNextPremium: number | null;
}

export const insuranceApi = {
  list:   () => apiClient.get<InsurancePolicyResponse[]>('/api/insurance'),
  add:    (r: InsurancePolicyRequest) => apiClient.post<InsurancePolicyResponse>('/api/insurance', r),
  update: (id: number, r: InsurancePolicyRequest) => apiClient.put<InsurancePolicyResponse>(`/api/insurance/${id}`, r),
  remove: (id: number) => apiClient.delete(`/api/insurance/${id}`),
};
