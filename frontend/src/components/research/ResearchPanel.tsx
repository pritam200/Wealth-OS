import { useState } from 'react';
import { Sparkles, RefreshCw, Loader2, AlertTriangle, ShieldAlert, ChevronDown, ChevronUp, ExternalLink } from 'lucide-react';
import { ACTIONABILITY_LABEL, BASIS_LABEL } from '../../api/research';
import type { Claim, CrossCheck, Evidence, Fact, ResearchResult, ResearchSubject } from '../../api/research';
import { useResearch } from './useResearch';
import type { ResearchState } from './useResearch';

const VIEW_TONE: Record<string, string> = {
  BUY: 'bg-bull/15 text-bull border-bull/40',
  SELL: 'bg-bear/15 text-bear border-bear/40',
  HOLD: 'bg-surface-hover text-gray-200 border-surface-border',
  NO_ACTIONABLE_SIGNAL: 'bg-surface-hover text-gray-300 border-surface-border',
  INSUFFICIENT_DATA: 'bg-transparent text-amber-300 border-dashed border-amber-500/50',
  CONFLICTING_EVIDENCE: 'bg-amber-500/10 text-amber-300 border-amber-500/40',
  RESEARCH_REQUIRED: 'bg-brand/10 text-brand-light border-brand/40',
};

const BASIS_TONE: Record<string, string> = {
  FILING: 'text-emerald-300 border-emerald-500/40', DATA: 'text-sky-300 border-sky-500/40',
  CALCULATION: 'text-sky-300 border-sky-500/30', MODEL: 'text-teal-300 border-teal-500/40',
  PORTFOLIO: 'text-teal-300 border-teal-500/40', NEWS: 'text-gray-300 border-gray-500/40',
  LLM_INTERPRETATION: 'text-amber-300 border-amber-500/40 border-dashed',
};

const ANSWER_TONE: Record<string, string> = {
  SUPPORTS: 'text-bull', CONTRADICTS: 'text-bear', MIXED: 'text-amber-300', UNKNOWN: 'text-gray-500',
};

export function ViewPill({ value }: { value: string | null | undefined }) {
  if (!value) return null;
  return (
    <span className={`inline-flex items-center text-xs font-bold px-2.5 py-1 rounded-lg border ${VIEW_TONE[value] ?? VIEW_TONE.NO_ACTIONABLE_SIGNAL}`}>
      {ACTIONABILITY_LABEL[value] ?? value}
    </span>
  );
}

function fmtTime(s: string | null | undefined) {
  if (!s) return '—';
  const d = new Date(s);
  return isNaN(d.getTime()) ? s : d.toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** A statement with what it rests on and the facts/evidence it cites (hover for the source). */
function ClaimView({ c, r, className = '' }: { c: Claim | null | undefined; r: ResearchResult; className?: string }) {
  if (!c) return null;
  const facts = new Map(r.facts.map(f => [f.id, f] as [string, Fact]));
  const ev = new Map(r.evidence.map(e => [e.id, e] as [string, Evidence]));
  return (
    <div className={className}>
      <p className="text-xs text-gray-300 leading-relaxed">{c.text}</p>
      <div className="flex flex-wrap items-center gap-1 mt-1">
        {c.basis.split('+').map(b => (
          <span key={b} className={`text-[10px] px-1.5 py-px rounded border ${BASIS_TONE[b] ?? BASIS_TONE.NEWS}`}>{BASIS_LABEL[b] ?? b}</span>
        ))}
        {c.evidence.slice(0, 6).map(id => {
          const f = facts.get(id), e = ev.get(id);
          const title = f ? `${f.label}: ${f.value ?? 'unavailable'}${f.asOf ? ` (as of ${f.asOf})` : ''} — ${f.source ?? ''}`
            : e ? `${e.source ?? ''}, ${e.publishedAt?.slice(0, 10) ?? 'undated'}: ${e.title}` : id;
          const chip = <span className="text-[10px] font-mono px-1 py-px rounded bg-surface-hover text-gray-400 cursor-help" title={title}>{id}</span>;
          return e?.url ? <a key={id} href={e.url} target="_blank" rel="noopener noreferrer">{chip}</a> : <span key={id}>{chip}</span>;
        })}
        {c.evidence.length > 6 && <span className="text-[10px] text-gray-500" title={c.evidence.slice(6).join(', ')}>+{c.evidence.length - 6} more</span>}
      </div>
    </div>
  );
}

function ClaimList({ title, items, r, tone = 'text-gray-400' }: { title: string; items: Claim[] | null | undefined; r: ResearchResult; tone?: string }) {
  if (!items?.length) return null;
  return (
    <div>
      <div className={`text-2xs font-semibold mb-1 ${tone}`}>{title}</div>
      <div className="space-y-2">{items.map((c, i) => <ClaimView key={i} c={c} r={r} />)}</div>
    </div>
  );
}

function CrossChecks({ items, r }: { items: CrossCheck[]; r: ResearchResult }) {
  return (
    <div className="space-y-2">
      {items.map(x => (
        <div key={x.question} className="grid grid-cols-[1fr_auto] gap-2 border-b border-surface-border/40 pb-2">
          <div>
            <div className="text-2xs text-gray-500">{x.label}</div>
            <ClaimView c={x.explanation} r={r} />
          </div>
          <span className={`text-2xs font-bold ${ANSWER_TONE[x.answer]}`}>{x.answer.toLowerCase()}</span>
        </div>
      ))}
    </div>
  );
}

/** Self-contained: loads (and if needed runs) research for a subject. */
export function ResearchSection({ subject, title }: { subject: ResearchSubject; title?: string }) {
  const state = useResearch(subject);
  return <ResearchPanel state={state} title={title} />;
}

export function ResearchPanel({ state, title = 'AI Research' }: { state: ResearchState; title?: string }) {
  const { result: r, loading, running, error, refresh } = state;
  const [more, setMore] = useState(false);

  const header = (
    <div className="flex items-center justify-between flex-wrap gap-2 mb-3">
      <div className="flex items-center gap-2">
        <div className="icon-badge icon-badge-sm icon-badge-violet"><Sparkles size={11} /></div>
        <span className="font-semibold text-ink text-sm">{title}</span>
        {r?.provider && r.report && <span className="text-2xs text-gray-500 font-mono">{r.provider}:{r.model}{r.fallbackUsed ? ' (fallback)' : ''}</span>}
      </div>
      <button onClick={refresh} disabled={running} className="btn-ghost text-2xs flex items-center gap-1 disabled:opacity-50"
        title="Fetch the latest data and news and run the research again">
        {running ? <Loader2 size={12} className="animate-spin" /> : <RefreshCw size={12} />} {running ? 'Researching…' : 'Refresh research'}
      </button>
    </div>
  );

  if (loading && !r) return <div className="card">{header}<p className="text-2xs text-gray-500">Loading research…</p></div>;
  if (!r) return <div className="card">{header}<p className="text-2xs text-gray-500">{error ?? 'No research.'}</p></div>;

  const rep = r.report;
  const d = r.devilsAdvocate;
  const unavailable = !rep;

  return (
    <div className="card">
      {header}

      {running && !rep && (
        <p className="text-2xs text-gray-500 mb-3 flex items-center gap-1"><Loader2 size={11} className="animate-spin" />
          Researching — two model passes over the verified data; on a local model this can take a few minutes.</p>
      )}

      {/* Final view: a fixed gate over the quantitative call and the research — not a score. */}
      <div className="rounded-lg border border-surface-border p-3 mb-3">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="stat-label text-2xs">Final research view</span>
          <ViewPill value={r.finalView?.actionability} />
        </div>
        <p className="text-2xs text-gray-400 mt-1.5">{r.finalView?.reason}</p>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mt-2">
          <div className="rounded bg-surface-hover/50 p-2">
            <div className="text-2xs text-gray-500">Quantitative assessment (tested model)</div>
            <div className="text-xs text-gray-200 font-semibold">{ACTIONABILITY_LABEL[r.quant?.rating ?? ''] ?? r.quant?.rating ?? '—'}</div>
            <div className="text-2xs text-gray-500">{r.quant?.summary}</div>
          </div>
          <div className="rounded bg-surface-hover/50 p-2">
            <div className="text-2xs text-gray-500">LLM research conclusion</div>
            <div className="text-xs text-gray-200 font-semibold">{rep ? (ACTIONABILITY_LABEL[rep.actionability] ?? rep.actionability) : '—'}</div>
            <div className="text-2xs text-gray-500">{rep ? `Evidence quality ${rep.evidenceQuality.toLowerCase()}` : 'No research for the current data'}</div>
          </div>
        </div>
      </div>

      {unavailable && (
        <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3 mb-3">
          <div className="flex items-center gap-1.5 text-xs font-semibold text-amber-300">
            <AlertTriangle size={13} /> {r.status === 'NOT_RUN' ? 'AI research not run yet' : 'AI research unavailable'}
          </div>
          {r.statusReason && <p className="text-2xs text-gray-400 mt-1">{r.statusReason}</p>}
          <p className="text-2xs text-gray-500 mt-1">
            {r.staleReason ?? 'Last research: none.'} The quantitative analysis above is unaffected.
          </p>
          {r.previous?.report && (
            <details className="mt-2">
              <summary className="text-2xs text-gray-400 cursor-pointer">Show last research ({fmtTime(r.previous.researchTimestamp)}, data to {r.previous.marketDate}) — out of date</summary>
              <div className="mt-2 opacity-80"><ClaimView c={r.previous.report.executiveSummary} r={r.previous} /></div>
              <div className="mt-2 opacity-80"><ClaimView c={r.previous.report.researchConclusion} r={r.previous} /></div>
            </details>
          )}
        </div>
      )}

      {rep && (
        <div className="space-y-3">
          {(r.stale || r.fromCache) && (
            <p className="text-2xs text-gray-500">
              {r.fromCache ? 'Stored research' : 'Research'} from {fmtTime(r.researchTimestamp)} on data to {r.marketDate}.{r.statusReason ? ` ${r.statusReason}` : ''}
            </p>
          )}
          {r.status === 'PARTIAL' && <p className="text-2xs text-amber-300">{r.statusReason}</p>}

          <ClaimView c={rep.executiveSummary} r={r} />

          {(rep.marketContext || rep.sectorContext || rep.newsAssessment) && (
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              {([['Market & macro', rep.marketContext], ['Sector', rep.sectorContext], ['News & filings', rep.newsAssessment]] as const).map(([t, c]) => c && (
                <div key={t}><div className="text-2xs font-semibold text-gray-400 mb-1">{t}</div><ClaimView c={c} r={r} /></div>
              ))}
            </div>
          )}
          {r.quant?.trend && <p className="text-2xs text-gray-500">Regime (descriptive trend label): {r.quant.trend.replace(/_/g, ' ').toLowerCase()}{r.quant.trendSummary ? ` — ${r.quant.trendSummary}` : ''}</p>}

          <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
            {([['Bull case', rep.bullCase, 'text-bull'], ['Base case', rep.baseCase, 'text-gray-300'], ['Bear case', rep.bearCase, 'text-bear']] as const).map(([t, c, tone]) => (
              <div key={t} className="rounded-lg border border-surface-border p-2">
                <div className={`text-2xs font-semibold mb-1 ${tone}`}>{t}</div>
                {c ? <ClaimView c={c} r={r} /> : <p className="text-2xs text-gray-600">Not given.</p>}
              </div>
            ))}
          </div>
          <p className="text-2xs text-gray-600">Cases are described, not priced: ranges and probabilities come only from the forecast model.</p>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            <ClaimList title="Key risks" items={rep.keyRisks} r={r} tone="text-bear" />
            <ClaimList title="Catalysts" items={rep.catalysts} r={r} tone="text-bull" />
            <ClaimList title="Contradicting evidence" items={rep.contradictingEvidence} r={r} tone="text-amber-300" />
          </div>

          {/* Devil's-advocate pass, stored and shown separately. */}
          <div className="rounded-lg border border-bear/30 bg-bear/5 p-3">
            <div className="flex items-center justify-between gap-2 mb-2">
              <span className="flex items-center gap-1.5 text-xs font-semibold text-ink"><ShieldAlert size={13} className="text-bear" /> Why this analysis could be wrong</span>
              {d && <span className="text-2xs text-gray-400">risk to the conclusion: <b className={d.thesisRisk === 'HIGH' ? 'text-bear' : d.thesisRisk === 'MEDIUM' ? 'text-amber-300' : 'text-gray-300'}>{d.thesisRisk.toLowerCase()}</b></span>}
            </div>
            {!d ? <p className="text-2xs text-gray-500">The devil's-advocate review did not complete{r.statusReason ? `: ${r.statusReason}` : '.'}</p> : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                <ClaimList title="Contradictory evidence" items={d.contradictoryEvidence} r={r} />
                <ClaimList title="Overlooked risks" items={d.overlookedRisks} r={r} />
                <ClaimList title="Data-quality problems" items={d.dataQualityProblems} r={r} />
                <ClaimList title="Upcoming catalysts" items={d.upcomingCatalysts} r={r} />
                <ClaimList title="Why the technical signal may fail" items={d.technicalSignalFailure} r={r} />
                <ClaimList title="Why the fundamental thesis may fail" items={d.fundamentalThesisFailure} r={r} />
                <ClaimList title="Forecast range reliability" items={d.forecastRangeReliability} r={r} />
                {d.verdict && <div><div className="text-2xs font-semibold mb-1 text-gray-400">Verdict</div><ClaimView c={d.verdict} r={r} /></div>}
              </div>
            )}
          </div>

          <button onClick={() => setMore(m => !m)} className="text-2xs text-brand-light flex items-center gap-1">
            {more ? <ChevronUp size={12} /> : <ChevronDown size={12} />} {more ? 'Less' : 'Assessments, cross-checks and missing information'}
          </button>
          {more && (
            <div className="space-y-3">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {([['Fundamentals', rep.fundamentalAssessment], ['Technicals', rep.technicalAssessment], ['Valuation', rep.valuationAssessment],
                   ['Forecast interpretation', rep.forecastInterpretation], ['Your portfolio', rep.portfolioImpact]] as const).map(([t, c]) => c && (
                  <div key={t}><div className="text-2xs font-semibold text-gray-400 mb-1">{t}</div><ClaimView c={c} r={r} /></div>
                ))}
              </div>
              <div>
                <div className="text-2xs font-semibold text-gray-400 mb-1">Cross-checks</div>
                <CrossChecks items={rep.crossChecks} r={r} />
              </div>
              {rep.missingInformation.length > 0 && (
                <div>
                  <div className="text-2xs font-semibold text-gray-400 mb-1">Missing information</div>
                  <ul className="list-disc ml-4 text-2xs text-gray-500">{rep.missingInformation.map((m, i) => <li key={i}>{m}</li>)}</ul>
                </div>
              )}
              <ClaimView c={rep.researchConclusion} r={r} />
            </div>
          )}
        </div>
      )}

      <p className="text-2xs text-gray-700 mt-3 pt-2 border-t border-surface-border">
        Research is an interpretation of the verified data listed in Analysis details; the model cannot add figures
        (any it tries to add are removed) and cannot change the quantitative analysis. Not investment advice.
      </p>
    </div>
  );
}

/** The full trail for the Analysis Details panel: who ran it, on what, from which sources. */
export function ResearchTrail({ r }: { r: ResearchResult }) {
  const Row = ({ k, v }: { k: string; v: React.ReactNode }) => (
    <div className="flex justify-between gap-3 py-0.5 border-b border-surface-border/30">
      <span className="text-gray-500">{k}</span><span className="text-gray-300 font-mono text-right break-all">{v}</span>
    </div>
  );
  const cited = new Set(r.report?.sources ?? []);
  return (
    <div className="space-y-4">
      <div>
        <Row k="Status" v={`${r.status}${r.statusReason ? ` — ${r.statusReason}` : ''}`} />
        <Row k="Provider / model" v={r.provider ? `${r.provider}:${r.model}${r.fallbackUsed ? ' (fallback)' : ''}` : '—'} />
        <Row k="Research time" v={fmtTime(r.researchTimestamp)} />
        <Row k="Market data to" v={r.marketDate ?? '—'} />
        <Row k="Prompt versions" v={[r.analystPromptVersion, r.reviewPromptVersion, r.webPromptVersion].filter(Boolean).join(' · ') || '—'} />
        <Row k="Data snapshot" v={r.dataSnapshotHash?.slice(0, 16) ?? '—'} />
        <Row k="Served from cache" v={r.fromCache ? 'yes' : 'no'} />
        {r.stale && <Row k="Stale" v={r.staleReason ?? 'yes'} />}
        {r.latencyMs != null && <Row k="Run time" v={`${(r.latencyMs / 1000).toFixed(1)} s`} />}
        {r.webSearchQueries.length > 0 && <Row k="Web searches" v={r.webSearchQueries.join('; ')} />}
        {r.finalView && <Row k="Final-view rule" v={r.finalView.rule} />}
      </div>

      <div>
        <h5 className="text-gray-400 font-semibold mb-1">Sources researched</h5>
        {r.sources.map((s, i) => (
          <div key={i} className="flex justify-between gap-3 py-0.5 border-b border-surface-border/30">
            <span className="text-gray-400">{s.source}</span>
            <span className={s.status === 'OK' ? 'text-bull' : s.status === 'NOT_SUPPORTED' ? 'text-gray-500' : 'text-amber-300'}>
              {s.status === 'OK' ? `${s.items} item(s)` : s.status.replace('_', ' ').toLowerCase()}{s.detail ? ` — ${s.detail}` : ''}
            </span>
          </div>
        ))}
      </div>

      {r.guard && (r.guard.removedFigures.length > 0 || r.guard.droppedCitations.length > 0 || r.guard.notes.length > 0) && (
        <div>
          <h5 className="text-gray-400 font-semibold mb-1">Output checks</h5>
          {r.guard.notes.map((n, i) => <p key={i} className="text-gray-500">• {n}</p>)}
          {r.guard.removedFigures.length > 0 && <p className="text-gray-500">Removed figures: <span className="font-mono">{r.guard.removedFigures.join(', ')}</span></p>}
        </div>
      )}

      <div>
        <h5 className="text-gray-400 font-semibold mb-1">Evidence</h5>
        {r.evidence.length === 0 ? <p className="text-gray-600">No filings or news were retrieved (see sources above).</p> : (
          <table className="w-full">
            <thead><tr className="text-gray-500 text-left"><th className="pr-2">Id</th><th className="pr-2">Fact</th><th className="pr-2">Source</th><th className="pr-2">Date</th><th>Tier</th></tr></thead>
            <tbody>
              {r.evidence.map(e => (
                <tr key={e.id} className="border-t border-surface-border/30 align-top">
                  <td className={`font-mono pr-2 ${cited.has(e.id) ? 'text-brand-light' : 'text-gray-500'}`} title={cited.has(e.id) ? 'Cited by the research' : 'Not cited'}>{e.id}</td>
                  <td className="text-gray-300 pr-2">{e.url ? <a href={e.url} target="_blank" rel="noopener noreferrer" className="hover:text-ink">{e.title} <ExternalLink size={9} className="inline" /></a> : e.title}</td>
                  <td className="text-gray-400 pr-2">{e.source} <span className="text-gray-600">({e.kind.toLowerCase().replace('_', ' ')})</span></td>
                  <td className="text-gray-400 pr-2 whitespace-nowrap">{e.publishedAt?.slice(0, 10) ?? 'undated'}</td>
                  <td className="text-gray-500">{e.tier.toLowerCase()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <details>
        <summary className="text-gray-400 font-semibold cursor-pointer">Verified facts sent to the model ({r.facts.length}; {r.missingData.length} unavailable)</summary>
        <table className="w-full mt-1">
          <tbody>
            {r.facts.map(f => (
              <tr key={f.id} className="border-t border-surface-border/30 align-top">
                <td className="font-mono text-gray-500 pr-2">{f.id}</td>
                <td className="text-gray-400 pr-2">{f.label}</td>
                <td className={`font-mono pr-2 ${f.available ? 'text-gray-300' : 'text-gray-600'}`}>{f.available ? f.value : 'unavailable'}</td>
                <td className="text-gray-500 pr-2 whitespace-nowrap">{f.asOf ?? ''}</td>
                <td className="text-gray-600">{f.available ? `${BASIS_LABEL[f.basis] ?? f.basis} · ${f.source ?? ''}` : f.source}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  );
}
