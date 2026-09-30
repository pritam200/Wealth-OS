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

/**
 * Stored research first (no model call); if none exists for the current data, research runs
 * once automatically. Refresh always runs it again.
 */
export function useResearch(subject: ResearchSubject | null): ResearchState {
  const [result, setResult] = useState<ResearchResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const key = subject ? `${subject.kind}:${subject.symbol}` : null;
  const current = useRef<string | null>(null);

  const run = useCallback((refresh: boolean) => {
    if (!subject) return;
    const k = key;
    setRunning(true);
    setError(null);
    researchApi.get(subject, { refresh })
      .then(r => { if (current.current === k) setResult(r.data); })
      .catch(e => { if (current.current === k) setError(e?.response?.status === 503 ? 'AI research unavailable' : 'Research request failed'); })
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
        // Nothing stored for today's data: run research once.
        if (r.data.status === 'NOT_RUN') run(false);
      })
      .catch(() => { if (current.current === key) setError('Research could not be loaded'); })
      .finally(() => { if (current.current === key) setLoading(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return { result, loading, running, error, refresh: () => run(true) };
}
