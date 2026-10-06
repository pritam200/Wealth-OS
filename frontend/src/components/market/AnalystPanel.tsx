import { useEffect, useState } from 'react';
import { TrendingUp, TrendingDown, Minus, Sparkles, Newspaper, Gauge, Loader2, ArrowRight, HelpCircle } from 'lucide-react';
import { analystApi, isInsufficient, NON_CALL_LABEL } from '../../api/analyst';
import type { AnalystAssessment } from '../../api/analyst';
import { ViewPill } from '../research/ResearchPanel';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 }).format(n);
const fmtCr = (n: number | null | undefined) =>
  n == null ? '—' : n >= 1e7 ? `₹${(n / 1e7).toFixed(0)} Cr` : `₹${fmt(n)}`;

const RATING: Record<string, { cls: string; Icon: any }> = {
  BUY:  { cls: 'text-white border-transparent bg-bull-gradient shadow-glow-bull', Icon: TrendingUp },
  HOLD: { cls: 'text-gray-200 border-surface-border bg-surface-hover', Icon: Minus },
  NO_ACTIONABLE_SIGNAL: { cls: 'text-gray-300 border-surface-border bg-surface-hover', Icon: Minus },
  NOT_RATED: { cls: 'text-gray-400 border-dashed border-gray-700 bg-transparent', Icon: HelpCircle },
  STALE_DATA: { cls: 'text-amber-300 border-dashed border-amber-500/50 bg-transparent', Icon: HelpCircle },
  SELL: { cls: 'text-white border-transparent bg-bear-gradient shadow-glow-bear', Icon: TrendingDown },
};


// Covers both the stock (ACCUMULATE/CONTINUE/BOOK_PROFIT/EXIT/REVIEW/HOLD) and MF
// (CONTINUE_SIP/INCREASE_SIP/...) next-action value sets from RecommendationEngine.
const NEXT_ACTION_TONE: Record<string, string> = {
  ACCUMULATE: 'text-bull border-bull/40 bg-bull/10', CONTINUE: 'text-bull border-bull/40 bg-bull/10',
  CONTINUE_SIP: 'text-bull border-bull/40 bg-bull/10', INCREASE_SIP: 'text-bull border-bull/40 bg-bull/10',
  BOOK_PROFIT: 'text-neutral border-neutral/40 bg-neutral/10', PARTIAL_PROFIT_BOOKING: 'text-neutral border-neutral/40 bg-neutral/10',
  EXIT: 'text-bear border-bear/40 bg-bear/10', FULL_REDEMPTION: 'text-bear border-bear/40 bg-bear/10', SWITCH_FUND: 'text-bear border-bear/40 bg-bear/10',
  REVIEW: 'text-neutral border-neutral/40 bg-neutral/10', REBALANCE: 'text-neutral border-neutral/40 bg-neutral/10', PAUSE_SIP: 'text-neutral border-neutral/40 bg-neutral/10',
  HOLD: 'text-gray-300 border-surface-border bg-surface-hover',
  AVOID: 'text-bear border-bear/40 bg-bear/10',
  NO_ACTIONABLE_SIGNAL: 'text-gray-300 border-surface-border bg-surface-hover',
  STALE_DATA: 'text-amber-300 border-amber-500/40 bg-amber-500/10',
};
const nextActionLabel = (a: string) => a.replace(/_/g, ' ').replace(/\w\S*/g, w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase());

export function AnalystPanel({ symbol, name }: { symbol: string; name?: string }) {
  const [a, setA] = useState<AnalystAssessment | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    analystApi.assess(symbol, name).then(r => { if (alive) setA(r.data); }).catch(() => {}).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [symbol]);

  if (loading) return <div className="card h-32 flex items-center justify-center text-gray-500"><Loader2 size={15} className="animate-spin mr-2" /> Running analysis…</div>;
  if (!a) return null;

  // Only a BUY/SELL validated on this stock's own history is a call; everything else is a
  // labelled non-call (no actionable signal, stale or insufficient data), never a HOLD chip.
  const insufficient = isInsufficient(a);
  const rt = (a.rating && RATING[a.rating]) || RATING.NO_ACTIONABLE_SIGNAL;
  const ratingText = a.rating ? (NON_CALL_LABEL[a.rating] ?? a.rating) : 'Not rated';
  const RIcon = rt.Icon;
  const barColor = (s: number) => s >= 0 ? 'bg-bull-gradient' : 'bg-bear-gradient';

  return (
    <div className="card space-y-4">
      {/* Verdict header */}
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge icon-badge-brand"><Gauge size={15} /></div>
          <div>
            <h3 className="font-bold text-ink text-sm leading-tight">Analyst View</h3>
            <div className="text-2xs text-gray-500">{a.displayName}</div>
          </div>
        </div>
        {insufficient ? (
          <span className="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-lg border border-dashed border-gray-700 text-gray-500">
            <HelpCircle size={13} /> Insufficient data
          </span>
        ) : (
          <div className="flex items-center gap-2">
            <span className={`inline-flex items-center gap-1 text-xs font-bold px-2.5 py-1 rounded-lg border ${rt.cls}`}>
              <RIcon size={13} /> {ratingText}
            </span>
            {a.conviction && (
              <span className="text-2xs text-gray-500">{a.conviction.toLowerCase()} conviction (edge over base rate)</span>
            )}
          </div>
        )}
      </div>

      {/* Next action — the actual answer to "why BUY/HOLD/SELL/BOOK PROFIT", not just the rating */}
      {a.nextAction && !insufficient && (
        <div className="rounded-xl p-3 border" style={{
          borderColor: 'rgba(34,197,94,0.25)',
          backgroundImage: 'linear-gradient(135deg, rgba(34,197,94,0.10), rgba(52,211,153,0.05))',
        }}>
          <div className="flex items-center gap-2 mb-1.5 flex-wrap">
            <ArrowRight size={13} className="text-brand-light shrink-0" />
            <span className={`text-xs font-bold px-2.5 py-1 rounded-lg border ${NEXT_ACTION_TONE[a.nextAction] ?? NEXT_ACTION_TONE.HOLD}`}>
              {nextActionLabel(a.nextAction)}
            </span>
            {/* confidenceScore is the measured hit rate of this call on this stock's history —
                shown only when there is a validated call; it is not a probability of profit. */}
            {a.confidenceScore != null && (
              <span className="text-2xs text-gray-500">
                right <span className="font-mono font-bold text-ink">{a.confidenceScore}%</span> of the time historically
              </span>
            )}
          </div>
          {a.nextActionReason && <p className="text-xs text-gray-300 leading-snug">{a.nextActionReason}</p>}
        </div>
      )}

      {/* Factor breakdown */}
      <div className="space-y-2">
        {a.factorBreakdown.map(f => (
          <div key={f.name}>
            <div className="flex justify-between text-2xs mb-0.5">
              <span className="text-gray-400">
                {f.name}
                <span className="text-gray-600"> · {f.contributesToRating ? 'rating input' : 'context only'}</span>
              </span>
              {f.score != null
                ? <span className={f.score >= 0 ? 'text-bull' : 'text-bear'}>{f.score >= 0 ? '+' : ''}{f.score}</span>
                : f.reading ? <span className="text-gray-300">{f.reading.replace(/_/g, ' ')}</span> : null}
            </div>
            {f.score != null && (
              <div className="relative h-2 bg-surface-hover rounded-full overflow-hidden">
                <div className="absolute left-1/2 top-0 h-full w-px bg-gray-600/50" />
                <div className={`absolute top-0 h-full rounded-full transition-all duration-500 ease-snap ${barColor(f.score)}`}
                  style={{ left: f.score >= 0 ? '50%' : `${50 + f.score / 2}%`, width: `${Math.abs(f.score) / 2}%` }} />
              </div>
            )}
            <div className="text-2xs text-gray-600 mt-0.5">{f.note}</div>
          </div>
        ))}
      </div>

      {/* Fundamentals */}
      <div>
        <div className="stat-label text-2xs mb-1.5">Fundamentals</div>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
          {[
            ['P/E', a.fundamentals.pe != null ? a.fundamentals.pe.toFixed(1) : '—'],
            ['Market cap', fmtCr(a.fundamentals.marketCap)],
            ['52w range', a.fundamentals.weekLow52 != null ? `${fmt(a.fundamentals.weekLow52)}–${fmt(a.fundamentals.weekHigh52)}` : '—'],
            ['In range', a.fundamentals.pctOf52wRange != null ? `${a.fundamentals.pctOf52wRange}%` : '—'],
            ['Sector', a.fundamentals.sector ?? '—'],
            ['Trend', a.fundamentals.trend?.replace(/_/g, ' ') ?? '—'],
            ['RSI', a.fundamentals.rsi != null ? a.fundamentals.rsi.toFixed(0) : '—'],
          ].map(([l, v]) => (
            <div key={l as string} className="rounded-lg bg-surface-hover border border-surface-border/60 p-2 transition-colors hover:border-brand/30">
              <div className="text-2xs text-gray-600">{l}</div>
              <div className="font-mono text-ink text-xs font-semibold truncate">{v}</div>
            </div>
          ))}
        </div>
      </div>

      {/* Technical levels */}
      {a.technicals && (
        <div>
          <div className="stat-label text-2xs mb-1.5">Technical levels</div>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
            {[
              ['Support', a.technicals.support != null ? fmt(a.technicals.support) : '—'],
              ['Resistance', a.technicals.resistance != null ? fmt(a.technicals.resistance) : '—'],
              ['SMA 50', a.technicals.sma50 != null ? fmt(a.technicals.sma50) : '—'],
              ['SMA 200', a.technicals.sma200 != null ? fmt(a.technicals.sma200) : '—'],
              ['MACD', a.technicals.macd != null ? a.technicals.macd.toFixed(2) : '—'],
              ['ATR', a.technicals.atr != null ? fmt(a.technicals.atr) : '—'],
              ['Day %', a.technicals.dayChangePercent != null ? `${a.technicals.dayChangePercent >= 0 ? '+' : ''}${a.technicals.dayChangePercent.toFixed(2)}%` : '—'],
              ['Volume', a.technicals.volume != null ? fmt(a.technicals.volume) : '—'],
            ].map(([l, v]) => (
              <div key={l as string} className="rounded-lg bg-surface-hover border border-surface-border/60 p-2 transition-colors hover:border-brand/30">
                <div className="text-2xs text-gray-600">{l}</div>
                <div className="font-mono text-ink text-xs font-semibold">{v}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Positives / risks */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        <div>
          <div className="text-2xs text-bull mb-1 font-medium">Positives</div>
          <ul className="space-y-1">
            {a.positives.length ? a.positives.map((p, i) => <li key={i} className="text-2xs text-gray-400 flex gap-1.5"><span className="text-bull">▲</span>{p}</li>) : <li className="text-2xs text-gray-600">—</li>}
          </ul>
        </div>
        <div>
          <div className="text-2xs text-bear mb-1 font-medium">Risks</div>
          <ul className="space-y-1">
            {a.risks.length ? a.risks.map((p, i) => <li key={i} className="text-2xs text-gray-400 flex gap-1.5"><span className="text-bear">▼</span>{p}</li>) : <li className="text-2xs text-gray-600">—</li>}
          </ul>
        </div>
      </div>

      {/* Recent news — `news` is null on the INSUFFICIENT path */}
      {a.news && (a.news.articles?.length || a.news.total > 0) && (
        <div>
          <div className="flex items-center gap-1.5 text-2xs text-gray-500 mb-1.5">
            <Newspaper size={11} /> Recent news
            {a.news.score != null ? (
              <span>· sentiment <span className={a.news.score >= 0 ? 'text-bull' : 'text-bear'}>{a.news.score >= 0 ? '+' : ''}{a.news.score}</span>
              <span className="text-gray-600"> ({a.news.positive}+ / {a.news.negative}− of {a.news.total}{a.news.windowDays ? `, last ${a.news.windowDays} days` : ''}; {a.news.confidence?.toLowerCase() ?? 'low'} confidence; not a rating input)</span></span>
            ) : (
              <span className="text-gray-600">· sentiment unavailable (too few recent, relevant headlines)</span>
            )}
          </div>
          <div className="space-y-1">
            {a.news.articles?.length
              ? a.news.articles.map((art, i) => (
                <a key={i} href={art.url ?? '#'} target="_blank" rel="noreferrer"
                  className="block text-2xs text-gray-400 hover:text-brand leading-snug">
                  <span className="text-gray-600 mr-1">›</span>{art.title}
                  <span className="text-gray-700"> — {art.source}{art.publishedAt ? ` · ${art.publishedAt}` : ''}{art.sentiment && art.sentiment !== 'NEUTRAL' ? ` · ${art.sentiment.toLowerCase()}` : ''}</span>
                </a>
              ))
              : a.news.headlines.slice(0, 4).map((h, i) => <div key={i} className="text-2xs text-gray-500 truncate">• {h}</div>)}
          </div>
        </div>
      )}

      {/* Research view — from the one research engine (full research is on the stock page). */}
      <div className="rounded-xl border p-3" style={{ borderColor: 'rgba(5,150,105,0.30)' }}>
        <div className="flex items-center gap-2 flex-wrap">
          <div className="icon-badge icon-badge-sm icon-badge-violet"><Sparkles size={11} /></div>
          <span className="text-xs font-bold text-ink">Research view</span>
          {a.researchActionability ? <ViewPill value={a.researchActionability} /> : <span className="text-2xs text-gray-500">no research for today's data yet</span>}
        </div>
        {a.researchReason && <p className="text-2xs text-gray-400 mt-1.5">{a.researchReason}</p>}
        {a.researchAt && <p className="text-2xs text-gray-600 mt-1">Research {new Date(a.researchAt).toLocaleString('en-IN')} · <span className="font-mono">{a.researchModel}</span>. Open the stock page for the evidence and the devil's-advocate review.</p>}
      </div>

      <p className="text-2xs text-gray-700">{a.basis}</p>
    </div>
  );
}
