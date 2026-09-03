import { useEffect, useState } from 'react';
import { marketApi } from '../../api/market';
import type { NewsItem } from '../../types';
import { ExternalLink, TrendingUp, TrendingDown, AlertTriangle, RefreshCw, Newspaper } from 'lucide-react';
import { format } from 'date-fns';

const RISK_LABELS: Record<string, { label: string; cls: string; Icon: any }> = {
  POSITIVE: { label: 'Bullish', cls: 'badge-bull', Icon: TrendingUp },
  NEGATIVE: { label: 'Bearish', cls: 'badge-bear', Icon: TrendingDown },
  NEUTRAL:  { label: 'Watch',   cls: 'badge-neutral', Icon: AlertTriangle },
};

function NewsCard({ item }: { item: NewsItem }) {
  const risk = RISK_LABELS[item.sentiment] ?? RISK_LABELS.NEUTRAL;
  const Icon = risk.Icon;
  return (
    <a href={item.url} target="_blank" rel="noopener noreferrer"
      className="block card hover:border-brand/30 transition-all group">
      <div className="flex items-start gap-3">
        <div className="flex-1 min-w-0">
          <h3 className="text-sm font-medium text-gray-200 group-hover:text-white leading-snug mb-2 line-clamp-3">
            {item.title}
          </h3>
          {item.description && (
            <p className="text-xs text-gray-600 line-clamp-2 mb-2">{item.description}</p>
          )}
          <div className="flex items-center gap-3">
            <span className="text-xs text-gray-600">{item.source}</span>
            {item.publishedAt && (
              <span className="text-xs text-gray-700">
                {format(new Date(item.publishedAt), 'd MMM, HH:mm')}
              </span>
            )}
            <span className={`flex items-center gap-1 text-xs px-2 py-0.5 rounded-full ${risk.cls}`}>
              <Icon size={10} /> {risk.label}
            </span>
          </div>
        </div>
        <ExternalLink size={14} className="text-gray-600 shrink-0 mt-0.5 group-hover:text-brand" />
      </div>
    </a>
  );
}

export function Tab5NewsAndCatalysts() {
  const [news, setNews] = useState<NewsItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<'ALL' | 'POSITIVE' | 'NEGATIVE' | 'NEUTRAL'>('ALL');
  const [page, setPage] = useState(0);

  const load = (p = 0) => {
    setLoading(true);
    marketApi.getNews(p).then(r => {
      const items = r.data.content ?? [];
      setNews(prev => p === 0 ? items : [...prev, ...items]);
    }).finally(() => setLoading(false));
  };

  useEffect(() => { load(0); }, []);

  const filtered = filter === 'ALL' ? news : news.filter(n => n.sentiment === filter);
  const bullCount = news.filter(n => n.sentiment === 'POSITIVE').length;
  const bearCount = news.filter(n => n.sentiment === 'NEGATIVE').length;
  const neutCount = news.filter(n => n.sentiment === 'NEUTRAL').length;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#ffb454] to-[#ff8a5b] flex items-center justify-center text-white shadow-lift shrink-0">
            <Newspaper size={20} />
          </div>
          <div>
            <h2 className="text-xl font-bold text-white mb-0.5">News &amp; Catalysts</h2>
            <p className="text-gray-500 text-sm">Weekly micro/macro events with sentiment tagging</p>
          </div>
        </div>
        <button onClick={() => load(0)} className="btn-secondary flex items-center gap-2">
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> Refresh
        </button>
      </div>

      {/* Sentiment summary */}
      <div className="grid grid-cols-3 gap-3">
        <div className="card text-center border-bull/20">
          <div className="text-2xl font-bold text-bull">{bullCount}</div>
          <div className="text-xs text-gray-500 mt-1">Bullish</div>
        </div>
        <div className="card text-center border-neutral/20">
          <div className="text-2xl font-bold text-neutral">{neutCount}</div>
          <div className="text-xs text-gray-500 mt-1">Watchful</div>
        </div>
        <div className="card text-center border-bear/20">
          <div className="text-2xl font-bold text-bear">{bearCount}</div>
          <div className="text-xs text-gray-500 mt-1">Bearish</div>
        </div>
      </div>

      {/* Filter tabs */}
      <div className="flex gap-2">
        {(['ALL', 'POSITIVE', 'NEGATIVE', 'NEUTRAL'] as const).map(f => (
          <button key={f} onClick={() => setFilter(f)}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
              filter === f ? 'bg-brand text-white' : 'bg-surface-card border border-surface-border text-gray-400 hover:text-white'
            }`}>
            {f === 'POSITIVE' ? 'Bullish' : f === 'NEGATIVE' ? 'Bearish' : f === 'NEUTRAL' ? 'Neutral' : 'All'}
          </button>
        ))}
      </div>

      {news.length === 0 && !loading && (
        <div className="card text-center py-16 text-gray-600">
          <p>No news available. Configure NEWS_API_KEY in backend to enable live news.</p>
        </div>
      )}

      <div className="space-y-3">
        {filtered.map(item => <NewsCard key={item.id} item={item} />)}
      </div>

      {filtered.length > 0 && (
        <div className="text-center">
          <button onClick={() => { const next = page + 1; setPage(next); load(next); }}
            disabled={loading}
            className="btn-ghost disabled:opacity-50">
            {loading ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </div>
  );
}
