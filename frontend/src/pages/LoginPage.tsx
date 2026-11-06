import { FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("admin-change-me");
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await login(username, password);
      navigate("/");
    } catch {
      setError("Sign-in failed. Check the backend is running and credentials are correct.");
    }
  }

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={onSubmit}>
        <div className="brand">
          <span className="brand-mark" />
          Aexa
        </div>
        <h1>Welcome back</h1>
        <p className="empty">Local development accounts are documented in `.env.example`. Change them before any shared demo.</p>
        <label className="field">
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} />
        </label>
        <label className="field">
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error ? <p className="error">{error}</p> : null}
        <button className="primary-btn" type="submit">
          Sign in
        </button>
      </form>
    </div>
  );
}
