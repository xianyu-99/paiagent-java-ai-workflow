import { create } from 'zustand';
import { clearStoredAuth, getAccessToken, getRefreshToken, getRole, getUserId, getUsername, setStoredAuth } from '../utils/auth';

interface AuthState {
  token: string | null;
  refreshToken: string | null;
  userId: number | null;
  username: string | null;
  role: string | null;
  isAuthenticated: boolean;
  setAuth: (token: string, refreshToken: string, username: string, role?: string, userId?: number) => void;
  clearAuth: () => void;
}

/**
 * 认证状态管理
 */
export const useAuthStore = create<AuthState>((set) => ({
  token: getAccessToken(),
  refreshToken: getRefreshToken(),
  userId: getUserId(),
  username: getUsername(),
  role: getRole(),
  isAuthenticated: !!getRefreshToken(),
  
  setAuth: (token: string, refreshToken: string, username: string, role?: string, userId?: number) => {
    setStoredAuth(token, refreshToken, username, role, userId);
    set({ token, refreshToken, username, role: role || null, userId: userId || null, isAuthenticated: true });
  },
  
  clearAuth: () => {
    clearStoredAuth();
    set({ token: null, refreshToken: null, userId: null, username: null, role: null, isAuthenticated: false });
  },
}));
