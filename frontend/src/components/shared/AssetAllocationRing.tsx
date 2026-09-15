import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import { useMaskedText } from './Amount';
import type { PortfolioContext } from '../../api/todaysActions';
import { CHART, tooltipStyle } from '../../theme/chartTheme';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

/**
 * Asset-class ring for the Action Center — equity / debt / other from the single backend
 * wealth summary, deliberately not recomputed here. Slice colours carry meaning (growth vs
 * safety vs other) rather than being a decorative palette.
 */
const SLICES = [
  { key: 'equity', label: 'Equity',      color: CHART.bull },
  { key: 'debt',   label: 'Debt / Fixed', color: CHART.brand },
  { key: 'other',  label: 'Other',       color: '#64748B' },
] as const;

export function AssetAllocationRing({ wealth }: { wealth: PortfolioContext | null }) {
  const maskText = useMaskedText();

  if (!wealth || !wealth.totalAssets) {
    return <p className="text-center text-gray-600 text-xs py-10">No assets to allocate yet.</p>;
  }

  const equityValue = (wealth.stocksValue ?? 0) + (wealth.mfValue ?? 0);
  const debtValue = (wealth.fdValue ?? 0) + (wealth.rdValue ?? 0) + (wealth.epfValue ?? 0);
  const otherValue = wealth.otherAssetsValue ?? 0;
  const total = equityValue + debtValue + otherValue;

  const data = [
    { key: 'equity', label: 'Equity', value: equityValue },
    { key: 'debt', label: 'Debt / Fixed', value: debtValue },
    { key: 'other', label: 'Other', value: otherValue },
  ].filter(d => d.value > 0);

  if (data.length === 0) {
    return <p className="text-center text-gray-600 text-xs py-10">No assets to allocate yet.</p>;
  }

  const colorOf = (key: string) => SLICES.find(s => s.key === key)?.color ?? '#64748B';

  return (
    <div>
      <div className="relative">
        <ResponsiveContainer width="100%" height={168}>
          <PieChart>
            <Pie data={data} dataKey="value" nameKey="label" cx="50%" cy="50%"
                 outerRadius={78} innerRadius={54} paddingAngle={2} strokeWidth={0}>
              {data.map(d => <Cell key={d.key} fill={colorOf(d.key)} />)}
            </Pie>
            <Tooltip
              contentStyle={tooltipStyle}
              itemStyle={{ color: CHART.ink }}
              formatter={(v: any, _n, entry: any) => [
                `${maskText(fmt(Number(v ?? 0)))} (${total > 0 ? ((Number(v ?? 0) / total) * 100).toFixed(0) : 0}%)`,
                entry?.payload?.label,
              ]}
            />
          </PieChart>
        </ResponsiveContainer>
        {/* Centre label — the headline split, readable without hovering a slice. */}
        <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
          <span className="text-2xs text-gray-500 uppercase tracking-wider font-semibold">Equity</span>
          <span className="text-xl font-mono tabular-nums font-bold text-ink">
            {total > 0 ? `${((equityValue / total) * 100).toFixed(0)}%` : '—'}
          </span>
        </div>
      </div>

      <div className="mt-3 space-y-1.5">
        {data.map(d => (
          <div key={d.key} className="flex items-center justify-between text-xs">
            <span className="flex items-center gap-2 text-gray-400">
              <span className="w-2 h-2 rounded-full shrink-0" style={{ background: colorOf(d.key) }} />
              {d.label}
            </span>
            <span className="flex items-center gap-2">
              <span className="font-mono tabular-nums text-gray-200">{maskText(fmt(d.value))}</span>
              <span className="font-mono tabular-nums text-gray-600 w-9 text-right">
                {total > 0 ? `${((d.value / total) * 100).toFixed(0)}%` : '—'}
              </span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
