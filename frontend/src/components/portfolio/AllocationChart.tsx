import { PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import type { AllocationDto } from '../../types';
import { useMaskedText } from '../shared/Amount';

const COLORS = [
  '#6366f1', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16',
  '#14b8a6', '#f97316', '#a855f7', '#eab308',
];

const MAX_SLICES = 8; // beyond this, readability suffers — bucket the tail into "Others"

interface Props { allocation: AllocationDto[] }

const truncate = (s: string, n = 22) => (s.length > n ? s.slice(0, n - 1) + '…' : s);

function prepareSlices(allocation: AllocationDto[]): AllocationDto[] {
  const sorted = [...allocation].filter(a => a.value > 0).sort((a, b) => b.value - a.value);
  if (sorted.length <= MAX_SLICES) return sorted;
  const head = sorted.slice(0, MAX_SLICES - 1);
  const tail = sorted.slice(MAX_SLICES - 1);
  const othersValue = tail.reduce((s, a) => s + a.value, 0);
  const othersPercent = tail.reduce((s, a) => s + a.percent, 0);
  return [...head, { label: `Others (${tail.length})`, value: othersValue, percent: othersPercent }];
}

export function AllocationChart({ allocation }: Props) {
  const maskText = useMaskedText();
  const slices = prepareSlices(allocation);

  if (slices.length === 0) {
    return <p className="text-center text-gray-600 text-sm py-10">No holdings to allocate yet.</p>;
  }

  return (
    <ResponsiveContainer width="100%" height={300}>
      <PieChart>
        <Pie
          data={slices}
          dataKey="value"
          nameKey="label"
          cx="50%"
          cy="42%"
          outerRadius={95}
          innerRadius={55}
          paddingAngle={2}
        >
          {slices.map((_, i) => (
            <Cell key={i} fill={COLORS[i % COLORS.length]} stroke="transparent" />
          ))}
        </Pie>
        <Tooltip
          contentStyle={{ background: '#1a1d27', border: '1px solid #2a2d3e', borderRadius: 8 }}
          formatter={(v, _name, entry: any) => [
            `${maskText(new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(Number(v ?? 0)))} (${entry?.payload?.percent?.toFixed(1) ?? '0'}%)`,
            entry?.payload?.label,
          ]}
        />
        <Legend
          layout="horizontal"
          verticalAlign="bottom"
          wrapperStyle={{ fontSize: 11, paddingTop: 8 }}
          formatter={(value, entry: any) => (
            <span className="text-gray-400 text-xs" title={value}>
              {truncate(value)} <span className="text-gray-600">{entry?.payload?.percent?.toFixed(0)}%</span>
            </span>
          )}
        />
      </PieChart>
    </ResponsiveContainer>
  );
}
