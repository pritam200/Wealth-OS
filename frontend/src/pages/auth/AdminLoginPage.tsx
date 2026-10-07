import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import { authApi } from '../../api/auth';
import { adminApi } from '../../api/admin';
import { useAuthStore } from '../../store/authStore';

/**
 * Sign-in for the admin console. It is the normal account login, followed by a check that this
 * account may use the console from this network. The answer is deliberately vague: it never says
 * whether the password, the allowlist or the network was the problem.
 */
export function AdminLoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { login, logout } = useAuthStore();
  const navigate = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(''); setLoading(true);
    try {
      const { data } = await authApi.login(email, password);
      login(data);
      try {
        await adminApi.me();
        navigate('/admin', { replace: true });
      } catch {
        logout();   // signed in, but not an administrator here: do not leave a session behind
        setError('Access denied. This account cannot use the admin console from this location.');
      }
    } catch {
      setError('Sign-in failed. Check your email and password.');
    } finally {
      setPassword('');
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen app-canvas flex items-center justify-center p-4">
      <form onSubmit={submit} className="card p-6 w-full max-w-sm space-y-4" autoComplete="off">
        <div className="flex items-center gap-2 text-ink">
          <ShieldCheck className="text-brand-light" size={22} />
          <div>
            <h1 className="text-lg font-bold leading-tight">Admin console</h1>
            <p className="text-[11px] text-gray-500">Authorised administrators only. Activity is logged.</p>
          </div>
        </div>
        <input type="email" required autoFocus className="input w-full" placeholder="Admin email" value={email} onChange={e => setEmail(e.target.value)} />
        <input type="password" required className="input w-full" placeholder="Password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} />
        {error && <p role="alert" className="text-xs text-bear">{error}</p>}
        <button disabled={loading} className="btn-primary w-full text-sm py-2">{loading ? 'Checking…' : 'Sign in'}</button>
        <a href="/" className="block text-center text-[11px] text-gray-500">← Back to MarketAI</a>
      </form>
    </div>
  );
}
