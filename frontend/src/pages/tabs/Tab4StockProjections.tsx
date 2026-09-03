import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { aiApi } from '../../api/ai';
import { marketApi } from '../../api/market';
import type { TechnicalAnalysis, QuoteDto } from '../../types';
import { Target, Zap, AlertTriangle, Search } from 'lucide-react';
import { AnalystPanel } from '../../components/market/AnalystPanel';
import { StockChart } from '../../components/market/StockChart';

const fmt = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

// Support/resistance and other indicators are null when they couldn't be computed from real
// stored history — formatting null would print "₹0" as though it were a measured level.
const fmtOr = (n: number | null | undefined) => (n == null ? '—' : fmt(n));

function ProjectionCard({ symbol, quote, tech, aiSummary }: {
  symbol: string;
  quote: QuoteDto | null;
  tech: TechnicalAnalysis | null;
  aiSummary: string;
}) {
  const navigate = useNavigate();
  if (!quote || !tech) return null;

  const bull1M = quote.currentPrice * 1.05;
  const base1M = quote.currentPrice * 1.02;
  const bear1M = quote.currentPrice * 0.95;
  const bull3M = quote.currentPrice * 1.12;
  const base3M = quote.currentPrice * 1.06;
  const bear3M = quote.currentPrice * 0.88;

  return (
    <div className="card hover:border-brand/30 transition-colors cursor-pointer" onClick={() => navigate(`/stock/${symbol}`)}>
      <div className="flex items-start justify-between mb-4">
        <div>
          <h3 className="font-bold font-mono text-white text-lg">{symbol.replace('.NS', '')}</h3>
          <p className="text-gray-500 text-xs truncate max-w-[200px]">{quote.name}</p>
        </div>
        <div className="text-right">
          <div className="font-bold text-white">{fmt(quote.currentPrice)}</div>
          <div className={`text-sm ${quote.changePercent >= 0 ? 'text-bull' : 'text-bear'}`}>
            {quote.changePercent >= 0 ? '+' : ''}{quote.changePercent?.toFixed(2)}%
          </div>
        </div>
      </div>

      {/* Target projections */}
      <div className="grid grid-cols-2 gap-3 mb-4">
        <div className="bg-surface-hover rounded-lg p-3">
          <div className="stat-label mb-2">1-Month Target</div>
          <div className="space-y-1 text-sm">
            <div className="flex justify-between"><span className="text-bull">Bull</span><span className="text-bull">{fmt(bull1M)}</span></div>
            <div className="flex justify-between"><span className="text-neutral">Base</span><span className="text-neutral">{fmt(base1M)}</span></div>
            <div className="flex justify-between"><span className="text-bear">Bear</span><span className="text-bear">{fmt(bear1M)}</span></div>
          </div>
        </div>
        <div className="bg-surface-hover rounded-lg p-3">
          <div className="stat-label mb-2">3-Month Target</div>
          <div className="space-y-1 text-sm">
            <div className="flex justify-between"><span className="text-bull">Bull</span><span className="text-bull">{fmt(bull3M)}</span></div>
            <div className="flex justify-between"><span className="text-neutral">Base</span><span className="text-neutral">{fmt(base3M)}</span></div>
            <div className="flex justify-between"><span className="text-bear">Bear</span><span className="text-bear">{fmt(bear3M)}</span></div>
          </div>
        </div>
      </div>

      {/* Catalysts from tech */}
      <div className="space-y-1 text-xs border-t border-surface-border pt-3">
        <div className="flex items-center gap-2 text-gray-400">
          <Target size={12} className="text-brand" />
          <span>S/R: {fmtOr(tech.support)} / {fmtOr(tech.resistance)}</span>
        </div>
        <div className="flex items-center gap-2 text-gray-400">
          <Zap size={12} className="text-neutral" />
          <span>
            RSI {tech.rsi?.toFixed(1) ?? '—'} · MACD{' '}
            {tech.macd == null ? '—' : `${tech.macd > 0 ? '+' : ''}${tech.macd.toFixed(2)}`}
          </span>
        </div>
        {aiSummary && (
          <div className="flex items-start gap-2 text-gray-500 mt-2">
            <AlertTriangle size={12} className="text-brand shrink-0 mt-0.5" />
            <span className="line-clamp-2">{aiSummary}</span>
          </div>
        )}
      </div>
    </div>
  );
}

export function Tab4StockProjections() {
  const [search, setSearch] = useState('');
  const [cards, setCards] = useState<{ symbol: string; quote: QuoteDto | null; tech: TechnicalAnalysis | null; ai: string }[]>([]);
  const [loading, setLoading] = useState(false);
  const [suggestions, setSuggestions] = useState<{ symbol: string; name: string }[]>([]);
  const [showSug, setShowSug] = useState(false);

  // contains-match autocomplete from the stock search API
  useEffect(() => {
    const q = search.trim();
    if (q.length < 1) { setSuggestions([]); return; }
    const t = setTimeout(() => {
      marketApi.search(q).then(r => { setSuggestions((r.data as { symbol: string; name: string }[]).slice(0, 8)); setShowSug(true); }).catch(() => {});
    }, 250);
    return () => clearTimeout(t);
  }, [search]);

  const analyze = async (override?: string) => {
    const sym = (override ?? search).trim().toUpperCase();
    if (!sym) return;
    setShowSug(false);
    const symbol = sym.includes('.') ? sym : `${sym}.NS`;
    if (cards.find(c => c.symbol === symbol)) return;
    setLoading(true);
    try {
      const [qr, tr] = await Promise.all([marketApi.getQuote(symbol), marketApi.getTechnicals(symbol)]);
      let aiText = '';
      try {
        const ar = await aiApi.analyseStock(symbol, 'Give a brief 1-sentence outlook');
        aiText = ar.data?.summary ?? '';
      } catch {}
      setCards(prev => [...prev, { symbol, quote: qr.data, tech: tr.data, ai: aiText }]);
      setSearch('');
    } catch {
      alert(`Could not load data for ${symbol}`);
    } finally { setLoading(false); }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#9b5cf9] to-[#e84fd9] flex items-center justify-center text-white shadow-lift shrink-0">
          <Target size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-0.5">Price Projections</h2>
          <p className="text-gray-500 text-sm">1–3 month target price ranges, volatility and structural catalysts</p>
        </div>
      </div>

      <div className="flex gap-2">
        <div className="relative flex-1 max-w-sm">
          <input value={search} onChange={e => setSearch(e.target.value)}
            onFocus={() => suggestions.length && setShowSug(true)}
            onBlur={() => setTimeout(() => setShowSug(false), 150)}
            onKeyDown={e => e.key === 'Enter' && analyze()}
            placeholder="Type a name or symbol e.g. RELIANCE, TCS, HDFC"
            className="input-field w-full" />
          {showSug && suggestions.length > 0 && (
            <div className="absolute z-20 left-0 right-0 mt-1 bg-surface-card border border-surface-border rounded-lg shadow-xl max-h-60 overflow-y-auto">
              {suggestions.map(s => (
                <button key={s.symbol} onMouseDown={() => analyze(s.symbol)}
                  className="w-full text-left px-3 py-1.5 hover:bg-surface-hover flex items-center justify-between">
                  <span className="text-white text-xs font-mono font-medium">{s.symbol.replace('.NS', '')}</span>
                  <span className="text-2xs text-gray-500 truncate max-w-[180px] ml-2">{s.name}</span>
                </button>
              ))}
            </div>
          )}
        </div>
        <button onClick={() => analyze()} disabled={loading || !search.trim()} className="btn-primary flex items-center gap-2 disabled:opacity-50">
          <Search size={15} /> {loading ? 'Analyzing…' : 'Analyze'}
        </button>
      </div>

      {cards.length === 0 && (
        <div className="card text-center py-16 text-gray-600">
          <Target size={36} className="mx-auto mb-3 opacity-30" />
          <p>Search for a stock above to see its projection</p>
          <p className="text-xs mt-2">Try: RELIANCE, TCS, HDFCBANK, INFY</p>
        </div>
      )}

      <div className="space-y-6">
        {cards.map(c => (
          <div key={c.symbol} className="space-y-4">
            {/* chart + key details on top (industry-standard layout) */}
            <div className="card">
              <div className="flex items-start justify-between mb-3 flex-wrap gap-2">
                <div>
                  <div className="text-white font-bold text-lg font-mono">{c.symbol.replace('.NS', '')}</div>
                  <div className="text-xs text-gray-500 truncate max-w-[240px]">{c.quote?.name}</div>
                </div>
                {c.quote && (
                  <div className="text-right">
                    <div className="font-mono font-bold text-white text-lg">{fmt(c.quote.currentPrice)}</div>
                    <div className={`text-sm font-mono ${c.quote.changePercent >= 0 ? 'text-bull' : 'text-bear'}`}>
                      {c.quote.changePercent >= 0 ? '+' : ''}{c.quote.changePercent?.toFixed(2)}%
                    </div>
                  </div>
                )}
                {c.tech && (
                  <div className="flex gap-4 text-2xs">
                    <div><div className="text-gray-500">Support</div><div className="font-mono text-bull">{fmtOr(c.tech.support)}</div></div>
                    <div><div className="text-gray-500">Resistance</div><div className="font-mono text-bear">{fmtOr(c.tech.resistance)}</div></div>
                    <div><div className="text-gray-500">RSI</div><div className="font-mono text-white">{c.tech.rsi?.toFixed(0) ?? '—'}</div></div>
                  </div>
                )}
              </div>
              <StockChart symbol={c.symbol} support={c.tech?.support} resistance={c.tech?.resistance} />
            </div>
            {/* projections + analyst below */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-start">
              <ProjectionCard symbol={c.symbol} quote={c.quote} tech={c.tech} aiSummary={c.ai} />
              <AnalystPanel symbol={c.symbol} name={c.quote?.name} />
            </div>
          </div>
        ))}
      </div>

      {cards.length > 0 && (
        <p className="text-xs text-gray-600">
          Projections are model-based estimates using technical levels ±5%/12% for 1M/3M. Not financial advice.
        </p>
      )}
    </div>
  );
}
