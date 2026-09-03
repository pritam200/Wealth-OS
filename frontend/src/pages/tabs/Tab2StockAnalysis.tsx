import { useEffect, useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { marketApi } from '../../api/market';
import { portfolioApi } from '../../api/portfolio';
import type { TechnicalAnalysis, Portfolio } from '../../types';
import { RefreshCw, Plus, X, Trash2, Upload, Download, LineChart } from 'lucide-react';
import { format } from 'date-fns';
import { HoldingTrendBadge } from '../../components/wealth/HoldingTrendBadge';
import { useMaskedText } from '../../components/shared/Amount';

const DEFAULT_WATCHLIST = [
  'RELIANCE.NS','TCS.NS','HDFCBANK.NS','INFY.NS',
  'ICICIBANK.NS','SBIN.NS','WIPRO.NS','HINDUNILVR.NS',
];

// Indicators are null whenever the backend couldn't compute them from real stored history,
// so this must render an em-dash rather than a confident-looking "0".
const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 }).format(n);

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

/* CSV format: Symbol,Quantity,Buy Price,Date,Company Name
   e.g. RELIANCE.NS,10,2450.50,2024-01-15,Reliance Industries */
async function handleCSV(
  e: React.ChangeEvent<HTMLInputElement>,
  portfolio: { id: number } | null,
  onDone: () => void
) {
  const file = e.target.files?.[0];
  if (!file || !portfolio) return;
  const text = await file.text();
  const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
  // skip header line if it starts with non-numeric
  const dataLines = lines.filter(l => /^\d|^[A-Z]/.test(l) && !/symbol/i.test(l.split(',')[0]));
  let ok = 0;
  let fail = 0;
  for (const line of dataLines) {
    const parts = line.split(',');
    const symbol = parts[0]?.trim();
    const qty    = parseFloat(parts[1]);
    const price  = parseFloat(parts[2]);
    const date   = parts[3]?.trim() || new Date().toISOString().slice(0, 10);
    const name   = parts[4]?.trim() || symbol;
    if (!symbol || isNaN(qty) || isNaN(price)) { fail++; continue; }
    const sym = symbol.includes('.') ? symbol : `${symbol}.NS`;
    try {
      await portfolioApi.addHolding(portfolio.id, { symbol: sym, name, quantity: qty, price, transactionDate: date });
      ok++;
    } catch { fail++; }
  }
  e.target.value = '';
  alert(`CSV import done: ${ok} added${fail ? `, ${fail} failed (check format)` : ''}.`);
  onDone();
}

function downloadCSVTemplate() {
  const csv = 'Symbol,Quantity,Buy Price,Date,Company Name\nRELIANCE.NS,10,2450.50,2024-01-15,Reliance Industries\nTCS.NS,5,3800,2023-11-20,TCS Ltd\n';
  const a = document.createElement('a');
  a.href = 'data:text/csv;charset=utf-8,' + encodeURIComponent(csv);
  a.download = 'portfolio_template.csv';
  a.click();
}

// The backend only ever emits these six values — it has never emitted BULLISH/BEARISH, which
// is what this badge used to compare against, so it was permanently stuck on the neutral
// style and printed the raw SCREAMING_SNAKE enum.
const TREND_META: Record<string, { cls: string; label: string; title: string }> = {
  STRONG_UPTREND:   { cls: 'badge-bull',    label: 'Strong Uptrend',   title: 'Price above the 20/50/200-day averages with strong momentum' },
  UPTREND:          { cls: 'badge-bull',    label: 'Uptrend',          title: 'Price trending above its shorter-term moving averages' },
  SIDEWAYS:         { cls: 'badge-neutral', label: 'Sideways',         title: 'No clear directional trend' },
  DOWNTREND:        { cls: 'badge-bear',    label: 'Downtrend',        title: 'Price trending below its shorter-term moving averages' },
  STRONG_DOWNTREND: { cls: 'badge-bear',    label: 'Strong Downtrend', title: 'Price below the 20/50/200-day averages with strong downward momentum' },
  UNKNOWN:          { cls: 'badge-neutral opacity-50', label: 'Unknown', title: 'Not enough stored price history to determine a trend' },
};

function TrendBadge({ trend }: { trend?: string }) {
  if (!trend) return <span className="text-gray-600 text-xs">—</span>;
  const m = TREND_META[trend];
  if (!m) return <span className="badge-neutral">{trend.replace(/_/g, ' ')}</span>;
  return <span className={m.cls} title={m.title}>{m.label}</span>;
}
function AddStockModal({ portfolioId, onClose, onAdded }: {
  portfolioId: number; onClose: () => void; onAdded: () => void;
}) {
  const [form, setForm] = useState({
    symbol: '', name: '', quantity: '', price: '',
    transactionDate: format(new Date(), 'yyyy-MM-dd'), charges: '',
  });
  const [suggestions, setSuggestions] = useState<any[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const debounce = useRef<any>(null);

  const onSymbolChange = (v: string) => {
    setForm(f => ({ ...f, symbol: v }));
    clearTimeout(debounce.current);
    if (v.length < 2) return setSuggestions([]);
    debounce.current = setTimeout(async () => {
      try { setSuggestions((await marketApi.search(v)).data?.slice(0, 6) ?? []); }
      catch { setSuggestions([]); }
    }, 300);
  };

  const save = async () => {
    if (!form.symbol || !form.quantity || !form.price) {
      setError('Symbol, quantity and buy price are required'); return;
    }
    setSaving(true); setError('');
    try {
      await portfolioApi.addHolding(portfolioId, {
        symbol: form.symbol.includes('.') ? form.symbol : `${form.symbol}.NS`,
        name: form.name || form.symbol,
        quantity: Number(form.quantity),
        price: Number(form.price),
        transactionDate: form.transactionDate,
        charges: form.charges ? Number(form.charges) : undefined,
      });
      onAdded(); onClose();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Failed to add holding');
    } finally { setSaving(false); }
  };

  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50 p-4">
      <div className="bg-surface-card border border-surface-border rounded-xl w-full max-w-md shadow-2xl">
        <div className="flex items-center justify-between px-6 py-4 border-b border-surface-border">
          <div>
            <h3 className="font-semibold text-white">Add Stock Holding</h3>
            <p className="text-2xs text-gray-600 mt-0.5">Log a historical or new purchase</p>
          </div>
          <button onClick={onClose} className="btn-icon"><X size={16} /></button>
        </div>
        <div className="p-6 space-y-4">
          {error && <p className="text-bear text-sm bg-bear/10 px-3 py-2 rounded">{error}</p>}

          <div className="relative">
            <label className="stat-label block mb-1">Stock Symbol *</label>
            <input value={form.symbol} onChange={e => onSymbolChange(e.target.value)}
              placeholder="e.g. RELIANCE, TCS, HDFC" className="input-field" />
            {suggestions.length > 0 && (
              <div className="absolute top-full mt-1 left-0 right-0 bg-surface-panel border border-surface-border rounded-lg shadow-panel z-10 max-h-48 overflow-y-auto">
                {suggestions.map(s => (
                  <button key={s.symbol} onClick={() => { setForm(f => ({ ...f, symbol: s.symbol, name: s.name })); setSuggestions([]); }}
                    className="w-full flex justify-between px-3 py-2 hover:bg-surface-hover text-sm text-left transition-colors">
                    <span className="font-mono text-white">{s.symbol}</span>
                    <span className="text-gray-500 text-xs truncate max-w-[200px]">{s.name}</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          <div>
            <label className="stat-label block mb-1">Company Name</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              placeholder="Auto-filled from symbol search" className="input-field" />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="stat-label block mb-1">Quantity / Shares *</label>
              <input type="number" value={form.quantity} onChange={e => setForm(f => ({ ...f, quantity: e.target.value }))}
                placeholder="e.g. 10" className="input-field" />
            </div>
            <div>
              <label className="stat-label block mb-1">Buy Price per Share (₹) *</label>
              <input type="number" value={form.price} onChange={e => setForm(f => ({ ...f, price: e.target.value }))}
                placeholder="e.g. 2450" className="input-field" />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="stat-label block mb-1">Purchase Date</label>
              <input type="date" value={form.transactionDate} onChange={e => setForm(f => ({ ...f, transactionDate: e.target.value }))}
                className="input-field" />
            </div>
            <div>
              <label className="stat-label block mb-1">Brokerage / Charges (₹)</label>
              <input type="number" value={form.charges} onChange={e => setForm(f => ({ ...f, charges: e.target.value }))}
                placeholder="Optional" className="input-field" />
            </div>
          </div>

          {form.quantity && form.price && (
            <div className="bg-surface-hover rounded px-3 py-2 text-sm text-gray-400">
              Total cost: <span className="text-white font-mono">{fmtINR(Number(form.quantity) * Number(form.price))}</span>
              {form.charges && <span className="ml-2">+ {fmtINR(Number(form.charges))} charges</span>}
            </div>
          )}
        </div>
        <div className="flex gap-3 px-6 pb-6">
          <button onClick={onClose} className="btn-ghost flex-1">Cancel</button>
          <button onClick={save} disabled={saving} className="btn-primary flex-1 disabled:opacity-50">
            {saving ? 'Saving…' : 'Add Holding'}
          </button>
        </div>
      </div>
    </div>
  );
}

function MyPortfolioSection() {
  const maskText = useMaskedText();
  const navigate = useNavigate();
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [summary, setSummary] = useState<any>(null);
  const [showModal, setShowModal] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const { data: portfolios } = await portfolioApi.list();
      if (!portfolios.length) {
        const { data } = await portfolioApi.create('My Portfolio');
        setPortfolio(data);
        setSummary(await portfolioApi.getSummary(data.id).then(r => r.data));
        setLoading(false);
        return;
      }
      // pick the portfolio that actually holds positions (some may be empty duplicates)
      const summaries = (await Promise.all(
        portfolios.map(pp => portfolioApi.getSummary(pp.id).then(r => ({ p: pp, s: r.data })).catch(() => null))
      )).filter(Boolean) as { p: Portfolio; s: any }[];
      const best = summaries.sort((a, b) => (b.s.holdings?.length ?? 0) - (a.s.holdings?.length ?? 0))[0];
      setPortfolio(best?.p ?? portfolios[0]);
      setSummary(best?.s ?? null);
    } catch {}
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  if (loading) return <div className="card animate-pulse h-32" />;

  // Stock Analysis shows equities only — mutual funds live in the MF tab
  const holdings = (summary?.holdings ?? []).filter((h: any) => !(h.symbol ?? '').endsWith('.MF'));
  const totalInvested = holdings.reduce((s: number, h: any) => s + (h.investedValue ?? 0), 0);
  const currentValue = holdings.reduce((s: number, h: any) => s + (h.currentValue ?? 0), 0);
  const pnlPct = totalInvested > 0 ? ((currentValue - totalInvested) / totalInvested * 100) : 0;

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="font-semibold text-white">My Stock Portfolio</h3>
          <p className="text-2xs text-gray-600 mt-0.5">
            {holdings.length} holding{holdings.length !== 1 ? 's' : ''} &nbsp;·&nbsp;
            Invested {maskText(fmtINR(totalInvested))} &nbsp;·&nbsp; Current {maskText(fmtINR(currentValue))}
            {totalInvested > 0 && (
              <span className={`ml-2 font-mono ${pnlPct >= 0 ? 'text-bull' : 'text-bear'}`}>
                {maskText(`${pnlPct >= 0 ? '+' : ''}${pnlPct.toFixed(2)}%`)}
              </span>
            )}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={downloadCSVTemplate} className="btn-ghost flex items-center gap-1.5 text-xs">
            <Download size={13} /> Template
          </button>
          <label className="btn-ghost flex items-center gap-1.5 text-xs cursor-pointer">
            <Upload size={13} /> Import CSV
            <input type="file" accept=".csv" className="hidden" onChange={e => handleCSV(e, portfolio, load)} />
          </label>
          <button onClick={() => setShowModal(true)} className="btn-primary flex items-center gap-1.5 text-xs">
            <Plus size={13} /> Add Stock Buy
          </button>
        </div>
      </div>

      {holdings.length === 0 ? (
        <div className="text-center py-10 text-gray-600">
          <p className="text-sm">No stock holdings yet.</p>
          <p className="text-xs mt-1">Click "Add Stock Buy" to log your first purchase — past or present.</p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="data-table">
            <thead>
              <tr>
                <th className="text-left">Symbol</th>
                <th className="text-right">Qty</th>
                <th className="text-right">Avg Buy Price</th>
                <th className="text-right">Current Price</th>
                <th className="text-right">Amount Invested</th>
                <th className="text-right">Current Value</th>
                <th className="text-right">Profit / Loss %</th>
              </tr>
            </thead>
            <tbody>
              {holdings.map((h: any) => (
                <tr key={h.id} onClick={() => navigate(`/stock/${h.symbol}`)}>
                  <td>
                    <div className="font-mono text-white font-medium">{h.symbol?.replace('.NS', '')}</div>
                    <div className="text-2xs text-gray-600 truncate max-w-[120px]">{h.name}</div>
                  </td>
                  <td className="text-right num text-gray-300">{maskText(h.quantity)}</td>
                  <td className="text-right num text-gray-400">{maskText(fmtINR(h.avgPrice))}</td>
                  <td className="text-right num text-white">{maskText(fmtINR(h.currentPrice))}</td>
                  <td className="text-right num text-gray-400">{maskText(fmtINR(h.investedValue))}</td>
                  <td className="text-right num text-white">{maskText(fmtINR(h.currentValue))}</td>
                  <td className={`text-right num font-semibold ${h.pnlPercent >= 0 ? 'text-bull' : 'text-bear'}`}>
                    {maskText(`${h.pnlPercent >= 0 ? '+' : ''}${h.pnlPercent?.toFixed(2)}%`)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showModal && portfolio && (
        <AddStockModal portfolioId={portfolio.id} onClose={() => setShowModal(false)} onAdded={load} />
      )}
    </div>
  );
}

function WatchlistSection() {
  const navigate = useNavigate();
  const [watchlist, setWatchlist] = useState(DEFAULT_WATCHLIST);
  const [data, setData] = useState<Record<string, TechnicalAnalysis>>({});
  const [loading, setLoading] = useState(true);
  const [addInput, setAddInput] = useState('');

  const load = async (symbols: string[]) => {
    setLoading(true);
    const results = await Promise.allSettled(symbols.map(s => marketApi.getTechnicals(s)));
    const map: Record<string, TechnicalAnalysis> = {};
    results.forEach((r, i) => {
      if (r.status === 'fulfilled') map[symbols[i]] = r.value.data;
    });
    setData(map);
    setLoading(false);
  };

  useEffect(() => { load(watchlist); }, []);

  const addSymbol = () => {
    const sym = addInput.trim().toUpperCase();
    if (!sym) return;
    const full = sym.includes('.') ? sym : `${sym}.NS`;
    if (watchlist.includes(full)) { setAddInput(''); return; }
    const next = [...watchlist, full];
    setWatchlist(next);
    setAddInput('');
    marketApi.getTechnicals(full).then(r => setData(d => ({ ...d, [full]: r.data }))).catch(() => {});
  };

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-4">
        <h3 className="font-semibold text-white">Market Watchlist — Technical Indicators</h3>
        <div className="flex items-center gap-2">
          <input value={addInput} onChange={e => setAddInput(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && addSymbol()}
            placeholder="Add symbol…" className="input-field w-36 text-xs py-1" />
          <button onClick={addSymbol} className="btn-ghost text-xs py-1 px-2"><Plus size={13} /></button>
          <button onClick={() => load(watchlist)} className="btn-icon"><RefreshCw size={13} /></button>
        </div>
      </div>
      {loading ? (
        <div className="space-y-2">{[...Array(5)].map((_, i) => <div key={i} className="h-10 bg-surface-hover rounded animate-pulse" />)}</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="data-table">
            <thead>
              <tr>
                <th className="text-left">Symbol</th>
                <th className="text-right">Current Price</th>
                <th className="text-right">RSI (14)</th>
                <th className="text-right">20-Day Avg</th>
                <th className="text-right">50-Day Avg</th>
                <th className="text-right">Support Level</th>
                <th className="text-right">Resistance</th>
                <th className="text-center">Trend</th>
                <th className="text-center">Signal</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {watchlist.map(sym => {
                const d = data[sym];
                return (
                  <tr key={sym} onClick={() => navigate(`/stock/${sym}`)}>
                    <td><span className="font-mono text-white font-medium">{sym.replace('.NS', '')}</span></td>
                    <td className="text-right num text-white">{fmt(d?.price)}</td>
                    <td className={`text-right num ${d?.rsi == null ? 'text-gray-600' : d.rsi > 70 ? 'text-bear' : d.rsi < 30 ? 'text-bull' : 'text-gray-300'}`}>
                      {d?.rsi?.toFixed(1) ?? '—'}
                    </td>
                    <td className="text-right num text-gray-400">{fmt(d?.sma20)}</td>
                    <td className="text-right num text-gray-400">{fmt(d?.sma50)}</td>
                    <td className="text-right num text-bull">{fmt(d?.support)}</td>
                    <td className="text-right num text-bear">{fmt(d?.resistance)}</td>
                    <td className="text-center"><TrendBadge trend={d?.trend} /></td>
                    {/* Same RecommendationEngine call every other tab uses — this watchlist
                        can never show a different signal for a symbol than Stocks/MF or
                        AI Advisor do, closing the last independently-coded signal source. */}
                    <td className="text-center"><HoldingTrendBadge symbol={sym} /></td>
                    <td onClick={e => { e.stopPropagation(); setWatchlist(wl => wl.filter(s => s !== sym)); }}>
                      <button className="btn-icon text-gray-700 hover:text-bear"><Trash2 size={12} /></button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export function Tab2StockAnalysis() {
  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#6d5efc] to-[#9b5cf9] flex items-center justify-center text-white shadow-lift shrink-0">
          <LineChart size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-0.5">Stock Insights</h2>
          <p className="text-gray-500 text-sm">Your stock portfolio + market watchlist with technical indicators</p>
        </div>
      </div>
      <MyPortfolioSection />
      <WatchlistSection />
    </div>
  );
}
