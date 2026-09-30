import { useState, useEffect, useCallback } from 'react';
import { AiCopilot } from '../../components/ai/AiCopilot';
import {
  TrendingUp, TrendingDown, Minus, Search, RefreshCw, Loader2, Info, Radar, HelpCircle, AlertTriangle,
} from 'lucide-react';
import { forecastApi, FORECAST_HORIZONS } from '../../api/forecast';
import type { ForecastResponse, ForecastScenario, CurvePoint, DataIssue, ForecastHorizon } from '../../api/forecast';
import { marketApi } from '../../api/market';
import { ResearchSection } from '../../components/research/ResearchPanel';

interface StockHit { symbol: string; name: string }

const fmt = (n: number | null | undefined) => n == null ? '—' : new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(n);
const fmtPct = (n: number) => `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`;
const pctOrDash = (n: number | null | undefined) => n == null ? '—' : `${(n * 100).toFixed(0)}%`;

const HORIZONS = FORECAST_HORIZONS;

const STATUS_LABEL: Record<string, string> = {
  INSUFFICIENT_DATA: 'Insufficient data', STALE_DATA: 'Stale data', DATA_QUALITY_WARNING: 'Data-quality warning',
};

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
  const [horizon, setHorizon] = useState<ForecastHorizon>('20D');
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

  // STALE/INSUFFICIENT (or no scenarios) means there is nothing honest to draw: no range, no zeros.
  const noForecast = !!data && (data.status === 'INSUFFICIENT_DATA' || data.status === 'STALE_DATA' || !data.scenarios?.length);
  const scenarios = data?.scenarios ?? [];
  const rangeMin = scenarios.length ? Math.min(...scenarios.map(s => s.low)) : 0;
  const rangeMax = scenarios.length ? Math.max(...scenarios.map(s => s.high)) : 0;
  const pct = (v: number) => rangeMax > rangeMin ? `${((v - rangeMin) / (rangeMax - rangeMin)) * 100}%` : '50%';
  const bandHigh = (label: string) => scenarios.find(s => s.label === label)?.high ?? rangeMin;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
          <Radar size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-ink mb-0.5">Market Forecast</h2>
          <p className="text-gray-500 text-xs">Model-estimated price ranges from measured volatility, with their historical track record</p>
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
                  symbol === code ? 'bg-brand/15 border-brand/50 text-brand' : 'border-surface-border text-gray-400 hover:text-ink'
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
                        <span className="text-ink text-xs font-mono font-medium">{s.symbol}</span>
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
                    horizon === h ? 'bg-brand/15 border-brand/50 text-brand' : 'border-surface-border text-gray-400 hover:text-ink'
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
              <div className="text-ink font-semibold text-lg">{data.displayName}</div>
              <span className="inline-flex items-center gap-1 mt-1 text-2xs px-2 py-0.5 rounded-full border border-dashed border-gray-700 text-gray-500">
                <HelpCircle size={11} /> {STATUS_LABEL[data.status] ?? data.status}
              </span>
            </div>
            <button aria-label="Refresh" onClick={() => run(symbol)} className="btn-icon"><RefreshCw size={13} /></button>
          </div>
          <p className="text-xs text-gray-400 leading-relaxed">{data.statusReason ?? data.basis}</p>
          <p className="text-2xs text-gray-600 mt-2">
            No range is shown: a band built from missing or out-of-date prices would look like a real estimate.
          </p>
          <Issues issues={data.dataIssues} />
        </div>
      ) : data ? (
        <>
          {/* No BUY/SELL chip and no trend-derived odds: this page shows a volatility range and how
              that range has held up historically on this instrument — nothing else. */}
          <div className="card">
            <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
              <div>
                <div className="text-ink font-semibold text-lg">{data.displayName}</div>
                <div className="flex items-center gap-2 mt-0.5">
                  <span className="text-2xl font-mono font-bold text-ink">{fmt(data.currentPrice)}</span>
                  {data.trend && (
                    <span className={`text-xs font-semibold ${TREND_COLOR[data.trend] ?? 'text-gray-400'}`}>{data.trend.replace(/_/g, ' ')}</span>
                  )}
                </div>
                <div className="text-2xs text-gray-500 mt-0.5">
                  Close of {data.priceDate ?? '—'} · {data.source ?? 'unknown source'} · {data.dataPoints} daily bars since {data.firstBarDate ?? '—'}
                </div>
                {data.status === 'DATA_QUALITY_WARNING' && (
                  <div className="text-2xs text-amber-400 mt-1 flex items-center gap-1"><AlertTriangle size={11} /> {data.statusReason ?? 'Data-quality warning — see details below.'}</div>
                )}
              </div>
              <button aria-label="Refresh" onClick={() => run(symbol)} className="btn-icon"><RefreshCw size={13} /></button>
            </div>

            <div className="text-2xs text-gray-500 mb-1.5">
              Model-estimated range for the next {data.tradingDays} trading session{data.tradingDays === 1 ? '' : 's'} (to around {data.horizonEndsAround ?? '—'})
            </div>
            <div className="relative h-8 rounded-lg overflow-hidden flex mb-1">
              <div className="h-full bg-bear/20" style={{ width: pct(bandHigh('Bear')) }} />
              <div className="h-full bg-gray-700/40" style={{ width: `calc(${pct(bandHigh('Base'))} - ${pct(bandHigh('Bear'))})` }} />
              <div className="h-full bg-bull/20" style={{ flex: 1 }} />
              {data.currentPrice != null && <div className="absolute w-0.5 h-full bg-ink" style={{ left: pct(data.currentPrice) }} />}
            </div>
            <div className="relative h-5 text-2xs font-mono text-gray-500">
              <span className="absolute left-0 text-left text-bear">{fmt(rangeMin)}</span>
              {data.currentPrice != null && <span className="absolute -translate-x-1/2 text-ink" style={{ left: pct(data.currentPrice) }}>now</span>}
              <span className="absolute right-0 text-right text-bull">{fmt(rangeMax)}</span>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mt-3">
              {[data.range50, data.range90].map(r => r && (
                <div key={r.nominalCoverage} className="bg-surface-hover rounded p-2">
                  <div className="stat-label text-2xs">{Math.round(r.nominalCoverage * 100)}% model range</div>
                  <div className="font-mono text-ink text-xs font-semibold">{fmt(r.low)} – {fmt(r.high)}</div>
                  <div className="text-2xs text-gray-500 mt-0.5">
                    {r.historicalCoverage != null
                      ? `Held in ${(r.historicalCoverage * 100).toFixed(1)}% of past windows`
                      : 'Historical coverage not measured'}
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            {[...scenarios].reverse().map(s => <ScenarioCard key={s.label} s={s} empirical={data.probabilityStatus === 'EMPIRICAL'} />)}
          </div>
          <p className="text-2xs text-gray-500 -mt-1 flex gap-1.5">
            <Info size={11} className="text-brand shrink-0 mt-0.5" />
            {data.probabilityStatus === 'EMPIRICAL'
              ? data.probabilityNote
              : 'Probability unavailable — too few independent past windows to measure how often each band occurred.'}
          </p>

          <div className="card">
            <h3 className="text-ink font-semibold text-sm mb-3">How reliable this range has been</h3>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-2 mb-3">
              {[
                ['Daily volatility', data.volatility ? `${data.volatility.dailyPct.toFixed(2)}%` : '—'],
                ['Annualised volatility', data.volatility ? `${data.volatility.annualizedPct.toFixed(1)}%` : '—'],
                [`${data.horizon} volatility (1σ)`, data.volatility ? `±${data.volatility.horizonPct.toFixed(2)}%` : '—'],
                ['Calibration', data.calibration?.label?.replace(/_/g, ' ').toLowerCase() ?? 'unmeasured'],
                ['Past windows ending higher', pctOrDash(data.directional?.historicalUpFrequency)],
                ['SMA50 direction call hit rate', pctOrDash(data.directional?.movingAverageBaselineHitRate)],
                ['Typical move error (MAE)', data.pointErrors?.maeRandomWalkPct != null ? `${data.pointErrors.maeRandomWalkPct.toFixed(2)}%` : '—'],
                ['Independent windows', data.calibration ? String(data.calibration.effectiveSample) : '—'],
              ].map(([l, v]) => (
                <div key={l} className="bg-surface-hover rounded p-2">
                  <div className="stat-label text-2xs">{l}</div>
                  <div className="font-mono text-ink text-xs font-semibold capitalize">{v}</div>
                </div>
              ))}
            </div>
            {data.calibration?.summary && <p className="text-2xs text-gray-400 mb-1">{data.calibration.summary}</p>}
            {data.directional?.summary && <p className="text-2xs text-gray-400 mb-1">{data.directional.summary}</p>}
            {data.pointErrors?.summary && <p className="text-2xs text-gray-400 mb-2">{data.pointErrors.summary}</p>}
            {data.calibration?.curve?.length ? <CalibrationCurve curve={data.calibration.curve} /> : null}
          </div>

          <div className="card">
            <h3 className="text-ink font-semibold text-sm mb-2">Context (not inputs to the range)</h3>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-2 mb-3">
              {[
                ['RSI (14)', data.rsi != null ? data.rsi.toFixed(1) : '—'],
                ['SMA 50', fmt(data.sma50)],
                ['SMA 200', fmt(data.sma200)],
                ['Nearest support', data.support != null ? fmt(data.support) : 'No reliable level'],
                ['Nearest resistance', data.resistance != null ? fmt(data.resistance) : 'No reliable level'],
              ].map(([l, v]) => (
                <div key={l} className="bg-surface-hover rounded p-2">
                  <div className="stat-label text-2xs">{l}</div>
                  <div className="font-mono text-ink text-xs font-semibold">{v}</div>
                </div>
              ))}
            </div>
            {!!data.keyDrivers?.length && (
              <ul className="text-2xs text-gray-400 space-y-0.5 mb-2 list-disc pl-4">{data.keyDrivers.map(d => <li key={d}>{d}</li>)}</ul>
            )}
            {!!data.keyRisks?.length && (
              <ul className="text-2xs text-amber-400/80 space-y-0.5 mb-2 list-disc pl-4">{data.keyRisks.map(d => <li key={d}>{d}</li>)}</ul>
            )}
            <details className="text-2xs text-gray-500">
              <summary className="cursor-pointer text-gray-400">Methodology</summary>
              <p className="mt-1 leading-relaxed">{data.methodology}</p>
              {data.volatility?.method && <p className="mt-1">{data.volatility.method}</p>}
            </details>
            <Issues issues={data.dataIssues} />
            <p className="text-2xs text-gray-600 mt-2">A model estimate, not a prediction and not investment advice. This app never places real trades.</p>
          </div>
        </>
      ) : null}

      <HorizonTable symbol={symbol} />

      <ResearchSection key={symbol} title={indices[symbol] ? `AI market research — ${indices[symbol]}` : `AI research — ${symbol}`}
        subject={indices[symbol] ? { kind: 'market', symbol } : { kind: 'stock', symbol }} />

      <AiCopilot />
    </div>
  );
}

/** Every horizon side by side: the model's ranges and how often each held in the past. */
function HorizonTable({ symbol }: { symbol: string }) {
  const [rows, setRows] = useState<(ForecastResponse | null)[]>([]);
  useEffect(() => {
    let live = true;
    setRows([]);
    Promise.allSettled(HORIZONS.map(h => forecastApi.get(symbol, h)))
      .then(rs => { if (live) setRows(rs.map(r => r.status === 'fulfilled' ? r.value.data : null)); });
    return () => { live = false; };
  }, [symbol]);
  const cur = rows.find(r => r?.currentPrice != null)?.currentPrice;
  const cov = (v: number | null | undefined) => v == null ? '—' : `${(v * 100).toFixed(1)}%`;
  return (
    <div className="card">
      <h3 className="text-ink font-semibold text-sm mb-2">Quantitative forecast — all horizons</h3>
      {rows.length === 0 ? <p className="text-2xs text-gray-500">Loading…</p> : (
        <table className="w-full text-2xs">
          <thead><tr className="text-gray-500 text-left">
            <th className="py-1">Horizon</th><th>50% model range</th><th>held</th><th>90% model range</th><th>held</th><th>Calibration</th>
          </tr></thead>
          <tbody>
            <tr className="border-t border-surface-border/40"><td className="py-1 text-gray-400">Current</td><td colSpan={5} className="font-mono text-ink">{fmt(cur)}</td></tr>
            {rows.map((r, i) => (
              <tr key={HORIZONS[i]} className="border-t border-surface-border/40">
                <td className="py-1 text-gray-400">{HORIZONS[i]}</td>
                {r?.range90 ? <>
                  <td className="font-mono text-gray-300">{fmt(r.range50?.low)} – {fmt(r.range50?.high)}</td>
                  <td className="text-gray-500">{cov(r.range50?.historicalCoverage)}</td>
                  <td className="font-mono text-gray-300">{fmt(r.range90.low)} – {fmt(r.range90.high)}</td>
                  <td className="text-gray-500">{cov(r.range90.historicalCoverage)}</td>
                  <td className="text-gray-500 capitalize">{r.calibration?.label?.replace(/_/g, ' ').toLowerCase() ?? 'unmeasured'}</td>
                </> : <td colSpan={5} className="text-gray-500">{r?.statusReason ?? 'Unavailable'}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="text-2xs text-gray-600 mt-2">"Held" is the share of past windows on this instrument whose outcome landed inside the range (target: 50% and 90%).</p>
    </div>
  );
}

function ScenarioCard({ s, empirical }: { s: ForecastScenario; empirical: boolean }) {
  const st = SCN_STYLE[s.label] ?? SCN_STYLE.Base;
  const Icon = st.Icon;
  const p = empirical ? s.probability : null;
  return (
    <div className={`card border ${st.border} ${st.bg} flex flex-col gap-3`}>
      <div className={`flex items-center justify-between ${st.color}`}>
        <div className="flex items-center gap-2"><Icon size={16} /><span className="font-semibold text-sm">{s.label} scenario</span></div>
        <span className="font-bold text-sm">{p != null ? `${p.toFixed(0)}%` : '—'}</span>
      </div>
      {p != null ? (
        <>
          <div className="h-2 rounded-full bg-surface-border overflow-hidden">
            <div className={`h-full rounded-full ${st.bar}`} style={{ width: `${p}%` }} />
          </div>
          <div className="text-2xs text-gray-500 -mt-1">
            Historical frequency{s.probabilityCiLow != null && s.probabilityCiHigh != null ? ` (95% CI ${s.probabilityCiLow.toFixed(0)}–${s.probabilityCiHigh.toFixed(0)}%)` : ''} · model share {s.nominalProbability.toFixed(0)}%
          </div>
        </>
      ) : (
        <div className="text-2xs text-gray-500">Probability unavailable</div>
      )}
      <div>
        <div className="text-gray-500 text-2xs mb-1">Model-estimated band (percentiles {Math.round(s.quantileLow * 100)}–{Math.round(s.quantileHigh * 100)})</div>
        <div className="font-mono text-ink font-semibold text-sm">{fmt(s.low)} – {fmt(s.high)}</div>
      </div>
      <div className="bg-surface-border/40 rounded p-2">
        <div className="text-gray-500 text-2xs mb-0.5">Move from last close</div>
        <div className={`font-mono font-bold text-xs ${st.color}`}>{fmtPct(s.movePctLow)} to {fmtPct(s.movePctHigh)}</div>
      </div>
    </div>
  );
}

function CalibrationCurve({ curve }: { curve: CurvePoint[] }) {
  return (
    <div>
      <div className="stat-label text-2xs mb-1">Calibration: stated range coverage vs. how often it actually held</div>
      <div className="grid grid-cols-5 md:grid-cols-10 gap-1">
        {curve.map(c => {
          const gap = (c.empirical - c.nominal) * 100;
          return (
            <div key={c.nominal} className="bg-surface-hover rounded p-1 text-center">
              <div className="text-2xs text-gray-500">{Math.round(c.nominal * 100)}%</div>
              <div className={`font-mono text-2xs font-semibold ${Math.abs(gap) <= 5 ? 'text-ink' : 'text-amber-400'}`}>{(c.empirical * 100).toFixed(0)}%</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function Issues({ issues }: { issues: DataIssue[] | null }) {
  const shown = (issues ?? []).filter(i => i.warning).slice(-5);
  if (!shown.length) return null;
  return (
    <div className="mt-2 space-y-0.5">
      {shown.map((i, k) => (
        <div key={k} className="text-2xs text-amber-400/80 flex gap-1"><AlertTriangle size={10} className="mt-0.5 shrink-0" />{i.date ? `${i.date}: ` : ''}{i.detail}</div>
      ))}
    </div>
  );
}
