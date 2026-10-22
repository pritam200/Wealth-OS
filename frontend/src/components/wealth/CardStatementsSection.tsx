import { useEffect, useMemo, useState, useCallback } from 'react';
import { FileText, Loader2, RefreshCw, Search, CheckCircle2, PlusCircle, AlertCircle, Undo2, ArrowDownLeft } from 'lucide-react';
import { cardApi } from '../../api/card';
import type { CardResponse, CardStatement, StatementDetail, StatementLine, LineStatus } from '../../api/card';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number | null | undefined) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const fmtINR2 = (n: number | null | undefined) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n || 0);

const STATUS: Record<LineStatus, { label: string; cls: string; hint: string }> = {
  MATCHED: { label: 'In expenses', cls: 'pill-bull', hint: 'Already recorded by an earlier sync (usually the card alert).' },
  ADDED:   { label: 'Added from statement', cls: 'pill-info', hint: 'No earlier sync had this charge, so it was added to your expenses.' },
  MISSING: { label: 'Not in expenses', cls: 'pill-neutral', hint: 'No matching expense, and no date to book it on. Add it yourself.' },
  IGNORED: { label: 'Removed', cls: 'pill-muted', hint: 'You took this charge out of your expenses.' },
  CREDIT:  { label: 'Payment / refund', cls: 'pill-muted', hint: 'Money back onto the card — not an expense.' },
};

type Filter = 'ALL' | 'DEBITS' | 'CREDITS' | 'ADDED' | 'MISSING';

/**
 * Every statement read for a card, with its transactions. Each charge is ticked off against the
 * expenses already on record; charges no earlier sync caught are added to expenses from the
 * statement (and can be taken back out). Payments and refunds are listed but never counted.
 */
export function CardStatementsSection({ cards, refreshKey }: { cards: CardResponse[]; refreshKey?: number }) {
  const maskText = useMaskedText();
  const [cardId, setCardId] = useState<number | null>(cards[0]?.id ?? null);
  const [statements, setStatements] = useState<CardStatement[]>([]);
  const [statementId, setStatementId] = useState<number | null>(null);
  const [detail, setDetail] = useState<StatementDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState<number | 'tally' | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [q, setQ] = useState('');
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    if (cardId == null || !cards.some(c => c.id === cardId)) setCardId(cards[0]?.id ?? null);
  }, [cards, cardId]);

  useEffect(() => {
    if (cardId == null) { setStatements([]); return; }
    cardApi.statements(cardId).then(r => {
      const sorted = [...r.data].sort((a, b) => (b.statementDate ?? b.dueDate ?? '').localeCompare(a.statementDate ?? a.dueDate ?? ''));
      setStatements(sorted);
      setStatementId(sorted[0]?.id ?? null);
    }).catch(() => setStatements([]));
  }, [cardId, refreshKey]);

  const loadDetail = useCallback(() => {
    if (cardId == null || statementId == null) { setDetail(null); return; }
    setLoading(true);
    cardApi.statement(cardId, statementId).then(r => setDetail(r.data)).catch(() => setDetail(null)).finally(() => setLoading(false));
  }, [cardId, statementId]);
  useEffect(() => { loadDetail(); }, [loadDetail]);

  const lines = detail?.lines ?? [];
  const shown = useMemo(() => lines.filter(l => {
    if (filter === 'DEBITS' && !l.debit) return false;
    if (filter === 'CREDITS' && l.debit) return false;
    if (filter === 'ADDED' && l.status !== 'ADDED') return false;
    if (filter === 'MISSING' && l.status !== 'MISSING' && l.status !== 'IGNORED') return false;
    if (q && !(l.merchant ?? '').toLowerCase().includes(q.toLowerCase())) return false;
    return true;
  }), [lines, filter, q]);

  const byCategory = useMemo(() => {
    const m = new Map<string, number>();
    lines.filter(l => l.debit).forEach(l => m.set(l.category || 'Other', (m.get(l.category || 'Other') ?? 0) + l.amount));
    return [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6);
  }, [lines]);
  const topMerchants = useMemo(() => {
    const m = new Map<string, { amt: number; n: number }>();
    lines.filter(l => l.debit).forEach(l => {
      const k = (l.merchant || 'Unknown').trim();
      const v = m.get(k) ?? { amt: 0, n: 0 };
      m.set(k, { amt: v.amt + l.amount, n: v.n + 1 });
    });
    return [...m.entries()].sort((a, b) => b[1].amt - a[1].amt).slice(0, 5);
  }, [lines]);

  const act = async (line: StatementLine, action: 'add' | 'remove') => {
    if (cardId == null) return;
    setBusy(line.id); setMsg(null);
    try { setDetail((await cardApi.lineAction(cardId, line.id, action)).data); }
    catch (e: any) { setMsg(e?.response?.data?.message ?? 'Could not update that line.'); }
    finally { setBusy(null); }
  };
  const retally = async () => {
    if (cardId == null || statementId == null) return;
    setBusy('tally'); setMsg(null);
    try {
      const before = detail?.statement.tally?.added ?? 0;
      const r = (await cardApi.tally(cardId, statementId)).data;
      setDetail(r);
      const added = (r.statement.tally?.added ?? 0) - before;
      setMsg(added > 0 ? `${added} more charge${added > 1 ? 's' : ''} added to expenses.` : 'Everything on this statement is accounted for.');
    } catch { setMsg('Could not re-check this statement.'); }
    finally { setBusy(null); }
  };

  if (cards.length === 0) return null;
  const t = detail?.statement.tally;
  const maxCat = byCategory[0]?.[1] ?? 1;

  return (
    <div className="card space-y-3">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="flex items-center gap-2">
          <FileText size={14} className="text-brand" />
          <h3 className="font-semibold text-ink text-sm">Statements &amp; transactions</h3>
        </div>
        <div className="flex gap-2 flex-wrap">
          <select className="input-field text-xs py-1" value={cardId ?? ''} onChange={e => setCardId(Number(e.target.value))}>
            {cards.map(c => <option key={c.id} value={c.id}>{c.name}{c.lastFour ? ` ••${c.lastFour}` : ''}</option>)}
          </select>
          {statements.length > 0 && (
            <select className="input-field text-xs py-1" value={statementId ?? ''} onChange={e => setStatementId(Number(e.target.value))}>
              {statements.map(s => <option key={s.id} value={s.id}>
                {s.statementDate ? `Statement ${s.statementDate}` : s.dueDate ? `Due ${s.dueDate}` : `Statement #${s.id}`}
              </option>)}
            </select>
          )}
        </div>
      </div>

      {statements.length === 0 ? (
        <p className="text-xs text-gray-500 py-4 text-center">
          No statement read for this card yet. Statements are read from your email on each sync; a locked statement PDF opens once
          your PAN and date of birth are saved in Settings.
        </p>
      ) : loading && !detail ? (
        <div className="flex items-center gap-2 text-xs text-gray-500 py-4"><Loader2 size={12} className="animate-spin" /> Loading statement…</div>
      ) : detail && (
        <>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
            <Tile label="Total due" value={maskText(fmtINR(detail.statement.totalDue))}
              sub={detail.statement.dueDate ? `by ${detail.statement.dueDate}` : undefined} />
            <Tile label="Charges" value={maskText(fmtINR(t?.debitTotal))} sub={t ? `${t.lines - t.credits} line${t.lines - t.credits === 1 ? '' : 's'}` : undefined} />
            <Tile label="Payments & refunds" value={maskText(fmtINR(t?.creditTotal))} sub={t ? `${t.credits} line${t.credits === 1 ? '' : 's'}` : undefined} />
            <Tile label="Tally with expenses"
              value={t ? `${t.matched + t.added}/${t.lines - t.credits}` : '—'}
              sub={t ? `${t.added} added · ${t.missing} not in expenses` : 'lines not kept for this statement'}
              tone={t && t.missing > 0 ? 'warn' : 'ok'} />
          </div>

          {!t ? (
            <p className="text-2xs text-gray-500">
              This statement was read before transaction lines were kept, so there is nothing to list. Statements read from now on
              show every line here.
            </p>
          ) : (
            <>
              {(byCategory.length > 0 || topMerchants.length > 0) && (
                <div className="grid sm:grid-cols-2 gap-3">
                  <div className="space-y-1">
                    <div className="text-2xs text-gray-500">Where the money went</div>
                    {byCategory.map(([cat, amt]) => (
                      <div key={cat} className="flex items-center gap-2 text-2xs">
                        <span className="w-24 truncate text-gray-400">{cat}</span>
                        <div className="flex-1 h-1.5 bg-surface-hover rounded"><div className="h-1.5 bg-brand rounded" style={{ width: `${(amt / maxCat) * 100}%` }} /></div>
                        <span className="font-mono text-gray-300 w-20 text-right">{maskText(fmtINR(amt))}</span>
                      </div>
                    ))}
                  </div>
                  <div className="space-y-1">
                    <div className="text-2xs text-gray-500">Top merchants</div>
                    {topMerchants.map(([m, v]) => (
                      <div key={m} className="flex items-center justify-between text-2xs">
                        <span className="truncate text-gray-400">{m}{v.n > 1 && <span className="text-gray-600"> ×{v.n}</span>}</span>
                        <span className="font-mono text-gray-300">{maskText(fmtINR(v.amt))}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div className="flex items-center gap-2 flex-wrap">
                <div className="relative">
                  <Search size={11} className="absolute left-2 top-1/2 -translate-y-1/2 text-gray-600" />
                  <input className="input-field text-xs py-1 pl-6 w-44" placeholder="Search merchant" value={q} onChange={e => setQ(e.target.value)} />
                </div>
                {(['ALL', 'DEBITS', 'CREDITS', 'ADDED', 'MISSING'] as Filter[]).map(f => (
                  <button key={f} onClick={() => setFilter(f)}
                    className={`text-2xs px-2 py-1 rounded border ${filter === f ? 'border-brand text-brand' : 'border-surface-border text-gray-500'}`}>
                    {{ ALL: 'All', DEBITS: 'Charges', CREDITS: 'Payments', ADDED: 'Added from statement', MISSING: 'Not in expenses' }[f]}
                  </button>
                ))}
                <button onClick={retally} disabled={busy === 'tally'} className="btn-ghost text-2xs px-2 py-1 flex items-center gap-1 ml-auto">
                  {busy === 'tally' ? <Loader2 size={11} className="animate-spin" /> : <RefreshCw size={11} />} Re-check with expenses
                </button>
              </div>
              {msg && <div className="text-2xs text-gray-400">{msg}</div>}

              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="text-2xs text-gray-500 border-b border-surface-border">
                      <th className="text-left py-1.5 font-medium">Date</th>
                      <th className="text-left font-medium">Merchant</th>
                      <th className="text-right font-medium">Amount</th>
                      <th className="text-left font-medium pl-3">In your expenses</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map(l => {
                      const s = STATUS[l.status];
                      return (
                        <tr key={l.id} className="border-b border-surface-border/40 last:border-0" title={l.evidence ?? undefined}>
                          <td className="py-1.5 text-gray-400 whitespace-nowrap">{l.txnDate ?? '—'}</td>
                          <td className="text-ink">
                            {l.merchant ?? 'Unknown'}
                            {l.category && <span className="text-2xs text-gray-600"> · {l.category}</span>}
                            {l.currency && l.currency !== 'INR' && <span className="text-2xs text-neutral"> · {l.currency}</span>}
                          </td>
                          <td className={`text-right font-mono whitespace-nowrap ${l.debit ? 'text-gray-200' : 'text-bull'}`}>
                            {!l.debit && <ArrowDownLeft size={10} className="inline mr-0.5" />}{maskText(fmtINR2(l.amount))}
                          </td>
                          <td className="pl-3">
                            <span className={`${s.cls} text-2xs`} title={s.hint}>
                              {l.status === 'MATCHED' ? <CheckCircle2 size={10} className="inline mr-1" />
                                : l.status === 'ADDED' ? <PlusCircle size={10} className="inline mr-1" />
                                : l.status === 'MISSING' ? <AlertCircle size={10} className="inline mr-1" /> : null}
                              {s.label}
                            </span>
                            {l.status === 'MATCHED' && l.expenseDescription && (
                              <span className="text-2xs text-gray-600"> {l.expenseDescription}{l.expenseDate && l.expenseDate !== l.txnDate ? ` (${l.expenseDate})` : ''}</span>
                            )}
                          </td>
                          <td className="text-right whitespace-nowrap">
                            {l.status === 'ADDED' && (
                              <button className="text-2xs text-gray-500 hover:text-bear inline-flex items-center gap-1" disabled={busy === l.id}
                                onClick={() => act(l, 'remove')} title="Take this charge back out of your expenses">
                                <Undo2 size={10} /> Undo
                              </button>
                            )}
                            {(l.status === 'MISSING' || l.status === 'IGNORED') && (
                              <button className="text-2xs text-brand hover:underline" disabled={busy === l.id} onClick={() => act(l, 'add')}>
                                Add to expenses
                              </button>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                    {shown.length === 0 && (
                      <tr><td colSpan={5} className="text-center text-2xs text-gray-600 py-3">No lines match.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}

function Tile({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: 'ok' | 'warn' }) {
  return (
    <div className={`rounded-lg border p-2 ${tone === 'warn' ? 'border-neutral/40' : 'border-surface-border'}`}>
      <div className="text-2xs text-gray-500">{label}</div>
      <div className={`text-sm font-bold font-mono ${tone === 'warn' ? 'text-neutral' : 'text-ink'}`}>{value}</div>
      {sub && <div className="text-2xs text-gray-600">{sub}</div>}
    </div>
  );
}
