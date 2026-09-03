import { TrendingUp, TrendingDown } from 'lucide-react';
import type { IndexQuote } from '../../types';

interface Props {
  index: IndexQuote;
  size?: 'sm' | 'lg';
}

const fmtVal = (n: number | null | undefined) =>
  n === null || n === undefined ? '—' : n.toLocaleString('en-IN', { maximumFractionDigits: 2 });
const fmtChg = (n: number | null | undefined) =>
  n === null || n === undefined ? '—' : `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`;

export function IndexTicker({ index, size = 'sm' }: Props) {
  const hasChange = index.changePercent !== null && index.changePercent !== undefined;
  const isPositive = hasChange && index.changePercent >= 0;
  const color = !hasChange ? 'text-gray-500' : isPositive ? 'text-bull' : 'text-bear';
  const Icon = isPositive ? TrendingUp : TrendingDown;

  if (size === 'lg') {
    return (
      <div className="card flex-1 min-w-[160px]">
        <div className="stat-label mb-1">{index.name}</div>
        <div className="stat-value">{fmtVal(index.value)}</div>
        <div className={`flex items-center gap-1 mt-1 ${color}`}>
          {hasChange && <Icon size={14} />}
          <span className="text-sm font-medium">
            {hasChange ? `${isPositive ? '+' : ''}${index.change?.toFixed(2)} (` : ''}{fmtChg(index.changePercent)}{hasChange ? ')' : ''}
          </span>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3 px-4 py-2 border-r border-surface-border last:border-0">
      <div>
        <div className="text-xs text-gray-500">{index.name}</div>
        <div className="text-sm font-semibold text-white">
          {fmtVal(index.value)}
        </div>
      </div>
      <span className={`text-xs font-medium ${color}`}>
        {fmtChg(index.changePercent)}
      </span>
    </div>
  );
}
