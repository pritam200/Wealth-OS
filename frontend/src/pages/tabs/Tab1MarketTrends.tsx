import { useEffect, useState, useCallback } from 'react';
import { marketApi } from '../../api/market';
import type { MarketOverview, IndexQuote } from '../../types';
import { TrendingUp, TrendingDown, Minus, Activity } from 'lucide-react';
import { LoadFailure } from '../../components/shared/LoadFailure';

const fmtPct = (n: number | null | undefined) =>
  n == null ? '—' : `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`;
const fmtNum = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(n);

/* ── Ticker ribbon ──────────────────────────────────────────────────────────────────────
   Index metrics across the top, terminal-style: one row, monospaced figures, direction shown
   by text colour on a uniform surface rather than by tinting each cell.                   */
function TickerRibbon({ indices }: { indices: { label: string; index?: IndexQuote }[] }) {
  const present = indices.filter(i => i.index);
  if (present.length === 0) return null;

  return (
    <div className="rounded-xl border border-surface-border bg-surface-card overflow-hidden">
      <div className="flex divide-x divide-surface-border overflow-x-auto">
        {present.map(({ label, index }) => {
          const pct = index!.changePercent;
          const pos = (pct ?? 0) >= 0;
          return (
            <div key={label} className="flex-1 min-w-[150px] px-4 py-3">
              <div className="stat-label mb-1 truncate">{label}</div>
              <div className="flex items-baseline gap-2 flex-wrap">
                <span className="text-base font-mono tabular-nums font-bold text-ink">
                  {fmtNum(index!.value)}
                </span>
                <span className={`text-xs font-mono tabular-nums font-semibold ${pos ? 'text-bull' : 'text-bear'}`}>
                  {fmtPct(pct)}
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ── Sentiment meter ────────────────────────────────────────────────────────────────────
   The needle now sits ON the gradient track. Previously it lived in a separate element
   below the bar, so it never actually pointed at a position on the scale.                 */
function SentimentMeter({ sectors }: { sectors: { changePercent: number }[] }) {
  if (!sectors.length) {
    return (
      <div className="card">
        <div className="stat-label mb-2">Market Sentiment</div>
        <p className="text-gray-600 text-xs py-10 text-center">No sector data available.</p>
      </div>
    );
  }

  const avg = sectors.reduce((s, x) => s + x.changePercent, 0) / sectors.length;
  const label = avg > 0.5 ? 'Bullish' : avg < -0.5 ? 'Bearish' : 'Sideways';
  const pill = avg > 0.5 ? 'pill-bull' : avg < -0.5 ? 'pill-bear' : 'pill-neutral';
  const color = avg > 0.5 ? 'text-bull' : avg < -0.5 ? 'text-bear' : 'text-neutral';
  const Icon = avg > 0.5 ? TrendingUp : avg < -0.5 ? TrendingDown : Minus;

  // Clamped to a ±2% scale — beyond that the needle simply pins to the end.
  const pct = Math.min(Math.max((avg + 2) / 4, 0), 1) * 100;
  const advancing = sectors.filter(s => s.changePercent > 0).length;

  return (
    <div className="card">
      <div className="flex items-start justify-between mb-4">
        <div className="stat-label">Market Sentiment</div>
        <span className={pill}>{label}</span>
      </div>

      <div className={`flex items-center gap-2 mb-1 ${color}`}>
        <Icon size={22} />
        <span className="text-2xl font-bold">{label}</span>
      </div>
      <div className="text-2xs text-gray-500 mb-5">
        Across <span className="font-mono tabular-nums">{sectors.length}</span> sectors ·{' '}
        <span className="font-mono tabular-nums text-bull">{advancing}</span> advancing,{' '}
        <span className="font-mono tabular-nums text-bear">{sectors.length - advancing}</span> declining
      </div>

      <div className="relative">
        <div className="w-full h-2 rounded-full bg-gradient-to-r from-bear via-neutral to-bull opacity-80" />
        <div
          className="absolute -top-1 w-1 h-4 bg-ink rounded-full ring-2 ring-surface-card"
          style={{ left: `${pct}%`, transform: 'translateX(-50%)' }}
          aria-hidden
        />
      </div>
      <div className="flex justify-between w-full mt-2.5 text-2xs text-gray-600 font-mono tabular-nums">
        <span>-2%</span><span>0%</span><span>+2%</span>
      </div>

      <div className="mt-4 pt-3 border-t border-surface-border flex items-baseline justify-between">
        <span className="stat-label">Avg sector move</span>
        <span className={`text-lg font-mono tabular-nums font-bold ${color}`}>{fmtPct(avg)}</span>
      </div>
    </div>
  );
}

/* Sector row — uniform surface, direction via colour, magnitude via a proportional bar. */
function SectorRow({ sector, changePercent, max }: { sector: string; changePercent: number; max: number }) {
  const pos = changePercent >= 0;
  const width = max > 0 ? Math.min(Math.abs(changePercent) / max, 1) * 100 : 0;
  return (
    <div className="flex items-center gap-3">
      <span className="text-xs text-gray-300 truncate flex-1 min-w-0" title={sector}>{sector}</span>
      <div className="w-16 h-1.5 rounded-full bg-surface-hover overflow-hidden shrink-0">
        <div className={`h-full rounded-full ${pos ? 'bg-bull' : 'bg-bear'}`} style={{ width: `${width}%` }} />
      </div>
      <span className={`text-xs font-mono tabular-nums font-semibold w-16 text-right shrink-0 ${pos ? 'text-bull' : 'text-bear'}`}>
        {fmtPct(changePercent)}
      </span>
    </div>
  );
}

export function Tab1MarketTrends() {
  const [overview, setOverview] = useState<MarketOverview | null>(null);
  const [loading, setLoading] = useState(true);

  // This is the app's landing tab. The initial fetch had no .catch at all, so a failure was an
  // unhandled rejection and the page rendered an empty ticker and no sectors with nothing said.
  const [failed, setFailed] = useState(false);
  const load = useCallback(() => {
    marketApi.getOverview()
      .then(r => { setOverview(r.data); setFailed(false); })
      .catch(() => setFailed(true))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 60_000);
    return () => clearInterval(t);
  }, [load]);

  if (loading) return (
    <div className="space-y-4">
      <div className="card animate-pulse h-20 bg-surface-hover" />
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {[...Array(3)].map((_, i) => <div key={i} className="card animate-pulse h-56 bg-surface-hover" />)}
      </div>
    </div>
  );

  const sectors = overview?.sectors ?? [];
  const sorted = [...sectors].sort((a, b) => b.changePercent - a.changePercent);
  const topGain = sorted.slice(0, 5);
  const topLose = [...sorted].reverse().slice(0, 5);
  const maxAbs = sectors.reduce((m, s) => Math.max(m, Math.abs(s.changePercent)), 0);

  return (
    <div className="space-y-4">
      {failed && <LoadFailure what="live market data" onRetry={load} />}
      <TickerRibbon indices={[
        { label: 'NIFTY 50', index: overview?.nifty50 },
        { label: 'BANK NIFTY', index: overview?.bankNifty },
        { label: 'SENSEX', index: overview?.sensex },
        { label: 'NIFTY MIDCAP', index: overview?.niftyMidcap },
      ]} />

      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <Activity size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-ink mb-0.5">Market Trends</h2>
          <p className="text-gray-500 text-sm">Where the market is leaning today, and which sectors are driving it</p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <SentimentMeter sectors={sectors} />

        <div className="card">
          <h3 className="section-title"><TrendingUp size={15} className="text-bull" /> Leading Sectors</h3>
          <div className="space-y-2.5">
            {topGain.map(s => <SectorRow key={s.sector} sector={s.sector} changePercent={s.changePercent} max={maxAbs} />)}
            {topGain.length === 0 && <p className="text-gray-600 text-xs text-center py-6">No data</p>}
          </div>
        </div>

        <div className="card">
          <h3 className="section-title"><TrendingDown size={15} className="text-bear" /> Lagging Sectors</h3>
          <div className="space-y-2.5">
            {topLose.map(s => <SectorRow key={s.sector} sector={s.sector} changePercent={s.changePercent} max={maxAbs} />)}
            {topLose.length === 0 && <p className="text-gray-600 text-xs text-center py-6">No data</p>}
          </div>
        </div>
      </div>

      {sectors.length > 0 && (
        <div className="card">
          <h3 className="section-title">All Sectors</h3>
          {/* Every card sits on the same neutral surface. Direction is carried by a
              high-contrast pill, not by tinting the container — the previous per-card
              red/green washes read as muddy blocks and hurt figure legibility. */}
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-2.5">
            {sorted.map(s => {
              const pos = s.changePercent >= 0;
              return (
                <div key={s.sector}
                     className="rounded-xl border border-surface-border bg-surface-hover/40 p-3 hover:bg-surface-hover transition-colors">
                  <div className="text-2xs text-gray-400 truncate mb-1.5" title={s.sector}>{s.sector}</div>
                  <span className={pos ? 'pill-bull' : 'pill-bear'}>
                    <span className="font-mono tabular-nums">{fmtPct(s.changePercent)}</span>
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
