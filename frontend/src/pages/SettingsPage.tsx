import { useEffect, useState, useCallback } from 'react';
import { Settings as SettingsIcon, ShieldCheck, AlertCircle, Trash2 } from 'lucide-react';
import { identityApi } from '../api/identity';
import type { FinancialIdentityStatus } from '../api/identity';

const PAN_FORMAT = /^[A-Za-z]{5}[0-9]{4}[A-Za-z]$/;

/**
 * The server never returns a stored PAN/DOB, so these inputs are write-only: they clear after
 * a successful save rather than re-populating from a value that was never sent back.
 */
function FinancialIdentityCard() {
  const [status, setStatus] = useState<FinancialIdentityStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [pan, setPan] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [saving, setSaving] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    identityApi.status()
      .then(r => setStatus(r.data))
      .catch(() => setStatus(null))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    setError(null);
    const trimmedPan = pan.trim().toUpperCase();
    if (trimmedPan && !PAN_FORMAT.test(trimmedPan)) {
      setError('PAN must be 5 letters, 4 digits, then 1 letter (for example ABCDE1234F)');
      return;
    }
    if (!trimmedPan && !dateOfBirth) {
      setError('Enter a PAN or date of birth to save');
      return;
    }
    setSaving(true);
    try {
      const { data } = await identityApi.save(trimmedPan || undefined, dateOfBirth || undefined);
      setStatus(data);
      setPan('');
      setDateOfBirth('');
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Could not save — please try again');
    } finally {
      setSaving(false);
    }
  };

  const clear = async () => {
    if (!window.confirm('Remove your saved PAN and date of birth? Locked statement PDFs will no longer unlock automatically.')) return;
    setClearing(true);
    setError(null);
    try {
      await identityApi.clear();
      load();
    } catch {
      setError('Could not clear — please try again');
    } finally {
      setClearing(false);
    }
  };

  if (loading) return <div className="card animate-pulse h-40 bg-surface-hover" />;

  return (
    <div className="card">
      <h3 className="section-title"><ShieldCheck size={15} className="text-brand-light" /> Financial Identity</h3>
      <p className="text-xs text-gray-600 mb-3">
        Used only to automatically open password-protected bank/broker statement PDFs from your
        email. Encrypted at rest — never shown back to you or anyone else, only whether a value
        is currently saved.
      </p>

      {status && (
        <div className={`text-xs mb-3 px-3 py-2 rounded-lg border ${
          status.canDerivePasswords
            ? 'border-bull/30 bg-bull/10 text-bull'
            : 'border-surface-border bg-surface-hover/40 text-gray-500'
        }`}>
          {status.message}
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-3">
        <div>
          <label className="stat-label block mb-1.5">PAN</label>
          <input
            value={pan}
            onChange={e => setPan(e.target.value)}
            maxLength={10}
            className="input-field uppercase"
            placeholder={status?.panSaved ? 'Saved — enter a new PAN to replace it' : 'ABCDE1234F'}
          />
        </div>
        <div>
          <label className="stat-label block mb-1.5">Date of Birth</label>
          <input
            type="date"
            value={dateOfBirth}
            onChange={e => setDateOfBirth(e.target.value)}
            max={new Date().toISOString().slice(0, 10)}
            className="input-field"
          />
        </div>
      </div>

      {error && (
        <div className="text-xs text-bear flex items-center gap-1.5 mb-3">
          <AlertCircle size={12} /> {error}
        </div>
      )}

      <div className="flex items-center gap-2">
        <button onClick={save} disabled={saving} className="btn-primary text-xs px-3 py-1.5 disabled:opacity-50">
          {saving ? 'Saving...' : 'Save'}
        </button>
        {status?.canDerivePasswords && (
          <button onClick={clear} disabled={clearing} className="btn-ghost text-xs px-3 py-1.5 text-bear hover:text-bear flex items-center gap-1.5">
            <Trash2 size={12} /> {clearing ? 'Clearing...' : 'Clear saved identity'}
          </button>
        )}
      </div>
    </div>
  );
}

export function SettingsPage() {
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <SettingsIcon size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-ink mb-0.5">Settings</h2>
          <p className="text-gray-500 text-xs">Account and data-sync preferences</p>
        </div>
      </div>

      <FinancialIdentityCard />
    </div>
  );
}
