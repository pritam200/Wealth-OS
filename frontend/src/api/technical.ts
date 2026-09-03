import { apiClient } from './client';
import type { TechnicalAnalysis } from '../types';

// Single definition of the shape lives in ../types — this file used to carry a second,
// slightly different copy of it, which is how the two drifted apart (one had nullable
// indicators, the other didn't).
export type { TechnicalAnalysis, DataQuality, TrendValue } from '../types';

export const technicalApi = {
  analyse: (symbol: string) =>
    apiClient.get<TechnicalAnalysis>(`/api/technical/${encodeURIComponent(symbol)}`),
};
