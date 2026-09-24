import { apiClient } from './client';
import type { AuthResponse } from '../types';

export const authApi = {
  register: (name: string, email: string, password: string, otpVerificationToken: string) =>
    apiClient.post<AuthResponse>('/api/auth/register', { name, email, password, otpVerificationToken }),

  login: (email: string, password: string) =>
    apiClient.post<AuthResponse>('/api/auth/login', { email, password }),

  logout: () => apiClient.post('/api/auth/logout'),

  requestRegistrationOtp: (email: string) =>
    apiClient.post<void>('/api/auth/register/otp/request', { email }),

  verifyRegistrationOtp: (email: string, code: string) =>
    apiClient.post<{ verificationToken: string }>('/api/auth/register/otp/verify', { email, code }),
};
