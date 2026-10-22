import { useEffect, useState } from 'react';
import { AlertTriangle, ShieldCheck } from 'lucide-react';
import { cardApi } from '../../api/card';
import type { ChargesReport } from '../../api/card';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number | null | undefined) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

const STYLE: Record<string, string> = {
  ALERT: 'bg-bear/10 border-bear/30 text-bear',
  WARN:  'bg-neutral/10 border-neutral/30 text-neutral',
  INFO:  'bg-surface-hover border-surface-border text-gray-400',
};

/**
 * Every bank charge (late fee, interest, annual fee, GST on charges...) and unusual item (possible
 * double charge, very large purchase, foreign-currency or cash use, over-limit) found on any card
 * statement read from email. Statements are checked as they are read; this gathers the results.
 */
export function CardChargesPanel({ refreshKey }: { refreshKey?: number }) {
  const maskText = useMaskedText();
  const [r, setR] = useState<ChargesReport | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    cardApi.charges().then(x => { setR(x.data); setFailed(false); }).catch(() => setFailed(true));
  }, [refreshKey]);

  return (
    <div className="card border border-bear/20">
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <AlertTriangle size={14} className="text-bear" />
          <h3 className="font-semibold text-ink text-sm">Unusual activity &amp; bank charges</h3>
        </div>
        {r && r.statementsChecked > 0 && (
          <span className="text-2xs text-gray-500">{r.statementsChecked} statement{r.statementsChecked === 1 ? '' : 's'} checked</span>
        )}
      </div>

      {failed && <p className="text-2xs text-gray-500">Could not load the statement checks right now.</p>}
      {!r && !failed && <p className="text-2xs text-gray-600">Loading…</p>}

      {r && r.statementsChecked === 0 && (
        <p className="text-xs text-gray-500">
          No card statements have been read yet. Sync your email: every card statement or bill is checked for bank charges and unusual activity as it is read.
        </p>
      )}

      {r && r.statementsChecked > 0 && r.statements.length === 0 && (
        <p className="text-xs text-bull flex items-center gap-1.5">
          <ShieldCheck size={13} /> No bank charges or unusual activity on any statement read so far.
        </p>
      )}

      {r && r.statements.length > 0 && (
        <>
          <div className="grid grid-cols-2 gap-2 mb-3">
            <div className="card-flat">
              <div className="stat-label mb-0.5">Bank charges paid</div>
              <div className="text-lg font-mono font-bold text-bear">{maskText(fmtINR(r.bankChargesTotal))}</div>
            </div>
            <div className="card-flat">
              <div className="stat-label mb-0.5">Unusual items</div>
              <div className="text-lg font-mono font-bold text-neutral">{r.unusualCount}</div>
            </div>
          </div>
          <div className="space-y-2">
            {r.statements.map(s => (
              <div key={s.statementId} className="bg-surface-hover rounded-lg p-2.5">
                <div className="flex items-center justify-between mb-1.5 text-2xs">
                  <span className="text-ink text-xs font-medium">{s.cardName}{s.cardLastFour ? ` ••${s.cardLastFour}` : ''}</span>
                  <span className="text-gray-500">Statement {s.statementDate ?? (s.dueDate ? `due ${s.dueDate}` : '')}</span>
                </div>
                <ul className="space-y-1">
                  {s.flags.map((f, i) => (
                    <li key={i} className={`text-2xs border rounded px-1.5 py-1 flex justify-between gap-2 ${STYLE[f.severity] ?? STYLE.INFO}`}>
                      <span><span className="font-medium">{f.title}</span>{f.detail ? <span className="opacity-80"> — {f.detail}</span> : null}</span>
                      {f.amount != null && <span className="font-mono shrink-0">{maskText(fmtINR(f.amount))}</span>}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
          <p className="text-2xs text-gray-600 mt-2">
            Late fees and interest can often be reversed by asking the bank, especially the first time. A possible double charge is worth checking with the merchant or bank.
          </p>
        </>
      )}
    </div>
  );
}
