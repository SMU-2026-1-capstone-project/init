import { create } from 'zustand';
import * as SecureStore from 'expo-secure-store';
import { authService } from '@/services/authService';
import type { User, LoginRequest, SignupRequest } from '@/types/auth';

interface AuthState {
  user: User | null;
  token: string | null;
  isLoading: boolean;
  isAuthenticated: boolean;

  login: (data: LoginRequest) => Promise<void>;
  signup: (data: SignupRequest) => Promise<void>;
  logout: () => Promise<void>;
  restoreSession: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  token: null,
  isLoading: true,
  isAuthenticated: false,

  login: async (data) => {
    const res = await authService.login(data);
    const { token, user } = res.data;
    await SecureStore.setItemAsync('accessToken', token);
    set({ user, token, isAuthenticated: true });
  },

  signup: async (data) => {
    const res = await authService.signup(data);
    const { token, user } = res.data;
    await SecureStore.setItemAsync('accessToken', token);
    set({ user, token, isAuthenticated: true });
  },

  logout: async () => {
    await SecureStore.deleteItemAsync('accessToken');
    set({ user: null, token: null, isAuthenticated: false });
  },

  restoreSession: async () => {
    try {
      const token = await SecureStore.getItemAsync('accessToken');
      if (token) {
        const res = await authService.getMe();
        set({ user: res.data, token, isAuthenticated: true, isLoading: false });
      } else {
        set({ isLoading: false });
      }
    } catch {
      await SecureStore.deleteItemAsync('accessToken');
      set({ user: null, token: null, isAuthenticated: false, isLoading: false });
    }
  },
}));
