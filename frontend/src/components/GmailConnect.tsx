import { useState, useEffect, useCallback } from 'react';
import { Mail, CheckCircle, RefreshCw, Unlink, Loader, AlertCircle, AlertTriangle, Zap, ChevronDown, ChevronUp, XCircle, MinusCircle, Lock, X, UserX, Plus, KeyRound, Edit2, Trash2, HelpCircle, FileText, Inbox, ClipboardList, ShieldQuestion } from 'lucide-react';
import { gmailApi } from '../api/gmail';
import { portfolioApi } from '../api/portfolio';
import { StatTile } from './shared/StatTile';
import type { GmailStatus, GmailSyncResult, ProcessedEmailEntry, PendingPdf, PipelineStep, ExcludedSender, SavedPassword, ReconciliationReportDto } from '../api/gmail';

function timeAgo(isoStr: string | null): string {
  if (!isoStr) return 'Never';
  const diff = Math.floor((Date.now() - new Date(isoStr).getTime()) / 60000);
  if (diff < 1) return 'Just now';
  if (diff < 60) return `${diff} min ago`;
  if (diff < 1440) return `${Math.floor(diff / 60)}h ago`;
  return `${Math.floor(diff / 1440)}d ago`;
}

// Financial emails whose PDF attachment (broker contract note, margin/account statement)
// couldn't be read automatically — queued so the user can supply the password once. A
// successful unlock saves the password (encrypted) for that sender's institution, so future
// statements from them unlock automatically without asking again (see Manage Saved
// Passwords below, and the PASSWORD_FAILED status here when a saved password stops working).
function LockedStatements({ onImport }: { onImport?: () => void }) {
  const [items, setItems] = useState<PendingPdf[] | null>(null);
  const [passwords, setPasswords] = useState<Record<number, string>>({});
  const [busyId, setBusyId] = useState<number | null>(null);
  const [messages, setMessages] = useState<Record<number, string>>({});

  const load = useCallback(async () => {
    try { const { data } = await gmailApi.getPendingPdfs(); setItems(data); } catch {}
  }, []);
  useEffect(() => { load(); }, [load]);

  const unlock = async (id: number) => {
    const password = passwords[id];
    if (!password) return;
    setBusyId(id);
    setMessages(m => ({ ...m, [id]: '' }));
    try {
      const { data } = await gmailApi.unlockPdf(id, password);
      setMessages(m => ({ ...m, [id]: data.message }));
      if (data.unlocked) {
        setPasswords(p => ({ ...p, [id]: '' }));
        await load();
        try { await portfolioApi.recalculate(); } catch {}
        if (onImport) onImport();
        window.location.reload();
      }
    } catch {
      setMessages(m => ({ ...m, [id]: 'Something went wrong — try again.' }));
    } finally { setBusyId(null); }
  };

  const dismiss = async (id: number) => {
    try { await gmailApi.dismissPdf(id); await load(); } catch {}
  };

  if (!items || items.length === 0) return null;

  return (
    <div className="mt-3 bg-yellow-400/5 border border-yellow-400/30 rounded-lg p-2.5">
      <div className="flex items-center gap-1.5 text-2xs text-yellow-400 mb-2">
        <Lock size={11} /> {items.length} locked statement{items.length > 1 ? 's' : ''} — unlocking saves the password
        (encrypted) so future statements from the same sender import automatically
      </div>
      <ul className="space-y-2">
        {items.map(pdf => {
          const passwordFailed = pdf.status === 'PASSWORD_FAILED';
          return (
          <li key={pdf.id} className={`rounded p-2 ${passwordFailed ? 'bg-bear/5 border border-bear/25' : 'bg-surface-hover'}`}>
            <div className="flex items-center justify-between gap-2">
              <div className="min-w-0">
                <div className="text-xs text-white truncate">{pdf.filename}</div>
                <div className="text-2xs text-gray-500 truncate">{pdf.sender} · {pdf.subject}</div>
              </div>
              <button onClick={() => dismiss(pdf.id)} className="btn-icon text-gray-600 hover:text-bear shrink-0" title="Dismiss">
                <X size={12} />
              </button>
            </div>

            {passwordFailed && (
              <div className="text-2xs text-bear mt-1.5 flex items-center gap-1">
                <AlertCircle size={10} /> Saved password failed — please update it below.
              </div>
            )}

            <div className="text-2xs mt-1.5 flex items-start gap-1">
              {pdf.passwordHint ? (
                <span className="text-brand-light flex items-center gap-1"><KeyRound size={10} /> Hint: {pdf.passwordHint}</span>
              ) : (
                <span className="text-gray-600 flex items-center gap-1"><HelpCircle size={10} /> Password format unknown — check the email for instructions.</span>
              )}
            </div>

            <div className="flex items-center gap-1.5 mt-1.5">
              <input type="password" placeholder="Password" value={passwords[pdf.id] ?? ''}
                onChange={e => setPasswords(p => ({ ...p, [pdf.id]: e.target.value }))}
                onKeyDown={e => e.key === 'Enter' && unlock(pdf.id)}
                className="input-field text-xs w-40 py-1" />
              <button onClick={() => unlock(pdf.id)} disabled={busyId === pdf.id || !passwords[pdf.id]}
                className="btn-primary text-2xs px-2.5 py-1">
                {busyId === pdf.id ? 'Unlocking…' : 'Unlock & import'}
              </button>
            </div>
            {messages[pdf.id] && <p className="text-2xs text-gray-400 mt-1">{messages[pdf.id]}</p>}
          </li>
          );
        })}
      </ul>
    </div>
  );
}

// View/update/delete the encrypted passwords saved from past unlocks. Never shows the
// actual password — only lets the user replace it (e.g. when a provider changes format)
// or remove it (falling back to a manual prompt next time).
function ManageSavedPasswords() {
  const [items, setItems] = useState<SavedPassword[] | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [editId, setEditId] = useState<number | null>(null);
  const [editValue, setEditValue] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try { const { data } = await gmailApi.getSavedPasswords(); setItems(data); } catch {}
  }, []);
  useEffect(() => { load(); }, [load]);

  const save = async (id: number) => {
    if (!editValue) return;
    setBusy(true);
    try { await gmailApi.updateSavedPassword(id, editValue); setEditId(null); setEditValue(''); await load(); }
    catch {} finally { setBusy(false); }
  };

  const remove = async (id: number) => {
    try { await gmailApi.deleteSavedPassword(id); await load(); } catch {}
  };

  const fmtDate = (s: string | null) => s ? new Date(s).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—';

  return (
    <div className="mt-3">
      <button onClick={() => setExpanded(e => !e)}
        className="btn-ghost text-xs flex items-center gap-1.5 px-2 py-1.5">
        {expanded ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
        <KeyRound size={12} /> Saved passwords {items && items.length > 0 ? `(${items.length})` : ''}
      </button>
      {expanded && (
        <div className="mt-2 bg-surface-hover rounded-lg p-2.5">
          <p className="text-2xs text-gray-500 mb-2">
            Encrypted passwords saved after a successful statement unlock, reused automatically for
            future statements from the same sender. The password itself is never shown here.
          </p>
          {!items || items.length === 0 ? (
            <p className="text-2xs text-gray-600">No saved passwords yet — unlock a statement to save one.</p>
          ) : (
            <ul className="space-y-1.5">
              {items.map(sp => (
                <li key={sp.id} className="bg-surface-panel rounded px-2 py-1.5">
                  <div className="flex items-center justify-between gap-2">
                    <div className="min-w-0">
                      <span className="text-xs text-white font-mono">{sp.providerKey}</span>
                      <span className="text-2xs text-gray-600 ml-2">last used {fmtDate(sp.lastUsedAt)}</span>
                    </div>
                    <div className="flex items-center gap-1 shrink-0">
                      <button onClick={() => { setEditId(editId === sp.id ? null : sp.id); setEditValue(''); }}
                        className="btn-icon text-gray-600 hover:text-white p-0.5" title="Update password"><Edit2 size={11} /></button>
                      <button onClick={() => remove(sp.id)} className="btn-icon text-gray-600 hover:text-bear p-0.5" title="Delete saved password"><Trash2 size={11} /></button>
                    </div>
                  </div>
                  {editId === sp.id && (
                    <div className="flex items-center gap-1.5 mt-1.5">
                      <input type="password" placeholder="New password" value={editValue}
                        onChange={e => setEditValue(e.target.value)}
                        onKeyDown={e => e.key === 'Enter' && save(sp.id)}
                        className="input-field text-xs flex-1 py-1" />
                      <button onClick={() => save(sp.id)} disabled={busy || !editValue} className="btn-primary text-2xs px-2.5 py-1">
                        {busy ? 'Saving…' : 'Save'}
                      </button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

// Lets the user exclude emails belonging to someone else sharing the same inbox/broker
// (e.g. a parent's account) from ever being auto-imported. Matched against sender + subject +
// body on the backend, so a plain email address isn't required — a client code, folio number,
// or account-holder name works just as well.
function ExcludedSenders() {
  const [items, setItems] = useState<ExcludedSender[] | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [newPattern, setNewPattern] = useState('');
  const [newLabel, setNewLabel] = useState('');
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    try { const { data } = await gmailApi.getExcludedSenders(); setItems(data); } catch {}
  }, []);
  useEffect(() => { load(); }, [load]);

  const add = async () => {
    if (!newPattern.trim()) return;
    setAdding(true);
    try {
      await gmailApi.addExcludedSender(newPattern.trim(), newLabel.trim() || undefined);
      setNewPattern(''); setNewLabel('');
      await load();
    } catch {} finally { setAdding(false); }
  };

  const remove = async (id: number) => {
    try { await gmailApi.removeExcludedSender(id); await load(); } catch {}
  };

  return (
    <div className="mt-3">
      <button onClick={() => setExpanded(e => !e)}
        className="btn-ghost text-xs flex items-center gap-1.5 px-2 py-1.5">
        {expanded ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
        <UserX size={12} /> Excluded senders {items && items.length > 0 ? `(${items.length})` : ''}
      </button>
      {expanded && (
        <div className="mt-2 bg-surface-hover rounded-lg p-2.5">
          <p className="text-2xs text-gray-500 mb-2">
            Emails matching any pattern below (sender address, client code, folio number, or name) are
            skipped entirely — never imported, never sent to AI, never queued as a PDF. Useful when a
            shared inbox also receives a parent's or family member's account emails.
          </p>
          {items && items.length > 0 && (
            <ul className="space-y-1 mb-2">
              {items.map(es => (
                <li key={es.id} className="flex items-center justify-between gap-2 bg-surface-panel rounded px-2 py-1">
                  <div className="min-w-0">
                    <span className="text-xs text-white">{es.pattern}</span>
                    {es.label && <span className="text-2xs text-gray-500"> · {es.label}</span>}
                  </div>
                  <button onClick={() => remove(es.id)} className="btn-icon text-gray-600 hover:text-bear shrink-0" title="Remove">
                    <X size={12} />
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div className="flex items-center gap-1.5">
            <input type="text" placeholder="Sender, client code, folio no., or name"
              value={newPattern} onChange={e => setNewPattern(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && add()}
              className="input-field text-xs flex-1 py-1" />
            <input type="text" placeholder="Label (optional)"
              value={newLabel} onChange={e => setNewLabel(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && add()}
              className="input-field text-xs w-32 py-1" />
            <button onClick={add} disabled={adding || !newPattern.trim()}
              className="btn-primary text-2xs px-2.5 py-1 flex items-center gap-1 shrink-0">
              <Plus size={11} /> Add
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

const STEP_LABELS: Record<string, string> = {
  email_detected: 'Email Detected',
  attachment_detected: 'Attachment Detected',
  password_found: 'Password Found',
  pdf_downloaded: 'PDF Downloaded',
  pdf_unlocked: 'PDF Unlocked',
  parser_tried: 'Parser Attempted',
  trades_extracted: 'Trades Extracted',
  holdings_updated: 'Holdings Updated',
  dashboard_updated: 'Dashboard Updated',
};

// The persistent, queryable reconciliation report — distinct from the one-shot "Reconciliation"
// panel shown right after a sync (see lastResult.reconciliation below), which only reflects the
// last sync run and disappears on refresh. This panel reads the full ProcessedEmail + PendingPdf
// history any time it's opened, so it survives across page loads, and every Failed/Unparsed row
// links back to the exact source email/attachment that caused it.
function ReconciliationReportPanel() {
  const [report, setReport] = useState<ReconciliationReportDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const { data } = await gmailApi.getReconciliationReport();
      setReport(data);
    } catch {
      setError('Could not load the reconciliation report.');
    }
    setLoading(false);
  }, []);

  if (!open) {
    return (
      <button onClick={() => { setOpen(true); load(); }}
        className="mt-2 flex items-center gap-1.5 text-xs text-gray-500 hover:text-brand transition-colors">
        <ClipboardList size={12} /> Reconciliation report
      </button>
    );
  }

  return (
    <div className="mt-3 space-y-2.5">
      <div className="flex items-center justify-between">
        <h4 className="text-xs font-semibold text-white flex items-center gap-1.5">
          <ClipboardList size={13} className="text-brand" /> Reconciliation Report
        </h4>
        <div className="flex items-center gap-2">
          <button onClick={load} disabled={loading}
            className="btn-ghost text-2xs flex items-center gap-1 px-2 py-1">
            {loading ? <Loader size={10} className="animate-spin" /> : <RefreshCw size={10} />} Refresh
          </button>
          <button onClick={() => setOpen(false)} className="btn-icon text-gray-600 hover:text-gray-300">
            <X size={12} />
          </button>
        </div>
      </div>

      {loading && !report && (
        <div className="text-xs text-gray-500 flex items-center gap-1.5 py-3">
          <Loader size={12} className="animate-spin" /> Loading reconciliation report…
        </div>
      )}

      {error && (
        <div className="text-xs text-bear flex items-center gap-1.5"><AlertCircle size={12} /> {error}</div>
      )}

      {report && (
        <>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
            <StatTile label="Imported" value={String(report.imported)} Icon={CheckCircle} tone="bull" sensitive={false}
              sub="Includes topped-up holdings — see note below" />
            <StatTile label="Failed" value={String(report.failed)} Icon={XCircle} tone="bear" sensitive={false} />
            <StatTile label="Unparsed" value={String(report.unparsed)} Icon={MinusCircle} tone="neutral" sensitive={false} />
            <StatTile label="Duplicate" value={report.duplicates == null ? 'Unknown' : String(report.duplicates)}
              Icon={ShieldQuestion} tone="neutral" sensitive={false}
              sub={report.duplicates == null ? 'Not determinable — see note' : undefined} />
            <StatTile label="Reconciled" value={report.reconciled == null ? 'Unknown' : String(report.reconciled)}
              Icon={RefreshCw} tone="neutral" sensitive={false}
              sub={report.reconciled == null ? 'Not determinable — see note' : 'Recovered by a recent fix'} />
            <StatTile label="Excluded" value={String(report.excluded)} Icon={UserX} tone="neutral" sensitive={false}
              sub="Intentional — not a gap" />
            <StatTile label="PDFs awaiting password" value={String(report.pdfsAwaitingPassword)}
              Icon={Lock} tone="gold" sensitive={false} />
            <StatTile label="Generated" value={new Date(report.generatedAt).toLocaleTimeString()}
              Icon={Inbox} tone="neutral" sensitive={false} />
          </div>

          <div className="bg-surface-hover rounded-lg p-2.5 space-y-1.5 text-2xs text-gray-500">
            <p><span className="text-gray-300 font-medium">Updated</span> — {report.updatedNote}</p>
            <p><span className="text-gray-300 font-medium">Duplicate</span> — {report.duplicatesNote}</p>
            <p><span className="text-gray-300 font-medium">Reconciled</span> — {report.reconciledNote}</p>
          </div>

          <div>
            <div className="text-2xs text-gray-500 mb-1.5 font-semibold uppercase tracking-wider">
              Failed / Unparsed — traced to source ({report.details.length})
            </div>
            {report.details.length === 0 ? (
              <p className="text-xs text-gray-600 py-2">Nothing failed or went unparsed.</p>
            ) : (
              <div className="max-h-80 overflow-y-auto space-y-1">
                {report.details.map((d, i) => (
                  <div key={`${d.gmailMessageId}-${i}`} className="bg-surface-hover rounded p-2 text-2xs">
                    <div className="flex items-center gap-1.5 flex-wrap">
                      <span className={d.status === 'Failed' ? 'text-bear font-medium' : 'text-gray-400 font-medium'}>
                        {d.status}
                      </span>
                      <span className="text-gray-600 bg-surface-panel/60 px-1 py-0 rounded">{d.source}</span>
                      {d.matchedParser && (
                        <span className="text-brand-light bg-brand/10 px-1 py-0 rounded">{d.matchedParser}</span>
                      )}
                      {d.processedAt && <span className="text-gray-700">{new Date(d.processedAt).toLocaleString()}</span>}
                    </div>
                    <div className="text-gray-300 truncate mt-0.5">
                      {d.subject ?? <span className="text-gray-600 italic">subject not recorded</span>}
                    </div>
                    <div className="text-gray-600 truncate">{d.sender}</div>
                    {d.reason && <div className="text-gray-500 mt-0.5">{d.reason}</div>}
                    <div className="text-gray-700 mt-0.5">Gmail message: {d.gmailMessageId}</div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function ContractNoteDebug() {
  const [items, setItems] = useState<PendingPdf[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try { const { data } = await gmailApi.getContractNoteDebug(); setItems(data); } catch {}
    setLoading(false);
  }, []);

  if (!open) {
    return (
      <button onClick={() => { setOpen(true); load(); }}
        className="mt-2 flex items-center gap-1.5 text-xs text-gray-500 hover:text-brand transition-colors">
        <FileText size={12} /> Contract Note Debug View
      </button>
    );
  }

  const parseSteps = (raw: string | null): PipelineStep[] => {
    if (!raw) return [];
    try { return JSON.parse(raw); } catch { return []; }
  };

  const stepIcon = (status: string) => {
    if (status === 'OK') return <CheckCircle size={11} className="text-bull shrink-0" />;
    if (status === 'FAIL' || status === 'ERROR') return <XCircle size={11} className="text-bear shrink-0" />;
    if (status === 'EMPTY') return <MinusCircle size={11} className="text-yellow-400 shrink-0" />;
    return <MinusCircle size={11} className="text-gray-500 shrink-0" />;
  };

  const statusBadge = (s: string) => {
    const colors: Record<string, string> = {
      IMPORTED: 'bg-bull/15 text-bull',
      FAILED: 'bg-bear/15 text-bear',
      NEEDS_PASSWORD: 'bg-yellow-500/15 text-yellow-400',
      PASSWORD_FAILED: 'bg-orange-500/15 text-orange-400',
      DISMISSED: 'bg-gray-500/15 text-gray-400',
    };
    return <span className={`px-1.5 py-0.5 rounded text-2xs font-semibold ${colors[s] || 'bg-surface-hover text-gray-400'}`}>{s}</span>;
  };

  return (
    <div className="mt-3 space-y-2">
      <div className="flex items-center justify-between">
        <h4 className="text-xs font-semibold text-white flex items-center gap-1.5">
          <FileText size={13} className="text-brand" /> Contract Note Debug View
        </h4>
        <div className="flex items-center gap-2">
          <button onClick={load} disabled={loading}
            className="btn-ghost text-2xs flex items-center gap-1 px-2 py-1">
            {loading ? <Loader size={10} className="animate-spin" /> : <RefreshCw size={10} />} Refresh
          </button>
          <button onClick={() => setOpen(false)} className="btn-icon text-gray-600 hover:text-gray-300">
            <X size={12} />
          </button>
        </div>
      </div>

      {loading && !items && (
        <div className="text-xs text-gray-500 flex items-center gap-1.5 py-3">
          <Loader size={12} className="animate-spin" /> Loading contract note pipeline data...
        </div>
      )}

      {items && items.length === 0 && (
        <p className="text-xs text-gray-600 py-3">No PDF attachments detected from any emails yet.</p>
      )}

      {items && items.length > 0 && (
        <div className="space-y-1.5 max-h-96 overflow-y-auto">
          {items.map(pdf => {
            const steps = parseSteps(pdf.pipelineSteps);
            const isExpanded = expanded === pdf.id;
            return (
              <div key={pdf.id} className="bg-surface-hover rounded-lg border border-surface-border/50">
                <button onClick={() => setExpanded(isExpanded ? null : pdf.id)}
                  className="w-full flex items-center gap-2 p-2.5 text-left">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 mb-0.5">
                      {statusBadge(pdf.status)}
                      <span className="text-2xs text-gray-500 truncate">{pdf.filename}</span>
                      {pdf.tradesExtracted != null && (
                        <span className="text-2xs text-gray-500">
                          {pdf.tradesExtracted} found / {pdf.tradesImported ?? 0} imported
                        </span>
                      )}
                    </div>
                    <div className="text-xs text-gray-300 truncate">{pdf.subject}</div>
                    <div className="text-2xs text-gray-600">{pdf.sender} · {new Date(pdf.createdAt).toLocaleDateString()}</div>
                  </div>
                  {isExpanded ? <ChevronUp size={12} className="text-gray-500 shrink-0" /> : <ChevronDown size={12} className="text-gray-500 shrink-0" />}
                </button>

                {isExpanded && (
                  <div className="px-2.5 pb-2.5 space-y-2 border-t border-surface-border/30 pt-2">
                    {steps.length > 0 ? (
                      <div className="space-y-1">
                        {steps.map((s, i) => (
                          <div key={i} className="flex items-start gap-1.5 text-2xs">
                            {stepIcon(s.status)}
                            <span className="text-gray-400 w-28 shrink-0">{STEP_LABELS[s.step] || s.step}</span>
                            <span className={s.status === 'OK' ? 'text-gray-300' : s.status === 'FAIL' || s.status === 'ERROR' ? 'text-bear' : 'text-yellow-400'}>
                              {s.detail}
                            </span>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="text-2xs text-gray-600">
                        {pdf.status === 'NEEDS_PASSWORD'
                          ? 'Waiting for password — no pipeline steps recorded yet.'
                          : 'No pipeline steps recorded for this entry.'}
                      </p>
                    )}

                    {pdf.resultSummary && (
                      <div className="text-2xs text-gray-500 bg-surface rounded p-1.5 mt-1">
                        {pdf.resultSummary}
                      </div>
                    )}

                    {pdf.textSnippet && (
                      <details className="text-2xs">
                        <summary className="text-gray-600 cursor-pointer hover:text-gray-400">
                          PDF text snippet ({pdf.textSnippet.length} chars shown)
                        </summary>
                        <pre className="mt-1 text-gray-500 bg-surface rounded p-1.5 overflow-x-auto whitespace-pre-wrap max-h-32 overflow-y-auto font-mono text-[10px]">
                          {pdf.textSnippet}
                        </pre>
                      </details>
                    )}
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

interface Props { onImport?: () => void; }

export function GmailConnect({ onImport }: Props) {
  const [status, setStatus]       = useState<GmailStatus | null>(null);
  const [syncing, setSyncing]     = useState(false);
  const [lastResult, setLastResult] = useState<GmailSyncResult | null>(null);
  const [polling, setPolling]     = useState(false);
  const [error, setError]         = useState<string | null>(null);
  const [history, setHistory]     = useState<ProcessedEmailEntry[] | null>(null);
  const [showHistory, setShowHistory] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [expandedEntry, setExpandedEntry] = useState<number | null>(null);

  const loadStatus = useCallback(async () => {
    try { const { data } = await gmailApi.getStatus(); setStatus(data); } catch {}
  }, []);

  useEffect(() => { loadStatus(); }, [loadStatus]);

  // Poll for connection after OAuth redirect
  useEffect(() => {
    if (!polling) return;
    const t = setInterval(async () => {
      await loadStatus();
      setStatus(s => {
        if (s?.connected) { setPolling(false); clearInterval(t); }
        return s;
      });
    }, 3000);
    return () => clearInterval(t);
  }, [polling, loadStatus]);

  // Detect redirect back from Google OAuth via hash
  useEffect(() => {
    const hash = window.location.hash;
    if (hash === '#gmail-connected') {
      window.location.hash = '';
      loadStatus();
    } else if (hash.startsWith('#gmail-error')) {
      window.location.hash = '';
      setError('Gmail connection failed. Please try again.');
    }
  }, [loadStatus]);

  const handleConnect = async () => {
    setError(null);
    try {
      const { data } = await gmailApi.getAuthUrl();
      if (data.error) { setError(data.error as unknown as string); return; }
      setPolling(true);
      window.open(data.url, '_blank', 'width=500,height=600');
    } catch (e: any) {
      const serverError = e?.response?.data?.error;
      setError(serverError || (e?.request ? 'Could not reach the backend. Is it running?' : 'Could not get auth URL.'));
    }
  };

  const handleSync = async (mode: 'normal' | 'full' | 'retry' = 'normal') => {
    setSyncing(true);
    setLastResult(null);
    setError(null);
    try {
      const { data } = mode === 'full'
        ? await gmailApi.fullResync()
        : mode === 'retry'
          ? await gmailApi.retryFailed()
          : await gmailApi.triggerSync();
      setLastResult(data);
      if (data.error) setError(data.error);
      await loadStatus();
      if (data.imported > 0) {
        try { await portfolioApi.recalculate(); } catch {}
        if (onImport) onImport();
        window.location.reload();
      }
    } catch { setError('Sync failed — could not reach the backend.'); }
    setSyncing(false);
  };

  const toggleHistory = async () => {
    if (showHistory) { setShowHistory(false); return; }
    setShowHistory(true);
    setHistoryLoading(true);
    try { const { data } = await gmailApi.getHistory(100); setHistory(data); } catch {} finally { setHistoryLoading(false); }
  };

  const excludeSender = async (sender: string) => {
    try { await gmailApi.addExcludedSender(sender); } catch {}
  };

  const handleDisconnect = async () => {
    await gmailApi.disconnect();
    setStatus(null);
    setLastResult(null);
  };

  if (!status) return (
    <div className="card animate-pulse h-24 bg-surface-hover" />
  );

  return (
    <div className="card border border-brand/20">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <div className={`p-2 rounded-lg ${status.connected ? 'bg-bull/10' : 'bg-surface-hover'}`}>
            <Mail size={16} className={status.connected ? 'text-bull' : 'text-gray-400'} />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="font-semibold text-white text-sm">Data Sync</span>
              {status.connected && (
                <span className="flex items-center gap-1 text-2xs text-bull bg-bull/10 px-1.5 py-0.5 rounded-full">
                  <CheckCircle size={9} /> Connected
                </span>
              )}
            </div>
            {status.connected ? (
              <p className="text-xs text-gray-500 mt-0.5">
                {status.connectedEmail} · Last synced {timeAgo(status.lastSyncAt)} · {status.importedCount} items imported
              </p>
            ) : (
              <p className="text-xs text-gray-500 mt-0.5">
                Auto-import trades, FDs &amp; SIPs from your bank/broker emails
              </p>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2 shrink-0">
          {status.connected ? (
            <>
              <button onClick={() => handleSync('normal')} disabled={syncing}
                className="btn-ghost text-xs flex items-center gap-1.5 px-2 py-1.5">
                {syncing ? <Loader size={12} className="animate-spin" /> : <RefreshCw size={12} />}
                {syncing ? 'Syncing…' : 'Sync now'}
              </button>
              <button onClick={() => handleSync('retry')} disabled={syncing}
                className="btn-ghost text-xs flex items-center gap-1.5 px-2 py-1.5 text-orange-400 hover:text-orange-300"
                title="Re-process emails that previously failed or were skipped">
                <RefreshCw size={12} /> Retry failed
              </button>
              <button onClick={() => handleSync('full')} disabled={syncing}
                className="btn-ghost text-xs flex items-center gap-1.5 px-2 py-1.5 text-yellow-400 hover:text-yellow-300"
                title="Delete all sync history and re-process every email from the past year">
                <RefreshCw size={12} /> Full resync
              </button>
              <button onClick={toggleHistory}
                className="btn-ghost text-xs flex items-center gap-1.5 px-2 py-1.5" title="See which parser matched each email, and why anything was skipped">
                {showHistory ? <ChevronUp size={12} /> : <ChevronDown size={12} />} Sync history
              </button>
              <button onClick={handleDisconnect}
                className="btn-icon text-gray-600 hover:text-bear" title="Disconnect Gmail">
                <Unlink size={13} />
              </button>
            </>
          ) : (
            <button onClick={handleConnect}
              className="btn-primary text-xs flex items-center gap-1.5 px-3 py-1.5">
              {polling ? <Loader size={12} className="animate-spin" /> : <Mail size={12} />}
              {polling ? 'Waiting…' : 'Connect Gmail'}
            </button>
          )}
        </div>
      </div>

      {/* Last sync result */}
      {lastResult && !syncing && (
        <div className="mt-3 space-y-2">
          {/* Summary bar */}
          <div className={`flex items-center gap-2 text-xs p-2.5 rounded-lg border ${
            lastResult.imported > 0 ? 'bg-bull/5 border-bull/20 text-bull'
              : lastResult.failed > 0 ? 'bg-bear/5 border-bear/20 text-bear'
              : 'bg-surface-hover border-surface-border text-gray-400'}`}>
            <Zap size={12} />
            <span className="font-semibold">
              {lastResult.imported > 0
                ? `${lastResult.imported} items imported`
                : lastResult.failed > 0
                  ? `${lastResult.failed} failed`
                  : 'No new transactions found'}
            </span>
            {lastResult.skipped > 0 && <span className="text-gray-600">· {lastResult.skipped} skipped</span>}
            {lastResult.failed > 0 && lastResult.imported > 0 && <span className="text-bear">· {lastResult.failed} failed</span>}
          </div>

          {/* Reconciliation report */}
          {lastResult.reconciliation && (
            <div className={`rounded-lg border p-3 text-xs ${
              lastResult.reconciliation.status === 'OK'
                ? 'bg-bull/5 border-bull/20' : 'bg-yellow-500/5 border-yellow-500/20'}`}>
              <div className="flex items-center gap-2 mb-2">
                {lastResult.reconciliation.status === 'OK'
                  ? <CheckCircle size={13} className="text-bull" />
                  : <AlertTriangle size={13} className="text-yellow-400" />}
                <span className="font-semibold text-white">
                  Reconciliation: {lastResult.reconciliation.status === 'OK' ? 'All records accounted for' : 'Action Required'}
                </span>
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-2xs">
                <div><span className="text-gray-500">Emails scanned</span><div className="text-white font-medium">{lastResult.reconciliation.emailsProcessed}</div></div>
                <div><span className="text-gray-500">Attachments</span><div className="text-white font-medium">{lastResult.reconciliation.attachmentsProcessed}</div></div>
                <div><span className="text-gray-500">Transactions found</span><div className="text-white font-medium">{lastResult.reconciliation.transactionsFound}</div></div>
                <div><span className="text-gray-500">Imported</span><div className="text-bull font-medium">{lastResult.reconciliation.transactionsImported}</div></div>
                <div><span className="text-gray-500">Duplicates skipped</span><div className="text-gray-400 font-medium">{lastResult.reconciliation.duplicatesSkipped}</div></div>
                <div><span className="text-gray-500">Failed</span><div className={`font-medium ${lastResult.reconciliation.failedImports > 0 ? 'text-bear' : 'text-gray-400'}`}>{lastResult.reconciliation.failedImports}</div></div>
                <div><span className="text-gray-500">PDFs pending</span><div className={`font-medium ${lastResult.reconciliation.pdfsPending > 0 ? 'text-yellow-400' : 'text-gray-400'}`}>{lastResult.reconciliation.pdfsPending}</div></div>
              </div>
              {lastResult.reconciliation.actionItems.length > 0 && (
                <div className="mt-2 space-y-1">
                  {lastResult.reconciliation.actionItems.map((item, i) => (
                    <div key={i} className="flex items-start gap-1.5 text-2xs text-yellow-400">
                      <AlertTriangle size={10} className="shrink-0 mt-0.5" />
                      <span>{item}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Per-email pipeline trace */}
          {lastResult.logEntries && lastResult.logEntries.length > 0 && (
            <div className="bg-surface-hover rounded-lg p-2.5 max-h-80 overflow-y-auto">
              <div className="text-2xs text-gray-500 mb-2 font-semibold uppercase tracking-wider">Sync pipeline trace — click to expand steps</div>
              <ul className="space-y-1">
                {lastResult.logEntries.map((entry, i) => {
                  const Icon = entry.status === 'IMPORTED' ? CheckCircle
                    : entry.status === 'FAILED' ? XCircle
                    : entry.status === 'PDF_QUEUED' ? Lock
                    : entry.status === 'EXCLUDED' ? UserX
                    : MinusCircle;
                  const color = entry.status === 'IMPORTED' ? 'text-bull'
                    : entry.status === 'FAILED' ? 'text-bear'
                    : entry.status === 'PDF_QUEUED' ? 'text-yellow-400'
                    : 'text-gray-500';
                  const isExpanded = expandedEntry === i;
                  return (
                    <li key={i} className="rounded bg-surface-panel/50 hover:bg-surface-panel transition-colors">
                      <button className="w-full text-left flex items-start gap-1.5 text-2xs p-1.5"
                        onClick={() => setExpandedEntry(isExpanded ? null : i)}>
                        <Icon size={11} className={`${color} shrink-0 mt-0.5`} />
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-1.5 flex-wrap">
                            <span className={`font-medium ${color}`}>{entry.status}</span>
                            {entry.matchedParser && (
                              <span className="text-brand-light bg-brand/10 px-1 py-0 rounded text-2xs">{entry.matchedParser}</span>
                            )}
                            {entry.type && <span className="text-gray-600">{entry.type}</span>}
                            {entry.itemsImported > 0 && <span className="text-bull">+{entry.itemsImported}</span>}
                          </div>
                          <div className="text-gray-500 truncate">{entry.detail || entry.subject}</div>
                        </div>
                        {entry.pipelineSteps && entry.pipelineSteps.length > 0 && (
                          isExpanded ? <ChevronUp size={10} className="text-gray-600 shrink-0 mt-0.5" /> : <ChevronDown size={10} className="text-gray-600 shrink-0 mt-0.5" />
                        )}
                      </button>
                      {isExpanded && entry.pipelineSteps && (
                        <div className="px-1.5 pb-1.5 ml-4 border-l border-surface-border">
                          <div className="text-2xs text-gray-700 mb-0.5">From: {entry.sender}</div>
                          <div className="text-2xs text-gray-700 mb-1">Subject: {entry.subject}</div>
                          {entry.pipelineSteps.map((step, si) => (
                            <div key={si} className="flex items-start gap-1 text-2xs text-gray-500 py-0.5">
                              <span className="text-gray-700 shrink-0">{si + 1}.</span>
                              <span>{step}</span>
                            </div>
                          ))}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          {/* Imported summaries (legacy fallback when logEntries is empty) */}
          {(!lastResult.logEntries || lastResult.logEntries.length === 0) && lastResult.summaries.length > 0 && (
            <ul className="space-y-0.5 px-1">
              {lastResult.summaries.slice(0, 5).map((s, i) => (
                <li key={i} className="text-2xs text-gray-400 flex gap-1.5">
                  <CheckCircle size={9} className="mt-0.5 text-bull shrink-0" />{s}
                </li>
              ))}
              {lastResult.summaries.length > 5 && (
                <li className="text-2xs text-gray-600">+{lastResult.summaries.length - 5} more</li>
              )}
            </ul>
          )}
        </div>
      )}

      {status.connected && <LockedStatements onImport={onImport} />}
      {status.connected && <ManageSavedPasswords />}
      {status.connected && <ExcludedSenders />}
      {status.connected && <ReconciliationReportPanel />}
      {status.connected && <ContractNoteDebug />}

      {/* Sync history — which parser matched each email, and why anything was skipped/failed */}
      {showHistory && (
        <div className="mt-3 bg-surface-hover rounded-lg p-2.5 max-h-72 overflow-y-auto">
          {historyLoading ? (
            <div className="flex items-center gap-2 text-xs text-gray-500 py-2"><Loader size={12} className="animate-spin" /> Loading sync history…</div>
          ) : !history || history.length === 0 ? (
            <p className="text-xs text-gray-600 py-1">No sync history yet — click "Sync now" first.</p>
          ) : (
            <ul className="space-y-1.5">
              {history.map(h => {
                const Icon = h.status === 'IMPORTED' ? CheckCircle : h.status === 'FAILED' ? XCircle : MinusCircle;
                const color = h.status === 'IMPORTED' ? 'text-bull' : h.status === 'FAILED' ? 'text-bear' : 'text-gray-500';
                return (
                  <li key={h.id} className="flex items-start gap-1.5 text-2xs">
                    <Icon size={11} className={`${color} shrink-0 mt-0.5`} />
                    <div className="min-w-0 flex-1">
                      <span className={color}>{h.status}</span>
                      {h.matchedParser && <span className="text-gray-600"> · {h.matchedParser}</span>}
                      <span className="text-gray-600"> · {h.type}</span>
                      {h.resultSummary && <div className="text-gray-500 truncate">{h.resultSummary}</div>}
                    </div>
                    {h.sender && (
                      <button onClick={() => excludeSender(h.sender!)}
                        className="btn-icon text-gray-600 hover:text-bear shrink-0" title={`Exclude sender: ${h.sender}`}>
                        <UserX size={11} />
                      </button>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}

      {error && (
        <div className="mt-2 flex items-center gap-1.5 text-xs text-bear">
          <AlertCircle size={12} /> {error}
        </div>
      )}

      {polling && (
        <p className="mt-2 text-xs text-gray-500 flex items-center gap-1.5">
          <Loader size={11} className="animate-spin" /> Waiting for Google authorization in the popup…
        </p>
      )}
    </div>
  );
}
