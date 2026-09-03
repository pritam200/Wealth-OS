import { apiClient } from './client';
import type { AiResponse } from '../types';

export const aiApi = {
  analyseStock: (symbol: string, prompt: string) =>
    apiClient.post<AiResponse>('/api/ai/analyse-stock', { symbol, prompt }),

  marketSummary: () =>
    apiClient.post<AiResponse>('/api/ai/market-summary'),

  portfolioReview: (portfolioId: number) =>
    apiClient.post<AiResponse>(`/api/ai/portfolio-review/${portfolioId}`),

  chat: (prompt: string, symbol?: string) =>
    apiClient.post<AiResponse>('/api/ai/chat', { prompt, symbol }),
};
