import { apiClient } from './client';
import type { AuthResponse } from '../types';

export const authApi = {
  register: (name: string, email: string, password: string) =>
    apiClient.post<AuthResponse>('/api/auth/register', { name, email, password }),

  login: (email: string, password: string) =>
    apiClient.post<AuthResponse>('/api/auth/login', { email, password }),

  logout: () => apiClient.post('/api/auth/logout'),
};
