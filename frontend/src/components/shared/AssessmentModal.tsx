import { useEffect } from 'react';
import { X } from 'lucide-react';
import type { AnalystAssessment } from '../../api/analyst';

/** The reasoning behind a signal: the call, why, every factor read, and the tax view. */
export function AssessmentModal({ name, label, cls, data, onClose }: { name: string; label: string; cls: string; data: AnalystAssessment; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-surface rounded-xl border border-surface-border shadow-2xl w-full max-w-lg mx-4 max-h-[85vh] flex flex-col overflow-hidden"
           onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between gap-3 p-4 border-b border-surface-border">
          <div className="flex items-center gap-2 min-w-0">
            <span className={`text-2xs px-1.5 py-0.5 rounded border shrink-0 ${cls}`}>{label}</span>
            <h3 className="text-ink font-bold text-sm truncate">{name}</h3>
          </div>
          <button aria-label="Close" onClick={onClose} className="btn-icon text-gray-500 hover:text-ink"><X size={16} /></button>
        </div>
        <div className="p-4 space-y-4 overflow-y-auto text-xs">
          {data.nextActionReason && <p className="text-gray-300 leading-relaxed">{data.nextActionReason}</p>}
          {(data.positives?.length > 0 || data.risks?.length > 0) && (
            <div className="space-y-1">
              {data.positives?.map((r, i) => <div key={`p${i}`} className="text-gray-400 flex gap-1.5"><span className="text-bull">▸</span>{r}</div>)}
              {data.risks?.map((r, i) => <div key={`r${i}`} className="text-gray-400 flex gap-1.5"><span className="text-bear">▸</span>{r}</div>)}
            </div>
          )}
          {data.factorBreakdown?.length > 0 && (
            <div>
              <div className="text-2xs uppercase tracking-wide text-gray-600 mb-1.5">What was checked</div>
              <div className="space-y-1.5">
                {data.factorBreakdown.map((f, i) => (
                  <div key={i} className="text-gray-400">
                    <span className="text-ink font-medium">{f.name}</span>{f.reading ? <span className="font-mono"> · {f.reading}</span> : ''}{f.note ? ` — ${f.note}` : ''}
                  </div>
                ))}
              </div>
            </div>
          )}
          {data.taxImpact && <p className="text-neutral/90">{data.taxImpact}</p>}
          {data.basis && <p className="text-2xs text-gray-600">{data.basis}</p>}
          <p className="text-2xs text-gray-600">Rule-based reading of the record and your position — not investment advice.</p>
        </div>
      </div>
    </div>
  );
}
