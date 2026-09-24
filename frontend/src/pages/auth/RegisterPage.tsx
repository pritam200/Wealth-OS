import { useState, useRef, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { TrendingUp, ShieldCheck, Lock, Mail, ArrowLeft, RefreshCw } from 'lucide-react';
import { authApi } from '../../api/auth';
import { identityApi } from '../../api/identity';
import { useAuthStore } from '../../store/authStore';
import { OnboardingModal } from '../../components/onboarding/OnboardingModal';

const PAN_FORMAT = /^[A-Za-z]{5}[0-9]{4}[A-Za-z]$/;
const RESEND_COOLDOWN_SECONDS = 45;

type Stage = 'details' | 'verify';

export function RegisterPage() {
  const [stage, setStage] = useState<Stage>('details');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pan, setPan] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [showOnboarding, setShowOnboarding] = useState(false);
  const login = useAuthStore(s => s.login);
  const navigate = useNavigate();
  const codeInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setTimeout(() => setCooldown(c => c - 1), 1000);
    return () => clearTimeout(t);
  }, [cooldown]);

  useEffect(() => {
    if (stage === 'verify') codeInputRef.current?.focus();
  }, [stage]);

  const requestCode = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    const trimmedPan = pan.trim().toUpperCase();
    if (trimmedPan && !PAN_FORMAT.test(trimmedPan)) {
      setError('PAN must be 5 letters, 4 digits, then 1 letter (for example ABCDE1234F)');
      return;
    }

    setLoading(true);
    try {
      await authApi.requestRegistrationOtp(email);
      setStage('verify');
      setCooldown(RESEND_COOLDOWN_SECONDS);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Could not send a verification code. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const resendCode = async () => {
    if (cooldown > 0) return;
    setError('');
    setLoading(true);
    try {
      await authApi.requestRegistrationOtp(email);
      setCooldown(RESEND_COOLDOWN_SECONDS);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Could not resend the code. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const verifyAndRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setNotice('');
    setLoading(true);

    const trimmedPan = pan.trim().toUpperCase();

    try {
      const { data: verified } = await authApi.verifyRegistrationOtp(email, code);
      const { data } = await authApi.register(name, email, password, verified.verificationToken);
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
        }
      }

      setShowOnboarding(true);
    } catch (err: any) {
      const validationErrors = err.response?.data?.validationErrors;
      if (validationErrors) {
        setError(Object.values(validationErrors).join(', '));
      } else {
        setError(err.response?.data?.message ?? 'Verification failed');
      }
    } finally {
      setLoading(false);
    }
  };

  if (showOnboarding) {
    return <OnboardingModal userName={name} onFinish={() => navigate('/')} />;
  }

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
            One place for every rupee — stocks, funds, deposits, and spend, actually reconciled.
          </h2>
          <p className="text-white/80 text-sm leading-relaxed max-w-md">
            Statements are parsed, cross-checked, and never guessed at — figures that can't be
            verified against the source document are flagged for your review instead of quietly
            assumed.
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
            {stage === 'details' ? (
              <>
                <h1 className="text-xl font-semibold text-ink mb-1">Create your account</h1>
                <p className="text-sm text-gray-500 mb-6">Takes about a minute. We'll email you a code to confirm it's really you.</p>

                {error && (
                  <div className="bg-bear/10 border border-bear/30 text-bear text-sm px-4 py-3 rounded-lg mb-4">{error}</div>
                )}

                <form onSubmit={requestCode} className="space-y-4">
                  <div>
                    <label className="stat-label block mb-1.5">Full Name</label>
                    <input value={name} onChange={e => setName(e.target.value)} required minLength={2}
                      className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors"
                      placeholder="Your Name" />
                  </div>
                  <div>
                    <label className="stat-label block mb-1.5">Email</label>
                    <input type="email" value={email} onChange={e => setEmail(e.target.value)} required
                      className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors"
                      placeholder="you@example.com" />
                  </div>
                  <div>
                    <label className="stat-label block mb-1.5">Password</label>
                    <input type="password" value={password} onChange={e => setPassword(e.target.value)} required minLength={8}
                      className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors"
                      placeholder="Min 8 characters" />
                  </div>

                  <div className="pt-2 border-t border-surface-border">
                    <p className="text-xs text-gray-500 mb-3">
                      Optional — lets us automatically open password-protected bank/broker statement
                      PDFs from your email. Encrypted at rest; never shown back to you or anyone else.
                    </p>
                    <div>
                      <label className="stat-label block mb-1.5">PAN (optional)</label>
                      <input value={pan} onChange={e => setPan(e.target.value)} maxLength={10}
                        className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors uppercase"
                        placeholder="ABCDE1234F" />
                    </div>
                    <div className="mt-4">
                      <label className="stat-label block mb-1.5">Date of Birth (optional)</label>
                      <input type="date" value={dateOfBirth} onChange={e => setDateOfBirth(e.target.value)}
                        max={new Date().toISOString().slice(0, 10)}
                        className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-2.5 text-sm text-gray-200 outline-none focus:border-brand/50 transition-colors" />
                    </div>
                  </div>

                  <button type="submit" disabled={loading} className="btn-primary w-full py-2.5 disabled:opacity-50">
                    {loading ? 'Sending code...' : 'Send verification code'}
                  </button>
                </form>

                <p className="mt-4 text-center text-sm text-gray-500">
                  Already have an account?{' '}
                  <Link to="/login" className="text-brand hover:text-brand-light">Sign In</Link>
                </p>
              </>
            ) : (
              <>
                <button onClick={() => setStage('details')} className="btn-ghost text-xs mb-3 -ml-2"><ArrowLeft size={13} /> Back</button>
                <div className="icon-badge-brand w-11 h-11 mb-3"><Mail size={18} /></div>
                <h1 className="text-xl font-semibold text-ink mb-1">Check your email</h1>
                <p className="text-sm text-gray-500 mb-6">
                  We sent a 6-digit code to <span className="text-gray-300 font-medium">{email}</span>. Enter it below to finish creating your account.
                </p>

                {error && (
                  <div className="bg-bear/10 border border-bear/30 text-bear text-sm px-4 py-3 rounded-lg mb-4">{error}</div>
                )}
                {notice && (
                  <div className="bg-brand/10 border border-brand/30 text-sm text-gray-200 px-4 py-3 rounded-lg mb-4">{notice}</div>
                )}

                <form onSubmit={verifyAndRegister} className="space-y-4">
                  <div>
                    <label className="stat-label block mb-1.5">Verification code</label>
                    <input ref={codeInputRef} value={code} onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                      required inputMode="numeric" pattern="\d{6}" maxLength={6}
                      className="w-full bg-surface-hover border border-surface-border rounded-lg px-4 py-3 text-center text-2xl font-mono tracking-[0.5em] text-ink outline-none focus:border-brand/50 transition-colors"
                      placeholder="000000" />
                  </div>

                  <button type="submit" disabled={loading || code.length !== 6} className="btn-primary w-full py-2.5 disabled:opacity-50">
                    {loading ? 'Verifying...' : 'Verify & create account'}
                  </button>

                  <button type="button" onClick={resendCode} disabled={cooldown > 0 || loading}
                    className="btn-ghost w-full justify-center text-sm disabled:opacity-50">
                    <RefreshCw size={13} /> {cooldown > 0 ? `Resend code in ${cooldown}s` : 'Resend code'}
                  </button>
                </form>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
