import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { TrendingUp, ShieldCheck, Lock, Mail } from 'lucide-react';
import { authApi } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const login = useAuthStore(s => s.login);
  const navigate = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const { data } = await authApi.login(email, password);
      login(data);
      navigate('/');
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Login failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen app-canvas flex">
      {/* Brand / trust panel — hidden on small screens, where the form alone needs the space. */}
      <div className="hidden lg:flex lg:w-[46%] flex-col justify-between p-12 bg-brand-gradient text-white relative overflow-hidden">
        <div className="absolute inset-0 opacity-10" style={{ backgroundImage: 'radial-gradient(circle at 20% 20%, white 1px, transparent 1px)', backgroundSize: '28px 28px' }} />
        <div className="relative z-10 flex items-center gap-2.5">
          <TrendingUp size={26} />
          <span className="text-xl font-bold">MarketAI</span>
        </div>
        <div className="relative z-10">
          <h2 className="text-3xl font-bold leading-tight mb-4">
            Welcome back. Your portfolio, statements, and spend — all in one reconciled view.
          </h2>
          <p className="text-white/80 text-sm leading-relaxed max-w-md">
            Nothing here is guessed — every figure either traces back to a source document or is
            flagged for your review.
          </p>
        </div>
        <div className="relative z-10 space-y-3 text-sm text-white/85">
          <div className="flex items-center gap-2.5"><ShieldCheck size={16} /> Bank/broker credentials are never stored in plaintext</div>
          <div className="flex items-center gap-2.5"><Lock size={16} /> PAN and DOB are encrypted at rest, never shown back to anyone</div>
          <div className="flex items-center gap-2.5"><Mail size={16} /> Email verified before any account is created</div>
        </div>
      </div>

      <div className="flex-1 flex items-center justify-center p-4">
        <div className="w-full max-w-md">
          <div className="flex lg:hidden items-center justify-center gap-3 mb-8">
            <TrendingUp size={28} className="text-brand" />
            <span className="text-2xl font-bold text-ink">MarketAI</span>
          </div>

          <div className="card-elevated">
            <h1 className="text-xl font-semibold text-ink mb-1">Sign in</h1>
            <p className="text-sm text-gray-500 mb-6">Welcome back — enter your details to continue.</p>

            {error && (
              <div className="bg-bear/10 border border-bear/30 text-bear text-sm px-4 py-3 rounded-lg mb-4">
                {error}
              </div>
            )}

            <form onSubmit={submit} className="space-y-4">
              <div>
                <label className="stat-label block mb-1.5">Email</label>
                <input
                  type="email"
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                  required
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors"
                  placeholder="you@example.com"
                />
              </div>
              <div>
                <label className="stat-label block mb-1.5">Password</label>
                <input
                  type="password"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  required
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors"
                  placeholder="••••••••"
                />
              </div>
              <button type="submit" disabled={loading} className="btn-primary w-full py-2.5 disabled:opacity-50">
                {loading ? 'Signing in...' : 'Sign In'}
              </button>
            </form>

            <p className="mt-4 text-center text-sm text-gray-500">
              Don't have an account?{' '}
              <Link to="/register" className="text-brand hover:text-brand-light">Register</Link>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
