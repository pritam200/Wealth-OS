import { apiClient } from './client';

export type ActionStatus = 'PENDING' | 'EXECUTED' | 'SKIPPED' | 'SNOOZED';
export type ActionType = 'BUY' | 'REDUCE' | 'BOOK_PROFIT' | 'HOLD' | 'WATCH';

export interface ActionItem {
  id: number;
  actionType: ActionType;
  symbol: string;
  name?: string;
  assetType?: 'STOCK' | 'MF';
  amount?: number;
  quantity?: number;
  status: ActionStatus;
  note?: string;
  snoozedUntil?: string;
  actionDate: string;
  updatedAt?: string;
}

export interface ActionUpdate {
  actionType: ActionType;
  symbol: string;
  name?: string;
  assetType?: string;
  amount?: number;
  quantity?: number;
  status: ActionStatus;
  note?: string;
  snoozedUntil?: string;
}

/**
 * Records what the user did with a recommendation. Marking one EXECUTED does not change any
 * holding — the portfolio only moves when a real transaction is booked, so after executing a
 * trade the user still records it (or lets the broker email import it).
 */
export const actionsApi = {
  today: () => apiClient.get<ActionItem[]>('/api/actions'),
  record: (update: ActionUpdate) => apiClient.post<ActionItem>('/api/actions', update),
  note: (id: number, note: string) => apiClient.patch<ActionItem>(`/api/actions/${id}/note`, { note }),
};
