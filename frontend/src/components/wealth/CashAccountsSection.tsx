import { useEffect, useState, useCallback } from 'react';
import { Landmark, Plus, ArrowRightLeft, Trash2, Info } from 'lucide-react';
import { ledgerApi } from '../../api/ledger';
import type { CashAccount, LedgerTransfer, TransferDestination } from '../../api/ledger';
import { useMaskedText } from '../shared/Amount';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

const DESTINATIONS: { value: TransferDestination; label: string }[] = [
  { value: 'CASH_ACCOUNT', label: 'Another account' },
  { value: 'MUTUAL_FUND', label: 'Mutual fund' },
  { value: 'STOCK', label: 'Stocks / broker' },
  { value: 'FD', label: 'Fixed deposit' },
  { value: 'RD', label: 'Recurring deposit' },
  { value: 'EPF', label: 'EPF' },
  { value: 'EXTERNAL', label: 'Outside (someone else)' },
];

/**
 * Bank and cash balances, plus internal transfers.
 *
 * Why this section matters: without a cash balance, moving money from a bank into a fund made
 * net worth jump by the transfer amount, because the fund grew and nothing shrank. Recording
 * cash — and recording moves as transfers rather than spending — is what keeps the total honest.
 */
export function CashAccountsSection() {
  const maskText = useMaskedText();
  const [accounts, setAccounts] = useState<CashAccount[]>([]);
  const [transfers, setTransfers] = useState<LedgerTransfer[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAdd, setShowAdd] = useState(false);
  const [showTransfer, setShowTransfer] = useState(false);
  const [error, setError] = useState('');

  const [name, setName] = useState('');
  const [bank, setBank] = useState('');
  const [balance, setBalance] = useState('');

  const [sourceId, setSourceId] = useState<string>('');
  const [destType, setDestType] = useState<TransferDestination>('MUTUAL_FUND');
  const [destId, setDestId] = useState<string>('');
  const [destRef, setDestRef] = useState('');
  const [amount, setAmount] = useState('');

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([ledgerApi.accounts(), ledgerApi.transfers()])
      .then(([a, t]) => { setAccounts(a.data); setTransfers(t.data); })
      .catch(() => { setAccounts([]); setTransfers([]); })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const total = accounts.reduce((s, a) => s + (a.balance ?? 0), 0);

  const addAccount = async () => {
    if (!name.trim()) { setError('Give the account a name.'); return; }
    setError('');
    try {
      await ledgerApi.addAccount({
        name: name.trim(), bank: bank.trim() || undefined,
        balance: Number(balance) || 0,
      });
      setName(''); setBank(''); setBalance(''); setShowAdd(false);
      load();
    } catch { setError('Could not add that account.'); }
  };

  const doTransfer = async () => {
    const amt = Number(amount);
    if (!Number.isFinite(amt) || amt <= 0) { setError('Enter an amount greater than zero.'); return; }
    if (!sourceId) { setError('Pick the account the money left.'); return; }
    if (destType === 'CASH_ACCOUNT' && !destId) { setError('Pick the destination account.'); return; }
    setError('');
    try {
      await ledgerApi.transfer({
        sourceAccountId: Number(sourceId),
        destinationAccountId: destType === 'CASH_ACCOUNT' ? Number(destId) : undefined,
        destinationType: destType,
        destinationRef: destRef.trim() || undefined,
        amount: amt,
      });
      setAmount(''); setDestRef(''); setShowTransfer(false);
      load();
    } catch { setError('Could not record that transfer.'); }
  };

  const removeTransfer = async (id: number) => {
    try { await ledgerApi.deleteTransfer(id); load(); }
    catch { setError('Could not remove that transfer.'); }
  };

  if (loading) return <div className="card animate-pulse h-40 bg-surface-hover" />;

  return (
    <div className="card">
      <div className="flex items-start justify-between mb-3 flex-wrap gap-2">
        <h3 className="section-title mb-0"><Landmark size={15} className="text-brand-light" /> Bank &amp; Cash</h3>
        <div className="flex items-center gap-1.5">
          {accounts.length > 0 && (
            <button onClick={() => { setShowTransfer(s => !s); setShowAdd(false); }} className="btn-secondary text-2xs py-1 px-2">
              <ArrowRightLeft size={11} /> Transfer
            </button>
          )}
          <button onClick={() => { setShowAdd(s => !s); setShowTransfer(false); }} className="btn-secondary text-2xs py-1 px-2">
            <Plus size={11} /> Account
          </button>
        </div>
      </div>

      <div className="card-flat mb-3">
        <div className="stat-label mb-0.5">Total cash</div>
        <div className="text-xl font-mono tabular-nums font-bold text-ink">{maskText(fmt(total))}</div>
      </div>

      {error && <p className="text-2xs text-bear mb-2">{error}</p>}

      {showAdd && (
        <div className="rounded-xl border border-surface-border bg-surface-hover/40 p-3 mb-3 space-y-2">
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
            <input value={name} onChange={e => setName(e.target.value)} placeholder="HDFC Savings" className="input-field text-xs" />
            <input value={bank} onChange={e => setBank(e.target.value)} placeholder="Bank (optional)" className="input-field text-xs" />
            <input value={balance} onChange={e => setBalance(e.target.value)} inputMode="numeric"
                   placeholder="Current balance" className="input-field text-xs font-mono tabular-nums" />
          </div>
          <button onClick={addAccount} className="btn-primary text-2xs">Add account</button>
        </div>
      )}

      {showTransfer && (
        <div className="rounded-xl border border-surface-border bg-surface-hover/40 p-3 mb-3 space-y-2">
          <p className="text-2xs text-gray-500 flex items-start gap-1.5">
            <Info size={11} className="mt-0.5 shrink-0 text-brand-light" />
            A transfer moves money between things you already own — it changes your allocation,
            never your net worth.
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <select value={sourceId} onChange={e => setSourceId(e.target.value)} className="input-field text-xs">
              <option value="">From account…</option>
              {accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
            <select value={destType} onChange={e => setDestType(e.target.value as TransferDestination)} className="input-field text-xs">
              {DESTINATIONS.map(d => <option key={d.value} value={d.value}>{d.label}</option>)}
            </select>
            {destType === 'CASH_ACCOUNT' ? (
              <select value={destId} onChange={e => setDestId(e.target.value)} className="input-field text-xs">
                <option value="">To account…</option>
                {accounts.filter(a => String(a.id) !== sourceId).map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            ) : (
              <input value={destRef} onChange={e => setDestRef(e.target.value)}
                     placeholder="Fund / broker name (optional)" className="input-field text-xs" />
            )}
            <input value={amount} onChange={e => setAmount(e.target.value)} inputMode="numeric"
                   placeholder="Amount" className="input-field text-xs font-mono tabular-nums" />
          </div>
          <button onClick={doTransfer} className="btn-primary text-2xs">Record transfer</button>
        </div>
      )}

      {accounts.length === 0 ? (
        <p className="text-xs text-gray-600 py-3">
          No cash accounts yet. Adding one lets net worth include your bank balance, and lets
          buy recommendations be sized against money you actually have.
        </p>
      ) : (
        <div className="space-y-1.5">
          {accounts.map(a => (
            <div key={a.id} className="flex items-center justify-between gap-2 rounded-lg border border-surface-border bg-surface-hover/40 px-3 py-2">
              <div className="min-w-0">
                <span className="text-xs text-gray-200 truncate">{a.name}</span>
                {a.bank && <span className="text-2xs text-gray-600 ml-2">{a.bank}</span>}
                {a.asOf && <div className="text-2xs text-gray-600">as of {a.asOf}</div>}
              </div>
              <span className="text-xs font-mono tabular-nums text-ink shrink-0">{maskText(fmt(a.balance))}</span>
            </div>
          ))}
        </div>
      )}

      {transfers.length > 0 && (
        <div className="mt-3 pt-3 border-t border-surface-border">
          <div className="stat-label mb-2">Recent transfers</div>
          <div className="space-y-1">
            {transfers.slice(0, 6).map(t => (
              <div key={t.id} className="flex items-center justify-between gap-2 text-2xs group">
                <span className="text-gray-400 truncate min-w-0">
                  {t.transferDate} · {t.sourceAccount?.name ?? 'external'} →{' '}
                  {t.destinationAccount?.name ?? t.destinationRef ?? DESTINATIONS.find(d => d.value === t.destinationType)?.label}
                </span>
                <span className="flex items-center gap-1.5 shrink-0">
                  <span className="font-mono tabular-nums text-gray-300">{maskText(fmt(t.amount))}</span>
                  <button onClick={() => removeTransfer(t.id)}
                          className="btn-icon opacity-0 group-hover:opacity-100 transition-opacity"
                          title="Remove and restore balances">
                    <Trash2 size={11} />
                  </button>
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
