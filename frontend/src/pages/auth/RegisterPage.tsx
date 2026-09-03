import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { TrendingUp } from 'lucide-react';
import { authApi } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';

export function RegisterPage() {
  const [name, setName] = useState('');
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
      const { data } = await authApi.register(name, email, password);
      login(data);
      navigate('/');
    } catch (err: any) {
      const validationErrors = err.response?.data?.validationErrors;
      if (validationErrors) {
        setError(Object.values(validationErrors).join(', '));
      } else {
        setError(err.response?.data?.message ?? 'Registration failed');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="flex items-center justify-center gap-3 mb-8">
          <TrendingUp size={28} className="text-brand" />
          <span className="text-2xl font-bold text-white">MarketAI</span>
        </div>

        <div className="card">
          <h1 className="text-xl font-semibold text-white mb-6">Create Account</h1>

          {error && (
            <div className="bg-bear/10 border border-bear/30 text-bear text-sm px-4 py-3 rounded-lg mb-4">
              {error}
            </div>
          )}

          <form onSubmit={submit} className="space-y-4">
            <div>
              <label className="stat-label block mb-1.5">Full Name</label>
              <input
                value={name}
                onChange={e => setName(e.target.value)}
                required minLength={2}
                className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors"
                placeholder="Your Name"
              />
            </div>
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
                required minLength={8}
                className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors"
                placeholder="Min 8 characters"
              />
            </div>
            <button type="submit" disabled={loading} className="btn-primary w-full py-2.5 disabled:opacity-50">
              {loading ? 'Creating account...' : 'Create Account'}
            </button>
          </form>

          <p className="mt-4 text-center text-sm text-gray-500">
            Already have an account?{' '}
            <Link to="/login" className="text-brand hover:text-brand-light">Sign In</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
