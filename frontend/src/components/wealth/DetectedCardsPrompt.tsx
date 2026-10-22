import { useEffect, useState, useCallback } from 'react';
import { CreditCard as CardIcon, X, Link2, Plus, Ban, Loader2, Mail } from 'lucide-react';
import { cardApi } from '../../api/card';
import type { CardResponse, DetectedCard } from '../../api/card';

// Remembers, for this browser session only, which detected cards the pop-up was already shown
// for, so it opens once rather than on every visit. The banner stays until the card is handled.
const SEEN_KEY = 'detectedCardsPopupSeen';
function seenIds(): number[] {
  try { return JSON.parse(sessionStorage.getItem(SEEN_KEY) ?? '[]'); } catch { return []; }
}
function markSeen(ids: number[]) {
  try { sessionStorage.setItem(SEEN_KEY, JSON.stringify(Array.from(new Set([...seenIds(), ...ids])))); } catch { /* storage unavailable */ }
}

const label = (d: DetectedCard) => `${d.issuer ?? 'Unknown bank'}${d.lastFour ? ` ••${d.lastFour}` : ''}`;

/**
 * Cards found in email that are not saved yet. Opens a pop-up the first time one is seen this
 * session and keeps a banner above the cards until each is added, linked to a saved card, or
 * dismissed. Statements already read for the card are filed onto it as soon as it is added or linked.
 */
export function DetectedCardsPrompt({ cards, onChanged }: { cards: CardResponse[]; onChanged: () => void }) {
  const [detected, setDetected] = useState<DetectedCard[]>([]);
  const [open, setOpen] = useState(false);

  const load = useCallback(() => {
    cardApi.detected().then(r => {
      setDetected(r.data);
      const fresh = r.data.filter(d => !seenIds().includes(d.id));
      if (fresh.length > 0) { setOpen(true); markSeen(fresh.map(d => d.id)); }
    }).catch(() => setDetected([]));
  }, []);
  useEffect(() => { load(); }, [load]);

  const done = () => { load(); onChanged(); };

  if (detected.length === 0) return null;

  return (
    <>
      <div className="card border-brand/40 bg-brand/[0.04] flex items-center gap-3 flex-wrap">
        <span className="icon-badge-brand w-8 h-8"><CardIcon size={15} /></span>
        <div className="flex-1 min-w-0">
          <div className="text-sm font-semibold text-ink">
            {detected.length === 1 ? 'New card detected in your email' : `${detected.length} new cards detected in your email`}
          </div>
          <div className="text-2xs text-gray-500">
            {detected.map(label).join(' · ')} — add or link {detected.length === 1 ? 'it' : 'them'} so statements and charges are tracked.
          </div>
        </div>
        <button className="btn-primary text-xs px-3 py-1.5" onClick={() => setOpen(true)}>Review</button>
      </div>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={() => setOpen(false)}>
          <div className="bg-surface rounded-xl border border-surface-border shadow-2xl w-full max-w-lg mx-4 max-h-[85vh] overflow-y-auto"
               onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between p-4 border-b border-surface-border">
              <div className="flex items-center gap-2">
                <CardIcon size={15} className="text-brand" />
                <h3 className="text-ink font-bold text-sm">New card{detected.length > 1 ? 's' : ''} detected</h3>
              </div>
              <button aria-label="Close" onClick={() => setOpen(false)} className="btn-icon text-gray-500 hover:text-ink"><X size={16} /></button>
            </div>
            <div className="p-4 space-y-3">
              <p className="text-2xs text-gray-500">
                These cards appear in statements or payment emails but are not in My Cards. Add each as a new card, link it to a
                card you already saved, or tell us it is not yours.
              </p>
              {detected.map(d => <DetectedRow key={d.id} d={d} cards={cards} onDone={done} />)}
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function DetectedRow({ d, cards, onDone }: { d: DetectedCard; cards: CardResponse[]; onDone: () => void }) {
  const likely = cards.filter(c => d.likelyCardIds.includes(c.id));
  const [mode, setMode] = useState<'add' | 'link'>(likely.length > 0 ? 'link' : 'add');
  const [name, setName] = useState(d.suggestedName ?? '');
  const [linkTo, setLinkTo] = useState<number | ''>(likely[0]?.id ?? cards[0]?.id ?? '');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true); setMsg(null);
    try { await fn(); setMsg(ok); onDone(); }
    catch (e: any) { setMsg(e?.response?.data?.message ?? 'That did not work — try again.'); }
    finally { setBusy(false); }
  };

  return (
    <div className="rounded-lg border border-surface-border p-3 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <div>
          <div className="text-sm font-semibold text-ink">{label(d)}</div>
          <div className="text-2xs text-gray-500 flex items-center gap-1">
            <Mail size={10} /> Seen in {d.seenCount} email{d.seenCount > 1 ? 's' : ''}
            {d.pendingStatements > 0 && <> · <b className="text-brand-light">{d.pendingStatements} statement{d.pendingStatements > 1 ? 's' : ''}</b> waiting to be filed</>}
          </div>
        </div>
        {likely.length > 0 && <span className="pill-neutral text-2xs">Looks like {likely[0].name}</span>}
      </div>

      <div className="flex gap-1 text-2xs">
        <button className={`px-2 py-1 rounded border ${mode === 'add' ? 'border-brand text-brand' : 'border-surface-border text-gray-500'}`}
          onClick={() => setMode('add')}><Plus size={10} className="inline mr-1" />Add as new card</button>
        <button disabled={cards.length === 0}
          className={`px-2 py-1 rounded border ${mode === 'link' ? 'border-brand text-brand' : 'border-surface-border text-gray-500'} disabled:opacity-40`}
          onClick={() => setMode('link')}><Link2 size={10} className="inline mr-1" />Link to a saved card</button>
      </div>

      {mode === 'add' ? (
        <div className="flex gap-2 items-center">
          <input className="input-field text-xs py-1 flex-1" value={name} onChange={e => setName(e.target.value)}
            placeholder="Card name, e.g. HDFC MoneyBack+" />
          <button className="btn-primary text-xs px-3 py-1" disabled={busy}
            onClick={() => run(() => cardApi.addDetected(d.id, name.trim() || undefined), 'Added to My Cards.')}>
            {busy ? <Loader2 size={12} className="animate-spin" /> : 'Add'}
          </button>
        </div>
      ) : (
        <div className="flex gap-2 items-center">
          <select className="input-field text-xs py-1 flex-1" value={linkTo} onChange={e => setLinkTo(Number(e.target.value))}>
            {cards.map(c => <option key={c.id} value={c.id}>{c.name}{c.lastFour ? ` ••${c.lastFour}` : ''}</option>)}
          </select>
          <button className="btn-primary text-xs px-3 py-1" disabled={busy || linkTo === ''}
            onClick={() => run(() => cardApi.linkDetected(d.id, Number(linkTo)), 'Linked.')}>
            {busy ? <Loader2 size={12} className="animate-spin" /> : 'Link'}
          </button>
        </div>
      )}

      <div className="flex items-center justify-between">
        <button className="text-2xs text-gray-500 hover:text-bear flex items-center gap-1" disabled={busy}
          onClick={() => run(() => cardApi.dismissDetected(d.id), 'Dismissed.')}>
          <Ban size={10} /> Not my card
        </button>
        {msg && <span className="text-2xs text-gray-400">{msg}</span>}
      </div>
    </div>
  );
}
