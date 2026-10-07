import { useCallback, useEffect, useState } from 'react';
import {
  AlertCircle, CheckCircle2, ChevronDown, ChevronUp, Clock, ListChecks, Loader2, Mail, Plus, Trash2, Upload,
} from 'lucide-react';
import { inboundApi, onboardingApi } from '../api/onboarding';
import type { InboundItem, InboundView } from '../api/onboarding';
import type { Checklist, CasSummary, Guide, SourceKind, SourceStatus, SourceView } from '../api/onboarding';

const today = () => new Date().toISOString().slice(0, 10);

const fmtDate = (iso: string) =>
  new Date(iso + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });

const errText = (e: any, fallback: string): string => {
  const d = e?.response?.data;
  return (typeof d === 'string' ? d : d?.message) || fallback;
};

const STATUS_STYLE: Record<SourceStatus, { label: string; cls: string }> = {
  NOT_STARTED: { label: 'Not started', cls: 'pill-neutral' },
  CURRENT: { label: 'Up to date', cls: 'pill-bull' },
  STALE: { label: 'Needs update', cls: 'pill-bear' },
};

/** "Data through 6 Oct 2026" — the label every source carries, so the user always knows how far it goes. */
function ThroughLabel({ s }: { s: SourceView }) {
  if (!s.syncedThrough) return <span className="text-2xs text-gray-500">No data imported yet</span>;
  return (
    <span className="text-2xs text-gray-400">
      Data through <span className="font-semibold text-ink">{fmtDate(s.syncedThrough)}</span>
      {s.status === 'STALE' && s.daysBehind != null && <span className="text-bear"> · {s.daysBehind} days behind</span>}
      {s.status === 'CURRENT' && s.nextUpdateDue && <span> · next update by {fmtDate(s.nextUpdateDue)}</span>}
    </span>
  );
}

function SourceRow({ s, onChanged, onRemoved }: { s: SourceView; onChanged: (s: SourceView) => void; onRemoved: () => void }) {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [complete, setComplete] = useState(false);
  const [date, setDate] = useState(today());
  const [marking, setMarking] = useState(false);
  const st = STATUS_STYLE[s.status];
  const [password, setPassword] = useState('');
  const [cas, setCas] = useState<CasSummary | null>(null);

  const uploadCas = async (file: File | undefined) => {
    if (!file) return;
    setBusy(true);
    setMsg(null);
    setCas(null);
    try {
      const { data } = await onboardingApi.importCas(s.id, file, password);
      setPassword('');
      onChanged(data.source);
      setCas(data.summary);
    } catch (e) {
      setMsg({ ok: false, text: errText(e, 'Could not read that statement.') });
    } finally {
      setBusy(false);
    }
  };

  const upload = async (file: File | undefined) => {
    if (!file) return;
    setBusy(true);
    setMsg(null);
    try {
      const { data } = await onboardingApi.importFile(s.id, file, complete);
      onChanged(data.source);
      const r = data.summary;
      setMsg({ ok: r.rejected === 0, text: `${r.created} new, ${r.duplicated} already present${r.rejected ? `, ${r.rejected} rejected${r.errors[0] ? ` (${r.errors[0]})` : ''}` : ''}.` });
    } catch (e) {
      setMsg({ ok: false, text: errText(e, 'Could not import that file.') });
    } finally {
      setBusy(false);
    }
  };

  const mark = async () => {
    setBusy(true);
    setMsg(null);
    try {
      onChanged((await onboardingApi.setSyncedThrough(s.id, date)).data);
      setMarking(false);
    } catch (e) {
      setMsg({ ok: false, text: errText(e, 'Could not save that date.') });
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!window.confirm(`Remove ${s.name} from the checklist? Transactions already imported are kept.`)) return;
    try { await onboardingApi.removeSource(s.id); onRemoved(); } catch (e) { setMsg({ ok: false, text: errText(e, 'Could not remove it.') }); }
  };

  return (
    <div className="rounded-xl border border-surface-border p-3 space-y-2">
      <div className="flex items-start justify-between gap-2 flex-wrap">
        <div>
          <div className="text-sm font-bold text-ink flex items-center gap-2">{s.name} <span className={st.cls}>{st.label}</span></div>
          <ThroughLabel s={s} />
          {s.lastImportNote && <div className="text-2xs text-gray-600">Last import: {s.lastImportNote}</div>}
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          {(s.kind === 'MUTUAL_FUNDS' || s.kind === 'STOCKS') && (
            <label className={`btn-primary text-xs px-3 py-1.5 flex items-center gap-1.5 cursor-pointer ${busy ? 'opacity-60 pointer-events-none' : ''}`}>
              {busy ? <Loader2 size={12} className="animate-spin" /> : <Upload size={12} />} Upload CAS (PDF)
              <input type="file" accept=".pdf" className="hidden"
                onChange={e => { uploadCas(e.target.files?.[0]); e.target.value = ''; }} />
            </label>
          )}
          {s.fileImportable && (
            <label className={`btn-primary text-xs px-3 py-1.5 flex items-center gap-1.5 cursor-pointer ${busy ? 'opacity-60 pointer-events-none' : ''}`}>
              {busy ? <Loader2 size={12} className="animate-spin" /> : <Upload size={12} />} Upload file
              <input type="file" accept=".csv,.txt,.xlsx,.xls" className="hidden"
                onChange={e => { upload(e.target.files?.[0]); e.target.value = ''; }} />
            </label>
          )}
          <button className="btn-secondary text-xs px-3 py-1.5" onClick={() => setMarking(v => !v)}>I entered it by hand</button>
          <button className="btn-icon p-1.5" onClick={remove} title="Remove from checklist"><Trash2 size={13} /></button>
        </div>
      </div>

      {(s.kind === 'MUTUAL_FUNDS' || s.kind === 'STOCKS') && (
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-2xs text-gray-500">CAS password</span>
          <input type="password" autoComplete="off" value={password} onChange={e => setPassword(e.target.value)}
            placeholder="The one you set when requesting it" className="input-field text-xs py-1 w-64" />
          <span className="text-2xs text-gray-600">Enter it, then choose the PDF. Used once to open the file; never stored.</span>
        </div>
      )}

      {cas && (
        <div className="rounded-lg bg-surface-hover/60 p-3 text-xs space-y-1.5">
          <div className="text-bull flex items-center gap-1.5">
            <CheckCircle2 size={12} /> Read {cas.schemes} {s.kind === 'STOCKS' ? 'holdings' : 'schemes'} ({cas.rows} {s.kind === 'STOCKS' ? 'records' : 'transactions'}): {cas.created} new, {cas.duplicated} already present
            {cas.rejected > 0 && <span className="text-bear">, {cas.rejected} rejected</span>}
            {cas.periodTo && <> · statement through {fmtDate(cas.periodTo)}</>}
          </div>
          <div className="max-h-40 overflow-y-auto space-y-0.5">
            {cas.holdings.map(h => (
              <div key={`${h.folio}-${h.isin}`} className="flex items-center justify-between gap-2 text-2xs text-gray-400">
                <span className="truncate">{h.scheme} <span className="text-gray-600">· folio {h.folio}</span></span>
                <span className="font-mono shrink-0">
                  {h.closingUnits ?? '—'} units {h.unitsReconcile ? <span className="text-bull">✓</span> : <span className="text-bear">⚠</span>}
                </span>
              </div>
            ))}
          </div>
          {cas.warnings.map(w => (
            <div key={w} className="text-2xs text-bear flex items-start gap-1.5"><AlertCircle size={12} className="mt-0.5 shrink-0" /> {w}</div>
          ))}
        </div>
      )}

      {s.fileImportable && (
        <label className="text-2xs text-gray-500 flex items-center gap-1.5">
          <input type="checkbox" checked={complete} onChange={e => setComplete(e.target.checked)} />
          This file is the complete statement for its date range (flags anything missing inside it)
        </label>
      )}

      {marking && (
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-2xs text-gray-500">My entries are up to date through</span>
          <input type="date" max={today()} value={date} onChange={e => setDate(e.target.value)} className="input-field text-xs py-1 w-40" />
          <button className="btn-primary text-2xs px-2.5 py-1" onClick={mark} disabled={busy || !date}>Save</button>
        </div>
      )}
      {msg && (
        <div className={`text-2xs flex items-start gap-1.5 ${msg.ok ? 'text-bull' : 'text-bear'}`}>
          {msg.ok ? <CheckCircle2 size={12} className="mt-0.5 shrink-0" /> : <AlertCircle size={12} className="mt-0.5 shrink-0" />} {msg.text}
        </div>
      )}
    </div>
  );
}

function KindSection({ guide, sources, onAdded, onChanged, onRemoved }: {
  guide: Guide; sources: SourceView[];
  onAdded: (s: SourceView) => void; onChanged: (s: SourceView) => void; onRemoved: () => void;
}) {
  const [showGuide, setShowGuide] = useState(sources.length === 0);
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const add = async () => {
    setSaving(true);
    setError(null);
    try {
      onAdded((await onboardingApi.addSource(guide.kind as SourceKind, name)).data);
      setName('');
      setAdding(false);
    } catch (e) {
      setError(errText(e, 'Could not add that.'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="card space-y-3">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h3 className="text-sm font-bold text-ink">{guide.title}</h3>
          <p className="text-2xs text-gray-500">{guide.best}</p>
        </div>
        <button className="btn-ghost text-2xs px-2 py-1 flex items-center gap-1" onClick={() => setShowGuide(v => !v)}>
          How to get it {showGuide ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
        </button>
      </div>

      {showGuide && (
        <div className="rounded-lg bg-surface-hover/60 p-3 text-xs text-gray-400 space-y-2">
          <ol className="list-decimal ml-4 space-y-1">{guide.steps.map(t => <li key={t}>{t}</li>)}</ol>
          <p className="flex items-start gap-1.5"><Clock size={12} className="mt-0.5 shrink-0" /> {guide.ongoing}</p>
        </div>
      )}

      {sources.map(s => <SourceRow key={s.id} s={s} onChanged={onChanged} onRemoved={onRemoved} />)}

      {adding ? (
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <input autoFocus className="input-field text-xs w-64" value={name} onChange={e => setName(e.target.value)}
              placeholder={guide.kind === 'CREDIT_CARDS' ? 'Card name, e.g. HDFC Regalia' : guide.kind === 'STOCKS' ? 'Broker, e.g. Zerodha' : 'Bank or platform, e.g. HDFC Bank'}
              onKeyDown={e => { if (e.key === 'Enter' && name.trim()) add(); }} />
            <button className="btn-primary text-xs px-3 py-1.5" onClick={add} disabled={saving || !name.trim()}>Add</button>
            <button className="btn-ghost text-xs px-2 py-1.5" onClick={() => { setAdding(false); setError(null); }}>Cancel</button>
          </div>
          {error && <div className="text-2xs text-bear">{error}</div>}
        </div>
      ) : (
        <button className="btn-secondary text-xs px-3 py-1.5 flex items-center gap-1.5" onClick={() => setAdding(true)}>
          <Plus size={12} /> Add {guide.kind === 'STOCKS' ? 'a broker' : guide.kind === 'CREDIT_CARDS' ? 'a card' : 'a bank or platform'}
        </button>
      )}
    </div>
  );
}

const INBOUND_STATUS: Record<InboundItem['status'], { label: string; cls: string }> = {
  IMPORTED: { label: 'Imported', cls: 'pill-bull' },
  NEEDS_PASSWORD: { label: 'Needs password', cls: 'pill-neutral' },
  FAILED: { label: 'Failed', cls: 'pill-bear' },
  IGNORED: { label: 'Skipped', cls: 'pill-neutral' },
};

/** Forward a statement email to a private address and it imports itself — no Gmail needed. */
function ForwardingInbox({ onImported }: { onImported: () => void }) {
  const [data, setData] = useState<InboundView | null>(null);
  const [copied, setCopied] = useState(false);
  const [pw, setPw] = useState('');
  const [itemPw, setItemPw] = useState<Record<number, string>>({});
  const [msg, setMsg] = useState<string | null>(null);

  const load = useCallback(async () => {
    try { setData((await inboundApi.view()).data); } catch { setData(null); }
  }, []);
  useEffect(() => { load(); }, [load]);

  if (!data) return null;
  if (!data.enabled) {
    return (
      <div className="card text-xs text-gray-500">
        <h3 className="text-sm font-bold text-ink mb-1 flex items-center gap-2"><Mail size={14} className="text-brand-light" /> Forward statements by email</h3>
        Not set up on this server yet. An administrator needs to configure an inbound-mail domain (INBOUND_MAIL_DOMAIN and INBOUND_WEBHOOK_SECRET).
      </div>
    );
  }

  const copy = async () => {
    if (!data.address) return;
    try { await navigator.clipboard.writeText(data.address); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch { /* clipboard blocked */ }
  };
  const act = async (fn: () => Promise<unknown>, ok?: string) => {
    setMsg(null);
    try { await fn(); if (ok) setMsg(ok); await load(); onImported(); }
    catch (e) { setMsg(errText(e, 'That did not work.')); }
  };

  return (
    <div className="card space-y-3">
      <div>
        <h3 className="text-sm font-bold text-ink flex items-center gap-2"><Mail size={14} className="text-brand-light" /> Forward statements by email</h3>
        <p className="text-2xs text-gray-500">
          Forward your CAS email (mutual funds from CAMS/KFintech, demat from NSDL/CDSL) to your private address and it is imported automatically.
          Even better: when you request a CAS, enter this address as a recipient too.
        </p>
      </div>

      <div className="flex items-center gap-2 flex-wrap">
        <code className="text-xs bg-surface-hover px-2.5 py-1.5 rounded-lg break-all">{data.address}</code>
        <button className="btn-secondary text-xs px-3 py-1.5" onClick={copy}>{copied ? 'Copied' : 'Copy'}</button>
        <button className="btn-ghost text-2xs px-2 py-1.5"
          onClick={() => { if (window.confirm('Make a new address? The old one stops working.')) act(() => inboundApi.rotate(), 'New address created. The old one no longer works.'); }}>
          New address
        </button>
      </div>

      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-2xs text-gray-500">CAS password</span>
        {data.hasCasPassword ? (
          <>
            <span className="text-2xs text-bull">Saved (encrypted) — locked PDFs open automatically</span>
            <button className="btn-ghost text-2xs px-2 py-1" onClick={() => act(() => inboundApi.clearPassword(), 'Saved password removed.')}>Remove</button>
          </>
        ) : (
          <>
            <input type="password" autoComplete="off" value={pw} onChange={e => setPw(e.target.value)} className="input-field text-xs py-1 w-56"
              placeholder="So locked PDFs open on their own" />
            <button className="btn-secondary text-2xs px-2.5 py-1" disabled={!pw}
              onClick={() => act(async () => { await inboundApi.savePassword(pw); setPw(''); }, 'Password saved.')}>Save</button>
          </>
        )}
      </div>

      {msg && <div className="text-2xs text-gray-400">{msg}</div>}

      {data.items.length > 0 && (
        <div className="space-y-1.5">
          {data.items.map(i => {
            const st = INBOUND_STATUS[i.status];
            return (
              <div key={i.id} className="rounded-lg border border-surface-border p-2.5 text-xs space-y-1.5">
                <div className="flex items-center justify-between gap-2 flex-wrap">
                  <div className="min-w-0">
                    <span className={st.cls}>{st.label}</span>{' '}
                    <span className="font-semibold text-ink">{i.filename ?? 'attachment'}</span>
                    <span className="text-2xs text-gray-600"> · {new Date(i.receivedAt).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}{i.from ? ` · ${i.from}` : ''}</span>
                  </div>
                  <button className="btn-icon p-1" title="Remove" onClick={() => act(() => inboundApi.dismiss(i.id))}><Trash2 size={12} /></button>
                </div>
                {i.note && <div className="text-2xs text-gray-500">{i.note}</div>}
                {i.status === 'NEEDS_PASSWORD' && (
                  <div className="flex items-center gap-2 flex-wrap">
                    <input type="password" autoComplete="off" className="input-field text-xs py-1 w-56" placeholder="PDF password"
                      value={itemPw[i.id] ?? ''} onChange={e => setItemPw({ ...itemPw, [i.id]: e.target.value })} />
                    <button className="btn-primary text-2xs px-2.5 py-1" disabled={!itemPw[i.id]}
                      onClick={() => act(async () => { await inboundApi.unlock(i.id, itemPw[i.id], false); setItemPw({ ...itemPw, [i.id]: '' }); }, 'Imported.')}>
                      Unlock &amp; import
                    </button>
                    <button className="btn-secondary text-2xs px-2.5 py-1" disabled={!itemPw[i.id]}
                      onClick={() => act(async () => { await inboundApi.unlock(i.id, itemPw[i.id], true); setItemPw({ ...itemPw, [i.id]: '' }); }, 'Imported, and the password is saved for next time.')}>
                      Unlock &amp; remember password
                    </button>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** First-time setup and ongoing freshness: every place the user keeps money, how to get its data, and how far it has been imported. */
export function SetupChecklistPage() {
  const [data, setData] = useState<Checklist | null>(null);
  const [error, setError] = useState(false);

  const load = useCallback(async () => {
    setError(false);
    try { setData((await onboardingApi.checklist()).data); } catch { setError(true); }
  }, []);
  useEffect(() => { load(); }, [load]);

  if (error) {
    return (
      <div className="card text-xs text-bear flex items-center gap-2">
        <AlertCircle size={14} /> Could not load the setup checklist.
        <button className="btn-secondary text-2xs px-2 py-1" onClick={load}>Retry</button>
      </div>
    );
  }
  if (!data) return <div className="card animate-pulse h-40 bg-surface-hover" />;

  const stale = data.sources.filter(s => s.status === 'STALE').length;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <ListChecks size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-ink mb-0.5">Setup Checklist</h2>
          <p className="text-gray-500 text-xs">Bring in every place you keep money. Each source shows how far its data goes, and updates continue from that date.</p>
        </div>
      </div>

      <div className="card flex flex-wrap items-center gap-x-6 gap-y-2 text-xs">
        <span><span className="font-bold text-ink">{data.current}</span> of {data.total} sources up to date</span>
        {stale > 0 && <span className="text-bear flex items-center gap-1"><AlertCircle size={12} /> {stale} need an update</span>}
        <span className="flex items-center gap-1.5 text-gray-400">
          <Mail size={12} />
          {data.gmail.connected
            ? <>Gmail connected{data.gmail.lastSyncAt ? <> · emails read through <span className="font-semibold text-ink">{new Date(data.gmail.lastSyncAt).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}</span></> : ''} (picks up new statements automatically)</>
            : 'Gmail not connected — optional, picks up new statements automatically (Email & Statement Sync)'}
        </span>
      </div>

      <ForwardingInbox onImported={load} />

      {data.guides.map(g => (
        <KindSection key={g.kind} guide={g}
          sources={data.sources.filter(s => s.kind === g.kind)}
          onAdded={() => load()} onChanged={() => load()} onRemoved={() => load()} />
      ))}
    </div>
  );
}
