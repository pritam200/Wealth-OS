import { useState, useRef, useEffect } from 'react';
import { Search } from 'lucide-react';
import { marketApi } from '../../api/market';
import { useNavigate } from 'react-router-dom';

export function StockSearch() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<any[]>([]);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (query.length < 2) { setResults([]); return; }
    const timer = setTimeout(() => {
      marketApi.search(query)
        .then(r => { setResults(r.data.slice(0, 8)); setOpen(true); })
        .catch(() => {});
    }, 300);
    return () => clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const select = (symbol: string) => {
    setQuery('');
    setOpen(false);
    navigate(`/stock/${symbol}`);
  };

  return (
    <div ref={ref} className="relative w-72">
      <div className="flex items-center bg-surface-hover/70 border border-surface-border rounded-full px-3.5 gap-2 h-9 transition-all focus-within:border-brand/50 focus-within:shadow-glow">
        <Search size={14} className="text-gray-500 shrink-0" />
        <input
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="Search stocks, funds…"
          className="bg-transparent text-sm text-gray-200 placeholder-gray-600 w-full h-full outline-none"
        />
        <kbd className="hidden md:inline text-2xs text-gray-600 bg-surface-panel border border-surface-border rounded px-1.5 py-0.5 font-mono shrink-0">/</kbd>
      </div>

      {open && results.length > 0 && (
        <div className="absolute top-full left-0 right-0 mt-2 bg-surface-card border border-surface-border rounded-2xl shadow-panel z-50 overflow-hidden">
          {results.map((s: any) => (
            <button
              key={s.symbol}
              onClick={() => select(s.symbol)}
              className="w-full flex items-center justify-between px-4 py-2.5 hover:bg-surface-hover transition-colors text-left"
            >
              <div>
                <span className="text-sm font-semibold text-white font-mono">{s.symbol}</span>
                <p className="text-xs text-gray-500">{s.name}</p>
              </div>
              <span className="text-xs text-gray-600 bg-surface-hover px-1.5 py-0.5 rounded-full">{s.exchange}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
