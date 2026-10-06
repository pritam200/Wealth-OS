import { useState } from 'react';
import { Sparkles, X, Loader2 } from 'lucide-react';
import { aiAuditApi } from '../../api/aiAudit';
import type { AiAuditTrailEntry } from '../../api/aiAudit';
import { provenanceApi } from '../../api/provenance';
import type { RecordKind, SourceView } from '../../api/provenance';

const ORIGIN_LABEL: Record<string, string> = {
  MANUAL: 'Entered by hand',
  EMAIL_LLM: 'Read from an email',
  PDF_LLM: 'Read from a statement attachment',
  OCR_LLM: 'Read from a scanned document',
  ATTACHMENT_LLM: 'Read from a text attachment (CSV, TXT or HTML)',
  UNKNOWN: 'Recorded before sources were tracked',
};

const STATUS_COLOR: Record<string, string> = {
  ACCEPTED: '#22C55E',
  REVIEW_REQUIRED: '#EAB308',
  REJECTED: '#EF4444',
  PARSE_FAILED: '#EF4444',
  UNAVAILABLE: '#6B7280',
};

// A verbatim source-text substring the extracted field cited, when the audit row's rawOutput
// happens to be JSON carrying one — best-effort only, never load-bearing for the modal.
function extractSpan(rawOutput: string | null): string | null {
  if (!rawOutput) return null;
  try {
    const parsed = JSON.parse(rawOutput);
    const span = parsed?.source_span ?? parsed?.span ?? parsed?.sourceText;
    return typeof span === 'string' ? span : null;
  } catch {
    return null;
  }
}

/**
 * "Why did the AI do this?" — dropped next to any auto-imported row so its provenance
 * (AiAuditTrail) is one click away instead of backend-only. Deliberately generic: it only
 * needs the same sourceEmailId every ingestion-produced entity already persists.
 */
export function AiProvenanceButton({ referenceId, record }: { referenceId?: string | null; record?: { kind: RecordKind; id: number } }) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [entries, setEntries] = useState<AiAuditTrailEntry[] | null>(null);
  const [source, setSource] = useState<SourceView | null>(null);
  const [error, setError] = useState(false);

  const onOpen = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setOpen(true);
    if (entries || loading) return;
    setLoading(true);
    setError(false);
    try {
      let emailId = referenceId ?? null;
      if (record) {
        const src = await provenanceApi.source(record.kind, record.id);
        setSource(src.data);
        emailId = src.data.gmailMessageId ?? emailId;
      }
      setEntries(emailId ? (await aiAuditApi.byReference(emailId)).data : []);
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  };

  const onClose = (e?: React.MouseEvent) => {
    e?.stopPropagation();
    setOpen(false);
  };

  return (
    <>
      <button
        aria-label={record ? 'View source' : 'Why did the AI do this?'}
        title={record ? 'View source' : 'Why did the AI do this?'}
        onClick={onOpen}
        className="btn-icon text-teal-400 hover:text-teal-300 p-0.5"
      >
        <Sparkles size={11} />
      </button>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
          <div className="bg-surface rounded-xl border border-surface-border shadow-2xl w-full max-w-md mx-4 max-h-[80vh] overflow-y-auto"
               onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between p-4 border-b border-surface-border sticky top-0 bg-surface">
              <div className="flex items-center gap-2">
                <Sparkles size={14} className="text-teal-400" />
                <h3 className="text-ink font-bold text-sm">{record ? 'Source' : 'Why the AI did this'}</h3>
              </div>
              <button aria-label="Close" onClick={onClose} className="btn-icon text-gray-500 hover:text-ink"><X size={16} /></button>
            </div>

            <div className="p-4 space-y-3">
              {loading && (
                <div className="flex items-center justify-center gap-2 py-6 text-gray-500 text-xs">
                  <Loader2 size={14} className="animate-spin" /> Loading audit trail…
                </div>
              )}
              {!loading && error && (
                <p className="text-bear text-xs text-center py-4">Couldn't load the audit trail. Try again later.</p>
              )}
              {!loading && !error && source && (
                <div className="rounded-lg border border-surface-border p-3 space-y-1.5 text-2xs">
                  <div className="flex justify-between"><span className="stat-label">Origin</span>
                    <span className="text-ink">{ORIGIN_LABEL[source.origin] ?? source.origin}</span></div>
                  {source.confidence != null && (
                    <div className="flex justify-between"><span className="stat-label">Extraction confidence</span>
                      <span className="text-ink font-mono">{(source.confidence * 100).toFixed(0)}%</span></div>
                  )}
                  {source.emailSender && (
                    <div className="flex justify-between gap-2"><span className="stat-label">From</span>
                      <span className="text-ink truncate">{source.emailSender}</span></div>
                  )}
                  {source.attachmentId && (
                    <div className="flex justify-between"><span className="stat-label">Document</span>
                      <span className="text-ink font-mono">{source.documentHash ? source.documentHash.slice(0, 12) + '…' : 'attachment'}</span></div>
                  )}
                  {source.extractedAs && (
                    <div className="flex justify-between gap-2"><span className="stat-label">Read as</span>
                      <span className="text-ink text-right">{source.extractedAs}</span></div>
                  )}
                  {source.extractionVersion && (
                    <div className="flex justify-between gap-2"><span className="stat-label">Read by</span>
                      <span className="text-ink font-mono text-right break-all">{source.extractionVersion}</span></div>
                  )}
                  {source.duplicateState && source.duplicateState !== 'NEW' && (
                    <div className="flex justify-between"><span className="stat-label">Duplicate check</span>
                      <span className="text-ink">{source.duplicateState.replace(/_/g, ' ').toLowerCase()}</span></div>
                  )}
                  {source.conflictDetail && <p className="text-bear">{source.conflictDetail}</p>}
                  {source.gmailLink && (
                    <a href={source.gmailLink} target="_blank" rel="noopener noreferrer" className="text-teal-400 underline">Open the email in Gmail</a>
                  )}
                </div>
              )}
              {!loading && !error && !source && entries?.length === 0 && (
                <p className="text-gray-500 text-xs text-center py-4">No AI audit record found for this item.</p>
              )}
              {!loading && !error && entries?.map(entry => {
                const span = extractSpan(entry.rawOutput);
                const color = STATUS_COLOR[entry.status] ?? '#6B7280';
                return (
                  <div key={entry.id} className="rounded-lg border border-surface-border p-3 space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="px-2 py-0.5 rounded text-2xs font-semibold" style={{ backgroundColor: color + '22', color }}>
                        {entry.status.replace(/_/g, ' ')}
                      </span>
                      <span className="text-2xs text-gray-500">{new Date(entry.createdAt).toLocaleString('en-IN')}</span>
                    </div>
                    <div className="flex justify-between text-2xs">
                      <span className="stat-label">Task</span>
                      <span className="text-ink">{entry.task.replace(/_/g, ' ')}</span>
                    </div>
                    {entry.confidence != null && (
                      <div className="flex justify-between text-2xs">
                        <span className="stat-label">Confidence</span>
                        <span className="text-ink font-mono">{(entry.confidence * 100).toFixed(0)}%</span>
                      </div>
                    )}
                    {(entry.provider || entry.model) && (
                      <div className="flex justify-between text-2xs">
                        <span className="stat-label">Model</span>
                        <span className="text-ink">
                          {[entry.provider, entry.model].filter(Boolean).join(' / ')}
                          {entry.fallbackUsed && <span className="text-neutral"> (fallback)</span>}
                        </span>
                      </div>
                    )}
                    {entry.promptVersion && (
                      <div className="flex justify-between text-2xs">
                        <span className="stat-label">Prompt</span>
                        <span className="text-ink font-mono">{entry.promptVersion}</span>
                      </div>
                    )}
                    {span && (
                      <div>
                        <div className="stat-label mb-1">Source text cited</div>
                        <div className="text-2xs text-ink bg-surface-hover rounded p-2 font-mono break-words">
                          "{span}"
                        </div>
                      </div>
                    )}
                    {entry.note && (
                      <div className="text-2xs text-gray-500 italic">{entry.note}</div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
