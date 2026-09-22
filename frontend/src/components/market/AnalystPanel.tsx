import { useEffect, useState } from 'react';
import { TrendingUp, TrendingDown, Minus, Sparkles, Newspaper, Gauge, Loader2, ArrowRight, HelpCircle } from 'lucide-react';
import { analystApi, isInsufficient } from '../../api/analyst';
import type { AnalystAssessment } from '../../api/analyst';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 }).format(n);
const fmtCr = (n: number | null | undefined) =>
  n == null ? '—' : n >= 1e7 ? `₹${(n / 1e7).toFixed(0)} Cr` : `₹${fmt(n)}`;

const RATING: Record<string, { cls: string; Icon: any }> = {
  BUY:  { cls: 'text-white border-transparent bg-bull-gradient shadow-glow-bull', Icon: TrendingUp },
  HOLD: { cls: 'text-gray-200 border-surface-border bg-surface-hover', Icon: Minus },
  SELL: { cls: 'text-white border-transparent bg-bear-gradient shadow-glow-bear', Icon: TrendingDown },
};

// The AI verdict is tinted, not gradient-filled: a solid pill would compete with the
// authoritative deterministic rating above it. WATCH is the model's "data too thin" answer.
const AI_RATING_TONE: Record<string, string> = {
  BUY:   'bg-bull/10 text-bull border-bull/40',
  HOLD:  'bg-surface-hover text-gray-300 border-surface-border',
  SELL:  'bg-bear/10 text-bear border-bear/40',
  WATCH: 'bg-brand/10 text-brand-light border-brand/40',
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

  if (loading) return <div className="card h-32 flex items-center justify-center text-gray-500"><Loader2 size={15} className="animate-spin mr-2" /> Running multi-factor analysis…</div>;
  if (!a) return null;

  // rating is null on the INSUFFICIENT_DATA path — the backend deliberately emits no
  // BUY/HOLD/SELL when there wasn't enough history to compute one.
  const insufficient = isInsufficient(a);
  const rt = (a.rating && RATING[a.rating]) || RATING.HOLD;
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
              <RIcon size={13} /> {a.rating}
            </span>
            <span className="text-2xs text-gray-500">
              {a.conviction} conviction · score <span className="font-mono font-bold text-ink">{a.compositeScore}</span>
            </span>
          </div>
        )}
      </div>

      {/* Next action — the actual answer to "why BUY/HOLD/SELL/BOOK PROFIT", not just the rating */}
      {a.nextAction && !insufficient && (
        <div className="rounded-xl p-3 border" style={{
          borderColor: 'rgba(99,102,241,0.25)',
          backgroundImage: 'linear-gradient(135deg, rgba(99,102,241,0.10), rgba(168,85,247,0.05))',
        }}>
          <div className="flex items-center gap-2 mb-1.5 flex-wrap">
            <ArrowRight size={13} className="text-brand-light shrink-0" />
            <span className={`text-xs font-bold px-2.5 py-1 rounded-lg border ${NEXT_ACTION_TONE[a.nextAction] ?? NEXT_ACTION_TONE.HOLD}`}>
              {nextActionLabel(a.nextAction)}
            </span>
            {/* Confidence as a meter, not just a number — it's the main "how much weight
                should I put on this" signal and was easy to skim past as text. */}
            <span className="text-2xs text-gray-500">confidence</span>
            <span className="w-16 meter" style={{ height: '5px' }}>
              <span className={`meter-fill block ${a.confidenceScore >= 60 ? 'bg-bull-gradient' : a.confidenceScore >= 30 ? 'bg-gold-gradient' : 'bg-bear-gradient'}`}
                style={{ width: `${Math.max(0, Math.min(100, a.confidenceScore))}%` }} />
            </span>
            <span className="text-2xs font-mono font-bold text-ink">{a.confidenceScore}%</span>
          </div>
          {a.nextActionReason && <p className="text-xs text-gray-300 leading-snug">{a.nextActionReason}</p>}
        </div>
      )}

      {/* Factor breakdown */}
      <div className="space-y-2">
        {a.factorBreakdown.map(f => (
          <div key={f.name}>
            <div className="flex justify-between text-2xs mb-0.5">
              <span className="text-gray-400">{f.name} <span className="text-gray-600">· {f.weightPct}%</span></span>
              <span className={f.score >= 0 ? 'text-bull' : 'text-bear'}>{f.score >= 0 ? '+' : ''}{f.score}</span>
            </div>
            {/* centered -100..100 bar */}
            <div className="relative h-2 bg-surface-hover rounded-full overflow-hidden">
              <div className="absolute left-1/2 top-0 h-full w-px bg-gray-600/50" />
              <div className={`absolute top-0 h-full rounded-full transition-all duration-500 ease-snap ${barColor(f.score)}`}
                style={{ left: f.score >= 0 ? '50%' : `${50 + f.score / 2}%`, width: `${Math.abs(f.score) / 2}%` }} />
            </div>
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
            ['Trend', a.fundamentals.trend?.replace('_', ' ')],
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
            {a.news.positive + a.news.negative > 0 && (
              <span>· sentiment <span className={a.news.score >= 0 ? 'text-bull' : 'text-bear'}>{a.news.score >= 0 ? '+' : ''}{a.news.score}</span>
              <span className="text-gray-600"> ({a.news.positive}+ / {a.news.negative}−)</span></span>
            )}
          </div>
          <div className="space-y-1">
            {a.news.articles?.length
              ? a.news.articles.map((art, i) => (
                <a key={i} href={art.url ?? '#'} target="_blank" rel="noreferrer"
                  className="block text-2xs text-gray-400 hover:text-brand leading-snug">
                  <span className="text-gray-600 mr-1">›</span>{art.title}
                  <span className="text-gray-700"> — {art.source}{art.publishedAt ? ` · ${art.publishedAt}` : ''}</span>
                </a>
              ))
              : a.news.headlines.slice(0, 4).map((h, i) => <div key={i} className="text-2xs text-gray-500 truncate">• {h}</div>)}
          </div>
        </div>
      )}

      {/* AI second opinion — the model's own call, shown BESIDE the deterministic rating.
          Never presented as the verdict: the label, the agreement badge and the footnote all
          make clear which number is authoritative. */}
      {(a.aiRating || a.aiNarrative) && (
        <div className="relative overflow-hidden rounded-xl border" style={{ borderColor: 'rgba(124,58,237,0.30)' }}>
          <div className="h-0.5 w-full bg-violet-gradient" />
          <div className="p-3" style={{ background: 'linear-gradient(180deg, rgba(124,58,237,0.06), transparent 70%)' }}>
            <div className="flex items-center justify-between flex-wrap gap-2 mb-2">
              <div className="flex items-center gap-2">
                <div className="icon-badge icon-badge-sm icon-badge-violet"><Sparkles size={11} /></div>
                <span className="text-xs font-bold text-ink">AI Second Opinion</span>
              </div>
              <div className="flex items-center gap-1.5">
                {a.aiRating && (
                  <span className={`inline-flex items-center gap-1 text-xs font-bold px-2.5 py-1 rounded-lg border ${AI_RATING_TONE[a.aiRating]}`}>
                    {a.aiRating}
                  </span>
                )}
                {a.aiAgrees != null && a.rating && (
                  <span className={`text-2xs font-semibold px-2 py-1 rounded-full border ${
                    a.aiAgrees ? 'bg-bull/10 text-bull border-bull/30' : 'bg-neutral/10 text-neutral border-neutral/30'}`}>
                    {a.aiAgrees ? `agrees with model (${a.rating})` : `differs — model says ${a.rating}`}
                  </span>
                )}
              </div>
            </div>

            {(a.aiKeyDriver || a.aiMainRisk) && (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mb-2">
                {a.aiKeyDriver && (
                  <div className="rounded-lg bg-surface-card/70 border border-surface-border p-2">
                    <div className="text-2xs font-semibold text-bull mb-0.5">Key driver</div>
                    <p className="text-2xs text-gray-400 leading-snug">{a.aiKeyDriver}</p>
                  </div>
                )}
                {a.aiMainRisk && (
                  <div className="rounded-lg bg-surface-card/70 border border-surface-border p-2">
                    <div className="text-2xs font-semibold text-bear mb-0.5">Main risk</div>
                    <p className="text-2xs text-gray-400 leading-snug">{a.aiMainRisk}</p>
                  </div>
                )}
              </div>
            )}

            {a.aiNarrative && <p className="text-xs text-gray-300 leading-relaxed">{a.aiNarrative}</p>}

            <p className="text-2xs text-gray-700 mt-2 pt-2 border-t border-surface-border">
              Advisory only — the {a.rating ?? 'model'} rating and score above are computed from the
              factor weights, not from the AI. {a.aiProvider && <>Model: <span className="font-mono">{a.aiProvider}</span>.</>}
            </p>
          </div>
        </div>
      )}

      <p className="text-2xs text-gray-700">{a.basis}</p>
    </div>
  );
}
