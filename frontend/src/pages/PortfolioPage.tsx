import { useEffect, useState } from 'react';
import { portfolioApi } from '../api/portfolio';
import { marketApi } from '../api/market';
import { PortfolioTable } from '../components/portfolio/PortfolioTable';
import { AllocationChart } from '../components/portfolio/AllocationChart';
import type { Portfolio, PortfolioSummary } from '../types';
import { Plus, X } from 'lucide-react';
import { format } from 'date-fns';
import { useMaskedText } from '../components/shared/Amount';

const fmt = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

function AddHoldingModal({ portfolioId, onClose, onAdded }: {
  portfolioId: number;
  onClose: () => void;
  onAdded: () => void;
}) {
  const [form, setForm] = useState({
    symbol: '', name: '', quantity: '', price: '',
    transactionDate: format(new Date(), 'yyyy-MM-dd'), charges: '',
  });
  const [suggestions, setSuggestions] = useState<any[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const searchSymbol = async (q: string) => {
    if (q.length < 2) return setSuggestions([]);
    try {
      const { data } = await marketApi.search(q);
      setSuggestions(data?.slice(0, 6) ?? []);
    } catch { setSuggestions([]); }
  };

  const pick = (s: any) => {
    setForm(f => ({ ...f, symbol: s.symbol, name: s.name }));
    setSuggestions([]);
  };

  const save = async () => {
    if (!form.symbol || !form.quantity || !form.price) {
      setError('Symbol, quantity and price are required');
      return;
    }
    setSaving(true);
    setError('');
    try {
      await portfolioApi.addHolding(portfolioId, {
        symbol: form.symbol,
        name: form.name || form.symbol,
        quantity: Number(form.quantity),
        price: Number(form.price),
        transactionDate: form.transactionDate,
        charges: form.charges ? Number(form.charges) : undefined,
      });
      onAdded();
      onClose();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Failed to add holding');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4">
      <div className="bg-surface-card border border-surface-border rounded-2xl w-full max-w-md shadow-2xl">
        <div className="flex items-center justify-between px-6 py-4 border-b border-surface-border">
          <h3 className="font-semibold text-white">Add Holding</h3>
          <button onClick={onClose} className="text-gray-500 hover:text-white"><X size={18} /></button>
        </div>
        <div className="p-6 space-y-4">
          {error && <p className="text-bear text-sm bg-bear/10 px-3 py-2 rounded-lg">{error}</p>}

          <div className="relative">
            <label className="stat-label block mb-1">Stock / MF Symbol</label>
            <input
              value={form.symbol}
              onChange={e => { setForm(f => ({ ...f, symbol: e.target.value })); searchSymbol(e.target.value); }}
              placeholder="e.g. RELIANCE, TCS"
              className="input-field"
            />
            {suggestions.length > 0 && (
              <div className="absolute top-full left-0 right-0 bg-surface-card border border-surface-border rounded-lg mt-1 z-10 overflow-hidden shadow-xl">
                {suggestions.map((s: any) => (
                  <button key={s.symbol} onClick={() => pick(s)}
                    className="w-full flex justify-between px-4 py-2.5 hover:bg-surface-hover text-left text-sm">
                    <span className="font-mono text-white">{s.symbol}</span>
                    <span className="text-gray-500 truncate ml-4">{s.name}</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          <div>
            <label className="stat-label block mb-1">Name (optional)</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              placeholder="Company name" className="input-field" />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="stat-label block mb-1">Quantity</label>
              <input type="number" value={form.quantity} onChange={e => setForm(f => ({ ...f, quantity: e.target.value }))}
                placeholder="0" className="input-field" />
            </div>
            <div>
              <label className="stat-label block mb-1">Buy Price (₹)</label>
              <input type="number" value={form.price} onChange={e => setForm(f => ({ ...f, price: e.target.value }))}
                placeholder="0.00" className="input-field" />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="stat-label block mb-1">Buy Date</label>
              <input type="date" value={form.transactionDate}
                onChange={e => setForm(f => ({ ...f, transactionDate: e.target.value }))}
                className="input-field" />
            </div>
            <div>
              <label className="stat-label block mb-1">Charges (₹)</label>
              <input type="number" value={form.charges} onChange={e => setForm(f => ({ ...f, charges: e.target.value }))}
                placeholder="0" className="input-field" />
            </div>
          </div>
        </div>
        <div className="px-6 py-4 border-t border-surface-border flex gap-3 justify-end">
          <button onClick={onClose} className="btn-ghost">Cancel</button>
          <button onClick={save} disabled={saving} className="btn-primary disabled:opacity-50">
            {saving ? 'Adding…' : 'Add Holding'}
          </button>
        </div>
      </div>
    </div>
  );
}

export function PortfolioPage() {
  const [portfolios, setPortfolios] = useState<Portfolio[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [summary, setSummary] = useState<PortfolioSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [showAddHolding, setShowAddHolding] = useState(false);
  const [newName, setNewName] = useState('');

  const loadSummary = (id: number) => {
    setLoading(true);
    portfolioApi.getSummary(id).then(r => setSummary(r.data)).finally(() => setLoading(false));
  };

  useEffect(() => {
    portfolioApi.list().then(r => {
      setPortfolios(r.data);
      if (r.data.length > 0) { setSelected(r.data[0].id); loadSummary(r.data[0].id); }
    });
  }, []);

  useEffect(() => {
    if (selected) loadSummary(selected);
  }, [selected]);

  const createPortfolio = async () => {
    if (!newName.trim()) return;
    const { data } = await portfolioApi.create(newName);
    setPortfolios(prev => [...prev, data]);
    setSelected(data.id);
    setShowCreate(false);
    setNewName('');
  };

  const removeHolding = async (holdingId: number) => {
    if (!selected) return;
    await portfolioApi.removeHolding(selected, holdingId);
    loadSummary(selected);
  };

  const isPnlPositive = (summary?.totalPnl ?? 0) >= 0;
  const maskText = useMaskedText();

  return (
    <div className="space-y-6">
      {showAddHolding && selected && (
        <AddHoldingModal
          portfolioId={selected}
          onClose={() => setShowAddHolding(false)}
          onAdded={() => loadSummary(selected)}
        />
      )}

      <div className="flex items-center justify-between flex-wrap gap-3">
        <h1 className="text-2xl font-bold text-white">Portfolio</h1>
        <div className="flex gap-2">
          {selected && (
            <button onClick={() => setShowAddHolding(true)} className="btn-primary flex items-center gap-2">
              <Plus size={16} /> Add Holding
            </button>
          )}
          <button onClick={() => setShowCreate(true)} className="btn-ghost flex items-center gap-2">
            <Plus size={16} /> New Portfolio
          </button>
        </div>
      </div>

      {showCreate && (
        <div className="card flex items-center gap-3">
          <input value={newName} onChange={e => setNewName(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && createPortfolio()}
            placeholder="Portfolio name" className="input-field flex-1" />
          <button onClick={createPortfolio} className="btn-primary">Create</button>
          <button onClick={() => setShowCreate(false)} className="btn-ghost">Cancel</button>
        </div>
      )}

      {portfolios.length > 0 && (
        <div className="flex gap-2 flex-wrap">
          {portfolios.map(p => (
            <button key={p.id} onClick={() => setSelected(p.id)}
              className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
                selected === p.id ? 'bg-brand text-white' : 'bg-surface-card border border-surface-border text-gray-400 hover:text-white'
              }`}>
              {p.name}
            </button>
          ))}
        </div>
      )}

      {summary && (
        <>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            {[
              { label: 'Total Invested', value: maskText(fmt(summary.totalInvested)), color: 'text-white' },
              { label: 'Current Value', value: maskText(fmt(summary.currentValue)), color: 'text-white' },
              { label: 'Total P&L', value: maskText(fmt(summary.totalPnl)),
                color: isPnlPositive ? 'text-bull' : 'text-bear',
                sub: maskText(`${isPnlPositive ? '+' : ''}${summary.totalPnlPercent?.toFixed(2)}%`) },
              { label: 'Holdings', value: String(summary.holdings?.length ?? 0), color: 'text-white' },
            ].map(s => (
              <div key={s.label} className="card">
                <div className="stat-label">{s.label}</div>
                <div className={`stat-value mt-1 ${s.color}`}>{s.value}</div>
                {s.sub && <div className={`text-sm mt-0.5 ${s.color}`}>{s.sub}</div>}
              </div>
            ))}
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <div className="lg:col-span-2 card">
              <h2 className="font-semibold text-white mb-4">Holdings</h2>
              {loading ? (
                <div className="animate-pulse space-y-2">
                  {[...Array(5)].map((_, i) => <div key={i} className="h-10 bg-surface-hover rounded" />)}
                </div>
              ) : (
                <PortfolioTable holdings={summary.holdings ?? []} onRemove={removeHolding} />
              )}
            </div>
            <div className="card">
              <h2 className="font-semibold text-white mb-4">Allocation</h2>
              {summary.allocation && summary.allocation.length > 0
                ? <AllocationChart allocation={summary.allocation} />
                : <div className="text-gray-600 text-sm text-center py-8">No holdings yet</div>
              }
            </div>
          </div>
        </>
      )}

      {portfolios.length === 0 && (
        <div className="card text-center py-16">
          <p className="text-gray-500 mb-4">No portfolios yet. Create one to start tracking.</p>
          <button onClick={() => setShowCreate(true)} className="btn-primary">Create First Portfolio</button>
        </div>
      )}
    </div>
  );
}
