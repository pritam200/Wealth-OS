import { useState, useEffect, useCallback, Fragment } from 'react';
import {
  Shield, TrendingUp, AlertTriangle, Landmark,
  CreditCard, RefreshCw, PieChart, ArrowUpRight, ArrowDownRight,
  Coins, Briefcase, Banknote,
  ChevronDown, ChevronUp, Calendar, Eye, EyeOff,
} from 'lucide-react';
import { StatTile } from '../../components/shared/StatTile';
import { Amount, useMaskedText } from '../../components/shared/Amount';
import { usePrivacyStore } from '../../store/privacyStore';
import { HoldingTrendBadge } from '../../components/wealth/HoldingTrendBadge';
import { MfTrendBadge } from '../../components/wealth/MfTrendBadge';
import { RedeemedInvestments } from '../../components/wealth/RedeemedInvestments';
import { LoanSection } from '../../components/wealth/LoanSection';
import { WealthCalculator } from '../../components/wealth/WealthCalculator';
import { amfiNavApi } from '../../api/amfiNav';
import { portfolioApi } from '../../api/portfolio';
import { trackingApi } from '../../api/tracking';
import type { TrackingSummary } from '../../api/tracking';
import type { PortfolioSummary, HoldingDto } from '../../types';
import { PieChart as RechartsPie, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
const fmtPct = (n: number) => `${n >= 0 ? '+' : ''}${(n || 0).toFixed(2)}%`;

/* ─────────────────────────────────────────────────────────────
   PORTFOLIO (stocks + MF from backend)
───────────────────────────────────────────────────────────── */
export function PortfolioSection({ onValues, showSignal = false, only }: { onValues: (inv: number, cur: number, mfCur: number) => void; showSignal?: boolean; only?: 'stocks' | 'mf' }) {
  const [summary, setSummary] = useState<PortfolioSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [sellId, setSellId] = useState<number | null>(null);
  const [sellQty, setSellQty] = useState('');
  const [sellPrice, setSellPrice] = useState('');
  const [rebuilding, setRebuilding] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const { data: list } = await portfolioApi.list();
      if (!list.length) { setSummary(null); onValues(0, 0, 0); setLoading(false); return; }
      // A user can end up with several portfolio rows (each Gmail-import path resolves
      // "the user's portfolio" independently). Holdings must never be silently dropped
      // just because they live in a portfolio other than the one with the most rows —
      // merge every portfolio's holdings into one aggregated view instead of picking one.
      const summaries = (await Promise.all(
        list.map(p => portfolioApi.getSummary(p.id).then(r => r.data).catch(() => null))
      )).filter(Boolean) as PortfolioSummary[];
      const allHoldings = summaries.flatMap(s => s.holdings ?? []);
      const totalInv = summaries.reduce((s, p) => s + (p.totalInvested ?? 0), 0);
      const totalCur = summaries.reduce((s, p) => s + (p.currentValue ?? 0), 0);
      const mfCur = allHoldings.filter(h => (h.symbol ?? '').endsWith('.MF')).reduce((s, h) => s + (h.currentValue ?? 0), 0);
      const merged: PortfolioSummary | null = summaries.length ? {
        ...summaries[0],
        holdings: allHoldings,
        totalInvested: totalInv,
        currentValue: totalCur,
        totalPnl: totalCur - totalInv,
        totalPnlPercent: totalInv > 0 ? ((totalCur - totalInv) / totalInv) * 100 : 0,
      } : null;
      setSummary(merged);
      onValues(totalInv, totalCur, mfCur);
    } catch { onValues(0, 0, 0); }
    setLoading(false);
  };

  const doSell = async (holdingId: number, holdingPortfolioId: number) => {
    if (!sellQty || !sellPrice) return;
    try {
      await portfolioApi.sellHolding(holdingPortfolioId, holdingId, Number(sellQty), Number(sellPrice));
      setSellId(null); setSellQty(''); setSellPrice('');
      await load();
    } catch {}
  };

  useEffect(() => { load(); }, []);

  const holdings = summary?.holdings ?? [];
  const cur = summary?.currentValue ?? 0;
  const stocks = holdings.filter(h => !h.symbol?.endsWith('.MF'));
  const mfs    = holdings.filter(h => h.symbol?.endsWith('.MF'));
  const sell = { sellId, setSellId, sellQty, setSellQty, sellPrice, setSellPrice, doSell };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Briefcase size={14} className="text-brand" />
          <h3 className="font-semibold text-white text-sm">Investments</h3>
          {cur > 0 && <span className="text-xs text-gray-500 font-mono"><Amount value={fmtINR(cur)} /></span>}
        </div>
        <div className="flex items-center gap-1.5">
          <button onClick={async () => { setRebuilding(true); try { await portfolioApi.rebuild(); await load(); } catch {} setRebuilding(false); }}
            disabled={rebuilding} className="btn-ghost text-2xs px-2 py-1 text-yellow-400 hover:text-yellow-300"
            title="Recalculate all holdings from transaction history — fixes quantity/P&L mismatches">
            {rebuilding ? 'Rebuilding…' : 'Rebuild'}
          </button>
          <button onClick={() => load()} className="btn-icon"><RefreshCw size={12} /></button>
        </div>
      </div>
      {loading
        ? <div className="h-12 animate-pulse bg-surface-hover rounded" />
        : !holdings.length
          ? <p className="text-gray-600 text-xs text-center py-6">No holdings yet — use Bulk Import or connect Gmail to auto-import trades.</p>
          : (
            <>
              {only !== 'mf' && <HoldingsGroup title="Stocks" Icon={Briefcase} holdings={stocks} sell={sell} reload={load} showSignal={showSignal} />}
              {only !== 'stocks' && <HoldingsGroup title="Mutual Funds" Icon={PieChart} holdings={mfs} sell={sell} reload={load} showSignal={showSignal} />}
            </>
          )}
      {only !== 'stocks' && <RedeemedInvestments />}
    </div>
  );
}

/* Stocks-only holdings view with recommendation signals — used by the Stocks section's
   Holdings & Signals sub-tab. My Wealth renders the badge-free version above. */
export function StocksHoldingsSignals() {
  return <PortfolioSection onValues={() => {}} showSignal only="stocks" />;
}

/* MF-only holdings view with recommendation signals — used by the Mutual Funds section. */
export function MfHoldingsSignals() {
  return <PortfolioSection onValues={() => {}} showSignal only="mf" />;
}

/* ─────────────────────────────────────────────────────────────
   NET WORTH SUMMARY
───────────────────────────────────────────────────────────── */
type SellState = {
  sellId: number | null; setSellId: (n: number | null) => void;
  sellQty: string; setSellQty: (s: string) => void;
  sellPrice: string; setSellPrice: (s: string) => void;
  doSell: (id: number, portfolioId: number) => void;
};

// portfolioId is deliberately NOT a prop: every per-row action (edit/sell/remove) now uses
// that holding's own `portfolioId`, so a single passed-down id can't misroute an action when
// holdings are aggregated across several portfolios.
function HoldingsGroup({ title, Icon, holdings, sell, reload, showSignal = false }:
  { title: string; Icon: any; holdings: HoldingDto[]; sell: SellState; reload: () => void; showSignal?: boolean }) {
  const masked = usePrivacyStore(s => s.masked);
  const mask = (v: string | number) => (masked ? '••••••' : v);
  const [open, setOpen] = useState(true);
  const [editId, setEditId] = useState<number | null>(null);
  const [eQty, setEQty] = useState('');
  const [eAvg, setEAvg] = useState('');
  const [ePrice, setEPrice] = useState('');
  const [eInvested, setEInvested] = useState('');
  const [eBroker, setEBroker] = useState('');
  const [eFolio, setEFolio] = useState('');
  const [eBuyDate, setEBuyDate] = useState('');
  const [navFetching, setNavFetching] = useState(false);
  const [navError, setNavError] = useState('');

  const fetchLiveNav = async (fundName: string) => {
    setNavFetching(true); setNavError('');
    try {
      const { data } = await amfiNavApi.lookup(fundName);
      setEPrice(String(data.nav));
    } catch {
      setNavError('No AMFI match found — enter NAV manually');
    } finally { setNavFetching(false); }
  };
  if (!holdings.length) return null;
  const isMf = title === 'Mutual Funds';
  const brokerOptions = isMf
    ? ['HDFC', 'SBI', 'ICICI', 'Axis', 'Kotak', 'Nippon', 'Groww', 'Zerodha Coin', 'Other']
    : ['UPStox', 'MStock', 'Kotak', 'Zerodha', 'Groww', 'Angel One', 'ICICI Direct', 'HDFC Sec', 'Other'];

  const startEdit = (h: HoldingDto) => {
    setEditId(editId === h.id ? null : h.id);
    setEQty(String(h.quantity)); setEAvg(String(h.averageCost));
    setEPrice(h.currentPrice ? String(h.currentPrice) : '');
    setEInvested(''); setEBroker(h.broker || ''); setEFolio(h.folio || '');
    setEBuyDate(h.buyDate || '');
    sell.setSellId(null);
  };
  const saveEdit = async (id: number, holdingPortfolioId: number) => {
    await portfolioApi.updateHolding(holdingPortfolioId, id, {
      quantity: eQty ? Number(eQty) : undefined,
      averageCost: eAvg ? Number(eAvg) : undefined,
      currentPrice: ePrice ? Number(ePrice) : undefined,
      investedAmount: eInvested ? Number(eInvested) : undefined,
      broker: eBroker || undefined,
      folio: eFolio || undefined,
      buyDate: eBuyDate || undefined,
    });
    setEditId(null); reload();
  };

  // group holdings by broker/provider for segregated display
  const groups = new Map<string, HoldingDto[]>();
  [...holdings].sort((a, b) => b.pnlPercent - a.pnlPercent).forEach(h => {
    const key = h.broker || 'Unassigned';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(h);
  });
  const inv = holdings.reduce((s, h) => s + (h.investedValue ?? 0), 0);
  const cur = holdings.reduce((s, h) => s + (h.currentValue ?? 0), 0);
  const pnl = cur - inv, pnlPct = inv > 0 ? pnl / inv * 100 : 0;
  const cols = showSignal ? 8 : 7; // symbol, qty, avg, current, value, return, [signal], actions

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-3 cursor-pointer" onClick={() => setOpen(o => !o)}>
        <div className="flex items-center gap-2">
          <Icon size={14} className="text-brand" />
          <h3 className="font-semibold text-white text-sm">{title}</h3>
          <span className="text-2xs bg-brand/15 text-brand px-1.5 py-0.5 rounded-full">{holdings.length}</span>
          <span className="text-xs text-gray-500 font-mono">{mask(fmtINR(cur))}</span>
        </div>
        {open ? <ChevronUp size={14} className="text-gray-500" /> : <ChevronDown size={14} className="text-gray-500" />}
      </div>
      {open && (
        <div className="space-y-3">
          <div className="grid grid-cols-4 gap-2">
            <StatTile label="Invested" value={fmtINR(inv)} Icon={Landmark} tone="brand" />
            <StatTile label="Current" value={fmtINR(cur)} Icon={TrendingUp} tone="brand" />
            <StatTile label="Gain/Loss" value={fmtINR(pnl)} Icon={pnl >= 0 ? ArrowUpRight : ArrowDownRight} tone={pnl >= 0 ? 'bull' : 'bear'} sub={fmtPct(pnlPct)} />
            <StatTile label="Count" value={String(holdings.length)} Icon={Icon} tone="neutral" sensitive={false} />
          </div>
          <div className="overflow-x-auto">
            <table className="data-table">
              <thead><tr>
                <th className="text-left">{isMf ? 'Fund' : 'Symbol'}</th>
                <th className="text-right">{isMf ? 'Units' : 'Qty'}</th>
                <th className="text-right">Avg {isMf ? 'NAV' : 'Cost'}</th>
                <th className="text-right">Current</th>
                <th className="text-right">Value</th><th className="text-right">Return</th>
                {showSignal && <th className="text-center">Signal</th>}
                <th className="text-right"></th>
              </tr></thead>
              <tbody>{Array.from(groups.entries()).map(([bk, rows]) => (
                <Fragment key={bk}>
                {groups.size > 1 && (
                  <tr><td colSpan={cols} className="bg-surface/60 py-1 px-2 text-2xs font-medium text-brand uppercase tracking-wide">{bk} · {rows.length} · {mask(fmtINR(rows.reduce((s, x) => s + (x.currentValue || 0), 0)))}</td></tr>
                )}
                {rows.map(h => (
                <Fragment key={h.id}>
                <tr>
                  <td>
                    {isMf
                      ? <div className="text-white text-xs font-medium truncate max-w-[220px]">{h.name}</div>
                      : <><div className="font-mono text-white text-xs font-medium">{h.symbol?.replace('.NS', '')}</div><div className="text-2xs text-gray-600 truncate max-w-[140px]">{h.name}</div></>}
                    <div className="text-2xs text-gray-700">
                      {h.folio && <span>Folio {h.folio}</span>}
                      {h.folio && h.buyDate && <span> · </span>}
                      {h.buyDate && <span>Bought {h.buyDate}</span>}
                    </div>
                  </td>
                  <td className="text-right num text-gray-300 text-xs">{mask(h.quantity)}</td>
                  <td className="text-right num text-gray-400 text-xs">{mask(fmtINR(h.averageCost))}</td>
                  <td className="text-right num text-white text-xs">{h.currentPrice ? mask(fmtINR(h.currentPrice)) : <span className="text-gray-700" title={isMf ? 'No live NAV — tap Edit to set it' : 'No live price'}>—</span>}</td>
                  <td className="text-right num text-white text-xs">{mask(fmtINR(h.currentValue))}</td>
                  <td className="text-right num font-semibold text-xs">{isMf && !h.currentPrice
                    ? <span className="text-gray-600" title="At cost — set the latest NAV via Edit to see returns">at cost</span>
                    : <span className={h.pnlPercent >= 0 ? 'text-bull' : 'text-bear'}>{masked ? '••••••' : `${h.pnlPercent >= 0 ? '+' : ''}${h.pnlPercent?.toFixed(2)}%`}</span>}</td>
                  {showSignal && (
                    <td className="text-center">{isMf
                      ? <MfTrendBadge symbol={h.symbol} fundName={h.name} investedValue={h.investedValue ?? 0}
                          currentValue={h.currentValue ?? 0} quantity={h.quantity} xirr={h.xirr} buyDate={h.buyDate}
                          totalMfPortfolioValue={cur} />
                      : <HoldingTrendBadge symbol={h.symbol} pnlPercent={h.pnlPercent} holdingValue={h.currentValue} totalPortfolioValue={cur} />}</td>
                  )}
                  <td className="text-right whitespace-nowrap">
                    <button title="Correct quantity / avg cost / NAV" onClick={() => startEdit(h)}
                      className="text-2xs px-1.5 py-0.5 rounded border border-surface-border text-gray-400 hover:text-white mr-1">Edit</button>
                    <button title="Record in your tracker — does not place a real order" onClick={() => { setEditId(null); sell.setSellId(sell.sellId === h.id ? null : h.id); sell.setSellQty(String(h.quantity)); sell.setSellPrice(String(h.currentPrice ?? h.averageCost)); }}
                      className="text-2xs px-1.5 py-0.5 rounded border border-bear/40 text-bear hover:bg-bear/10 mr-1">{isMf ? 'Redeem' : 'Sell'}</button>
                    <button title="Remove this holding and its transaction history — for incorrect or duplicate entries"
                      onClick={async () => {
                        if (!window.confirm(`Remove "${h.name || h.symbol}" and its transaction history? This cannot be undone.`)) return;
                        await portfolioApi.removeHolding(h.portfolioId, h.id);
                        reload();
                      }}
                      className="text-2xs px-1.5 py-0.5 rounded border border-surface-border text-gray-500 hover:text-bear hover:border-bear/40">✕</button>
                  </td>
                </tr>
                {editId === h.id && (
                  <tr key={`${h.id}-edit`}>
                    <td colSpan={cols} className="bg-surface-hover">
                      <div className="flex items-center gap-2 p-2 flex-wrap">
                        <span className="text-2xs text-gray-500">Correct —</span>
                        <label className="text-2xs text-gray-600">{isMf ? 'Units' : 'Qty'}</label>
                        <input type="number" value={eQty} onChange={e => setEQty(e.target.value)} className="input-field text-xs w-24 py-1" />
                        <label className="text-2xs text-gray-600">Avg {isMf ? 'NAV' : 'cost'}</label>
                        <input type="number" value={eAvg} onChange={e => setEAvg(e.target.value)} className="input-field text-xs w-24 py-1" />
                        <label className="text-2xs text-gray-600">{isMf ? 'Current NAV' : 'Current price'}</label>
                        <input type="number" value={ePrice} onChange={e => setEPrice(e.target.value)} placeholder={isMf ? 'latest NAV' : 'live'} className="input-field text-xs w-24 py-1" />
                        {isMf && (
                          <button type="button" onClick={() => fetchLiveNav(h.name)} disabled={navFetching}
                            title="Look up today's official NAV from AMFI (free, no manual entry)"
                            className="btn-ghost text-2xs px-2 py-1 border border-surface-border">
                            {navFetching ? 'Fetching…' : 'Fetch live NAV'}
                          </button>
                        )}
                        {isMf && navError && <span className="text-2xs text-bear">{navError}</span>}
                        <label className="text-2xs text-gray-600">{isMf ? 'Provider' : 'Broker'}</label>
                        <input list={`brk-${h.id}`} value={eBroker} onChange={e => setEBroker(e.target.value)} placeholder={isMf ? 'HDFC…' : 'UPStox…'} className="input-field text-xs w-28 py-1" />
                        <datalist id={`brk-${h.id}`}>{brokerOptions.map(b => <option key={b} value={b} />)}</datalist>
                        {isMf && <><label className="text-2xs text-gray-600">Folio</label>
                        <input value={eFolio} onChange={e => setEFolio(e.target.value)} placeholder="folio no." className="input-field text-xs w-28 py-1" />
                        <label className="text-2xs text-gray-600">Invested ₹</label>
                        <input type="number" value={eInvested} onChange={e => setEInvested(e.target.value)} placeholder="total (sets NAV cost)" className="input-field text-xs w-32 py-1" /></>}
                        <button onClick={() => saveEdit(h.id, h.portfolioId)} className="btn-primary text-xs py-1 px-3 ml-auto">Save</button>
                        <button onClick={() => setEditId(null)} className="btn-ghost text-xs py-1">Cancel</button>
                        {isMf && <span className="text-2xs text-gray-600 w-full">MFs have no live feed — set NAV (or total invested) and folio from your statement.</span>}
                      </div>
                    </td>
                  </tr>
                )}
                {sell.sellId === h.id && (
                  <tr key={`${h.id}-sell`}>
                    <td colSpan={cols} className="bg-surface-hover">
                      <div className="flex items-center gap-2 p-2 flex-wrap">
                        <span className="text-2xs text-gray-500">Record {isMf ? 'redemption' : 'sale'} —</span>
                        <input type="number" value={sell.sellQty} onChange={e => sell.setSellQty(e.target.value)} placeholder={isMf ? 'Units' : 'Qty'} className="input-field text-xs w-20 py-1" />
                        <span className="text-2xs text-gray-500">@ ₹</span>
                        <input type="number" value={sell.sellPrice} onChange={e => sell.setSellPrice(e.target.value)} placeholder="Price" className="input-field text-xs w-24 py-1" />
                        {sell.sellQty && sell.sellPrice && (
                          <span className={`text-2xs font-mono ${(Number(sell.sellPrice) - h.averageCost) >= 0 ? 'text-bull' : 'text-bear'}`}>
                            PnL: {fmtINR((Number(sell.sellPrice) - h.averageCost) * Number(sell.sellQty))}
                          </span>
                        )}
                        <button onClick={() => sell.doSell(h.id, h.portfolioId)} className="btn-primary text-xs py-1 px-3 ml-auto">Record</button>
                        <button onClick={() => sell.setSellId(null)} className="btn-ghost text-xs py-1">Cancel</button>
                        <span className="text-2xs text-gray-600 w-full">Updates your tracker only — no real order is placed.</span>
                      </div>
                    </td>
                  </tr>
                )}
                </Fragment>
                ))}
                </Fragment>
              ))}</tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

export function NetWorthBar({ stocksCurrent, mfCurrent = 0, summary }: { stocksCurrent: number; mfCurrent?: number; summary: TrackingSummary | null }) {
  const masked = usePrivacyStore(s => s.masked);
  const toggleMasked = usePrivacyStore(s => s.toggle);
  if (!summary && stocksCurrent === 0) return null;
  const fdVal    = summary?.totalFdCurrentValue ?? summary?.totalFdPrincipal ?? 0;
  const rdVal    = summary?.totalRdCurrentValue ?? 0;
  const otherVal = summary?.totalOtherAssets ?? 0;
  const epfVal   = summary?.totalEpf ?? 0;
  const loans    = summary?.totalLoanOutstanding ?? 0;
  const emi      = summary?.totalMonthlyEmi ?? 0;
  const stockOnly = Math.max(0, stocksCurrent - mfCurrent);   // stocksCurrent = stocks + MF combined
  const total    = stocksCurrent + fdVal + rdVal + otherVal + epfVal;
  const net      = total - loans;
  const pieData  = [
    stockOnly > 0     ? { name: 'Stocks',        value: stockOnly,     fill: '#6d5efc' } : null,
    mfCurrent > 0     ? { name: 'Mutual Funds',  value: mfCurrent,     fill: '#e84fd9' } : null,
    fdVal > 0         ? { name: 'FD',            value: fdVal,         fill: '#00d68f' } : null,
    rdVal > 0         ? { name: 'RD',            value: rdVal,         fill: '#ffb454' } : null,
    epfVal > 0        ? { name: 'EPF',           value: epfVal,        fill: '#00c2ff' } : null,
    otherVal > 0      ? { name: 'Other',         value: otherVal,      fill: '#f5c451' } : null,
  ].filter(Boolean) as { name: string; value: number; fill: string }[];
  if (total === 0) return null;
  return (
    <div className="card-elevated">
      <div className="flex items-center justify-between gap-2.5 mb-4">
        <div className="flex items-center gap-2.5">
          <div className="icon-badge-brand"><Banknote size={15} /></div>
          <div>
            <h3 className="font-bold text-white text-sm">Net Worth Summary</h3>
            <p className="text-2xs text-gray-500">Everything you own, minus what you owe</p>
          </div>
        </div>
        <button onClick={toggleMasked} title={masked ? 'Show wealth' : 'Hide wealth'}
          className={`flex items-center gap-1.5 text-2xs font-semibold px-2.5 py-1.5 rounded-lg transition-colors shrink-0 ${
            masked ? 'text-gray-400 hover:text-white hover:bg-surface-hover' : 'text-brand-light bg-brand/10'
          }`}>
          {masked ? <Eye size={13} /> : <EyeOff size={13} />}
          {masked ? 'Show' : 'Hide'} wealth
        </button>
      </div>
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-2.5 mb-4">
        <StatTile label="Total Assets" value={fmtINR(total)} Icon={Coins} tone="brand" />
        <StatTile label="Loans" value={fmtINR(loans)} Icon={CreditCard} tone="bear" />
        <div className="kpi-tile">
          <div className="icon-badge-bull"><TrendingUp size={15} /></div>
          <div className="min-w-0">
            <div className="stat-label">Net Worth</div>
            <div className="stat-value-gradient text-sm">{masked ? '••••••' : fmtINR(net)}</div>
          </div>
        </div>
        <StatTile label="FD + RD + EPF + Other" value={fmtINR(fdVal + rdVal + epfVal + otherVal)} Icon={Landmark} tone="neutral" />
        <StatTile label="Monthly EMI" value={fmtINR(emi) + '/mo'} Icon={Calendar} tone="bear" />
      </div>
      {pieData.length > 1 && (
        <div className="flex items-center gap-6">
          <ResponsiveContainer width="35%" height={110}>
            <RechartsPie>
              <Pie data={pieData} cx="50%" cy="50%" innerRadius={28} outerRadius={50} dataKey="value" nameKey="name" paddingAngle={3}>
                {pieData.map((d, i) => <Cell key={i} fill={d.fill} stroke="none" />)}
              </Pie>
              <Tooltip formatter={(v) => (masked ? '••••••' : fmtINR(Number(v ?? 0)))} contentStyle={{ background: '#0e0d16', border: '1px solid #232032', borderRadius: 10, fontSize: 11 }} />
            </RechartsPie>
          </ResponsiveContainer>
          <div className="space-y-1">
            {pieData.map(d => (
              <div key={d.name} className="flex items-center gap-2 text-2xs">
                <div className="w-2 h-2 rounded-full shrink-0" style={{ background: d.fill }} />
                <span className="text-gray-400">{d.name}</span>
                <span className="text-white font-mono ml-2">{masked ? '••••••' : fmtINR(d.value)}</span>
                <span className="text-gray-600">({masked ? '••••••' : ((d.value / total) * 100).toFixed(1)}%)</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

/* ─────────────────────────────────────────────────────────────
   RISK SCORE CARD
───────────────────────────────────────────────────────────── */
function RiskScoreCard({ summary, stocksCurrent }: { summary: TrackingSummary | null; stocksCurrent: number }) {
  const maskText = useMaskedText();
  if (!summary) return null;

  const fdVal   = summary.totalFdCurrentValue ?? summary.totalFdPrincipal ?? 0;
  const rdVal   = summary.totalRdCurrentValue ?? 0;
  const loanVal = summary.totalLoanOutstanding ?? 0;
  const otherVal = summary.totalOtherAssets ?? 0;
  const epfVal  = summary.totalEpf ?? 0;
  const totalAssets = stocksCurrent + fdVal + rdVal + otherVal + epfVal;

  const equityPct  = totalAssets > 0 ? (stocksCurrent / totalAssets * 100) : 0;
  const debtPct    = totalAssets > 0 ? ((fdVal + rdVal) / totalAssets * 100) : 0;
  const otherPct   = totalAssets > 0 ? (otherVal / totalAssets * 100) : 0;
  const debtToEquity = stocksCurrent > 0 ? loanVal / stocksCurrent : 0;

  let riskScore = 50;
  if (equityPct > 70)  riskScore += 20;
  else if (equityPct > 50) riskScore += 10;
  else if (equityPct < 20) riskScore -= 15;
  if (debtPct > 50)    riskScore -= 10;
  if (debtToEquity > 0.5) riskScore += 15;
  riskScore = Math.max(10, Math.min(90, riskScore));

  const riskLabel = riskScore > 65 ? 'High Risk' : riskScore > 40 ? 'Moderate' : 'Conservative';
  const riskColor = riskScore > 65 ? 'text-bear' : riskScore > 40 ? 'text-yellow-400' : 'text-bull';
  const riskBg    = riskScore > 65 ? 'bg-bear/10 border-bear/20' : riskScore > 40 ? 'bg-yellow-400/10 border-yellow-400/20' : 'bg-bull/10 border-bull/20';

  const epfPct = totalAssets > 0 ? (epfVal / totalAssets * 100) : 0;

  const allocData = [
    { name: 'Equity', value: Math.round(equityPct), color: '#2563eb' },
    { name: 'Debt (FD/RD)', value: Math.round(debtPct), color: '#00c47a' },
    { name: 'EPF', value: Math.round(epfPct), color: '#00c2ff' },
    { name: 'Other', value: Math.round(otherPct), color: '#e8a020' },
  ].filter(d => d.value > 0);

  const recommendations: string[] = [];
  if (equityPct > 80) recommendations.push('Heavy equity concentration — consider adding debt for stability.');
  if (debtPct > 70)   recommendations.push('Over-allocated to fixed income — equities may improve long-term returns.');
  if (loanVal > stocksCurrent * 0.5) recommendations.push('Significant debt load — prioritise loan repayment.');
  if (equityPct > 0 && debtPct === 0) recommendations.push('No fixed income — add FDs or RDs as a safety buffer.');
  if (recommendations.length === 0) recommendations.push('Portfolio looks balanced. Keep investing consistently.');

  return (
    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
      {/* Risk Score */}
      <div className={`card border ${riskBg}`}>
        <div className="flex items-center gap-2 mb-2">
          <Shield size={14} className={riskColor} />
          <h3 className="font-semibold text-white text-sm">Risk Score</h3>
        </div>
        <div className={`text-4xl font-bold font-mono ${riskColor} mb-1`}>{riskScore}</div>
        <div className={`text-xs font-semibold ${riskColor} mb-3`}>{riskLabel}</div>
        <div className="h-2 bg-surface-hover rounded-full overflow-hidden mb-4">
          <div className="h-full rounded-full" style={{ width: `${riskScore}%`, background: riskScore > 65 ? '#f03e3e' : riskScore > 40 ? '#fbbf24' : '#00c47a' }} />
        </div>
        <div className="space-y-1.5">
          <div className="flex justify-between text-2xs">
            <span className="text-gray-500">Equity exposure</span>
            <span className="text-white font-mono">{maskText(`${equityPct.toFixed(1)}%`)}</span>
          </div>
          <div className="flex justify-between text-2xs">
            <span className="text-gray-500">Debt/Fixed</span>
            <span className="text-white font-mono">{maskText(`${debtPct.toFixed(1)}%`)}</span>
          </div>
          <div className="flex justify-between text-2xs">
            <span className="text-gray-500">Loan burden</span>
            <span className={`font-mono ${loanVal > 0 ? 'text-bear' : 'text-gray-500'}`}>{maskText(fmtINR(loanVal))}</span>
          </div>
        </div>
      </div>

      {/* Asset Allocation Pie */}
      <div className="card">
        <div className="flex items-center gap-2 mb-3">
          <PieChart size={14} className="text-brand" />
          <h3 className="font-semibold text-white text-sm">Asset Allocation</h3>
        </div>
        {allocData.length > 0 ? (
          <ResponsiveContainer width="100%" height={140}>
            <RechartsPie>
              <Pie data={allocData} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={55}>
                {allocData.map((e, i) => <Cell key={i} fill={e.color} />)}
              </Pie>
              <Tooltip formatter={(v) => maskText(`${Number(v ?? 0)}%`)} contentStyle={{ background: '#1a1f2e', border: '1px solid #2a3040', fontSize: 11 }} />
            </RechartsPie>
          </ResponsiveContainer>
        ) : (
          <div className="h-36 flex items-center justify-center text-gray-600 text-xs">Add assets to see allocation</div>
        )}
        <div className="flex flex-wrap gap-3 mt-1 justify-center">
          {allocData.map(d => (
            <div key={d.name} className="flex items-center gap-1.5 text-2xs">
              <span className="w-2 h-2 rounded-full shrink-0" style={{ background: d.color }} />
              <span className="text-gray-400">{d.name}</span>
              <span className="text-gray-200 font-mono">{maskText(`${d.value}%`)}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Recommendations */}
      <div className="card">
        <div className="flex items-center gap-2 mb-3">
          <AlertTriangle size={14} className="text-yellow-400" />
          <h3 className="font-semibold text-white text-sm">Recommendations</h3>
        </div>
        <ul className="space-y-2">
          {recommendations.map((r, i) => (
            <li key={i} className="flex gap-2 text-xs text-gray-300">
              <span className="text-brand shrink-0 mt-0.5">▸</span>
              <span>{r}</span>
            </li>
          ))}
        </ul>
        <div className="mt-4 pt-3 border-t border-surface-border/40">
          <div className="text-2xs text-gray-600 mb-2">Diversification Index</div>
          <div className="grid grid-cols-2 gap-1.5">
            {[
              { label: 'Equity', ok: equityPct > 5 && equityPct < 85 },
              { label: 'FD/RD', ok: debtPct > 5 },
              { label: 'Other', ok: otherPct > 0 },
              { label: 'No over-leverage', ok: debtToEquity < 0.5 },
            ].map(({ label, ok }) => (
              <div key={label} className={`text-2xs flex items-center gap-1 ${ok ? 'text-bull' : 'text-gray-600'}`}>
                {ok ? '✓' : '○'} {label}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

/* ─────────────────────────────────────────────────────────────
   MAIN TAB — RISK MATRIX
───────────────────────────────────────────────────────────── */
export function Tab7RiskMatrix() {
  const [stocksCurrent,  setStocksCurrent]  = useState(0);
  const [summary, setSummary] = useState<TrackingSummary | null>(null);

  const loadSummary = useCallback(async () => {
    try { const { data } = await trackingApi.getSummary(); setSummary(data); } catch {}
  }, []);

  useEffect(() => { loadSummary(); }, [loadSummary]);

  const fdVal   = summary?.totalFdCurrentValue ?? summary?.totalFdPrincipal ?? 0;
  const rdVal   = summary?.totalRdCurrentValue ?? 0;
  const loanVal = summary?.totalLoanOutstanding ?? 0;
  const otherVal = summary?.totalOtherAssets ?? 0;
  const epfVal  = summary?.totalEpf ?? 0;
  const totalAssets = stocksCurrent + fdVal + rdVal + otherVal + epfVal;
  const netWorth    = totalAssets - loanVal;

  return (
    <div className="space-y-5">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#00d68f] to-[#00b8d4] flex items-center justify-center text-white shadow-lift shrink-0">
          <Shield size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-0.5">Net Worth &amp; Risk</h2>
          <p className="text-gray-500 text-xs">Portfolio risk scoring, asset allocation and diversification analysis</p>
        </div>
      </div>

      {/* Chart-bearing section (Risk Score gauge + Asset Allocation pie) renders first,
          ahead of plain stat tiles and tables below. */}
      <RiskScoreCard summary={summary} stocksCurrent={stocksCurrent} />

      {/* Net worth summary strip */}
      {totalAssets > 0 && (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
          <StatTile label="Total Assets" value={fmtINR(totalAssets)} Icon={Coins} tone="brand" />
          <StatTile label="Equity Value" value={fmtINR(stocksCurrent)} Icon={TrendingUp} tone="brand" />
          <StatTile label="Fixed Income" value={fmtINR(fdVal + rdVal)} Icon={Landmark} tone="bull" />
          <StatTile label="Net Worth" value={fmtINR(netWorth)} Icon={Shield} tone={netWorth >= 0 ? 'bull' : 'bear'} />
        </div>
      )}

      <PortfolioSection onValues={(_inv, cur) => { setStocksCurrent(cur); }} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div>
          <h3 className="text-white font-semibold text-sm mb-3">Liabilities</h3>
          <LoanSection onRefresh={loadSummary} />
        </div>
        <div>
          <h3 className="text-white font-semibold text-sm mb-3">Wealth Projections</h3>
          <WealthCalculator />
        </div>
      </div>
    </div>
  );
}
