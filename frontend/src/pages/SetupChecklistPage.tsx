import { useCallback, useEffect, useState } from 'react';
import {
  AlertCircle, CheckCircle2, ChevronDown, ChevronUp, Clock, FileSpreadsheet, Layers, Loader2, Mail, Plus, Sparkles, Trash2, TrendingUp, Upload,
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

/**
 * "Start here": one box. Drop any statement PDF (mutual funds or demat); the app works out which it is,
 * opens it with the PAN / date of birth already on file, and files it under the right section.
 */
function StartHere({ data, onDone, onShowAll, showAll }: { data: Checklist; onDone: () => void; onShowAll: () => void; showAll: boolean }) {
  const [password, setPassword] = useState('');
  const [needPw, setNeedPw] = useState(false);
  const [busy, setBusy] = useState(false);
  const [over, setOver] = useState(false);
  const [results, setResults] = useState<{ ok: boolean; text: string }[]>([]);
  const done = data.sources.filter(s => (s.kind === 'MUTUAL_FUNDS' || s.kind === 'STOCKS') && s.syncedThrough);

  const send = async (files: FileList | File[] | null | undefined) => {
    if (!files || files.length === 0) return;
    setBusy(true);
    const out: { ok: boolean; text: string }[] = [];
    for (const f of Array.from(files)) {
      try {
        const { data: r } = await onboardingApi.importCasAuto(f, password);
        const what = r.summary.kind === 'DEMAT' ? 'shares and demat holdings' : 'mutual funds';
        out.push({ ok: true, text: `${f.name}: read your ${what} (${r.summary.schemes} ${r.summary.kind === 'DEMAT' ? 'holdings' : 'schemes'})${r.summary.periodTo ? `, up to ${fmtDate(r.summary.periodTo)}` : ''}.` });
        setNeedPw(false);
      } catch (e) {
        const t = errText(e, 'Could not read that file.');
        if (/password|locked/i.test(t)) setNeedPw(true);
        out.push({ ok: false, text: `${f.name}: ${t}` });
      }
    }
    setResults(out);
    setBusy(false);
    onDone();
  };

  return (
    <div className="card space-y-3">
      <div>
        <h3 className="text-sm font-bold text-ink">Start here: add your statements</h3>
        <p className="text-2xs text-gray-500">
          Drop the statement PDFs you already have: a mutual fund statement (CAS) and/or a demat statement. We work out which is which and
          unlock them with your saved PAN and date of birth, so there is nothing to choose or type.
        </p>
      </div>

      <label
        onDragOver={e => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)}
        onDrop={e => { e.preventDefault(); setOver(false); send(e.dataTransfer.files); }}
        className={`flex flex-col items-center justify-center gap-1.5 rounded-2xl border-2 border-dashed px-4 py-10 text-center bg-brand/[0.03] cursor-pointer transition
          ${over ? 'border-brand bg-brand/10' : 'border-surface-border hover:border-brand/60'} ${busy ? 'opacity-60 pointer-events-none' : ''}`}>
        <span className={`icon-badge-brand w-12 h-12 ${over ? 'scale-110' : ''} transition-transform`}>
          {busy ? <Loader2 size={20} className="animate-spin" /> : <Upload size={20} />}
        </span>
        <span className="text-sm font-semibold text-ink">{busy ? 'Reading your statements...' : 'Drop statement PDFs here, or click to choose'}</span>
        <span className="text-2xs text-gray-500">You can pick several at once. Files are read for your holdings and transactions only.</span>
        <input type="file" accept=".pdf" multiple className="hidden" onChange={e => { send(e.target.files); e.target.value = ''; }} />
      </label>

      {needPw && (
        <div className="flex items-center gap-2 flex-wrap rounded-lg bg-surface-hover/60 p-2.5">
          <span className="text-2xs text-gray-400">This file is locked. Type its password and drop it again:</span>
          <input type="password" autoComplete="off" value={password} onChange={e => setPassword(e.target.value)}
            placeholder="Password (often your PAN in capitals)" className="input-field text-xs py-1 w-64" />
        </div>
      )}

      {results.map(r => (
        <div key={r.text} className={`text-2xs flex items-start gap-1.5 ${r.ok ? 'text-bull' : 'text-bear'}`}>
          {r.ok ? <CheckCircle2 size={12} className="mt-0.5 shrink-0" /> : <AlertCircle size={12} className="mt-0.5 shrink-0" />} {r.text}
        </div>
      ))}

      {done.length > 0 && (
        <div className="text-2xs text-gray-400 flex flex-wrap gap-x-4 gap-y-1">
          {done.map(s => <span key={s.id}><CheckCircle2 size={11} className="inline text-bull mr-1" />{s.kind === 'STOCKS' ? 'Shares' : 'Mutual funds'}: data through <b className="text-ink">{fmtDate(s.syncedThrough!)}</b></span>)}
        </div>
      )}

      <div className="rounded-xl bg-brand/10 border border-brand/25 p-3 flex items-start gap-2.5 text-xs text-gray-300">
        <Mail size={14} className="text-brand-light mt-0.5 shrink-0" />
        <div>
          <b className="text-ink">Check your email first.</b> Search your inbox for <b>"Consolidated Account Statement"</b>, <b>"CAS"</b>, <b>"NSDL"</b> or <b>"CDSL"</b>.
          You probably already have one. Save the PDF and drop it above, or forward the email to your private address (see "Stay up to date automatically" below).
        </div>
      </div>

      <div className="grid gap-2.5 sm:grid-cols-3">
        {[
          { icon: Layers, title: 'Mutual funds', fmt: 'PDF', what: 'Consolidated statement (CAS), detailed type', where: 'Emailed by CAMS / KFintech, or request at', href: 'https://www.mfcentral.com', link: 'mfcentral.com' },
          { icon: TrendingUp, title: 'Shares, all brokers', fmt: 'PDF', what: 'Depository CAS. Shows what you hold, not what you paid', where: 'Emailed by NSDL / CDSL, or request at', href: 'https://www.cdslindia.com', link: 'cdslindia.com' },
          { icon: FileSpreadsheet, title: 'What you paid (optional)', fmt: 'CSV / Excel', what: 'Holdings or tradebook file from your broker', where: 'Your broker\'s website, under Reports or Portfolio. Upload it in the broker section below.', href: '', link: '' },
        ].map(c => (
          <div key={c.title} className="rounded-xl border border-surface-border p-3 space-y-1.5 hover:border-brand/40 hover:-translate-y-0.5 transition">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-bold text-ink flex items-center gap-2"><span className="icon-badge-brand w-7 h-7"><c.icon size={14} /></span>{c.title}</span>
              <span className="pill-neutral">{c.fmt}</span>
            </div>
            <p className="text-2xs text-gray-400">{c.what}</p>
            <p className="text-2xs text-gray-500">
              {c.where}{' '}
              {c.href && <a className="text-brand-light underline" href={c.href} target="_blank" rel="noopener noreferrer">{c.link}</a>}
            </p>
          </div>
        ))}
      </div>
      <p className="text-2xs text-gray-600">
        Locked PDFs usually use your PAN in capital letters as the password. If your PAN and date of birth are saved in Settings, we try that for you.
        Site menus change often, so look for "Consolidated Account Statement" on each site.
      </p>

      <div className="rounded-xl border border-surface-border p-3 flex items-center gap-3">
        <div className="flex-1 min-w-0">
          <div className="text-sm font-bold text-ink">Fixed deposits, recurring deposits, credit cards</div>
          <div className="text-2xs text-gray-500">These have no combined statement. Add each one and import its file or enter the details.</div>
        </div>
        <button className="btn-ghost text-2xs px-2 py-1 flex items-center gap-1" onClick={onShowAll}>
          {showAll ? 'Hide' : 'Show'} {showAll ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
        </button>
      </div>
    </div>
  );
}

/** Progress ring + headline: shows how far along setup is and what to do next. */
function Hero({ data, stale }: { data: Checklist; stale: number }) {
  const pct = data.total > 0 ? Math.round((data.current / data.total) * 100) : 0;
  const started = data.sources.some(s => s.syncedThrough);
  const r = 34, c = 2 * Math.PI * r;
  const headline = !started ? 'Let\'s fill your dashboard in two minutes'
    : stale > 0 ? `${stale} source${stale > 1 ? 's' : ''} need a fresh statement`
    : data.current === data.total && data.total > 0 ? 'Everything is up to date' : 'Nice start, keep going';
  const sub = !started ? 'Drop a statement below. We do the rest: detect it, unlock it and file it.'
    : 'Each source shows how far its data goes, and updates continue from that date.';
  return (
    <div className="relative overflow-hidden rounded-2xl bg-brand-gradient text-white p-5 flex items-center gap-5">
      <div className="absolute -right-10 -top-10 w-40 h-40 rounded-full bg-white/10" />
      <div className="absolute right-16 -bottom-14 w-32 h-32 rounded-full bg-white/10" />
      <div className="relative shrink-0">
        <svg width="84" height="84" viewBox="0 0 84 84" className="-rotate-90">
          <circle cx="42" cy="42" r={r} fill="none" stroke="rgba(255,255,255,0.25)" strokeWidth="7" />
          <circle cx="42" cy="42" r={r} fill="none" stroke="white" strokeWidth="7" strokeLinecap="round"
            strokeDasharray={c} strokeDashoffset={c * (1 - pct / 100)} style={{ transition: 'stroke-dashoffset .6s ease' }} />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-lg font-bold leading-none">{data.current}<span className="text-xs font-medium opacity-80">/{data.total}</span></span>
          <span className="text-[10px] opacity-80 mt-0.5">up to date</span>
        </div>
      </div>
      <div className="relative min-w-0">
        <div className="flex items-center gap-1.5 text-xs font-medium opacity-90 mb-0.5"><Sparkles size={13} /> Setup</div>
        <h2 className="text-lg sm:text-xl font-bold leading-tight">{headline}</h2>
        <p className="text-xs opacity-90 mt-1">{sub}</p>
        <div className="mt-2 inline-flex items-center gap-1.5 text-2xs bg-white/15 rounded-full px-2.5 py-1">
          <Mail size={11} />
          {data.gmail.connected
            ? <>Gmail connected{data.gmail.lastSyncAt ? <> · read through {new Date(data.gmail.lastSyncAt).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}</> : ''}</>
            : 'Gmail not connected (optional)'}
        </div>
      </div>
    </div>
  );
}

/** First-time setup and ongoing freshness: every place the user keeps money, how to get its data, and how far it has been imported. */
export function SetupChecklistPage() {
  const [data, setData] = useState<Checklist | null>(null);
  const [error, setError] = useState(false);
  const [showAll, setShowAll] = useState(false);

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
      <Hero data={data} stale={stale} />

      <StartHere data={data} onDone={load} showAll={showAll} onShowAll={() => setShowAll(v => !v)} />

      {showAll && data.guides.filter(g => g.kind !== 'MUTUAL_FUNDS' && g.kind !== 'STOCKS').map(g => (
        <KindSection key={g.kind} guide={g}
          sources={data.sources.filter(s => s.kind === g.kind)}
          onAdded={() => load()} onChanged={() => load()} onRemoved={() => load()} />
      ))}

      {/* Always shown: this is where a broker is added for the "What you paid" tradebook upload.
          Showing it only once a source existed left a new user nowhere to add the first one. */}
      {(
        <details className="card" open={!data.sources.some(s => s.kind === 'MUTUAL_FUNDS' || s.kind === 'STOCKS')}>
          <summary className="text-sm font-bold text-ink cursor-pointer">Your mutual fund and share sources (add a broker, upload a tradebook)</summary>
          <div className="mt-3 space-y-3">
            {data.guides.filter(g => g.kind === 'MUTUAL_FUNDS' || g.kind === 'STOCKS').map(g => (
              <KindSection key={g.kind} guide={g}
                sources={data.sources.filter(s => s.kind === g.kind)}
                onAdded={() => load()} onChanged={() => load()} onRemoved={() => load()} />
            ))}
          </div>
        </details>
      )}

      <details className="card">
        <summary className="text-sm font-bold text-ink cursor-pointer">Optional: stay up to date automatically</summary>
        <div className="mt-3"><ForwardingInbox onImported={load} /></div>
      </details>
    </div>
  );
}
