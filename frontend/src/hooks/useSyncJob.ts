import { useCallback, useEffect, useRef, useState } from 'react';
import { syncJobApi } from '../api/gmail';
import type { SyncJob } from '../api/gmail';

const POLL_MS = 2000;
/** Stop polling after this long so a wedged job can't leave a tab polling forever. */
const MAX_POLL_MS = 30 * 60 * 1000;

/**
 * Queues a Gmail sync and follows it to completion.
 *
 * Replaces awaiting a blocking request: a full scan takes minutes and the request would time
 * out long before the work finished, leaving the user with an error for a sync that was in
 * fact still running.
 */
export function useSyncJob(onFinished?: (job: SyncJob) => void) {
  const [job, setJob] = useState<SyncJob | null>(null);
  const [error, setError] = useState('');
  const timer = useRef<number | null>(null);
  const startedAt = useRef<number>(0);
  const finishedRef = useRef(onFinished);

  // Keep the latest callback without restarting polling when the parent re-renders.
  useEffect(() => { finishedRef.current = onFinished; }, [onFinished]);

  const stop = useCallback(() => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);

  useEffect(() => stop, [stop]);

  const poll = useCallback((id: number) => {
    const tick = async () => {
      try {
        const { data } = await syncJobApi.get(id);
        setJob(data);

        const done = data.status === 'SUCCEEDED' || data.status === 'FAILED' || data.status === 'CANCELLED';
        if (done) {
          stop();
          if (data.status === 'FAILED') setError(data.lastError || 'Sync failed.');
          finishedRef.current?.(data);
          return;
        }
        if (Date.now() - startedAt.current > MAX_POLL_MS) {
          stop();
          setError('Still running — stopped watching. Check sync history for the outcome.');
          return;
        }
        timer.current = window.setTimeout(tick, POLL_MS);
      } catch {
        // A transient poll failure shouldn't kill the watch; the job is server-side.
        timer.current = window.setTimeout(tick, POLL_MS * 2);
      }
    };
    tick();
  }, [stop]);

  const start = useCallback(async (opts?: { full?: boolean; lookback?: string }) => {
    setError('');
    stop();
    try {
      const { data } = await syncJobApi.queueGmail(opts);
      setJob(data);
      startedAt.current = Date.now();
      if (data?.id != null) poll(data.id);
      return data;
    } catch {
      setError('Could not start the sync.');
      return null;
    }
  }, [poll, stop]);

  const running = !!job && (job.status === 'QUEUED' || job.status === 'RUNNING');
  const progress =
    job?.itemsTotal && job.itemsTotal > 0
      ? Math.min(((job.itemsProcessed ?? 0) / job.itemsTotal) * 100, 100)
      : null;

  return { job, error, running, progress, start, stop };
}
