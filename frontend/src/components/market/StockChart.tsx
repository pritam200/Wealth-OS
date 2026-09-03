import { useEffect, useState } from 'react';
import { Loader2, Info } from 'lucide-react';
import { marketApi } from '../../api/market';
import type { PriceHistory } from '../../types';

const fmt = (n: number) => new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(n || 0);

// 6-month candlestick (hand-drawn SVG for reliable scaling) + a plain-English read
// of what the chart shows: trend, recent move, range position and volatility.
export function StockChart({ symbol, support, resistance }: { symbol: string; support?: number | null; resistance?: number | null }) {
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

  const first = closes[0], last = closes[n - 1];
  const chg6m = (last - first) / first * 100;
  const i1m = Math.max(0, n - 22);
  const chg1m = (last - closes[i1m]) / closes[i1m] * 100;
  const rangePos = (last - min) / span * 100;
  const rets = closes.slice(1).map((c, i) => c / closes[i] - 1);
  const mean = rets.reduce((s, r) => s + r, 0) / (rets.length || 1);
  const vol = Math.sqrt(rets.reduce((s, r) => s + (r - mean) ** 2, 0) / (rets.length || 1)) * Math.sqrt(252) * 100;

  const gridVals = [max, (max + min) / 2, min];

  const insights: string[] = [];
  insights.push(`Over 6 months the price is ${chg6m >= 0 ? 'up' : 'down'} ${Math.abs(chg6m).toFixed(1)}% and ${chg1m >= 0 ? 'up' : 'down'} ${Math.abs(chg1m).toFixed(1)}% in the last month.`);
  insights.push(rangePos >= 75 ? `Trading near its 6-month high (${rangePos.toFixed(0)}% of range) — strength, but watch for resistance.`
    : rangePos <= 25 ? `Trading near its 6-month low (${rangePos.toFixed(0)}% of range) — weak, or a possible value zone.`
    : `Mid-range (${rangePos.toFixed(0)}% of the 6-month band) — no extreme.`);
  insights.push(`Annualised volatility ≈ ${vol.toFixed(0)}% — ${vol > 45 ? 'high (large swings; size positions carefully)' : vol > 25 ? 'moderate' : 'relatively calm'}.`);
  if (support && last <= support * 1.03) insights.push(`Price is testing support near ₹${fmt(support)} — a bounce or breakdown level to watch.`);
  if (resistance && last >= resistance * 0.97) insights.push(`Price is pushing resistance near ₹${fmt(resistance)} — a breakout above it would be bullish.`);

  return (
    <div>
      <svg width="100%" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${symbol} 6-month candlestick chart`} style={{ display: 'block' }}>
        {gridVals.map((gv, i) => (
          <g key={i}>
            <line x1={padL} y1={y(gv)} x2={W - padR} y2={y(gv)} stroke="#232c3f" strokeDasharray="3 3" />
            <text x={padL - 6} y={y(gv) + 3} textAnchor="end" fontSize="9" fill="#8e99ab" fontFamily="monospace">{fmt(gv)}</text>
          </g>
        ))}
        {support && support > min && support < max ? (
          <line x1={padL} y1={y(support)} x2={W - padR} y2={y(support)} stroke="#1ecb8b" strokeDasharray="4 3" strokeOpacity={0.7} />
        ) : null}
        {resistance && resistance > min && resistance < max ? (
          <line x1={padL} y1={y(resistance)} x2={W - padR} y2={y(resistance)} stroke="#f04b5a" strokeDasharray="4 3" strokeOpacity={0.7} />
        ) : null}
        {data.map((d, i) => {
          const up = d.close >= d.open;
          const col = up ? '#1ecb8b' : '#f04b5a';
          const yO = y(d.open), yC = y(d.close);
          const top = Math.min(yO, yC), h = Math.max(1, Math.abs(yC - yO));
          return (
            <g key={i}>
              <line x1={x(i)} y1={y(d.high)} x2={x(i)} y2={y(d.low)} stroke={col} strokeWidth={0.8} />
              <rect x={x(i) - bodyW / 2} y={top} width={bodyW} height={h} fill={col} />
            </g>
          );
        })}
        <text x={W - padR} y={y(last) - 4} textAnchor="end" fontSize="9" fill="#e2e6ee" fontFamily="monospace">now ₹{fmt(last)}</text>
      </svg>
      <div className="flex justify-between text-2xs text-gray-500 mt-1 px-1">
        <span>6-month daily candles</span>
        <span className="flex gap-3">
          <span className="text-bull">■ up</span><span className="text-bear">■ down</span>
          {support ? <span className="text-bull">– – support</span> : null}
          {resistance ? <span className="text-bear">– – resistance</span> : null}
        </span>
      </div>
      <div className="mt-2.5 bg-surface-hover rounded-lg p-2.5">
        <div className="flex items-center gap-1.5 text-2xs text-brand mb-1"><Info size={11} /> What the chart shows</div>
        <ul className="space-y-1">
          {insights.map((t, i) => <li key={i} className="text-2xs text-gray-400 flex gap-1.5"><span className="text-gray-600">›</span>{t}</li>)}
        </ul>
      </div>
    </div>
  );
}
