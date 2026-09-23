import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { TrendingUp } from 'lucide-react';
import { authApi } from '../../api/auth';
import { identityApi } from '../../api/identity';
import { useAuthStore } from '../../store/authStore';

const PAN_FORMAT = /^[A-Za-z]{5}[0-9]{4}[A-Za-z]$/;

export function RegisterPage() {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pan, setPan] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(false);
  const login = useAuthStore(s => s.login);
  const navigate = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setNotice('');

    const trimmedPan = pan.trim().toUpperCase();
    if (trimmedPan && !PAN_FORMAT.test(trimmedPan)) {
      setError('PAN must be 5 letters, 4 digits, then 1 letter (for example ABCDE1234F)');
      return;
    }

    setLoading(true);
    try {
      const { data } = await authApi.register(name, email, password);
      login(data);

      // PAN/date of birth are optional and stored separately (never as part of the account
      // record) so they can power automatic statement-password unlocking. A failure here must
      // not undo the account that was just created — the user can always add these later from
      // settings, so we surface it as a notice, not a blocking error.
      if (trimmedPan || dateOfBirth) {
        try {
          await identityApi.save(trimmedPan || undefined, dateOfBirth || undefined);
        } catch (identityErr: any) {
          setNotice(
            identityErr.response?.data?.message ??
              'Account created, but PAN/date of birth could not be saved — you can add them later in settings.'
          );
          setLoading(false);
          return; // stay on the page so the notice is actually seen before navigating away
        }
      }

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
    <div className="min-h-screen app-canvas flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="flex items-center justify-center gap-3 mb-8">
          <TrendingUp size={28} className="text-brand" />
          <span className="text-2xl font-bold text-ink">MarketAI</span>
        </div>

        <div className="card">
          <h1 className="text-xl font-semibold text-ink mb-6">Create Account</h1>

          {error && (
            <div className="bg-bear/10 border border-bear/30 text-bear text-sm px-4 py-3 rounded-lg mb-4">
              {error}
            </div>
          )}

          {notice && (
            <div className="bg-brand/10 border border-brand/30 text-sm text-gray-200 px-4 py-3 rounded-lg mb-4">
              <p className="mb-2">{notice}</p>
              <button
                type="button"
                onClick={() => navigate('/')}
                className="text-brand hover:text-brand-light font-medium"
              >
                Continue to dashboard →
              </button>
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

            <div className="pt-2 border-t border-surface-border">
              <p className="text-xs text-gray-500 mb-3">
                Optional — lets us automatically open password-protected bank/broker statement
                PDFs from your email. Encrypted at rest; never shown back to you or anyone else.
              </p>
              <div>
                <label className="stat-label block mb-1.5">PAN (optional)</label>
                <input
                  value={pan}
                  onChange={e => setPan(e.target.value)}
                  maxLength={10}
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors uppercase"
                  placeholder="ABCDE1234F"
                />
              </div>
              <div className="mt-4">
                <label className="stat-label block mb-1.5">Date of Birth (optional)</label>
                <input
                  type="date"
                  value={dateOfBirth}
                  onChange={e => setDateOfBirth(e.target.value)}
                  max={new Date().toISOString().slice(0, 10)}
                  className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors"
                />
              </div>
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
