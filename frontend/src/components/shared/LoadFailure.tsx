import { AlertTriangle, RotateCw } from 'lucide-react';

/**
 * "This failed to load" — which is not the same statement as "you have none of these".
 *
 * Loaders across the app used to swallow errors and fall through to their empty state, so a failed
 * request to the reminders endpoint rendered "Nothing due soon" to a user with an overdue card
 * bill, and a failed wealth summary rendered a net worth of ₹0 as though it were a fact. Anything
 * that reports money or obligations must be able to say it doesn't know.
 */
export function LoadFailure({ what, onRetry }: { what: string; onRetry?: () => void }) {
  return (
    <div className="flex items-start gap-2.5 rounded-lg border border-bear/30 bg-bear/5 p-3">
      <AlertTriangle size={14} className="text-bear shrink-0 mt-0.5" />
      <div className="min-w-0 flex-1">
        <p className="text-xs text-ink font-medium">Could not load {what}</p>
        <p className="text-2xs text-gray-500 mt-0.5">
          This is a loading problem, not an empty list — don't read it as "nothing to show".
        </p>
      </div>
      {onRetry && (
        <button onClick={onRetry} className="btn-ghost text-2xs py-1 px-2 shrink-0">
          <RotateCw size={11} /> Retry
        </button>
      )}
    </div>
  );
}
