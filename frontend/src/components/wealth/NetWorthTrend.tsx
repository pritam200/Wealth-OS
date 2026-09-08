import { useState, useEffect } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts';
import { TrendingUp } from 'lucide-react';
import { netWorthApi } from '../../api/planning';
import type { NetWorthSnapshot } from '../../api/planning';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const fmtShort = (n: number) => n >= 1e7 ? `₹${(n / 1e7).toFixed(1)}Cr` : n >= 1e5 ? `₹${(n / 1e5).toFixed(1)}L` : `₹${(n / 1e3).toFixed(0)}k`;

export function NetWorthTrend() {
  const maskText = useMaskedText();
  const [series, setSeries] = useState<NetWorthSnapshot[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let alive = true;
    const go = async () => {
      try {
        // Captures today's snapshot from the server-computed wealth summary (never a
        // client-supplied total) so the trend has today's point even before any history exists.
        await netWorthApi.snapshot();
        const { data } = await netWorthApi.series();
        if (alive) setSeries(data);
      } catch {} finally { if (alive) setLoaded(true); }
    };
    go();
    return () => { alive = false; };
  }, []);

  if (!loaded) return null;

  const first = series[0]?.netWorth ?? 0;
  const last = series[series.length - 1]?.netWorth ?? 0;
  const change = last - first;
  const changePct = first > 0 ? change / first * 100 : 0;
  const chartData = series.map(s => ({ date: s.snapshotDate.slice(5), netWorth: s.netWorth, totalAssets: s.totalAssets }));

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <TrendingUp size={14} className="text-brand" />
          <h3 className="font-semibold text-white text-sm">Net Worth Trend</h3>
        </div>
        {series.length > 1 && (
          <span className={`text-xs font-mono ${change >= 0 ? 'text-bull' : 'text-bear'}`}>
            {maskText(`${change >= 0 ? '+' : ''}${fmtINR(change)} (${changePct >= 0 ? '+' : ''}${changePct.toFixed(1)}%)`)}
          </span>
        )}
      </div>
      {series.length < 2 ? (
        <p className="text-gray-600 text-xs text-center py-6">
          Tracking started{series.length === 1 ? ` — today's net worth is ${maskText(fmtINR(last))}` : ''}. Your growth curve builds as you use the app day to day.
        </p>
      ) : (
        <ResponsiveContainer width="100%" height={200}>
          <LineChart data={chartData} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#232c3f" />
            <XAxis dataKey="date" tick={{ fontSize: 10, fill: '#6b7280' }} />
            <YAxis tickFormatter={(v: number) => maskText(fmtShort(v))} tick={{ fontSize: 10, fill: '#6b7280' }} width={48} />
            <Tooltip formatter={(v) => maskText(fmtINR(Number(v ?? 0)))} contentStyle={{ background: '#121826', border: '1px solid #232c3f', borderRadius: 6, fontSize: 11 }} />
            <Line type="monotone" dataKey="netWorth" stroke="#00c47a" strokeWidth={2} dot={false} name="Net worth" />
            <Line type="monotone" dataKey="totalAssets" stroke="#2563eb" strokeWidth={1.5} dot={false} strokeDasharray="4 2" name="Total assets" />
          </LineChart>
        </ResponsiveContainer>
      )}
    </div>
  );
}
