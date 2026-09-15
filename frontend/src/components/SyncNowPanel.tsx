import { RefreshCw, CheckCircle2, AlertTriangle, Clock } from 'lucide-react';
import { useSyncJob } from '../hooks/useSyncJob';

/**
 * Queues a sync and shows its real progress.
 *
 * The status shown here is the job's server-side state, so closing the tab or losing the
 * connection doesn't abandon the work — it keeps running on the worker and the next load
 * picks the outcome back up from sync history.
 */
export function SyncNowPanel({ onFinished }: { onFinished?: () => void }) {
  const { job, error, running, progress, start } = useSyncJob(() => onFinished?.());

  const statusPill = () => {
    if (!job) return null;
    switch (job.status) {
      case 'QUEUED':    return <span className="pill-muted"><Clock size={11} /> Queued</span>;
      case 'RUNNING':   return <span className="pill-info"><RefreshCw size={11} className="animate-spin" /> Running</span>;
      case 'SUCCEEDED': return <span className="pill-bull"><CheckCircle2 size={11} /> Done</span>;
      case 'FAILED':    return <span className="pill-bear"><AlertTriangle size={11} /> Failed</span>;
      default:          return <span className="pill-muted">{job.status}</span>;
    }
  };

  return (
    <div className="card">
      <div className="flex items-start justify-between gap-3 flex-wrap mb-2">
        <div className="min-w-0">
          <h3 className="section-title mb-0.5"><RefreshCw size={15} className="text-brand-light" /> Sync Now</h3>
          <p className="text-2xs text-gray-600">
            Runs in the background — you can leave this page and it keeps going.
          </p>
        </div>
        <div className="flex items-center gap-1.5">
          {statusPill()}
          <button onClick={() => start()} disabled={running} className="btn-primary text-xs">
            {running ? 'Syncing…' : 'Sync new mail'}
          </button>
          <button onClick={() => start({ full: true, lookback: '30d' })} disabled={running}
                  className="btn-secondary text-xs" title="Re-scan the last 30 days regardless of the sync watermark">
            Full re-scan
          </button>
        </div>
      </div>

      {job && (job.itemsTotal ?? 0) > 0 && (
        <div className="mt-2">
          <div className="flex items-center justify-between text-2xs text-gray-500 mb-1">
            <span>
              <span className="font-mono tabular-nums">{job.itemsProcessed ?? 0}</span>
              {' / '}
              <span className="font-mono tabular-nums">{job.itemsTotal}</span> messages
            </span>
            {progress != null && <span className="font-mono tabular-nums">{progress.toFixed(0)}%</span>}
          </div>
          <div className="w-full h-1.5 rounded-full bg-surface-hover overflow-hidden">
            <div className="h-full rounded-full bg-brand transition-all" style={{ width: `${progress ?? 0}%` }} />
          </div>
        </div>
      )}

      {job?.status === 'SUCCEEDED' && job.resultSummary && (
        <p className="text-2xs text-gray-500 mt-2 font-mono">{job.resultSummary}</p>
      )}

      {/* An attempt count above 1 means it failed and was retried — worth surfacing rather
          than hiding, since a silently-retrying sync looks identical to a slow one. */}
      {running && job && (job.attempts ?? 0) > 1 && (
        <p className="text-2xs text-neutral mt-2">
          Retry <span className="font-mono tabular-nums">{job.attempts}</span> — a previous attempt failed and was requeued.
        </p>
      )}

      {error && (
        <p className="text-2xs text-bear mt-2 flex items-start gap-1.5">
          <AlertTriangle size={11} className="shrink-0 mt-0.5" /> {error}
        </p>
      )}
    </div>
  );
}
