import type { LucideIcon } from 'lucide-react';
import { usePrivacyStore } from '../../store/privacyStore';

type Tone = 'brand' | 'bull' | 'bear' | 'neutral' | 'gold';

interface Props {
  label: string;
  value: string;
  Icon: LucideIcon;
  tone?: Tone;
  sub?: string;
  // Most StatTiles show a rupee amount, so masking defaults on — pass false explicitly
  // for non-monetary tiles (counts, percentages of a signal, etc.) that are safe to show.
  sensitive?: boolean;
}

const MASK = '••••••';

// Icon + label/value KPI tile — the recurring building block for every stat grid in the app
// (net worth summary, holdings totals, FD/RD/loan summaries, dashboard headline numbers).
// Centralizing this one component is what makes every KPI in the app look consistent, and
// is also what lets the global privacy toggle (Topbar eye icon) mask every money figure at
// once instead of leaking whichever screen happens to be open when someone glances over.
export function StatTile({ label, value, Icon, tone = 'brand', sub, sensitive = true }: Props) {
  const masked = usePrivacyStore(s => s.masked);
  const hide = sensitive && masked;
  return (
    <div className="kpi-tile">
      <div className={`icon-badge-${tone}`}>
        <Icon size={15} />
      </div>
      <div className="min-w-0">
        <div className="stat-label">{label}</div>
        <div className="font-bold font-mono text-sm text-white truncate">{hide ? MASK : value}</div>
        {sub && <div className="text-2xs text-gray-600 mt-0.5">{hide ? MASK : sub}</div>}
      </div>
    </div>
  );
}
