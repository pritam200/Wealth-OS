import { useEffect, useState } from 'react';
import { Loader2, Info } from 'lucide-react';
import { marketApi } from '../../api/market';
import type { PriceHistory, TechnicalAnalysis } from '../../types';
import { CHART } from '../../theme/chartTheme';

const fmt = (n: number) => new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(n);

// 6-month candlestick (hand-drawn SVG for reliable scaling) + a plain-English read of the
// canonical technical values. Nothing is recomputed here: volatility, range position and
// level distances all come from the backend's technical read.
export function StockChart({ symbol, support, resistance, tech }: {
  symbol: string; support?: number | null; resistance?: number | null; tech?: TechnicalAnalysis | null;
}) {
  const [data, setData] = useState<PriceHistory[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    const to = new Date();
    const from = new Date(); from.setMonth(from.getMonth() - 6);
    const iso = (d: Date) => d.toISOString().slice(0, 10);
    marketApi.getHistory(symbol, iso(from), iso(to))
      .then(r => { if (alive) setData(r.data || []); })
      .catch(() => { if (alive) setData([]); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [symbol]);

  if (loading) return <div className="h-48 flex items-center justify-center text-gray-500"><Loader2 size={15} className="animate-spin mr-2" /> Loading price history…</div>;
  if (data.length < 5) return <div className="h-24 flex items-center justify-center text-gray-600 text-xs">No price history available for this symbol.</div>;

  // ── scales ──
  const W = 700, H = 220, padL = 46, padR = 10, padT = 10, padB = 22;
  const plotW = W - padL - padR, plotH = H - padT - padB;
  const lows = data.map(d => d.low), highs = data.map(d => d.high), closes = data.map(d => d.close);
  const min = Math.min(...lows), max = Math.max(...highs);
  const span = max - min || 1;
  const n = data.length;
  const slot = plotW / n;
  const bodyW = Math.max(1.2, Math.min(6, slot * 0.62));
  const x = (i: number) => padL + slot * i + slot / 2;
  const y = (p: number) => padT + (max - p) / span * plotH;

  const last = closes[n - 1];

  const gridVals = [max, (max + min) / 2, min];

  const insights: string[] = [];
  if (tech?.trendAssessment) {
    const t = tech.trendAssessment;
    insights.push(`Trend: ${t.label.replace(/_/g, ' ').toLowerCase()} (${t.bullishVotes} up / ${t.bearishVotes} down of ${t.votesAvailable} votes) — descriptive, not a forecast.`);
  }
  if (tech?.rangePosition52wPct != null && tech.high52w != null && tech.low52w != null) {
    insights.push(`At ${tech.rangePosition52wPct.toFixed(0)}% of its 52-week range (₹${fmt(tech.low52w)} – ₹${fmt(tech.high52w)}).`);
  }
  if (tech?.annualizedVolatilityPct != null && tech.dailyVolatilityPct != null) {
    insights.push(`Volatility ${tech.dailyVolatilityPct.toFixed(2)}% a day (${tech.annualizedVolatilityPct.toFixed(0)}% annualised, last ${tech.volatilityBars ?? 120} sessions).`);
  }
  const ns = tech?.levels?.nearestSupport, nr = tech?.levels?.nearestResistance;
  if (ns) insights.push(`Nearest support ₹${fmt(ns.price)} (${ns.distancePct.toFixed(1)}%, ${ns.source.replace(/_/g, ' ').toLowerCase()}).`);
  else if (tech?.levels) insights.push('No reliable support level below the current price.');
  if (nr) insights.push(`Nearest resistance ₹${fmt(nr.price)} (+${nr.distancePct.toFixed(1)}%, ${nr.source.replace(/_/g, ' ').toLowerCase()}).`);
  else if (tech?.levels) insights.push('No reliable resistance level above the current price.');

  return (
    <div>
      <svg width="100%" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${symbol} 6-month candlestick chart`} style={{ display: 'block' }}>
        {gridVals.map((gv, i) => (
          <g key={i}>
            <line x1={padL} y1={y(gv)} x2={W - padR} y2={y(gv)} stroke={CHART.grid} strokeDasharray="3 3" />
            <text x={padL - 6} y={y(gv) + 3} textAnchor="end" fontSize="9" fill={CHART.text} fontFamily="monospace">{fmt(gv)}</text>
          </g>
        ))}
        {support && support > min && support < max ? (
          <line x1={padL} y1={y(support)} x2={W - padR} y2={y(support)} stroke={CHART.bull} strokeDasharray="4 3" strokeOpacity={0.8} />
        ) : null}
        {resistance && resistance > min && resistance < max ? (
          <line x1={padL} y1={y(resistance)} x2={W - padR} y2={y(resistance)} stroke={CHART.bear} strokeDasharray="4 3" strokeOpacity={0.8} />
        ) : null}
        {data.map((d, i) => {
          const up = d.close >= d.open;
          const col = up ? CHART.bull : CHART.bear;
          const yO = y(d.open), yC = y(d.close);
          const top = Math.min(yO, yC), h = Math.max(1, Math.abs(yC - yO));
          return (
            <g key={i}>
              <line x1={x(i)} y1={y(d.high)} x2={x(i)} y2={y(d.low)} stroke={col} strokeWidth={0.8} />
              <rect x={x(i) - bodyW / 2} y={top} width={bodyW} height={h} fill={col} />
            </g>
          );
        })}
        <text x={W - padR} y={y(last) - 4} textAnchor="end" fontSize="9" fill={CHART.ink} fontFamily="monospace">now ₹{fmt(last)}</text>
      </svg>
      <div className="flex justify-between text-2xs text-gray-500 mt-1 px-1">
        <span>6-month daily candles</span>
        <span className="flex gap-3">
          <span className="text-bull">■ up</span><span className="text-bear">■ down</span>
          {support ? <span className="text-bull">– – support</span> : null}
          {resistance ? <span className="text-bear">– – resistance</span> : null}
        </span>
      </div>
      {insights.length > 0 && <div className="mt-2.5 bg-surface-hover rounded-lg p-2.5">
        <div className="flex items-center gap-1.5 text-2xs text-brand mb-1"><Info size={11} /> What the chart shows</div>
        <ul className="space-y-1">
          {insights.map((t, i) => <li key={i} className="text-2xs text-gray-400 flex gap-1.5"><span className="text-gray-600">›</span>{t}</li>)}
        </ul>
      </div>}
    </div>
  );
}
