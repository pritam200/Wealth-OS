import { apiClient } from './client';

export type SubscriptionCadence = 'WEEKLY' | 'MONTHLY' | 'ANNUAL';

export interface Subscription {
  merchant: string;
  category?: string;
  cadence: SubscriptionCadence;
  /** Historical average charge — the pre-increase baseline when priceIncreased is true. */
  typicalAmount: number;
  currentAmount: number;
  lastSeenDate: string;
  occurrenceCount: number;
  priceIncreased: boolean;
  previousAmount?: number;
}

/**
 * Recurring charges detected from already-imported/categorized expense history — read-only,
 * derived data, same as Reminders. Nothing here is user-editable; there's no entity to write to.
 */
export const subscriptionsApi = {
  list: () => apiClient.get<Subscription[]>('/api/subscriptions'),
};
