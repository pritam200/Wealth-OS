import { apiClient } from './client';
import type { MarketOverview, QuoteDto, PriceHistory, TechnicalAnalysis } from '../types';

export const marketApi = {
  getOverview: () =>
    apiClient.get<MarketOverview>('/api/market/overview'),

  getQuote: (symbol: string) =>
    apiClient.get<QuoteDto>(`/api/market/quote/${symbol}`),

  search: (q: string) =>
    apiClient.get('/api/market/search', { params: { q } }),

  getHistory: (symbol: string, from: string, to: string) =>
    apiClient.get<PriceHistory[]>(`/api/market/history/${symbol}`, { params: { from, to } }),

  // Raw indicator values only. `signal`/`signalStrength` here are the 4-vote technical
  // signal, NOT the centralized engine verdict — use recommendationApi/analystApi for
  // anything that renders a BUY/SELL/HOLD-style verdict to the user.
  getTechnicals: (symbol: string) =>
    apiClient.get<TechnicalAnalysis>(`/api/technical/${symbol}`),

  getNews: (page = 0) =>
    apiClient.get('/api/news', { params: { page, size: 50 } }),

  getStockNews: (symbol: string) =>
    apiClient.get(`/api/news/symbol/${symbol}`),
};
