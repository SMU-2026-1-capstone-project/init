import api from './api';
import type { LoginRequest, SignupRequest, AuthResponse } from '@/types/auth';

export const authService = {
  login: (data: LoginRequest) =>
    api.post<AuthResponse>('/auth/login', data),

  signup: (data: SignupRequest) =>
    api.post<AuthResponse>('/auth/signup', data),

  getMe: () =>
    api.get('/auth/me'),
};
