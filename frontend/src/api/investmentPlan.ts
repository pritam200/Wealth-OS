import { apiClient } from './client';

export type PlanInvestmentType = 'MUTUAL_FUND' | 'STOCK' | 'FD' | 'RD' | 'EPF' | 'OTHER';

export interface PlannedInvestmentRequest {
  sourceAccountId?: number;
  plannedAmount: number;
  investmentType: PlanInvestmentType;
  destinationRef?: string;
  scheduled?: boolean;
  dueDate?: string;
}

export interface PlannedInvestmentResponse {
  id: number;
  month: string;
  sourceAccountId: number | null;
  plannedAmount: number;
  investmentType: PlanInvestmentType;
  destinationRef: string | null;
  scheduled: boolean;
  dueDate: string | null;
  status: 'PLANNED' | 'PARTIAL' | 'COMPLETE' | 'OVER_INVESTED';
  actualAmount: number;
  remainingAmount: number;
  overInvestedAmount: number;
  completionPercent: number;
}

export interface MonthlyPlanReviewResponse {
  month: string;
  totalPlanned: number;
  totalCompleted: number;
  totalPending: number;
  totalOverInvested: number;
  completionRate: number;
  completed: PlannedInvestmentResponse[];
  pending: PlannedInvestmentResponse[];
  overInvested: PlannedInvestmentResponse[];
}

// Matches backend PlannedInvestment.InvestmentType
export const PLAN_INVESTMENT_TYPES: PlanInvestmentType[] = ['MUTUAL_FUND', 'STOCK', 'FD', 'RD', 'EPF', 'OTHER'];

const monthKey = (year: number, month: number) => `${year}-${String(month).padStart(2, '0')}`;

export const investmentPlanApi = {
  list: (year: number, month: number) =>
    apiClient.get<PlannedInvestmentResponse[]>(`/api/investment-plan/${monthKey(year, month)}`),
  add: (year: number, month: number, r: PlannedInvestmentRequest) =>
    apiClient.post<PlannedInvestmentResponse>(`/api/investment-plan/${monthKey(year, month)}`, r),
  delete: (year: number, month: number, id: number) =>
    apiClient.delete(`/api/investment-plan/${monthKey(year, month)}/${id}`),
  review: (year: number, month: number) =>
    apiClient.get<MonthlyPlanReviewResponse>(`/api/investment-plan/${monthKey(year, month)}/review`),
};
