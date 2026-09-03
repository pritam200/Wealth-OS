import { useEffect, useState, useCallback } from 'react';
import { portfolioApi } from '../../api/portfolio';
import { benchmarksApi } from '../../api/analyst';
import type { IndexBenchmarks } from '../../api/analyst';
import { AllocationChart } from '../../components/portfolio/AllocationChart';
import { StatTile } from '../../components/shared/StatTile';
import { useMaskedText } from '../../components/shared/Amount';
import { MfTrendBadge } from '../../components/wealth/MfTrendBadge';
import { RedeemedInvestments } from '../../components/wealth/RedeemedInvestments';
import type { PortfolioSummary, Portfolio, HoldingDto, TransactionDto } from '../../types';
import { Plus, TrendingUp, X, PieChart, Wallet, Layers, Clock, ChevronDown, ChevronUp, Calendar, ArrowUpRight, ArrowDownRight, Filter, RefreshCw, Lightbulb, BarChart3 } from 'lucide-react';
import { format, parseISO, isAfter, startOfMonth, subDays } from 'date-fns';

const fmt = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);
const fmtPct = (n: number) => `${n >= 0 ? '+' : ''}${n?.toFixed(2)}%`;
const fmtDate = (d: string | null | undefined) => d ? format(parseISO(d), 'dd MMM yyyy') : '—';
const fmtUnits = (n: number) => n?.toFixed(3) ?? '—';
const fmtNav = (n: number) => n ? `₹${n.toFixed(2)}` : '—';

/* ── Collapsible Section Wrapper ── */
function CollapsibleSection({ title, icon, children, onRefresh, loading }: {
  title: string; icon: React.ReactNode; children: React.ReactNode;
  onRefresh?: () => void; loading?: boolean;
}) {
  const [expanded, setExpanded] = useState(true);
  return (
    <div className="card">
      <div className="flex items-center justify-between cursor-pointer" onClick={() => setExpanded(e => !e)}>
        <h3 className="section-title mb-0 flex items-center gap-2">{icon} {title}</h3>
        <div className="flex items-center gap-2">
          {onRefresh && (
            <button onClick={e => { e.stopPropagation(); onRefresh(); }}
              className="btn-icon p-1 hover:bg-surface-hover rounded" title="Refresh">
              <RefreshCw size={13} className={loading ? 'animate-spin text-brand' : 'text-gray-500'} />
            </button>
          )}
          {expanded ? <ChevronUp size={14} className="text-gray-500" /> : <ChevronDown size={14} className="text-gray-500" />}
        </div>
      </div>
      {expanded && <div className="mt-4">{children}</div>}
    </div>
  );
}

function Benchmark({ label, cagr, color, highlight, sensitive }: { label: string; cagr: string | null; color: string; highlight?: boolean; sensitive?: boolean }) {
  const maskText = useMaskedText();
  const isNeg = cagr?.startsWith('-');
  return (
    <div className={`flex items-center justify-between py-2.5 px-2.5 rounded-lg border-b border-surface-border/50 last:border-0 ${highlight ? 'bg-brand-gradient-soft mb-1' : ''}`}>
      <div className="flex items-center gap-2">
        <div className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: color, boxShadow: `0 0 8px ${color}` }} />
        <span className={`text-sm ${highlight ? 'text-white font-medium' : 'text-gray-300'}`}>{label}</span>
      </div>
      <span className={`text-sm font-mono font-semibold ${cagr == null ? 'text-gray-600' : isNeg ? 'text-bear' : 'text-bull'}`}>{cagr == null ? 'n/a' : sensitive ? maskText(cagr) : cagr}</span>
    </div>
  );
}

/* ── Add MF Holding Modal ── */
function AddMFModal({ portfolioId, onClose, onAdded }: {
  portfolioId: number; onClose: () => void; onAdded: () => void;
}) {
  const [form, setForm] = useState({
    name: '', symbol: '', investedAmount: '', units: '', nav: '',
    transactionDate: format(new Date(), 'yyyy-MM-dd'), sipMonthly: '',
  });
  const [type, setType] = useState<'lumpsum' | 'sip'>('lumpsum');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const save = async () => {
    if (!form.name) { setError('Fund name is required'); return; }
    if (type === 'lumpsum' && !form.investedAmount) { setError('Invested amount is required'); return; }
    setSaving(true); setError('');
    try {
      const nav = form.nav ? Number(form.nav) : Number(form.investedAmount);
      const qty = form.units ? Number(form.units) : Number(form.investedAmount) / nav;
      await portfolioApi.addHolding(portfolioId, {
        symbol: form.symbol || `MF_${form.name.replace(/\s+/g, '_').toUpperCase().slice(0, 20)}`,
        name: form.name,
        quantity: qty,
        price: nav,
        transactionDate: form.transactionDate,
      });
      onAdded(); onClose();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Failed to add MF holding');
    } finally { setSaving(false); }
  };

  const totalInvested = form.units && form.nav
    ? Number(form.units) * Number(form.nav)
    : Number(form.investedAmount) || 0;

  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50 p-4">
      <div className="bg-surface-card border border-surface-border rounded-xl w-full max-w-lg shadow-2xl">
        <div className="flex items-center justify-between px-6 py-4 border-b border-surface-border">
          <div>
            <h3 className="font-semibold text-white">Add Mutual Fund Holding</h3>
            <p className="text-2xs text-gray-600 mt-0.5">Log historical lump sum or SIP investment</p>
          </div>
          <button onClick={onClose} className="btn-icon"><X size={16} /></button>
        </div>
        <div className="p-6 space-y-4">
          {error && <p className="text-bear text-sm bg-bear/10 px-3 py-2 rounded">{error}</p>}

          {/* Type toggle */}
          <div className="flex gap-2">
            {(['lumpsum', 'sip'] as const).map(t => (
              <button key={t} onClick={() => setType(t)}
                className={`flex-1 py-2 rounded text-xs font-semibold uppercase tracking-wider transition-all ${
                  type === t ? 'bg-brand text-white' : 'bg-surface-hover text-gray-500 hover:text-gray-300'
                }`}>
                {t === 'lumpsum' ? 'Lump Sum' : 'SIP / Regular'}
              </button>
            ))}
          </div>

          <div>
            <label className="stat-label block mb-1">Fund Name *</label>
            <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              placeholder="e.g. Mirae Asset Large Cap Fund - Direct Growth"
              className="input-field" />
          </div>

          <div>
            <label className="stat-label block mb-1">Fund Code / ISIN (optional)</label>
            <input value={form.symbol} onChange={e => setForm(f => ({ ...f, symbol: e.target.value }))}
              placeholder="e.g. INF209KB12X5 or MIRAE_LARGECAP" className="input-field" />
          </div>

          {type === 'lumpsum' ? (
            <>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="stat-label block mb-1">Amount Invested (₹) *</label>
                  <input type="number" value={form.investedAmount} onChange={e => setForm(f => ({ ...f, investedAmount: e.target.value }))}
                    placeholder="e.g. 50000" className="input-field" />
                </div>
                <div>
                  <label className="stat-label block mb-1">NAV at Purchase (₹)</label>
                  <input type="number" value={form.nav} onChange={e => setForm(f => ({ ...f, nav: e.target.value }))}
                    placeholder="e.g. 85.23" className="input-field" />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="stat-label block mb-1">Units Allotted</label>
                  <input type="number" value={form.units} onChange={e => setForm(f => ({ ...f, units: e.target.value }))}
                    placeholder="Auto from amount ÷ NAV" className="input-field" />
                </div>
                <div>
                  <label className="stat-label block mb-1">Purchase Date</label>
                  <input type="date" value={form.transactionDate} onChange={e => setForm(f => ({ ...f, transactionDate: e.target.value }))}
                    className="input-field" />
                </div>
              </div>
            </>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="stat-label block mb-1">Monthly SIP Amount (₹)</label>
                  <input type="number" value={form.sipMonthly} onChange={e => setForm(f => ({ ...f, sipMonthly: e.target.value }))}
                    placeholder="e.g. 5000" className="input-field" />
                </div>
                <div>
                  <label className="stat-label block mb-1">Total Invested Till Date (₹) *</label>
                  <input type="number" value={form.investedAmount} onChange={e => setForm(f => ({ ...f, investedAmount: e.target.value }))}
                    placeholder="e.g. 120000" className="input-field" />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="stat-label block mb-1">Current NAV (₹)</label>
                  <input type="number" value={form.nav} onChange={e => setForm(f => ({ ...f, nav: e.target.value }))}
                    placeholder="e.g. 95.40" className="input-field" />
                </div>
                <div>
                  <label className="stat-label block mb-1">Total Units Held</label>
                  <input type="number" value={form.units} onChange={e => setForm(f => ({ ...f, units: e.target.value }))}
                    placeholder="From account statement" className="input-field" />
                </div>
              </div>
            </>
          )}

          {totalInvested > 0 && (
            <div className="bg-surface-hover rounded px-3 py-2 text-sm text-gray-400">
              Total invested: <span className="text-white font-mono">{fmt(totalInvested)}</span>
              {form.units && form.nav && (
                <span className="ml-3">Units: <span className="text-white font-mono">{Number(form.units).toFixed(3)}</span></span>
              )}
            </div>
          )}
        </div>
        <div className="flex gap-3 px-6 pb-6">
          <button onClick={onClose} className="btn-ghost flex-1">Cancel</button>
          <button onClick={save} disabled={saving} className="btn-primary flex-1 disabled:opacity-50">
            {saving ? 'Saving…' : 'Add MF Holding'}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ── Transaction History Modal ── */
function TransactionHistoryModal({ holding, portfolioId, onClose }: {
  holding: HoldingDto; portfolioId: number; onClose: () => void;
}) {
  const maskText = useMaskedText();
  const [txns, setTxns] = useState<TransactionDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<'all' | 'week' | 'month' | 'year'>('all');

  useEffect(() => {
    setLoading(true);
    portfolioApi.getTransactions(portfolioId, holding.id)
      .then(r => setTxns(r.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [portfolioId, holding.id]);

  const now = new Date();
  const filtered = txns.filter(t => {
    if (filter === 'all') return true;
    const d = parseISO(t.transactionDate);
    if (filter === 'week') return isAfter(d, subDays(now, 7));
    if (filter === 'month') return isAfter(d, startOfMonth(now));
    return isAfter(d, new Date(now.getFullYear(), 0, 1));
  });

  const buys = filtered.filter(t => t.type === 'BUY');
  const sells = filtered.filter(t => t.type === 'SELL');
  const totalBought = buys.reduce((s, t) => s + t.totalAmount, 0);
  const totalSold = sells.reduce((s, t) => s + t.totalAmount, 0);

  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50 p-4">
      <div className="bg-surface-card border border-surface-border rounded-xl w-full max-w-2xl shadow-2xl max-h-[85vh] flex flex-col">
        <div className="flex items-center justify-between px-6 py-4 border-b border-surface-border shrink-0">
          <div>
            <h3 className="font-semibold text-white">{holding.name || holding.symbol?.replace('.MF', '')}</h3>
            <p className="text-2xs text-gray-500 mt-0.5">
              {holding.folio ? `Folio: ${holding.folio}` : holding.symbol}
              {holding.broker ? ` · ${holding.broker}` : ''}
            </p>
          </div>
          <button onClick={onClose} className="btn-icon"><X size={16} /></button>
        </div>

        {/* Summary strip */}
        <div className="px-6 py-3 border-b border-surface-border grid grid-cols-4 gap-3 text-center shrink-0">
          <div>
            <div className="text-2xs text-gray-500">Transactions</div>
            <div className="text-sm font-semibold text-white">{filtered.length}</div>
          </div>
          <div>
            <div className="text-2xs text-gray-500">Total Bought</div>
            <div className="text-sm font-semibold text-bull">{maskText(fmt(totalBought))}</div>
          </div>
          <div>
            <div className="text-2xs text-gray-500">Total Sold</div>
            <div className="text-sm font-semibold text-bear">{maskText(fmt(totalSold))}</div>
          </div>
          <div>
            <div className="text-2xs text-gray-500">Units Held</div>
            <div className="text-sm font-semibold text-white">{fmtUnits(holding.quantity)}</div>
          </div>
        </div>

        {/* Filter tabs */}
        <div className="flex gap-1.5 px-6 py-3 border-b border-surface-border shrink-0">
          {([['all', 'All'], ['week', 'This Week'], ['month', 'This Month'], ['year', 'This Year']] as const).map(([key, label]) => (
            <button key={key} onClick={() => setFilter(key)}
              className={`px-3 py-1.5 rounded text-xs font-medium transition-all ${
                filter === key ? 'bg-brand text-white' : 'bg-surface-hover text-gray-500 hover:text-gray-300'
              }`}>
              {label}
            </button>
          ))}
        </div>

        {/* Transaction list */}
        <div className="overflow-y-auto flex-1 px-6 py-3">
          {loading ? (
            <div className="space-y-3">{[0,1,2].map(i => <div key={i} className="h-14 animate-pulse bg-surface-hover rounded-lg" />)}</div>
          ) : filtered.length === 0 ? (
            <p className="text-center text-gray-600 py-8 text-sm">No transactions found for this period.</p>
          ) : (
            <div className="space-y-2">
              {filtered.map(t => (
                <div key={t.id} className="flex items-center justify-between py-3 px-3 rounded-lg bg-surface-hover/50 hover:bg-surface-hover transition-colors">
                  <div className="flex items-center gap-3">
                    <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${
                      t.type === 'BUY' ? 'bg-bull/15 text-bull' : 'bg-bear/15 text-bear'
                    }`}>
                      {t.type === 'BUY' ? <ArrowDownRight size={15} /> : <ArrowUpRight size={15} />}
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <span className={`text-xs font-semibold ${t.type === 'BUY' ? 'text-bull' : 'text-bear'}`}>
                          {t.type}
                        </span>
                        {t.notes && <span className="text-2xs text-gray-600">{t.notes}</span>}
                      </div>
                      <div className="text-2xs text-gray-500 mt-0.5">
                        {fmtDate(t.transactionDate)} · {fmtUnits(t.quantity)} units @ {fmtNav(t.price)}
                      </div>
                    </div>
                  </div>
                  <div className="text-right">
                    <div className={`text-sm font-mono font-semibold ${t.type === 'BUY' ? 'text-white' : 'text-bear'}`}>
                      {maskText(fmt(t.totalAmount))}
                    </div>
                    {t.charges ? <div className="text-2xs text-gray-600">Charges: {fmt(t.charges)}</div> : null}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/* ── Recent MF Purchases Section ── */
function RecentPurchases({ onRefresh: externalRefresh }: { onRefresh?: () => void }) {
  const maskText = useMaskedText();
  const [txns, setTxns] = useState<TransactionDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [range, setRange] = useState<'7d' | 'month' | 'custom'>('7d');
  const [customDays, setCustomDays] = useState('30');

  const loadTxns = useCallback(async () => {
    setLoading(true);
    try {
      const days = range === '7d' ? 7 : range === 'month' ? 30 : Number(customDays) || 30;
      const { data } = await portfolioApi.getRecentMfTransactions(days);
      setTxns(data.filter(t => t.type === 'BUY'));
    } catch {}
    setLoading(false);
  }, [range, customDays]);

  useEffect(() => { loadTxns(); }, [loadTxns]);

  const handleRefresh = () => {
    loadTxns();
    externalRefresh?.();
  };

  const inferType = (t: TransactionDto): string => {
    if (t.notes?.toLowerCase().includes('sip')) return 'SIP';
    return 'Lump Sum';
  };

  const inferProvider = (t: TransactionDto): string => {
    if (t.broker) return t.broker;
    const sym = (t.symbol || '').toUpperCase();
    if (sym.includes('SBI') || sym.includes('SBIMF')) return 'SBI';
    if (sym.includes('HDFC')) return 'HDFC';
    if (sym.includes('ICICI') || sym.includes('PRUDENTIAL')) return 'ICICI';
    if (sym.includes('AXIS')) return 'Axis';
    if (sym.includes('KOTAK')) return 'Kotak';
    if (sym.includes('NIPPON')) return 'Nippon';
    if (sym.includes('TATA')) return 'Tata';
    if (sym.includes('MIRAE')) return 'Mirae';
    if (sym.includes('UTI')) return 'UTI';
    if (sym.includes('DSP')) return 'DSP';
    return '—';
  };

  return (
    <CollapsibleSection title="Recent MF Purchases" icon={<Clock size={15} className="text-brand-light" />}
      onRefresh={handleRefresh} loading={loading}>
      {/* Range tabs */}
      <div className="flex items-center gap-2 mb-4">
        {([['7d', 'Last 7 Days'], ['month', 'This Month']] as const).map(([key, label]) => (
          <button key={key} onClick={() => setRange(key)}
            className={`px-3 py-1.5 rounded text-xs font-medium transition-all ${
              range === key ? 'bg-brand text-white' : 'bg-surface-hover text-gray-500 hover:text-gray-300'
            }`}>
            {label}
          </button>
        ))}
        <button onClick={() => setRange('custom')}
          className={`px-3 py-1.5 rounded text-xs font-medium transition-all flex items-center gap-1 ${
            range === 'custom' ? 'bg-brand text-white' : 'bg-surface-hover text-gray-500 hover:text-gray-300'
          }`}>
          <Filter size={11} /> Custom
        </button>
        {range === 'custom' && (
          <div className="flex items-center gap-1.5">
            <input type="number" value={customDays} onChange={e => setCustomDays(e.target.value)}
              className="input-field w-16 text-xs py-1.5" min="1" max="365" />
            <span className="text-xs text-gray-500">days</span>
          </div>
        )}
      </div>

      {loading ? (
        <div className="space-y-2">{[0,1,2].map(i => <div key={i} className="h-12 animate-pulse bg-surface-hover rounded-lg" />)}</div>
      ) : txns.length === 0 ? (
        <p className="text-center text-gray-600 py-6 text-sm">No mutual fund purchases in this period.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="data-table">
            <thead>
              <tr>
                <th className="text-left">Fund Name</th>
                <th className="text-left">Purchase Date</th>
                <th className="text-right">Amount</th>
                <th className="text-right">Units</th>
                <th className="text-right">NAV</th>
                <th className="text-center">Type</th>
                <th className="text-center">Provider</th>
              </tr>
            </thead>
            <tbody>
              {txns.map(t => (
                <tr key={t.id}>
                  <td>
                    <div className="font-mono text-white text-xs">{t.symbol?.replace('.MF', '')}</div>
                    <div className="text-2xs text-gray-500 truncate max-w-[180px]">{t.fundName}</div>
                  </td>
                  <td className="text-gray-300 text-xs whitespace-nowrap">
                    <Calendar size={11} className="inline mr-1 text-gray-500" />
                    {fmtDate(t.transactionDate)}
                  </td>
                  <td className="text-right num text-white">{maskText(fmt(t.totalAmount))}</td>
                  <td className="text-right num text-gray-400">{fmtUnits(t.quantity)}</td>
                  <td className="text-right num text-gray-400">{fmtNav(t.price)}</td>
                  <td className="text-center">
                    <span className={`badge text-2xs ${inferType(t) === 'SIP' ? 'badge-brand' : 'badge-gold'}`}>
                      {inferType(t)}
                    </span>
                  </td>
                  <td className="text-center text-xs text-gray-400">{inferProvider(t)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="text-xs text-gray-600 mt-2 text-right">
            {txns.length} purchase{txns.length !== 1 ? 's' : ''} · Total: {maskText(fmt(txns.reduce((s, t) => s + t.totalAmount, 0)))}
          </div>
        </div>
      )}
    </CollapsibleSection>
  );
}

/* ── Main MF Tab ── */
export function Tab6MutualFunds() {
  const maskText = useMaskedText();
  const [summaries, setSummaries] = useState<PortfolioSummary[]>([]);
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [benchmarks, setBenchmarks] = useState<IndexBenchmarks | null>(null);
  const [benchmarksLoading, setBenchmarksLoading] = useState(false);
  const [selectedHolding, setSelectedHolding] = useState<HoldingDto | null>(null);

  const loadBenchmarks = useCallback(async () => {
    setBenchmarksLoading(true);
    try { const r = await benchmarksApi.get(); setBenchmarks(r.data); } catch {}
    setBenchmarksLoading(false);
  }, []);

  useEffect(() => { loadBenchmarks(); }, [loadBenchmarks]);

  const load = async () => {
    setLoading(true);
    try {
      const { data: portfolios } = await portfolioApi.list();
      let p = portfolios[0];
      if (!p) {
        const { data } = await portfolioApi.create('My Portfolio');
        p = data;
      }
      setPortfolio(p);
      const results = await Promise.allSettled(portfolios.map(port => portfolioApi.getSummary(port.id)));
      setSummaries(results.filter(r => r.status === 'fulfilled').map(r => (r as any).value.data));
    } catch {}
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  const allHoldings = summaries.flatMap(s => s.holdings ?? []).filter(h => (h.symbol ?? '').endsWith('.MF'));

  const combined = allHoldings.reduce((acc, h) => ({
    totalInvested: acc.totalInvested + (h.investedValue ?? 0),
    currentValue: acc.currentValue + (h.currentValue ?? 0),
    totalPnl: 0,
    holdings: acc.holdings + 1,
  }), { totalInvested: 0, currentValue: 0, totalPnl: 0, holdings: 0 });
  combined.totalPnl = combined.currentValue - combined.totalInvested;

  const cagr = combined.totalInvested > 0
    ? ((combined.currentValue - combined.totalInvested) / combined.totalInvested * 100)
    : 0;

  // Build allocation from the MF-only holdings, not the portfolio-wide summaries[0].allocation
  // (which mixes in stocks). Percent is relative to the MF-only current value so it matches
  // the stat tiles/table above instead of being diluted by the rest of the portfolio.
  const mfAllocation = allHoldings
    .filter(h => (h.currentValue ?? 0) > 0)
    .map(h => ({
      label: h.name || h.symbol?.replace('.MF', '') || 'Unknown',
      value: h.currentValue ?? 0,
      percent: combined.currentValue > 0 ? ((h.currentValue ?? 0) / combined.currentValue) * 100 : 0,
    }));

  return (
    <div className="space-y-4">
      {/* Page Header */}
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#e84fd9] to-[#ff8a5b] flex items-center justify-center text-white shadow-lift shrink-0">
            <PieChart size={20} />
          </div>
          <div>
            <h2 className="text-xl font-bold text-white mb-0.5">Mutual Funds</h2>
            <p className="text-gray-500 text-sm">Holdings, recommendations, allocation and benchmark comparison</p>
          </div>
        </div>
        <button onClick={() => setShowModal(true)} className="btn-primary flex items-center gap-1.5 text-xs">
          <Plus size={13} /> Add MF Holding
        </button>
      </div>

      {loading ? (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {[...Array(4)].map((_, i) => <div key={i} className="card animate-pulse h-20 bg-surface-hover" />)}
        </div>
      ) : (
        <>
          {/* 1. Portfolio Summary */}
          <CollapsibleSection title="Portfolio Summary" icon={<Wallet size={15} className="text-brand-light" />}
            onRefresh={load} loading={loading}>
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
              <StatTile label="Amount Invested" value={fmt(combined.totalInvested)} Icon={Wallet} tone="brand" />
              <StatTile label="Current Value" value={fmt(combined.currentValue)} Icon={TrendingUp} tone="brand" />
              <StatTile label="Overall Returns" value={fmtPct(cagr)} Icon={cagr >= 0 ? TrendingUp : Layers} tone={cagr >= 0 ? 'bull' : 'bear'} />
              <StatTile label="Number of Funds" value={String(combined.holdings)} Icon={PieChart} tone="gold" sensitive={false} />
            </div>
          </CollapsibleSection>

          {/* 2. Holdings — full width */}
          <CollapsibleSection title="Fund Holdings &amp; Signals" icon={<Layers size={15} className="text-brand-light" />}
            onRefresh={load} loading={loading}>
            {allHoldings.length === 0 ? (
              <div className="text-center py-12 text-gray-600">
                <p className="text-sm">No MF holdings logged yet.</p>
                <p className="text-xs mt-2">Click "Add MF Holding" above to enter your existing funds or new purchases.</p>
                <button onClick={() => setShowModal(true)} className="btn-primary mt-4 text-xs">
                  <Plus size={12} className="inline mr-1" /> Add First Fund
                </button>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th className="text-left">Fund Name</th>
                      <th className="text-left">Purchase Date</th>
                      <th className="text-right">Amount Invested</th>
                      <th className="text-right">Current Value</th>
                      <th className="text-right">Returns</th>
                      <th className="text-right">Weight</th>
                      <th className="text-center">Signal</th>
                      <th className="text-center">Remove</th>
                    </tr>
                  </thead>
                  <tbody>
                    {allHoldings.map(h => (
                      <tr key={h.id} onClick={() => setSelectedHolding(h)}
                        className="cursor-pointer hover:bg-surface-hover/80 transition-colors">
                        <td>
                          <div className="font-mono text-white text-xs">{h.symbol?.replace('.MF', '')}</div>
                          <div className="text-xs text-gray-500 truncate max-w-[200px]">{h.name}</div>
                          {h.broker && <div className="text-2xs text-gray-600">{h.broker}</div>}
                        </td>
                        <td className="text-gray-300 text-xs whitespace-nowrap">
                          {h.buyDate ? fmtDate(h.buyDate) : '—'}
                        </td>
                        <td className="text-right num text-gray-400">{maskText(fmt(h.investedValue))}</td>
                        <td className="text-right num text-white">{maskText(fmt(h.currentValue))}</td>
                        <td className={`text-right num font-semibold ${h.pnl >= 0 ? 'text-bull' : 'text-bear'}`}>
                          {maskText(fmtPct(h.pnlPercent))}
                        </td>
                        <td className="text-right text-gray-500">{maskText(`${h.weightPercent?.toFixed(1)}%`)}</td>
                        <td className="text-center">
                          <MfTrendBadge symbol={h.symbol} fundName={h.name} investedValue={h.investedValue ?? 0}
                            currentValue={h.currentValue ?? 0} quantity={h.quantity} xirr={h.xirr} buyDate={h.buyDate}
                            totalMfPortfolioValue={combined.currentValue} />
                        </td>
                        <td className="text-center">
                          <button
                            onClick={async e => {
                              e.stopPropagation();
                              if (!window.confirm(`Remove "${h.name || h.symbol}" and its transaction history? This cannot be undone.`)) return;
                              await portfolioApi.removeHolding(h.portfolioId, h.id);
                              load();
                            }}
                            className="btn-icon p-1 hover:bg-bear/10 rounded text-gray-500 hover:text-bear"
                            title="Remove holding"
                          >
                            <X size={13} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="text-2xs text-gray-600 mt-3 px-1">Click any fund to view its complete transaction history. Use the ✕ to remove an incorrect or duplicate holding.</p>
              </div>
            )}
          </CollapsibleSection>

          {/* 3. Asset Allocation + 4. Performance & Analytics — side by side */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {/* Asset Allocation */}
            <CollapsibleSection title="Asset Allocation" icon={<PieChart size={15} className="text-brand-light" />}
              onRefresh={load} loading={loading}>
              {mfAllocation.length > 0 ? (
                <AllocationChart allocation={mfAllocation} />
              ) : (
                <p className="text-center text-gray-600 py-6 text-sm">No allocation data available.</p>
              )}
            </CollapsibleSection>

            {/* Performance & Analytics (Benchmark Comparison) */}
            <CollapsibleSection title="Performance &amp; Analytics" icon={<BarChart3 size={15} className="text-brand-light" />}
              onRefresh={loadBenchmarks} loading={benchmarksLoading}>
              <Benchmark label="Your Portfolio Return" cagr={fmtPct(cagr)} color="#6d5efc" highlight sensitive />
              <Benchmark label="Nifty 50 (trailing)"    cagr={benchmarks?.nifty50 != null ? fmtPct(benchmarks.nifty50) : null} color="#00d68f" />
              <Benchmark label="Sensex (trailing)"      cagr={benchmarks?.sensex != null ? fmtPct(benchmarks.sensex) : null} color="#00c2ff" />
              <Benchmark label="Bank Nifty (trailing)"  cagr={benchmarks?.bankNifty != null ? fmtPct(benchmarks.bankNifty) : null} color="#ffb454" />
              <p className="text-xs text-gray-700 mt-3">Trailing return over available price history (~9-10 months of stored data), not a calendar year. Not investment advice.</p>
            </CollapsibleSection>
          </div>

          {/* 5. Redemption Tracker */}
          <CollapsibleSection title="Redemption &amp; Reinvestment Tracker" icon={<ArrowUpRight size={15} className="text-brand-light" />}
            onRefresh={load} loading={loading}>
            <RedeemedInvestments />
          </CollapsibleSection>

          {/* 6. AI Recommendations */}
          <CollapsibleSection title="AI Recommendations" icon={<Lightbulb size={15} className="text-brand-light" />}
            onRefresh={load} loading={loading}>
            {allHoldings.length === 0 ? (
              <p className="text-center text-gray-600 py-6 text-sm">Add holdings to see AI-powered recommendations.</p>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                {allHoldings.map(h => (
                  <div key={h.id} className="bg-surface-hover/50 rounded-lg p-3 border border-surface-border/50 hover:border-surface-border transition-colors">
                    <div className="flex items-start justify-between gap-2 mb-2">
                      <div className="min-w-0">
                        <div className="font-mono text-white text-xs truncate">{h.symbol?.replace('.MF', '')}</div>
                        <div className="text-2xs text-gray-500 truncate">{h.name}</div>
                      </div>
                      <MfTrendBadge symbol={h.symbol} fundName={h.name} investedValue={h.investedValue ?? 0}
                        currentValue={h.currentValue ?? 0} quantity={h.quantity} xirr={h.xirr} buyDate={h.buyDate}
                        totalMfPortfolioValue={combined.currentValue} />
                    </div>
                    <div className="flex items-center justify-between text-2xs">
                      <span className="text-gray-500">Invested: {maskText(fmt(h.investedValue))}</span>
                      <span className={`font-semibold ${h.pnl >= 0 ? 'text-bull' : 'text-bear'}`}>{maskText(fmtPct(h.pnlPercent))}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CollapsibleSection>

          {/* 7. Recent MF Purchases — bottom */}
          <RecentPurchases />
        </>
      )}

      {showModal && portfolio && (
        <AddMFModal portfolioId={portfolio.id} onClose={() => setShowModal(false)} onAdded={load} />
      )}

      {selectedHolding && (
        <TransactionHistoryModal
          holding={selectedHolding}
          portfolioId={selectedHolding.portfolioId}
          onClose={() => setSelectedHolding(null)}
        />
      )}
    </div>
  );
}
