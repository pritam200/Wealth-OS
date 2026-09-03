import { apiClient } from './client';

export interface CardResponse {
  id: number;
  name: string; issuer: string; network: string; lastFour: string | null;
  annualFee: number; pointValue: number; pointsBalance: number;
  billingDay: number | null; dueDay: number | null;
  benefits: string | null; bestFor: string | null;
  rewardRates: Record<string, number>;
  pointsCashValue: number;
  currentDue: number | null;
  currentDueDate: string | null;
}

export interface CatalogEntry {
  name: string; issuer: string; network: string; bestFor: string;
  annualFee: number; pointValue: number;
  benefits: string[]; rewardRates: Record<string, number>;
}

export interface CardRequest {
  name?: string; issuer?: string; network?: string; lastFour?: string;
  annualFee?: number; pointValue?: number; pointsBalance?: number;
  billingDay?: number; dueDay?: number; benefits?: string; bestFor?: string;
  rewardRates?: Record<string, number>;
  catalogName?: string;
}

export interface RecommendResult {
  cardId: number | null; cardName: string; issuer: string;
  network?: string;
  rewardRate: number; expectedReward: number; reason: string;
  best: boolean; fromCatalog: boolean;
  eligible: boolean; ineligibilityNote?: string;
}

export interface PointsTip {
  cardId: number; cardName: string; pointsBalance: number;
  cashValue: number; bestValue: number; recommendation: string;
}

export const cardApi = {
  catalog:    () => apiClient.get<CatalogEntry[]>('/api/cards/catalog'),
  categories: () => apiClient.get<string[]>('/api/cards/categories'),
  list:       () => apiClient.get<CardResponse[]>('/api/cards'),
  add:        (r: CardRequest) => apiClient.post<CardResponse>('/api/cards', r),
  update:     (id: number, r: CardRequest) => apiClient.put<CardResponse>(`/api/cards/${id}`, r),
  delete:     (id: number) => apiClient.delete(`/api/cards/${id}`),
  updatePoints: (id: number, pointsBalance: number) =>
    apiClient.put<CardResponse>(`/api/cards/${id}/points`, { pointsBalance }),
  recommend:  (category: string, amount: number, merchant?: string) =>
    apiClient.post<RecommendResult[]>('/api/cards/recommend', { category, amount, merchant }),
  pointsTips: () => apiClient.get<PointsTip[]>('/api/cards/points-tips'),
};
