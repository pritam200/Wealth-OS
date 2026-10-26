import { useEffect, useState, useCallback, useMemo } from 'react';
import { Inbox, Check, X, Pencil, Quote, ChevronDown, ChevronUp } from 'lucide-react';
import { reviewApi } from '../api/review';
import type { EmailReviewItem, ReviewRule } from '../api/review';
import { useMaskedText } from './shared/Amount';
import { LoadFailure } from './shared/LoadFailure';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

// Types the user can correct a misclassification to. Transfers are here deliberately —
// they're the most common thing a bank debit alert gets mistaken for.
const TYPES = [
  'UPI_EXPENSE', 'CARD_EXPENSE', 'NETBANKING_EXPENSE', 'ATM_WITHDRAWAL', 'AUTO_DEBIT_EXPENSE',
  'EMI_PAYMENT', 'BILL_PAYMENT', 'OTHER_EXPENSE', 'CARD_BILL_GENERATED', 'CARD_BILL_PAID',
  'STOCK_BUY', 'STOCK_SELL', 'DIVIDEND',
  'MF_SIP', 'MF_LUMPSUM', 'MF_REDEMPTION', 'MF_SWITCH',
  'FD_OPEN', 'RD_OPEN', 'SALARY', 'INTEREST_CREDIT', 'REFUND', 'RENTAL_INCOME', 'OTHER_INCOME',
  'INTERNAL_TRANSFER', 'SELF_TRANSFER', 'STATEMENT_ONLY', 'PROMOTIONAL', 'UNKNOWN',
];

const NON_BOOKING = new Set([
  'MF_SWITCH', 'REFUND', 'INTERNAL_TRANSFER', 'SELF_TRANSFER', 'STATEMENT_ONLY', 'PROMOTIONAL',
  'OTP_OR_ALERT', 'UNKNOWN',
]);

function ReviewRow({ item, similar, onResolved }: {
  item: EmailReviewItem;
  /** Other pending items of the same kind — same sender, same kind of email, same question. */
  similar: number;
  onResolved: (alsoDecided: number) => void;
}) {
  const maskText = useMaskedText();
  const [editing, setEditing] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const [type, setType] = useState(item.proposedType ?? 'UNKNOWN');
  const [amount, setAmount] = useState(item.amount != null ? String(item.amount) : '');
  const [date, setDate] = useState(item.transactionDate ?? '');
  const [counterparty, setCounterparty] = useState(item.counterparty ?? '');
  const [note, setNote] = useState('');
  const [remember, setRemember] = useState(true);

  const decide = async (decision: 'ACCEPT' | 'EDIT' | 'REJECT') => {
    setBusy(true); setError('');
    try {
      const res = await reviewApi.decide(item.id, {
        decision,
        correctedType: decision === 'EDIT' ? type : undefined,
        correctedAmount: decision === 'EDIT' && amount ? Number(amount) : undefined,
        correctedDate: decision === 'EDIT' && date ? date : undefined,
        correctedCounterparty: decision === 'EDIT' ? counterparty || undefined : undefined,
        note: note || undefined,
        rememberForSimilar: remember,
      });
      onResolved(Number(res.headers['x-also-decided'] ?? 0));
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Could not save that decision.');
      setBusy(false);
    }
  };

  const conf = item.confidence != null ? item.confidence * 100 : null;
  const confPill = conf == null ? 'pill-muted' : conf >= 70 ? 'pill-neutral' : 'pill-bear';
  const willBook = !NON_BOOKING.has(type);

  return (
    <div className="rounded-xl border border-surface-border bg-surface-hover/40 p-3">
      <div className="flex items-start justify-between gap-3 mb-2 flex-wrap">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap mb-1">
            <span className="pill-info">{item.proposedType}</span>
            <span className={confPill}>
              {conf == null ? 'no confidence' : <><span className="font-mono tabular-nums">{conf.toFixed(0)}%</span> confident</>}
            </span>
            {item.amount != null && (
              <span className="text-sm font-mono tabular-nums text-ink">{maskText(fmt(item.amount))}</span>
            )}
            {similar > 0 && (
              <span className="pill-muted">+<span className="font-mono tabular-nums">{similar}</span> more like this</span>
            )}
          </div>
          <div className="text-2xs text-gray-400 truncate" title={item.subject}>{item.subject}</div>
          <div className="text-2xs text-gray-600 truncate">{item.sender}</div>
        </div>
        <button onClick={() => setExpanded(e => !e)} className="btn-ghost text-2xs shrink-0">
          {expanded ? <>Less <ChevronUp size={11} /></> : <>Why <ChevronDown size={11} /></>}
        </button>
      </div>

      <p className="text-2xs text-neutral mb-2">{item.reviewReason}</p>

      {expanded && (
        <div className="space-y-2 mb-2.5 border-t border-surface-border pt-2">
          {item.reasoning && (
            <p className="text-2xs text-gray-400">{item.reasoning}</p>
          )}
          {item.evidence && (
            <div className="flex items-start gap-1.5 text-2xs text-gray-300 bg-surface-card border border-surface-border rounded-lg px-2.5 py-2">
              <Quote size={11} className="shrink-0 mt-0.5 text-gray-600" />
              <span className="italic">{item.evidence}</span>
            </div>
          )}
          {item.extractedFields && (
            <pre className="text-2xs text-gray-500 font-mono bg-surface-card border border-surface-border rounded-lg p-2 overflow-x-auto">
              {item.extractedFields}
            </pre>
          )}
        </div>
      )}

      {editing && (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mb-2.5 border-t border-surface-border pt-2.5">
          <select value={type} onChange={e => setType(e.target.value)} className="input-field text-2xs py-1" style={{ minHeight: 30 }}>
            {TYPES.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
          <input value={amount} onChange={e => setAmount(e.target.value)} inputMode="numeric" placeholder="Amount"
                 className="input-field text-2xs py-1 font-mono tabular-nums" style={{ minHeight: 30 }} />
          <input value={date} onChange={e => setDate(e.target.value)} placeholder="yyyy-mm-dd"
                 className="input-field text-2xs py-1 font-mono" style={{ minHeight: 30 }} />
          <input value={counterparty} onChange={e => setCounterparty(e.target.value)} placeholder="Merchant / payer"
                 className="input-field text-2xs py-1" style={{ minHeight: 30 }} />
          {!willBook && (
            <p className="sm:col-span-2 text-2xs text-gray-500">
              {type.includes('TRANSFER')
                ? 'Saved as a transfer — recorded as a judgement only, with no income or spending row created, so net worth is unaffected.'
                : 'This type is recorded as a judgement only; no financial row is created.'}
            </p>
          )}
        </div>
      )}

      <div className="flex items-center gap-1.5 flex-wrap">
        <button onClick={() => editing ? decide('EDIT') : decide('ACCEPT')} disabled={busy}
                className="inline-flex items-center gap-1 text-2xs font-semibold px-2.5 py-1 rounded-lg bg-bull/10 text-bull border border-bull/25 hover:bg-bull/20 transition-colors disabled:opacity-50">
          <Check size={11} /> {editing ? 'Save & import' : 'Accept'}
        </button>
        <button onClick={() => setEditing(e => !e)} disabled={busy}
                className="inline-flex items-center gap-1 text-2xs font-semibold px-2.5 py-1 rounded-lg bg-surface-hover text-gray-300 border border-surface-border hover:text-ink transition-colors disabled:opacity-50">
          <Pencil size={11} /> {editing ? 'Cancel edit' : 'Edit'}
        </button>
        <button onClick={() => decide('REJECT')} disabled={busy}
                className="inline-flex items-center gap-1 text-2xs font-semibold px-2.5 py-1 rounded-lg bg-bear/10 text-bear border border-bear/25 hover:bg-bear/20 transition-colors disabled:opacity-50">
          <X size={11} /> Not a transaction
        </button>
        <input value={note} onChange={e => setNote(e.target.value)} placeholder="Note (optional)"
               className="input-field text-2xs py-1 flex-1 min-w-[120px]" style={{ minHeight: 28 }} />
      </div>
      {!editing && (
        <label className="flex items-center gap-1.5 text-2xs text-gray-400 mt-1.5 cursor-pointer select-none">
          <input type="checkbox" checked={remember} onChange={e => setRemember(e.target.checked)} />
          {similar > 0
            ? <>Do the same for the <span className="font-mono tabular-nums">{similar}</span> others like this, and future ones from this sender</>
            : 'Do the same for future ones like this from this sender'}
        </label>
      )}
      {error && <p className="text-2xs text-bear mt-1.5">{error}</p>}
    </div>
  );
}

/** Accepts and rejects the queue applies by itself; forgetting one stops it applying. */
function RememberedDecisions({ rules, onForget }: { rules: ReviewRule[]; onForget: (id: number) => void }) {
  const [open, setOpen] = useState(false);
  if (rules.length === 0) return null;
  return (
    <div className="mt-3 border-t border-surface-border pt-2.5">
      <button onClick={() => setOpen(o => !o)} className="btn-ghost text-2xs">
        Remembered decisions (<span className="font-mono tabular-nums">{rules.length}</span>)
        {open ? <ChevronUp size={11} /> : <ChevronDown size={11} />}
      </button>
      {open && (
        <ul className="mt-2 space-y-1.5">
          {rules.map(r => (
            <li key={r.id} className="flex items-start justify-between gap-2 text-2xs">
              <div className="min-w-0">
                <span className={r.decision === 'REJECT' ? 'text-bear' : 'text-bull'}>
                  {r.decision === 'REJECT' ? 'Not a transaction' : 'Accept'}
                </span>
                <span className="text-gray-400"> · {r.sender}</span>
                <div className="text-gray-600 truncate" title={`${r.subject ?? ''} — ${r.reviewReason ?? ''}`}>
                  {r.subject} — {r.reviewReason}
                </div>
              </div>
              <button onClick={() => onForget(r.id)} className="btn-ghost text-2xs shrink-0">Forget</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * Human review for emails the classifier wasn't confident enough to import.
 *
 * These have not been written anywhere yet — accepting one imports it through the normal
 * fingerprint-gated path, so it can't double-book something already captured another way.
 */
export function ReviewQueue() {
  const [items, setItems] = useState<EmailReviewItem[]>([]);
  const [kinds, setKinds] = useState<Record<string, string>>({});
  const [rules, setRules] = useState<ReviewRule[]>([]);
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);
  // Emptying the list on error told the user "Nothing waiting" — in the one place whose whole
  // promise is that uncertain transactions land here instead of being dropped silently.
  const [failed, setFailed] = useState(false);

  const load = useCallback(() => {
    setLoading(true); setFailed(false);
    Promise.all([reviewApi.list(), reviewApi.kinds().catch(() => null), reviewApi.rules().catch(() => null)])
      .then(([list, k, r]) => {
        setItems(list.data);
        setKinds(k?.data ?? {});
        setRules(r?.data ?? []);
      })
      .catch(() => setFailed(true))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  // One row per kind of question: the same question from the same sender is answered once.
  const groups = useMemo(() => {
    const byKind = new Map<string, EmailReviewItem[]>();
    for (const i of items) {
      const k = kinds[String(i.id)] ?? `item-${i.id}`;
      const g = byKind.get(k);
      if (g) g.push(i); else byKind.set(k, [i]);
    }
    return [...byKind.values()];
  }, [items, kinds]);

  const resolved = (also: number) => {
    setNotice(also > 0 ? `Also decided ${also} more like it.` : '');
    load();
  };

  const forget = (id: number) => reviewApi.forgetRule(id).then(load).catch(() => setNotice('Could not forget that decision.'));

  if (loading) return <div className="card animate-pulse h-32 bg-surface-hover" />;

  return (
    <div className="card">
      <div className="flex items-start justify-between mb-3 flex-wrap gap-2">
        <h3 className="section-title mb-0"><Inbox size={15} className="text-neutral" /> Needs Review</h3>
        {items.length > 0 && (
          <span className="pill-neutral">
            <span className="font-mono tabular-nums">{items.length}</span> pending
            {groups.length < items.length && <> · <span className="font-mono tabular-nums">{groups.length}</span> kinds</>}
          </span>
        )}
      </div>

      {notice && <p className="text-2xs text-bull mb-2">{notice}</p>}

      {failed ? (
        <LoadFailure what="the review queue" onRetry={load} />
      ) : items.length === 0 ? (
        <p className="text-xs text-gray-600 py-3">
          Nothing waiting. Emails whose financial content can't be read confidently land here
          instead of being dropped silently.
        </p>
      ) : (
        <>
          <p className="text-2xs text-gray-600 mb-3">
            Nothing here has been saved yet. Accepting an item imports it; the duplicate check
            still applies, so it can't double-book something already captured elsewhere. Your
            answer is applied to the others like it, so each kind of question is asked once.
          </p>
          <div className="space-y-2.5">
            {groups.map(g => <ReviewRow key={g[0].id} item={g[0]} similar={g.length - 1} onResolved={resolved} />)}
          </div>
        </>
      )}
      <RememberedDecisions rules={rules} onForget={forget} />
    </div>
  );
}
