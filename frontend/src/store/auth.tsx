import { createContext, useContext, useEffect, useState, ReactNode, useCallback } from 'react';
import { authApi } from '@/api/auth';
import { clearAuth, setToken, TOKEN_KEY, USER_KEY } from '@/api/http';
import { CurrentUser, LoginParams } from '@/types';

interface AuthState {
  user: CurrentUser | null;
  ready: boolean;
  signIn: (params: LoginParams) => Promise<void>;
  signOut: () => void;
}

const AuthContext = createContext<AuthState>({
  user: null,
  ready: false,
  signIn: async () => {},
  signOut: () => {},
});

function readStoredUser(): CurrentUser | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as CurrentUser;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(readStoredUser);
  const [ready, setReady] = useState(false);

  // 启动时若有 token，向后端核对当前用户
  useEffect(() => {
    let cancelled = false;
    async function verify() {
      if (!localStorage.getItem(TOKEN_KEY)) {
        setReady(true);
        return;
      }
      try {
        const u = await authApi.me();
        if (!cancelled) {
          setUser(u);
          localStorage.setItem(USER_KEY, JSON.stringify(u));
        }
      } catch {
        if (!cancelled) setUser(null);
      } finally {
        if (!cancelled) setReady(true);
      }
    }
    verify();
    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (params: LoginParams) => {
    const res = await authApi.login(params);
    setToken(res.token);
    setUser(res.user);
    localStorage.setItem(USER_KEY, JSON.stringify(res.user));
  }, []);

  const signOut = useCallback(() => {
    clearAuth();
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, ready, signIn, signOut }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
