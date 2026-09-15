import { apiClient } from './client';

export interface CashAccount {
  id: number;
  name: string;
  bank?: string;
  lastFour?: string;
  accountType?: 'SAVINGS' | 'CURRENT' | 'WALLET' | 'CASH';
  balance: number;
  asOf?: string;
  active?: boolean;
}

export type TransferDestination =
  | 'CASH_ACCOUNT' | 'MUTUAL_FUND' | 'STOCK' | 'FD' | 'RD' | 'EPF' | 'EXTERNAL';

export interface LedgerTransfer {
  id: number;
  sourceAccount?: CashAccount;
  destinationAccount?: CashAccount;
  destinationType: TransferDestination;
  destinationRef?: string;
  amount: number;
  transferDate: string;
  note?: string;
  applied?: boolean;
}

export interface TransferRequest {
  sourceAccountId?: number;
  destinationAccountId?: number;
  destinationType: TransferDestination;
  destinationRef?: string;
  amount: number;
  transferDate?: string;
  note?: string;
}

/**
 * Cash accounts and internal transfers. A transfer shifts allocation without changing net
 * worth — recording one debits the source and credits the destination by the same amount.
 */
export const ledgerApi = {
  accounts: () => apiClient.get<CashAccount[]>('/api/ledger/accounts'),
  addAccount: (a: Partial<CashAccount>) => apiClient.post<CashAccount>('/api/ledger/accounts', a),
  setBalance: (id: number, balance: number) =>
    apiClient.put<CashAccount>(`/api/ledger/accounts/${id}/balance`, { balance }),
  transfers: () => apiClient.get<LedgerTransfer[]>('/api/ledger/transfers'),
  transfer: (req: TransferRequest) => apiClient.post<LedgerTransfer>('/api/ledger/transfers', req),
  deleteTransfer: (id: number) => apiClient.delete(`/api/ledger/transfers/${id}`),
  cashTotal: () => apiClient.get<{ totalCash: number }>('/api/ledger/cash-total'),
};
