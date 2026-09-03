import { useState, useEffect, useCallback } from 'react';
import { AiCopilot } from '../../components/ai/AiCopilot';
import {
  TrendingUp, TrendingDown, Minus, Search, RefreshCw, Loader2, Info, Radar, HelpCircle,
} from 'lucide-react';
import { forecastApi } from '../../api/forecast';
import type { ForecastResponse, ForecastScenario } from '../../api/forecast';
import { marketApi } from '../../api/market';

interface StockHit { symbol: string; name: string }

const fmt = (n: number) => new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(n || 0);
const fmtPct = (n: number) => `${n >= 0 ? '+' : ''}${(n || 0).toFixed(2)}%`;

const HORIZONS = ['1W', '2W', '4W'] as const;

const SCN_STYLE: Record<string, { color: string; border: string; bg: string; bar: string; Icon: any }> = {
  Bull: { color: 'text-bull', border: 'border-bull/40', bg: 'bg-bull/5', bar: 'bg-bull', Icon: TrendingUp },
  Base: { color: 'text-gray-300', border: 'border-gray-600/40', bg: 'bg-surface-hover', bar: 'bg-gray-500', Icon: Minus },
  Bear: { color: 'text-bear', border: 'border-bear/40', bg: 'bg-bear/5', bar: 'bg-bear', Icon: TrendingDown },
};

const TREND_COLOR: Record<string, string> = {
  STRONG_UPTREND: 'text-bull', UPTREND: 'text-bull', SIDEWAYS: 'text-gray-400',
  DOWNTREND: 'text-bear', STRONG_DOWNTREND: 'text-bear',
};

export function Tab3MarketForecast() {
  const [indices, setIndices] = useState<Record<string, string>>({});
  const [symbol, setSymbol] = useState('NIFTY50');
  const [horizon, setHorizon] = useState<typeof HORIZONS[number]>('2W');
  const [data, setData] = useState<ForecastResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [stockInput, setStockInput] = useState('');
  const [suggestions, setSuggestions] = useState<StockHit[]>([]);
  const [showSug, setShowSug] = useState(false);

  useEffect(() => { forecastApi.indices().then(r => setIndices(r.data)).catch(() => {}); }, []);

  // Debounced autocomplete from the stock search API
  useEffect(() => {
    const q = stockInput.trim();
    if (q.length < 1) { setSuggestions([]); return; }
    const t = setTimeout(() => {
      marketApi.search(q).then(r => { setSuggestions((r.data as StockHit[]).slice(0, 8)); setShowSug(true); }).catch(() => {});
    }, 250);
    return () => clearTimeout(t);
  }, [stockInput]);

  const pick = (hit: StockHit) => { setStockInput(hit.symbol); setSymbol(hit.symbol); setShowSug(false); };

  const run = useCallback(async (sym: string, name?: string) => {
    setLoading(true); setError('');
    try {
      const { data } = await forecastApi.get(sym, horizon, name);
      setData(data);
    } catch {
      setError(`Couldn't build a forecast for ${sym}. Check the symbol (e.g. TCS, RELIANCE) and try again.`);
      setData(null);
    } finally { setLoading(false); }
  }, [horizon]);

  useEffect(() => { run(symbol); }, [symbol, horizon, run]);

  const submitStock = () => {
    const s = stockInput.trim().toUpperCase();
    if (!s) return;
    setSymbol(s);
  };

  // No scenarios (or an explicit INSUFFICIENT_DATA signal) means the backend measured no
  // volatility to project from. Everything below — the range map, the reference levels — would
  // otherwise render zeros as if they were real numbers.
  const noForecast = !!data && (data.signal === 'INSUFFICIENT_DATA' || !data.scenarios?.length);
  const scenarios = data?.scenarios ?? [];
  const rangeMin = scenarios.length ? Math.min(...scenarios.map(s => s.low)) : 0;
  const rangeMax = scenarios.length ? Math.max(...scenarios.map(s => s.high)) : 0;
  const pct = (v: number) => rangeMax > rangeMin ? `${((v - rangeMin) / (rangeMax - rangeMin)) * 100}%` : '50%';
  const bandHigh = (label: string) => scenarios.find(s => s.label === label)?.high ?? rangeMin;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#00c2ff] to-[#6d5efc] flex items-center justify-center text-white shadow-lift shrink-0">
          <Radar size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-0.5">Market Forecast</h2>
          <p className="text-gray-500 text-xs">Volatility-based projections grounded in real price history — any index or stock</p>
        </div>
      </div>

      {/* Controls */}
      <div className="card space-y-3">
        <div>
          <div className="stat-label text-2xs mb-1.5">Index</div>
          <div className="flex flex-wrap gap-1.5">
            {Object.entries(indices).map(([code, name]) => (
              <button key={code} onClick={() => setSymbol(code)}
                className={`text-xs px-3 py-1.5 rounded-lg border transition-colors ${
                  symbol === code ? 'bg-brand/15 border-brand/50 text-brand' : 'border-surface-border text-gray-400 hover:text-white'
                }`}>
                {name}
              </button>
            ))}
          </div>
        </div>

        <div className="flex flex-wrap items-end gap-3">
          <div className="flex-1 min-w-[200px]">
            <div className="stat-label text-2xs mb-1.5">Or forecast a single stock</div>
            <div className="flex gap-2">
              <div className="relative flex-1">
                <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-600" />
                <input value={stockInput} onChange={e => setStockInput(e.target.value)}
                  onFocus={() => suggestions.length && setShowSug(true)}
                  onBlur={() => setTimeout(() => setShowSug(false), 150)}
                  onKeyDown={e => e.key === 'Enter' && submitStock()}
                  placeholder="e.g. TCS, RELIANCE, HDFCBANK"
                  className="input-field text-xs pl-8 w-full uppercase" />
                {showSug && suggestions.length > 0 && (
                  <div className="absolute z-20 left-0 right-0 mt-1 bg-surface-card border border-surface-border rounded-lg shadow-xl max-h-56 overflow-y-auto">
                    {suggestions.map(s => (
                      <button key={s.symbol} onMouseDown={() => pick(s)}
                        className="w-full text-left px-3 py-1.5 hover:bg-surface-hover flex items-center justify-between">
                        <span className="text-white text-xs font-mono font-medium">{s.symbol}</span>
                        <span className="text-2xs text-gray-500 truncate max-w-[160px] ml-2">{s.name}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <button onClick={submitStock} className="btn-primary text-xs px-4">Forecast</button>
            </div>
          </div>

          <div>
            <div className="stat-label text-2xs mb-1.5">Horizon</div>
            <div className="flex gap-1">
              {HORIZONS.map(h => (
                <button key={h} onClick={() => setHorizon(h)}
                  className={`text-xs px-3 py-1.5 rounded-lg border ${
                    horizon === h ? 'bg-brand/15 border-brand/50 text-brand' : 'border-surface-border text-gray-400 hover:text-white'
                  }`}>{h}</button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {loading ? (
        <div className="card h-40 flex items-center justify-center text-gray-500"><Loader2 className="animate-spin mr-2" size={16} /> Analysing price history…</div>
      ) : error ? (
        <div className="card text-center py-8 text-bear text-sm">{error}</div>
      ) : noForecast && data ? (
        <div className="card">
          <div className="flex flex-wrap items-start justify-between gap-3 mb-3">
            <div>
              <div className="text-white font-semibold text-lg">{data.displayName}</div>
              <span className="inline-flex items-center gap-1 mt-1 text-2xs px-2 py-0.5 rounded-full border border-dashed border-gray-700 text-gray-500">
                <HelpCircle size={11} /> Insufficient data
              </span>
            </div>
            <button onClick={() => run(symbol)} className="btn-icon"><RefreshCw size={13} /></button>
          </div>
          <p className="text-xs text-gray-400 leading-relaxed">{data.basis}</p>
          <p className="text-2xs text-gray-700 mt-2">
            No projection range is shown because volatility could not be measured — an
            empty or zeroed band would look like a real forecast.
          </p>
        </div>
      ) : data ? (
        <>
          {/* Summary header. Deliberately no BUY/SELL/HOLD chip: `data.signal` is the raw
              pass-through technical signal, not the centralized RecommendationEngine verdict,
              so showing it here contradicted the Analyst panel / AI Advisor / holdings badge
              for the same symbol. This page's job is projection ranges, not a verdict — the
              verdict lives on the stock page and the analyst panel, from one engine. */}
          <div className="card">
            <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
              <div>
                <div className="text-white font-semibold text-lg">{data.displayName}</div>
                <div className="flex items-center gap-2 mt-0.5">
                  <span className="text-2xl font-mono font-bold text-white">{fmt(data.currentPrice)}</span>
                  <span className={`text-xs font-semibold ${TREND_COLOR[data.trend] ?? 'text-gray-400'}`}>{data.trend?.replace('_', ' ')}</span>
                </div>
              </div>
              <button onClick={() => run(symbol)} className="btn-icon"><RefreshCw size={13} /></button>
            </div>

            {/* Price range map */}
            <div className="relative h-8 rounded-lg overflow-hidden flex mb-1">
              <div className="h-full bg-bear/20" style={{ width: pct(bandHigh('Bear')) }} />
              <div className="h-full bg-gray-700/40" style={{ width: `calc(${pct(bandHigh('Base'))} - ${pct(bandHigh('Bear'))})` }} />
              <div className="h-full bg-bull/20" style={{ flex: 1 }} />
              <div className="absolute w-0.5 h-full bg-white" style={{ left: pct(data.currentPrice) }} />
            </div>
            <div className="relative h-5 text-2xs font-mono text-gray-500">
              {/* rangeMin/rangeMax sit exactly at 0%/100% by definition, so they're
                  left/right-anchored (not centered) to stay inside the bar */}
              <span className="absolute left-0 text-left text-bear">{fmt(rangeMin)}</span>
              <span className="absolute -translate-x-1/2 text-white" style={{ left: pct(data.currentPrice) }}>now</span>
              <span className="absolute right-0 text-right text-bull">{fmt(rangeMax)}</span>
            </div>
          </div>

          {/* Scenario cards */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            {data.scenarios.map(s => <ScenarioCard key={s.label} s={s} />)}
          </div>

          {/* Reference levels */}
          <div className="card">
            <h3 className="text-white font-semibold text-sm mb-3">What this forecast is based on</h3>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-2 mb-3">
              {[
                ['Expected move (1σ)', `± ${fmt(data.expectedMove)}`],
                ['14-day ATR', fmt(data.atr)],
                ['RSI', data.rsi.toFixed(0)],
                ['Data points', String(data.dataPoints)],
                ['SMA 50', fmt(data.sma50)],
                ['SMA 200', fmt(data.sma200)],
                ['Support', fmt(data.support)],
                ['Resistance', fmt(data.resistance)],
              ].map(([l, v]) => (
                <div key={l} className="bg-surface-hover rounded p-2">
                  <div className="stat-label text-2xs">{l}</div>
                  <div className="font-mono text-white text-xs font-semibold">{v}</div>
                </div>
              ))}
            </div>
            <p className="text-2xs text-gray-500 flex gap-1.5"><Info size={11} className="text-brand shrink-0 mt-0.5" />{data.basis}</p>
            <p className="text-2xs text-gray-700 mt-1.5">Informational only — not investment advice. This app never places real trades.</p>
          </div>
        </>
      ) : null}

      <AiCopilot />
    </div>
  );
}

function ScenarioCard({ s }: { s: ForecastScenario }) {
  const st = SCN_STYLE[s.label] ?? SCN_STYLE.Base;
  const Icon = st.Icon;
  return (
    <div className={`card border ${st.border} ${st.bg} flex flex-col gap-3`}>
      <div className={`flex items-center justify-between ${st.color}`}>
        <div className="flex items-center gap-2"><Icon size={16} /><span className="font-semibold text-sm">{s.label} Case</span></div>
        <span className="font-bold text-sm">{s.probability}%</span>
      </div>
      <div className="h-2 rounded-full bg-surface-border overflow-hidden">
        <div className={`h-full rounded-full ${st.bar}`} style={{ width: `${s.probability}%` }} />
      </div>
      <div>
        <div className="text-gray-500 text-2xs mb-1">Target range</div>
        <div className="font-mono text-white font-semibold text-sm">{fmt(s.low)} – {fmt(s.high)}</div>
      </div>
      <div className="bg-surface-border/40 rounded p-2">
        <div className="text-gray-500 text-2xs mb-0.5">Expected move</div>
        <div className={`font-mono font-bold text-xs ${st.color}`}>{fmtPct(s.movePctLow)} to {fmtPct(s.movePctHigh)}</div>
      </div>
    </div>
  );
}
