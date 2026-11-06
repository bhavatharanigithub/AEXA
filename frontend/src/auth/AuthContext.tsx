import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import { api, type LoginResponse } from "../api/client";

type AuthState = {
  username: string;
  role: string;
  token: string;
};

type AuthContextValue = {
  user: AuthState | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function readStoredUser(): AuthState | null {
  const token = localStorage.getItem("aexa.token");
  const username = localStorage.getItem("aexa.username");
  const role = localStorage.getItem("aexa.role");
  if (!token || !username || !role) {
    return null;
  }
  return { token, username, role };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthState | null>(readStoredUser);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      login: async (username, password) => {
        const response: LoginResponse = await api.login(username, password);
        localStorage.setItem("aexa.token", response.accessToken);
        localStorage.setItem("aexa.username", response.username);
        localStorage.setItem("aexa.role", response.role);
        setUser({ token: response.accessToken, username: response.username, role: response.role });
      },
      logout: () => {
        localStorage.removeItem("aexa.token");
        localStorage.removeItem("aexa.username");
        localStorage.removeItem("aexa.role");
        setUser(null);
      }
    }),
    [user]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}
