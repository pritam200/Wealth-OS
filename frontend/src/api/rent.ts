import { apiClient } from './client';

export interface RentScheduleRequest {
  amount: number;
  dueDayOfMonth: number;
  paidTo?: string;
  cashAccountId?: number;
  paymentMethod?: string;
}

export interface RentSchedule {
  id: number;
  amount: number;
  dueDayOfMonth: number;
  paidTo: string | null;
  cashAccountId: number | null;
  paymentMethod: string | null;
  active: boolean;
}

export interface RentRequest {
  month?: string; // yyyy-MM-dd
  amount: number;
  paidDate?: string;
  paidTo?: string;
  cashAccountId?: number;
  paymentMethod?: string;
  referenceId?: string;
  note?: string;
}

export interface RentResponse {
  id: number;
  scheduleId: number | null;
  month: string;
  amount: number;
  paidDate: string | null;
  paidTo: string | null;
  cashAccountId: number | null;
  paymentMethod: string | null;
  referenceId: string | null;
  note: string | null;
  sourceEmailId: string | null;
  status: 'PAID' | 'UPCOMING';
}

export const rentApi = {
  list: () => apiClient.get<RentResponse[]>('/api/rent'),
  recordPayment: (r: RentRequest) => apiClient.post<RentResponse>('/api/rent', r),
  delete: (id: number) => apiClient.delete(`/api/rent/${id}`),
  listSchedules: () => apiClient.get<RentSchedule[]>('/api/rent/schedule'),
  addSchedule: (r: RentScheduleRequest) => apiClient.post<RentSchedule>('/api/rent/schedule', r),
  setScheduleActive: (id: number, active: boolean) =>
    apiClient.patch<RentSchedule>(`/api/rent/schedule/${id}`, { active }),
  deleteSchedule: (id: number) => apiClient.delete(`/api/rent/schedule/${id}`),
};
