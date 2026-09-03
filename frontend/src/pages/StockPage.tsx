import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { marketApi } from '../api/market';
import { recommendationApi, isInsufficient } from '../api/analyst';
import type { AnalystAssessment } from '../api/analyst';
import { AiCopilot } from '../components/ai/AiCopilot';
import type { QuoteDto, TechnicalAnalysis, PriceHistory, NewsItem } from '../types';
import { TrendingUp, TrendingDown, ExternalLink, HelpCircle } from 'lucide-react';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts';
import { format, subDays } from 'date-fns';

const fmt = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(n);

// Indicators are null whenever they couldn't be computed from real stored history —
// formatting a null through Intl would print a confident "₹0.00" that isn't a measurement.
const fmtOr = (n: number | null | undefined) => (n == null ? '—' : fmt(n));
const numOr = (n: number | null | undefined, digits: number) => (n == null ? '—' : n.toFixed(digits));

// The verdict shown here comes from the centralized RecommendationEngine (the same call
// AnalystPanel and HoldingTrendBadge make), never from the raw 4-vote technical signal —
// that's what used to let this page say BUY while every other tab said BOOK PROFIT for the
// very same stock.
const VERDICT_TONE: Record<string, string> = {
  ACCUMULATE:  'bg-bull/20 text-bull border-bull/30',
  CONTINUE:    'bg-bull/20 text-bull border-bull/30',
  BOOK_PROFIT: 'bg-yellow-400/15 text-yellow-400 border-yellow-400/30',
  EXIT:        'bg-bear/20 text-bear border-bear/30',
  REVIEW:      'bg-orange-400/15 text-orange-400 border-orange-400/30',
  HOLD:        'bg-neutral/20 text-neutral border-neutral/30',
};
const verdictLabel = (a: string) => a.replace(/_/g, ' ');

const RANGES = [
  { label: '1W', days: 7 },
  { label: '1M', days: 30 },
  { label: '3M', days: 90 },
  { label: '6M', days: 180 },
  { label: '1Y', days: 365 },
];

export function StockPage() {
  const { symbol } = useParams<{ symbol: string }>();
  const [quote, setQuote] = useState<QuoteDto | null>(null);
  const [tech, setTech] = useState<TechnicalAnalysis | null>(null);
  const [assessment, setAssessment] = useState<AnalystAssessment | null>(null);
  const [history, setHistory] = useState<PriceHistory[]>([]);
  const [news, setNews] = useState<NewsItem[]>([]);
  const [range, setRange] = useState(RANGES[1]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!symbol) return;
    setLoading(true);
    Promise.all([
      marketApi.getQuote(symbol),
      marketApi.getTechnicals(symbol),
      marketApi.getStockNews(symbol),
    ]).then(([qr, tr, nr]) => {
      setQuote(qr.data);
      setTech(tr.data);
      setNews(nr.data?.content ?? []);
    }).finally(() => setLoading(false));
  }, [symbol]);

  // Kept separate from the Promise.all above so a recommendation outage can't blank the
  // whole page, and so a slow engine call doesn't hold up the quote/chart render.
  useEffect(() => {
    if (!symbol) return;
    let alive = true;
    setAssessment(null);
    recommendationApi.get(symbol)
      .then(r => { if (alive) setAssessment(r.data); })
      .catch(() => { if (alive) setAssessment(null); });
    return () => { alive = false; };
  }, [symbol]);

  useEffect(() => {
    if (!symbol) return;
    const to = format(new Date(), 'yyyy-MM-dd');
    const from = format(subDays(new Date(), range.days), 'yyyy-MM-dd');
    marketApi.getHistory(symbol, from, to)
      .then(r => setHistory(r.data ?? []))
      .catch(() => setHistory([]));
  }, [symbol, range]);

  if (loading) return (
    <div className="space-y-4">
      {[...Array(3)].map((_, i) => (
        <div key={i} className="card animate-pulse h-32 bg-surface-hover" />
      ))}
    </div>
  );

  if (!quote) return <div className="card text-gray-500 text-center py-16">Stock not found</div>;

  const isPositive = quote.changePercent >= 0;
  const chartColor = isPositive ? '#22c55e' : '#ef4444';

  const chartData = history.map(h => ({
    date: h.date,
    close: h.close,
    label: format(new Date(h.date), 'd MMM'),
  }));

  const minClose = Math.min(...chartData.map(d => d.close)) * 0.995;
  const maxClose = Math.max(...chartData.map(d => d.close)) * 1.005;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="card">
        <div className="flex items-start justify-between flex-wrap gap-4">
          <div>
            <h1 className="text-3xl font-bold font-mono text-white">{quote.symbol}</h1>
            <p className="text-gray-400 mt-1">{quote.name}</p>
            {quote.sector && (
              <span className="text-xs bg-brand/10 text-brand border border-brand/20 px-2 py-0.5 rounded-full mt-2 inline-block">
                {quote.sector}
              </span>
            )}
          </div>
          <div className="text-right">
            <div className="text-4xl font-bold text-white">{fmt(quote.currentPrice)}</div>
            <div className={`flex items-center gap-1 justify-end mt-1 ${isPositive ? 'text-bull' : 'text-bear'}`}>
              {isPositive ? <TrendingUp size={16} /> : <TrendingDown size={16} />}
              <span className="font-medium">
                {isPositive ? '+' : ''}{fmt(quote.change)} ({isPositive ? '+' : ''}{quote.changePercent?.toFixed(2)}%)
              </span>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-4 gap-4 mt-6 pt-4 border-t border-surface-border">
          {[
            { label: 'Open', value: fmt(quote.open) },
            { label: 'High', value: fmt(quote.high) },
            { label: 'Low', value: fmt(quote.low) },
            { label: 'Prev Close', value: fmt(quote.previousClose) },
          ].map(i => (
            <div key={i.label}>
              <div className="stat-label">{i.label}</div>
              <div className="text-white font-medium mt-1">{i.value}</div>
            </div>
          ))}
        </div>

        {/* 52W + Market Cap */}
        <div className="grid grid-cols-3 gap-4 mt-4 pt-4 border-t border-surface-border">
          <div>
            <div className="stat-label">52W High</div>
            <div className="text-bull font-medium mt-1">{fmt(quote.weekHigh52)}</div>
          </div>
          <div>
            <div className="stat-label">52W Low</div>
            <div className="text-bear font-medium mt-1">{fmt(quote.weekLow52)}</div>
          </div>
          <div>
            <div className="stat-label">P/E Ratio</div>
            <div className="text-white font-medium mt-1">{quote.pe?.toFixed(1) ?? '—'}</div>
          </div>
        </div>
      </div>

      {/* Price Chart */}
      <div className="card">
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-semibold text-white">Price History</h2>
          <div className="flex gap-1">
            {RANGES.map(r => (
              <button
                key={r.label}
                onClick={() => setRange(r)}
                className={`px-3 py-1 rounded text-xs font-medium transition-all ${
                  range.label === r.label
                    ? 'bg-brand text-white'
                    : 'text-gray-500 hover:text-white hover:bg-surface-hover'
                }`}
              >
                {r.label}
              </button>
            ))}
          </div>
        </div>

        {chartData.length > 0 ? (
          <ResponsiveContainer width="100%" height={260}>
            <AreaChart data={chartData} margin={{ top: 4, right: 4, left: 0, bottom: 0 }}>
              <defs>
                <linearGradient id="chartGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={chartColor} stopOpacity={0.25} />
                  <stop offset="95%" stopColor={chartColor} stopOpacity={0} />
                </linearGradient>
              </defs>
              <XAxis
                dataKey="label"
                tick={{ fill: '#6b7280', fontSize: 11 }}
                axisLine={false}
                tickLine={false}
                interval="preserveStartEnd"
              />
              <YAxis
                domain={[minClose, maxClose]}
                tick={{ fill: '#6b7280', fontSize: 11 }}
                axisLine={false}
                tickLine={false}
                tickFormatter={v => `₹${v.toLocaleString('en-IN')}`}
                width={80}
              />
              <Tooltip
                contentStyle={{ background: '#1a1d27', border: '1px solid #2a2d3e', borderRadius: 8, fontSize: 12 }}
                labelStyle={{ color: '#9ca3af' }}
                formatter={(v) => [fmt(Number(v ?? 0)), 'Close']}
              />
              {tech?.support && <ReferenceLine y={tech.support} stroke="#22c55e" strokeDasharray="4 4" strokeOpacity={0.5} label={{ value: 'Support', fill: '#22c55e', fontSize: 10 }} />}
              {tech?.resistance && <ReferenceLine y={tech.resistance} stroke="#ef4444" strokeDasharray="4 4" strokeOpacity={0.5} label={{ value: 'Resistance', fill: '#ef4444', fontSize: 10 }} />}
              <Area type="monotone" dataKey="close" stroke={chartColor} strokeWidth={2} fill="url(#chartGrad)" dot={false} />
            </AreaChart>
          </ResponsiveContainer>
        ) : (
          <div className="h-48 flex items-center justify-center text-gray-600 text-sm">
            Price history not available
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Technical Analysis */}
        {tech && (
          <div className="card">
            <div className="flex items-start justify-between gap-3 mb-6">
              <div>
                <h2 className="font-semibold text-white">Technical Analysis</h2>
                <p className="text-2xs text-gray-600 mt-0.5">Indicator values below · verdict from the recommendation engine</p>
              </div>
              <EngineVerdict a={assessment} />
            </div>

            {assessment?.nextActionReason && (
              <p className="text-xs text-gray-400 leading-snug mb-4 -mt-2">{assessment.nextActionReason}</p>
            )}

            <div className="grid grid-cols-2 gap-3 mb-4">
              {[
                { label: 'RSI (14)', value: numOr(tech.rsi, 1),
                  cls: tech.rsi == null ? 'text-gray-600' : tech.rsi < 30 ? 'text-bull' : tech.rsi > 70 ? 'text-bear' : 'text-white' },
                { label: 'MACD', value: numOr(tech.macd, 2),
                  cls: tech.macd == null ? 'text-gray-600' : tech.macd > 0 ? 'text-bull' : 'text-bear' },
                { label: 'Trend', value: tech.trend?.replace(/_/g, ' ') ?? '—',
                  cls: tech.trend?.includes('UP') ? 'text-bull' : tech.trend?.includes('DOWN') ? 'text-bear' : 'text-neutral' },
                { label: 'ATR', value: fmtOr(tech.atr), cls: tech.atr == null ? 'text-gray-600' : 'text-white' },
              ].map(i => (
                <div key={i.label} className="bg-surface-hover rounded-lg p-3">
                  <div className="stat-label">{i.label}</div>
                  <div className={`font-semibold mt-1 ${i.cls}`}>{i.value}</div>
                </div>
              ))}
            </div>

            <div className="space-y-2 pt-2 border-t border-surface-border">
              {[
                { label: 'SMA 20 / 50 / 200', value: `${fmtOr(tech.sma20)} · ${fmtOr(tech.sma50)} · ${fmtOr(tech.sma200)}` },
                { label: 'Bollinger Bands', value: `${fmtOr(tech.bollingerLower)} – ${fmtOr(tech.bollingerUpper)}` },
                { label: 'Support / Resistance', value: `${fmtOr(tech.support)} / ${fmtOr(tech.resistance)}` },
              ].map(i => (
                <div key={i.label} className="flex justify-between text-sm">
                  <span className="text-gray-500">{i.label}</span>
                  <span className="text-gray-300 font-mono text-xs">{i.value}</span>
                </div>
              ))}
            </div>

            {tech.dataQuality === 'PARTIAL' && (
              <p className="text-2xs text-gray-600 mt-3 pt-2 border-t border-surface-border/60">
                Based on {tech.barsAvailable ?? '<200'} days of stored history — the 200-day
                average and long-term trend filter aren't available yet.
              </p>
            )}
          </div>
        )}

        {/* News for this stock */}
        <div className="card">
          <h2 className="font-semibold text-white mb-4">Related News</h2>
          <div className="space-y-3 overflow-y-auto max-h-72">
            {news.length === 0 ? (
              <p className="text-gray-600 text-sm text-center py-8">No related news</p>
            ) : news.map(item => (
              <a
                key={item.id}
                href={item.url}
                target="_blank"
                rel="noopener noreferrer"
                className="block p-3 rounded-lg hover:bg-surface-hover transition-colors group"
              >
                <div className="flex items-start gap-2">
                  <p className="text-sm text-gray-300 group-hover:text-white line-clamp-2 flex-1">{item.title}</p>
                  <ExternalLink size={12} className="text-gray-600 shrink-0 mt-0.5" />
                </div>
                <div className="flex gap-2 mt-1">
                  <span className="text-xs text-gray-600">{item.source}</span>
                  <span className={`text-xs px-1.5 py-0.5 rounded ${
                    item.sentiment === 'POSITIVE' ? 'badge-bull' :
                    item.sentiment === 'NEGATIVE' ? 'badge-bear' : 'badge-neutral'
                  }`}>{item.sentiment}</span>
                </div>
              </a>
            ))}
          </div>
        </div>
      </div>

      {/* AI Analysis */}
      <AiCopilot />
    </div>
  );
}

// The headline verdict for this page. Sourced from /api/recommendation/{symbol} so it is
// literally the same value AI Advisor, the Analyst panel and the holdings badge display.
// When the engine says it doesn't have enough data, this shows a neutral "Insufficient data"
// chip — never a fabricated BUY/HOLD/SELL.
function EngineVerdict({ a }: { a: AnalystAssessment | null }) {
  if (!a) return <span className="text-xs text-gray-600 shrink-0">Verdict unavailable</span>;

  if (isInsufficient(a)) {
    return (
      <span
        title={a.nextActionReason ?? a.risks?.join(' · ') ?? 'Not enough stored price history to analyse this symbol.'}
        className="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-full border border-dashed border-gray-700 text-gray-500 shrink-0"
      >
        <HelpCircle size={12} /> Insufficient data
      </span>
    );
  }

  return (
    <span
      title={a.nextActionReason ?? undefined}
      className={`inline-flex items-center gap-1 text-xs font-bold px-2.5 py-1 rounded-full border shrink-0 ${
        VERDICT_TONE[a.nextAction] ?? VERDICT_TONE.HOLD
      }`}
    >
      {verdictLabel(a.nextAction)}
      <span className="font-normal opacity-75">· {a.confidenceScore}% confidence</span>
    </span>
  );
}
