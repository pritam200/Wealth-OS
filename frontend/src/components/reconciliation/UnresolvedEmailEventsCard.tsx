import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, Inbox } from 'lucide-react';
import { gmailApi } from '../../api/gmail';
import type { EmailFinancialEvent, EventState, UnresolvedEventsView } from '../../api/gmail';
import { Amount, MaskedSentence } from '../shared/Amount';
import { LoadFailure } from '../shared/LoadFailure';
import { usePrivacyStore } from '../../store/privacyStore';

const STATE_LABEL: Record<EventState, string> = {
  IMPORTED: 'Imported',
  DUPLICATE_OF_EXISTING: 'Already on record',
  RESOLVED: 'Needs nothing',
  REQUIRES_REVIEW: 'Waiting for your decision',
  RECONCILIATION_REQUIRED: 'Reconciliation required',
  FAILED_WITH_REASON: 'Could not be saved',
};

const SOURCE_LABEL: Record<EmailFinancialEvent['sourceKind'], string> = {
  BODY: 'Email body',
  ATTACHMENT: 'Attachment',
  EMAIL: 'Whole email',
};

/**
 * Every financial event found in email that is not yet accounted for, grouped by email, each
 * with its reason. Review-queue items are decided in Needs Review (accepting one books it);
 * email-level problems — a totals mismatch, a conflict, an attachment that could not be read —
 * are marked resolved here once checked.
 */
export function UnresolvedEmailEventsCard() {
  const [data, setData] = useState<UnresolvedEventsView | null>(null);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState<number | null>(null);
  const masked = usePrivacyStore(s => s.masked);

  const load = useCallback(async () => {
    try {
      const { data } = await gmailApi.getUnresolvedEvents();
      setData(data);
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const groups = useMemo(() => {
    const byEmail = new Map<string, { subject: string | null; sender: string | null; events: EmailFinancialEvent[] }>();
    for (const row of data?.events ?? []) {
      const g = byEmail.get(row.event.gmailMessageId) ?? { subject: row.subject, sender: row.sender, events: [] };
      g.events.push(row.event);
      byEmail.set(row.event.gmailMessageId, g);
    }
    return [...byEmail.entries()];
  }, [data]);

  const resolve = async (id: number) => {
    setBusy(id);
    try {
      await gmailApi.resolveEvent(id);
      await load();
    } catch {
      setFailed(true);
    } finally {
      setBusy(null);
    }
  };

  if (failed && !data) return <LoadFailure what="unresolved email events" onRetry={load} />;
  if (!data) return <div className="card animate-pulse h-24 bg-surface-hover" />;

  const accounted = data.total - data.unresolved;

  return (
    <div className="card space-y-3">
      <h2 className="text-sm font-bold text-ink flex items-center gap-2">
        {data.unresolved ? <AlertTriangle size={14} className="text-neutral" /> : <CheckCircle2 size={14} className="text-bull" />}
        <Inbox size={14} /> Unresolved email events ({data.unresolved})
      </h2>
      <p className="text-2xs text-gray-500">
        {data.total} financial event(s) found in email so far: {accounted} accounted for
        {data.unresolved > 0 ? `, ${data.unresolved} in ${data.emailsWithUnresolved} email(s) still open` : ' — nothing is open'}.
      </p>
      <div className="flex flex-wrap gap-2 text-2xs text-gray-400">
        {(Object.keys(STATE_LABEL) as EventState[]).filter(s => data.byState[s]).map(s => (
          <span key={s} className="px-2 py-0.5 rounded bg-surface-hover">{STATE_LABEL[s]}: {data.byState[s]}</span>
        ))}
      </div>

      {groups.map(([msgId, g]) => {
        const expanded = open[msgId] ?? groups.length <= 3;
        return (
          <div key={msgId} className="border-t border-surface-border/40 pt-2">
            <button className="w-full flex items-center gap-1.5 text-left text-xs text-ink"
                    onClick={() => setOpen(o => ({ ...o, [msgId]: !expanded }))}>
              {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
              <span className="truncate">{g.subject || g.sender || msgId}</span>
              <span className="pill-neutral ml-auto shrink-0">{g.events.length} open</span>
            </button>
            {expanded && (
              <div className="mt-1.5 space-y-1.5 pl-4">
                {g.events.map(e => (
                  <div key={e.id} className="text-2xs space-y-0.5">
                    <div className="flex flex-wrap items-center gap-x-2 text-gray-400">
                      <span className="text-ink">{STATE_LABEL[e.state]}</span>
                      <span>· {SOURCE_LABEL[e.sourceKind]}{e.attachmentName ? ` (${e.attachmentName})` : ''}</span>
                      {e.eventType && e.eventType !== 'DOCUMENT' && e.eventType !== 'ATTACHMENT' && <span>· {e.eventType}</span>}
                      {e.amount != null && <span>· <Amount value={`${e.currency && e.currency !== 'INR' ? e.currency + ' ' : '₹'}${e.amount.toLocaleString('en-IN')}`} /></span>}
                      {e.eventDate && <span>· {e.eventDate}</span>}
                      {e.merchant && e.sourceKind !== 'EMAIL' && <span>· {e.merchant}</span>}
                    </div>
                    <div className="text-gray-300"><MaskedSentence text={e.reason} /></div>
                    {e.evidence && !masked && <div className="text-gray-500 italic truncate" title={e.evidence}>“{e.evidence}”</div>}
                    <div className="flex items-center gap-2 text-gray-500">
                      {e.llmModel && <span>Read by {e.llmProvider}:{e.llmModel}{e.promptVersion ? ` / ${e.promptVersion}` : ''}</span>}
                      {e.state === 'RECONCILIATION_REQUIRED'
                        ? <button className="btn-ghost text-2xs py-0.5 px-2 ml-auto" disabled={busy === e.id} onClick={() => resolve(e.id)}>
                            Checked — mark resolved
                          </button>
                        : <span className="ml-auto">Decide it in Needs Review</span>}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
