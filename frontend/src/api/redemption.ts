import { apiClient } from './client';

export interface Reinvestment {
  id: number;
  amount: number;
  date: string;
  targetFund: string | null;
  note: string | null;
}

export interface MfRedemption {
  id: number;
  symbol: string;
  fundName: string | null;
  unitsRedeemed: number;
  navAtRedemption: number;
  redeemedAmount: number;
  investedValueAtRedemption: number;
  redemptionDate: string;
  holdingPeriodDays: number;
  gainType: 'STCG' | 'LTCG';
  capitalGain: number;
  estimatedTax: number;
  reinvestedAmount: number;
  status: 'ACTIVE' | 'COMPLETED';
  reinvestments: Reinvestment[];
  cashRemaining: number;
}

export interface DeploymentTranche {
  label: string;
  amount: number;
  percentOfTotal: number;
  trigger: string;
}

export interface DeploymentPlan {
  tranches: DeploymentTranche[];
  suitableFunds: string[];
  basis: string;
}

export interface ReinvestmentRequest {
  amount: number;
  date?: string;
  targetFund?: string;
  note?: string;
}

export const redemptionApi = {
  list: () => apiClient.get<MfRedemption[]>('/api/redemptions'),
  getDeploymentPlan: (id: number) => apiClient.get<DeploymentPlan>(`/api/redemptions/${id}/deployment-plan`),
  recordReinvestment: (id: number, req: ReinvestmentRequest) =>
    apiClient.post<MfRedemption>(`/api/redemptions/${id}/reinvestments`, req),
};
