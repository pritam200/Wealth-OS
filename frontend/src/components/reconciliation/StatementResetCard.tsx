import { useEffect, useState } from 'react';
import { FileSpreadsheet, ShieldAlert } from 'lucide-react';
import { reconciliationApi } from '../../api/reconciliation';
import type { StatementResetPreview } from '../../api/reconciliation';
import { portfolioApi } from '../../api/portfolio';
import type { Portfolio } from '../../types';
import { useMaskedText } from '../shared/Amount';
import { errorText, localToday } from '../../utils/dates';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const qty = (n: number) => Number(n ?? 0).toLocaleString('en-IN', { maximumFractionDigits: 4 });

const ACTION: Record<string, { label: string; tone: string }> = {
  KEEP: { label: 'Already right', tone: 'text-gray-500' },
  ADJUST: { label: 'Corrected', tone: 'text-neutral' },
  ADD: { label: 'Added', tone: 'text-bull' },
  REMOVE: { label: 'Removed', tone: 'text-bear' },
};

/**
 * Resets one broker portfolio's shares to the broker's own holdings statement (m.Stock's
 * "Portfolio Statement" export). The preview writes nothing; applying takes a fresh backup first
 * and needs a confirmation. Funds in the portfolio are not touched.
 */
export function StatementResetCard({ onApplied }: { onApplied?: () => void }) {
  const maskText = useMaskedText();
  const [portfolios, setPortfolios] = useState<Portfolio[]>([]);
  const [portfolioId, setPortfolioId] = useState<number | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [asOf, setAsOf] = useState(localToday());
  const [preview, setPreview] = useState<StatementResetPreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    portfolioApi.list().then(r => {
      setPortfolios(r.data);
      const mstock = r.data.find(p => /mirae|m\.?stock/i.test(p.name));
      setPortfolioId((mstock ?? r.data[0])?.id ?? null);
    }).catch(() => setError('The portfolios could not be loaded.'));
  }, []);

  // A preview is for one file, portfolio and date; changing any of them needs a new one.
  useEffect(() => { setPreview(null); }, [file, portfolioId, asOf]);

  const runPreview = async () => {
    if (!file || portfolioId == null) return;
    setBusy(true); setMessage(null); setError(null);
    try {
      const { data } = await reconciliationApi.statementResetPreview(file, portfolioId, asOf);
      setPreview(data);
    } catch (e) {
      setError(errorText(e, 'The statement could not be read; nothing was changed.'));
    } finally { setBusy(false); }
  };

  const apply = async () => {
    if (!file || portfolioId == null || !preview) return;
    const ok = window.confirm(
      `Reset the shares in ${preview.portfolioName} to this statement of ${preview.asOf}?\n\n`
      + `${preview.holdingsBefore} share holding(s) become ${preview.holdingsAfter}. ${preview.tradesReplaced} trade(s) dated on or before `
      + `${preview.asOf} are replaced by the statement's quantities, and ${preview.reviewsClosed} waiting review(s) from before it are closed.\n\n`
      + 'A backup is taken first. Funds are not touched.');
    if (!ok) return;
    setBusy(true); setMessage(null); setError(null);
    try {
      const { data: backup } = await reconciliationApi.createBackup();
      const { data } = await reconciliationApi.statementResetApply(file, portfolioId, asOf, backup.id);
      setMessage(`${data.summary ?? 'The shares now match the statement.'} Backup ${backup.id} holds the data from before.`);
      setPreview(null);
      onApplied?.();
    } catch (e) {
      setError(errorText(e, 'The reset was refused; nothing was changed.'));
    } finally { setBusy(false); }
  };

  const changed = preview?.lines.filter(l => l.action !== 'KEEP') ?? [];

  return (
    <div className="card space-y-3">
      <h2 className="text-sm font-bold text-ink flex items-center gap-2"><FileSpreadsheet size={14} /> Reset shares to a broker statement</h2>
      <p className="text-2xs text-gray-500">
        When trades read from contract notes have gone wrong, the broker's own holdings statement is the safe starting point.
        Upload the m.Stock portfolio statement (.xlsx): each share becomes the statement's quantity at its average price, trades
        after the statement date are kept on top, and contract notes from before it are never booked again. Funds are not touched.
      </p>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
        <label className="text-2xs text-gray-400 space-y-1">
          <span>Portfolio</span>
          <select className="input-field text-xs w-full" value={portfolioId ?? ''} onChange={e => setPortfolioId(Number(e.target.value))}>
            {portfolios.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
        <label className="text-2xs text-gray-400 space-y-1">
          <span>Statement file</span>
          <input type="file" accept=".xlsx,.xls" className="text-xs w-full" onChange={e => setFile(e.target.files?.[0] ?? null)} />
        </label>
        <label className="text-2xs text-gray-400 space-y-1">
          <span>Statement date (trades on this day are in it)</span>
          <input type="date" className="input-field text-xs w-full" value={asOf} max={localToday()} onChange={e => setAsOf(e.target.value)} />
        </label>
      </div>
      <button onClick={runPreview} disabled={busy || !file || portfolioId == null} className="btn-secondary text-xs">
        Preview changes
      </button>

      {preview && (
        <div className="space-y-2">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
            <div className="rounded-lg border border-surface-border p-2"><div className="stat-label">Invested now</div>
              <div className="font-mono text-sm font-bold">{maskText(fmtINR(preview.appInvested))}</div></div>
            <div className="rounded-lg border border-surface-border p-2"><div className="stat-label">Per statement</div>
              <div className="font-mono text-sm font-bold">{maskText(fmtINR(preview.statementInvested))}</div></div>
            <div className="rounded-lg border border-surface-border p-2"><div className="stat-label">Holdings</div>
              <div className="font-mono text-sm font-bold">{preview.holdingsBefore} → {preview.holdingsAfter}</div></div>
            <div className="rounded-lg border border-surface-border p-2"><div className="stat-label">Reviews closed</div>
              <div className="font-mono text-sm font-bold">{preview.reviewsClosed}</div></div>
          </div>
          {changed.length === 0
            ? <p className="text-xs text-bull">The portfolio already matches this statement.</p>
            : (
              <div className="max-h-80 overflow-y-auto">
                <table className="w-full text-2xs">
                  <thead className="text-gray-500 text-left">
                    <tr><th className="py-1">Share</th><th>Now</th><th>Statement</th><th>Change</th></tr>
                  </thead>
                  <tbody>
                    {changed.map(l => (
                      <tr key={l.symbol} className="border-t border-surface-border/60">
                        <td className="py-1 text-ink">{l.symbol}
                          {l.merged.length > 0 && <div className="text-gray-500">joins {l.merged.join(', ')}</div>}
                          {l.laterTrades > 0 && <div className="text-gray-500">{l.laterTrades} later trade(s) kept</div>}
                        </td>
                        <td className="font-mono">{qty(l.appQuantity)} · {maskText(fmtINR(l.appInvested))}</td>
                        <td className="font-mono">{qty(l.statementQuantity)} · {maskText(fmtINR(l.statementInvested))}</td>
                        <td className={ACTION[l.action]?.tone}>{ACTION[l.action]?.label ?? l.action}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          <button onClick={apply} disabled={busy || changed.length === 0} className="btn-primary text-xs flex items-center gap-1">
            <ShieldAlert size={11} /> Back up and reset {preview.portfolioName}
          </button>
        </div>
      )}
      {message && <p className="text-2xs text-bull">{message}</p>}
      {error && <p className="text-2xs text-bear">{error}</p>}
    </div>
  );
}
