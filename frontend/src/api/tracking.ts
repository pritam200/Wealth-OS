import { apiClient } from './client';

export interface FdRequest {
  bank: string; principal: number; rate: number;
  compounding: string; autoRenew: boolean;
  startDate?: string; maturityDate?: string;
}
export interface FdResponse {
  id: number; bank: string; principal: number; rate: number;
  compounding: string; autoRenew: boolean;
  startDate: string; maturityDate: string;
  maturityValue: number; currentValue: number; interestEarned: number; daysToMaturity: number | null;
  // MATURED_RENEWED: matured and its proceeds were rolled into a new FD (see renewedToId) —
  // excluded from net-worth totals since the money is now counted via that successor FD.
  status?: 'ACTIVE' | 'CLOSED' | 'MATURED_RENEWED';
  actualMaturityAmount?: number; closedDate?: string;
  // Present only on the relevant side of a detected renewal.
  renewedToId?: number | null;
  renewedFromId?: number | null;
}

export interface RdRequest {
  bank: string; monthlyAmount: number; rate: number;
  startDate?: string; tenureMonths: number;
}
export interface RdResponse {
  id: number; bank: string; monthlyAmount: number; rate: number;
  startDate: string; tenureMonths: number;
  monthsElapsed: number; totalDeposited: number; currentValue: number;
  projectedCorpus: number; interestEarned: number; progressPercent: number;
}

export interface LoanRequest {
  name: string; type: string; emi: number;
  outstanding: number; rate: number; remainingMonths: number;
}
export interface LoanResponse {
  id: number; name: string; type: string; emi: number;
  outstanding: number; rate: number; remainingMonths: number;
  totalPayable: number; totalInterestPayable: number; repaidPercent: number;
}

export interface OtherAssetRequest {
  name: string; category: string; value: number; note?: string; asOf?: string;
}
export interface OtherAssetResponse {
  id: number; name: string; category: string; value: number; note: string; asOf: string;
}

export interface EpfRequest {
  employer?: string; currentBalance: number;
  monthlyContribution?: number; rate?: number; asOfDate?: string;
}
export interface EpfResponse {
  id: number; employer: string | null; currentBalance: number;
  monthlyContribution: number; rate: number; asOfDate: string | null;
  projected5Y: number; annualInterest: number;
}

export interface TrackingSummary {
  fds: FdResponse[]; rds: RdResponse[];
  loans: LoanResponse[]; otherAssets: OtherAssetResponse[]; epfAccounts: EpfResponse[];
  totalFdPrincipal: number; totalFdMaturityValue: number; totalFdCurrentValue: number;
  totalRdCorpus: number; totalRdCurrentValue: number; totalOtherAssets: number;
  totalEpf: number; totalLoanOutstanding: number; totalMonthlyEmi: number;
}

export const trackingApi = {
  getSummary: () => apiClient.get<TrackingSummary>('/api/tracking/summary'),

  // FD
  listFds:  () => apiClient.get<FdResponse[]>('/api/tracking/fd'),
  addFd:    (r: FdRequest) => apiClient.post<FdResponse>('/api/tracking/fd', r),
  updateFd: (id: number, r: FdRequest) => apiClient.put<FdResponse>(`/api/tracking/fd/${id}`, r),
  deleteFd: (id: number) => apiClient.delete(`/api/tracking/fd/${id}`),
  closeFd: (id: number, actualAmount?: number) =>
    apiClient.post(`/api/tracking/fd/${id}/close`, actualAmount != null ? { actualAmount } : {}),

  // RD
  listRds:  () => apiClient.get<RdResponse[]>('/api/tracking/rd'),
  addRd:    (r: RdRequest) => apiClient.post<RdResponse>('/api/tracking/rd', r),
  updateRd: (id: number, r: RdRequest) => apiClient.put<RdResponse>(`/api/tracking/rd/${id}`, r),
  deleteRd: (id: number) => apiClient.delete(`/api/tracking/rd/${id}`),

  // Loan
  listLoans:   () => apiClient.get<LoanResponse[]>('/api/tracking/loan'),
  addLoan:     (r: LoanRequest) => apiClient.post<LoanResponse>('/api/tracking/loan', r),
  updateLoan:  (id: number, r: LoanRequest) => apiClient.put<LoanResponse>(`/api/tracking/loan/${id}`, r),
  deleteLoan:  (id: number) => apiClient.delete(`/api/tracking/loan/${id}`),

  // Other
  listOther:   () => apiClient.get<OtherAssetResponse[]>('/api/tracking/other'),
  addOther:    (r: OtherAssetRequest) => apiClient.post<OtherAssetResponse>('/api/tracking/other', r),
  updateOther: (id: number, r: OtherAssetRequest) => apiClient.put<OtherAssetResponse>(`/api/tracking/other/${id}`, r),
  deleteOther: (id: number) => apiClient.delete(`/api/tracking/other/${id}`),

  // EPF
  listEpf:  () => apiClient.get<EpfResponse[]>('/api/tracking/epf'),
  addEpf:   (r: EpfRequest) => apiClient.post<EpfResponse>('/api/tracking/epf', r),
  updateEpf: (id: number, r: EpfRequest) => apiClient.put<EpfResponse>(`/api/tracking/epf/${id}`, r),
  deleteEpf: (id: number) => apiClient.delete(`/api/tracking/epf/${id}`),
};
