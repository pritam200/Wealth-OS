import { useEffect, useState } from 'react';
import { marketApi } from '../../api/market';
import { IndexTicker } from '../../components/shared/IndexTicker';
import type { MarketOverview } from '../../types';
import { TrendingUp, TrendingDown, Minus, Activity } from 'lucide-react';

const fmtPct = (n: number) => `${n >= 0 ? '+' : ''}${n?.toFixed(2)}%`;

function SentimentMeter({ sectors }: { sectors: { changePercent: number }[] }) {
  if (!sectors.length) return null;
  const avg = sectors.reduce((s, x) => s + x.changePercent, 0) / sectors.length;
  const label = avg > 0.5 ? 'Bullish' : avg < -0.5 ? 'Bearish' : 'Sideways';
  const color = avg > 0.5 ? 'text-bull' : avg < -0.5 ? 'text-bear' : 'text-neutral';
  const Icon = avg > 0.5 ? TrendingUp : avg < -0.5 ? TrendingDown : Minus;
  const pct = Math.min(Math.max((avg + 2) / 4, 0), 1) * 100;

  return (
    <div className="card flex flex-col items-center py-8">
      <div className="stat-label mb-3">Market Sentiment</div>
      <div className={`flex items-center gap-2 text-3xl font-bold mb-4 ${color}`}>
        <Icon size={28} />
        {label}
      </div>
      <div className="w-full bg-surface-hover rounded-full h-3 overflow-hidden">
        <div className="h-full rounded-full bg-gradient-to-r from-bear via-neutral to-bull" />
      </div>
      <div className="relative w-full mt-1">
        <div className="absolute h-4 w-0.5 bg-white rounded-full top-0" style={{ left: `${pct}%`, transform: 'translateX(-50%)' }} />
      </div>
      <div className="flex justify-between w-full mt-3 text-xs text-gray-600">
        <span>Bearish</span><span>Sideways</span><span>Bullish</span>
      </div>
      <div className={`mt-4 text-sm font-medium ${color}`}>Avg sector: {fmtPct(avg)}</div>
    </div>
  );
}

export function Tab1MarketTrends() {
  const [overview, setOverview] = useState<MarketOverview | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    marketApi.getOverview().then(r => setOverview(r.data)).finally(() => setLoading(false));
    const t = setInterval(() => marketApi.getOverview().then(r => setOverview(r.data)).catch(() => {}), 60_000);
    return () => clearInterval(t);
  }, []);

  if (loading) return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      {[...Array(4)].map((_, i) => <div key={i} className="card animate-pulse h-24 bg-surface-hover" />)}
    </div>
  );

  const sectors = overview?.sectors ?? [];
  const topGain = [...sectors].sort((a, b) => b.changePercent - a.changePercent).slice(0, 5);
  const topLose = [...sectors].sort((a, b) => a.changePercent - b.changePercent).slice(0, 5);

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#6d5efc] to-[#9b5cf9] flex items-center justify-center text-white shadow-lift shrink-0">
          <Activity size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-0.5">Market Trends</h2>
          <p className="text-gray-500 text-sm">Live index metrics and market sentiment</p>
        </div>
      </div>

      {/* Sentiment meter + gainers/losers — the chart-like visuals lead the page */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Sentiment meter */}
        <SentimentMeter sectors={sectors} />

        {/* Top gainers */}
        <div className="card">
          <h3 className="font-semibold text-white mb-4 flex items-center gap-2">
            <TrendingUp size={16} className="text-bull" /> Top Sector Gainers
          </h3>
          <div className="space-y-3">
            {topGain.map(s => (
              <div key={s.sector} className="flex items-center justify-between">
                <span className="text-sm text-gray-300 truncate">{s.sector}</span>
                <span className="text-bull text-sm font-medium ml-4">{fmtPct(s.changePercent)}</span>
              </div>
            ))}
            {topGain.length === 0 && <p className="text-gray-600 text-sm text-center py-4">No data</p>}
          </div>
        </div>

        {/* Top losers */}
        <div className="card">
          <h3 className="font-semibold text-white mb-4 flex items-center gap-2">
            <TrendingDown size={16} className="text-bear" /> Top Sector Losers
          </h3>
          <div className="space-y-3">
            {topLose.map(s => (
              <div key={s.sector} className="flex items-center justify-between">
                <span className="text-sm text-gray-300 truncate">{s.sector}</span>
                <span className="text-bear text-sm font-medium ml-4">{fmtPct(s.changePercent)}</span>
              </div>
            ))}
            {topLose.length === 0 && <p className="text-gray-600 text-sm text-center py-4">No data</p>}
          </div>
        </div>
      </div>

      {/* All sectors heatmap */}
      {sectors.length > 0 && (
        <div className="card">
          <h3 className="font-semibold text-white mb-4">All Sectors</h3>
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
            {sectors.map(s => {
              const pos = s.changePercent >= 0;
              const intensity = Math.min(Math.abs(s.changePercent) / 3, 1);
              return (
                <div key={s.sector}
                  style={{ backgroundColor: pos ? `rgba(34,197,94,${0.08 + intensity * 0.2})` : `rgba(239,68,68,${0.08 + intensity * 0.2})` }}
                  className={`rounded-lg p-3 border ${pos ? 'border-bull/20' : 'border-bear/20'}`}>
                  <div className="text-xs text-gray-400 truncate mb-1">{s.sector}</div>
                  <div className={`text-sm font-bold ${pos ? 'text-bull' : 'text-bear'}`}>{fmtPct(s.changePercent)}</div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Index cards — summary stat tiles, after the chart-like visuals above */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {overview?.nifty50 && <IndexTicker index={overview.nifty50} size="lg" />}
        {overview?.bankNifty && <IndexTicker index={overview.bankNifty} size="lg" />}
        {overview?.sensex && <IndexTicker index={overview.sensex} size="lg" />}
        {overview?.niftyMidcap && <IndexTicker index={overview.niftyMidcap} size="lg" />}
      </div>
    </div>
  );
}
