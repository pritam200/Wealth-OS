import { useEffect, useState } from 'react';
import { ResearchTrail } from '../research/ResearchPanel';
import type { ResearchResult } from '../../api/research';
import { ChevronDown, ChevronUp, FileSearch, AlertTriangle } from 'lucide-react';
import { forecastApi } from '../../api/forecast';
import type { ForecastResponse } from '../../api/forecast';
import type { AnalystAssessment } from '../../api/analyst';
import type { TechnicalAnalysis, PriceLevel } from '../../types';

/**
 * The audit trail behind every number on the stock page: where the prices came from, how
 * each indicator is defined, the trend votes, how levels were found, the volatility and range
 * methodology with its backtest, and the signal rule's historical record. Everything here is
 * rendered from backend values — nothing is recomputed in the browser.
 */
export function AnalysisDetails({ symbol, tech, assessment, research }: {
  symbol: string; tech: TechnicalAnalysis | null; assessment: AnalystAssessment | null; research?: ResearchResult | null;
}) {
  const [open, setOpen] = useState(false);
  const [fc, setFc] = useState<ForecastResponse | null>(null);

  useEffect(() => {
    if (!open || fc) return;
    forecastApi.get(symbol, '20D').then(r => setFc(r.data)).catch(() => setFc(null));
  }, [open, symbol, fc]);
  useEffect(() => { setFc(null); }, [symbol]);

  return (
    <div className="card">
      <button onClick={() => setOpen(o => !o)} className="w-full flex items-center justify-between text-left">
        <span className="flex items-center gap-2">
          <FileSearch size={15} className="text-brand" />
          <span className="font-semibold text-ink text-sm">Analysis details</span>
          <span className="text-2xs text-gray-500">data, formulas, evidence and track record behind this page</span>
        </span>
        {open ? <ChevronUp size={15} className="text-gray-500" /> : <ChevronDown size={15} className="text-gray-500" />}
      </button>

      {open && (
        <div className="mt-4 space-y-5 text-2xs">
          {tech && <DataSection tech={tech} />}
          {tech?.indicators?.length ? <IndicatorSection tech={tech} /> : null}
          {tech?.trendAssessment && <TrendSection tech={tech} />}
          {tech?.levels && <LevelSection tech={tech} />}
          <ForecastSection fc={fc} />
          {assessment && <SignalSection a={assessment} />}
          {research && <Section title="LLM Research"><ResearchTrail r={research} /></Section>}
        </div>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h4 className="stat-label text-2xs mb-1.5">{title}</h4>
      {children}
    </section>
  );
}

function Row({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-3 py-0.5 border-b border-surface-border/30">
      <span className="text-gray-500">{k}</span>
      <span className="text-gray-300 font-mono text-right">{v}</span>
    </div>
  );
}

function DataSection({ tech }: { tech: TechnicalAnalysis }) {
  const warnings = (tech.dataIssues ?? []).filter(i => i.warning);
  const info = (tech.dataIssues ?? []).filter(i => !i.warning);
  return (
    <Section title="Price data">
      <div className="grid md:grid-cols-2 gap-x-6">
        <Row k="Status" v={tech.seriesStatus?.replace(/_/g, ' ').toLowerCase() ?? '—'} />
        <Row k="Source" v={tech.source ?? '—'} />
        <Row k="Timeframe" v={tech.timeframe ?? '1D'} />
        <Row k="History" v={`${tech.barsAvailable ?? 0} bars, ${tech.firstBarDate ?? '—'} → ${tech.lastBarDate ?? '—'}`} />
        <Row k="Expected latest session" v={tech.expectedSession ?? '—'} />
        <Row k="Bars rejected by validation" v={tech.barsRejected ?? 0} />
        <Row k="Last ingested" v={tech.dataUpdatedAt ? tech.dataUpdatedAt.replace('T', ' ').slice(0, 16) : '—'} />
      </div>
      {[...warnings.slice(-6), ...info].map((i, k) => (
        <div key={k} className={`mt-1 flex gap-1 ${i.warning ? 'text-amber-400/90' : 'text-gray-500'}`}>
          {i.warning && <AlertTriangle size={10} className="mt-0.5 shrink-0" />}
          <span><span className="font-mono">{i.code}</span>{i.date ? ` ${i.date}` : ''}: {i.detail}</span>
        </div>
      ))}
      {warnings.length > 6 && <div className="text-gray-600 mt-1">{warnings.length - 6} older warning(s) not shown.</div>}
    </Section>
  );
}

function IndicatorSection({ tech }: { tech: TechnicalAnalysis }) {
  return (
    <Section title="Indicators">
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="text-gray-600 text-left">
              <th className="py-1 pr-3 font-medium">Indicator</th>
              <th className="py-1 pr-3 font-medium">Value</th>
              <th className="py-1 pr-3 font-medium">As of</th>
              <th className="py-1 pr-3 font-medium">Bars (need / have)</th>
              <th className="py-1 font-medium">Formula</th>
            </tr>
          </thead>
          <tbody>
            {tech.indicators!.map(i => (
              <tr key={i.key} className="border-t border-surface-border/30 align-top">
                <td className="py-1 pr-3 text-gray-300 whitespace-nowrap">{i.name}{i.period && !i.name.includes(String(i.period)) ? ` (${i.period})` : ''}</td>
                <td className="py-1 pr-3 font-mono text-ink whitespace-nowrap">
                  {i.available && i.value != null ? `${i.value.toLocaleString('en-IN', { maximumFractionDigits: 2 })}` : '—'}
                  <span className="text-gray-600"> {i.unit}</span>
                </td>
                <td className="py-1 pr-3 text-gray-500 whitespace-nowrap">{i.asOf ?? '—'}</td>
                <td className="py-1 pr-3 text-gray-500 whitespace-nowrap">{i.barsRequired} / {i.barsAvailable}</td>
                <td className="py-1 text-gray-500">{i.available ? i.formula : i.reason ?? 'Not enough data'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {tech.volume && (
        <p className="text-gray-500 mt-1.5">
          Volume: latest {tech.volume.latestVolume?.toLocaleString('en-IN') ?? '—'}, 20-day average {tech.volume.averageVolume20?.toLocaleString('en-IN') ?? '—'}
          {tech.volume.relativeVolume != null ? `, relative ${tech.volume.relativeVolume.toFixed(2)}×` : ''}
          {tech.volume.volumeTrend ? `, trend ${tech.volume.volumeTrend.toLowerCase()}` : ''}
          {tech.volume.unusual ? ' — unusual' : ''}{tech.volume.reason ? ` (${tech.volume.reason})` : ''}
        </p>
      )}
    </Section>
  );
}

function TrendSection({ tech }: { tech: TechnicalAnalysis }) {
  const t = tech.trendAssessment!;
  return (
    <Section title={`Trend: ${t.label.replace(/_/g, ' ').toLowerCase()} (${t.bullishVotes} up / ${t.bearishVotes} down of ${t.votesAvailable} votes)`}>
      {t.evidence.map(e => (
        <Row key={e.name} k={`${e.name}${e.vote ? '' : ' (confirmation, no vote)'}`}
          v={<span className={e.reading === 'BULLISH' ? 'text-bull' : e.reading === 'BEARISH' ? 'text-bear' : 'text-gray-400'}>{e.detail}</span>} />
      ))}
      <p className="text-gray-500 mt-1.5">{t.rule}</p>
      <p className="text-gray-600 mt-1">Descriptive only: in the backtest the trend label did not predict forward returns, so it is not a rating input.</p>
    </Section>
  );
}

function LevelSection({ tech }: { tech: TechnicalAnalysis }) {
  const l = tech.levels!;
  const lv = (p: PriceLevel | null, status: string) => p
    ? <span title={p.reason}>₹{p.price.toLocaleString('en-IN')} ({p.distancePct >= 0 ? '+' : ''}{p.distancePct.toFixed(2)}%, {p.source.replace(/_/g, ' ').toLowerCase()})</span>
    : <span className="text-gray-600">{status === 'NO_RELIABLE_LEVEL' ? 'no reliable level' : '—'}</span>;
  return (
    <Section title="Support and resistance">
      <div className="grid md:grid-cols-2 gap-x-6">
        <Row k="Nearest support" v={lv(l.nearestSupport, l.supportStatus)} />
        <Row k="Nearest resistance" v={lv(l.nearestResistance, l.resistanceStatus)} />
        <Row k="Next support" v={lv(l.nextSupport, l.supportStatus)} />
        <Row k="Next resistance" v={lv(l.nextResistance, l.resistanceStatus)} />
      </div>
      {[l.nearestSupport, l.nearestResistance].filter(Boolean).map((p, k) => <p key={k} className="text-gray-500 mt-1">{p!.reason}</p>)}
      <p className="text-gray-600 mt-1">{l.method}</p>
    </Section>
  );
}

function ForecastSection({ fc }: { fc: ForecastResponse | null }) {
  if (!fc) return <Section title="Volatility and 20-session range"><p className="text-gray-600">Loading…</p></Section>;
  if (fc.status === 'STALE_DATA' || fc.status === 'INSUFFICIENT_DATA') {
    return <Section title="Volatility and 20-session range"><p className="text-gray-500">{fc.statusReason}</p></Section>;
  }
  const pe = fc.pointErrors;
  return (
    <Section title="Volatility and 20-session range">
      <div className="grid md:grid-cols-2 gap-x-6">
        <Row k="Daily σ / annualised" v={fc.volatility ? `${fc.volatility.dailyPct.toFixed(2)}% / ${fc.volatility.annualizedPct.toFixed(1)}%` : '—'} />
        <Row k="20-session σ" v={fc.volatility ? `±${fc.volatility.horizonPct.toFixed(2)}%` : '—'} />
        <Row k="50% model range" v={fc.range50 ? `₹${fc.range50.low.toLocaleString('en-IN')} – ₹${fc.range50.high.toLocaleString('en-IN')}` : '—'} />
        <Row k="90% model range" v={fc.range90 ? `₹${fc.range90.low.toLocaleString('en-IN')} – ₹${fc.range90.high.toLocaleString('en-IN')}` : '—'} />
        <Row k="Historical coverage (50% / 90%)" v={`${pct(fc.range50?.historicalCoverage)} / ${pct(fc.range90?.historicalCoverage)}`} />
        <Row k="Calibration" v={fc.calibration?.label?.replace(/_/g, ' ').toLowerCase() ?? 'unmeasured'} />
        <Row k="Backtest windows (independent)" v={fc.calibration ? `${fc.calibration.observations} (${fc.calibration.effectiveSample})` : '—'} />
        <Row k="Backtest period" v={fc.calibration?.from ? `${fc.calibration.from} → ${fc.calibration.to}` : '—'} />
        <Row k="Brier score, model vs trailing frequency" v={fc.directional?.brierModel != null ? `${fc.directional.brierModel.toFixed(3)} vs ${fc.directional.brierTrailingFrequency?.toFixed(3) ?? '—'}` : '—'} />
        <Row k="MAE: last close / drift / SMA20 reversion" v={pe?.maeRandomWalkPct != null ? `${pe.maeRandomWalkPct.toFixed(2)}% / ${pe.maeDriftPct?.toFixed(2)}% / ${pe.maeMaReversionPct?.toFixed(2)}%` : '—'} />
      </div>
      {fc.directional?.summary && <p className="text-gray-500 mt-1.5">{fc.directional.summary}</p>}
      <p className="text-gray-600 mt-1">{fc.methodology}</p>
    </Section>
  );
}

function SignalSection({ a }: { a: AnalystAssessment }) {
  const v = a.signalValidation;
  return (
    <Section title="Signal rule and recommendation inputs">
      <div className="grid md:grid-cols-2 gap-x-6">
        <Row k="Rule reading today" v={a.ruleOutput?.replace(/_/g, ' ') ?? '—'} />
        <Row k="Shown rating" v={a.rating?.replace(/_/g, ' ') ?? '—'} />
        {v && <>
          <Row k="Past BUY calls: ended higher after 20 sessions" v={`${v.buyCalls} · ${pct(v.buyHitRate)} (95% CI low ${pct(v.buyHitRateCiLow)})`} />
          <Row k="Past SELL calls: ended lower after 20 sessions" v={`${v.sellCalls} · ${pct(v.sellHitRate)} (95% CI low ${pct(v.sellHitRateCiLow)})`} />
          <Row k="Base rate: any session ended higher" v={pct(v.baseUpRate)} />
          <Row k="Current call validated" v={v.currentCallValidated ? 'yes' : 'no'} />
        </>}
        <Row k="Prices as of" v={`${a.priceDate ?? '—'} · ${a.priceSource ?? '—'}`} />
      </div>
      {v?.summary && <p className="text-gray-500 mt-1.5">{v.summary}</p>}
      <p className="text-gray-600 mt-1">
        A BUY or SELL is shown only when the lower 95% bound of that call&apos;s hit rate on this stock beats the base rate
        over at least 30 independent 20-session windows; otherwise the result is &quot;no actionable signal&quot;.
      </p>
      {!!a.fundamentalFacts?.length && (
        <div className="mt-2">
          {a.fundamentalFacts.map(f => (
            <Row key={f.name} k={f.name}
              v={`${f.value ?? '—'} ${f.unit}${f.period ? ` · ${f.period}` : ''}${f.source ? ` · ${f.source}` : ''}${f.fetchedAt ? ` · fetched ${f.fetchedAt.slice(0, 10)}` : ''}`} />
          ))}
        </div>
      )}
    </Section>
  );
}

const pct = (n: number | null | undefined) => n == null ? '—' : `${(n * 100).toFixed(1)}%`;
