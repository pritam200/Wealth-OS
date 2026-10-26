import { useCallback, useEffect, useRef, useState } from 'react';
import { researchApi } from '../../api/research';
import type { ResearchResult, ResearchSubject } from '../../api/research';

export interface ResearchState {
  result: ResearchResult | null;
  /** Reading stored research. */
  loading: boolean;
  /** A model run is in progress (can take minutes on a local model). */
  running: boolean;
  error: string | null;
  refresh: () => void;
}

// Subjects (with their session) research has already been started for on this page load. The
// automatic run happens once per subject and session at most: a remount, a second screen
// showing the same stock, or React's development double-mount must not start another run —
// each one is three calls on the free model quota.
const autoRun = new Set<string>();

function runError(e: any): string {
  if (e?.code === 'ECONNABORTED') return 'Research is taking longer than expected. It may still finish — check back in a few minutes.';
  if (!e?.response) return "Couldn't reach the server to run research. Check your connection and try Refresh.";
  return e.response.data?.message ?? 'Research request failed. Try Refresh.';
}

/**
 * Stored research first (no model call). Research runs automatically only when nothing has
 * been researched for the current session yet (or the last run was partial), and only once;
 * otherwise the stored research is shown with its date and Refresh runs it again.
 * {@code onResult} is called when a run finishes with research, so screens that read stored
 * research (the analyst panel, the recommendation) can reload.
 */
export function useResearch(subject: ResearchSubject | null, onResult?: (r: ResearchResult) => void): ResearchState {
  const [result, setResult] = useState<ResearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const key = subject ? `${subject.kind}:${subject.symbol}` : null;
  const current = useRef<string | null>(null);
  const resultCallback = useRef(onResult);
  resultCallback.current = onResult;

  const run = useCallback((refresh: boolean) => {
    if (!subject) return;
    const k = key;
    setRunning(true);
    setError(null);
    researchApi.get(subject, { refresh })
      .then(r => {
        if (current.current !== k) return;
        setResult(r.data);
        if (r.data.report && !r.data.fromCache) resultCallback.current?.(r.data);
      })
      .catch(e => { if (current.current === k) setError(runError(e)); })
      .finally(() => { if (current.current === k) setRunning(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  useEffect(() => {
    current.current = key;
    setResult(null);
    setError(null);
    setRunning(false);
    if (!subject) return;
    setLoading(true);
    researchApi.get(subject, { cached: true })
      .then(r => {
        if (current.current !== key) return;
        setResult(r.data);
        // NOT_RUN: nothing researched for this session. PARTIAL: the review didn't complete.
        // UNAVAILABLE (quota spent) and DISABLED are not retried automatically.
        const due = r.data.status === 'NOT_RUN' || (r.data.status === 'PARTIAL' && !r.data.stale);
        const once = `${key}:${r.data.marketDate ?? ''}`;
        if (due && !autoRun.has(once)) {
          autoRun.add(once);
          run(false);
        }
      })
      .catch(e => { if (current.current === key) setError(e?.response ? 'Stored research could not be loaded.' : runError(e)); })
      .finally(() => { if (current.current === key) setLoading(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return { result, loading, running, error, refresh: () => run(true) };
}
