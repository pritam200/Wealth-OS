import { useState, useEffect, useCallback } from 'react';
import { TrendingUp, PieChart, ArrowRight, Wallet, Landmark, ShieldCheck, Target, Receipt, CreditCard, Eye, EyeOff, AlertTriangle } from 'lucide-react';
import { portfolioApi } from '../api/portfolio';
import type { IntegrityReport } from '../api/portfolio';
import { trackingApi } from '../api/tracking';
import type { TrackingSummary } from '../api/tracking';
import type { PortfolioSummary } from '../types';
import { NetWorthBar } from './tabs/Tab7RiskMatrix';
import { usePrivacyStore } from '../store/privacyStore';

const ISSUE_LABELS: Record<string, string> = {
  UNVERIFIABLE_NAME: 'Unverified name',
  DUPLICATE_FOLIO: 'Duplicate folio',
  DUPLICATE_DISPLAY_NAME: 'Duplicate fund',
  DUPLICATE_SYMBOL: 'Duplicate position',
};

/* Surfaces holdings that fail the automated data-integrity checks (mis-parsed statement
   text mistaken for a fund name, the same fund fragmented across symbols, the same stock
   counted twice across portfolios, etc.). DUPLICATE_SYMBOL is a literal, exact-match
   duplicate — safe to auto-merge with one click. Every other issue type still needs a
   human's own judgment (is this really a duplicate, or just a similarly-named fund?), so
   those stay manual via the holdings table's ✕ button. */
function IntegrityBanner({ report, onMerged }: { report: IntegrityReport; onMerged: () => void }) {
  const [merging, setMerging] = useState(false);
  const [mergeResult, setMergeResult] = useState<string | null>(null);
  const fmt = (n: number) =>
    new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);
  if (!report.issueCount) return null;

  const duplicateSymbolCount = report.issues.filter(i => i.type === 'DUPLICATE_SYMBOL').length;

  const mergeDuplicates = async () => {
    setMerging(true); setMergeResult(null);
    try {
      const { data } = await portfolioApi.mergeDuplicateSymbols();
      setMergeResult(`Merged ${data.holdingsMerged} duplicate holding${data.holdingsMerged !== 1 ? 's' : ''} across ${data.groupsMerged} position${data.groupsMerged !== 1 ? 's' : ''}.`);
      onMerged();
    } catch {
      setMergeResult('Merge failed — please try again.');
    } finally { setMerging(false); }
  };

  return (
    <div className="card border border-bear/30 bg-bear/5">
      <div className="flex items-start gap-3">
        <AlertTriangle size={18} className="text-bear shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <div className="flex items-center justify-between gap-3 flex-wrap mb-1">
            <p className="text-sm font-semibold text-bear">
              {report.issueCount} data integrity issue{report.issueCount !== 1 ? 's' : ''} found in your holdings
            </p>
            {duplicateSymbolCount > 0 && (
              <button onClick={mergeDuplicates} disabled={merging}
                className="btn-primary text-2xs py-1 px-2.5 shrink-0">
                {merging ? 'Merging…' : `Merge ${duplicateSymbolCount} Duplicate${duplicateSymbolCount !== 1 ? 's' : ''}`}
              </button>
            )}
          </div>
          {mergeResult && <p className="text-2xs text-bull mb-1.5">{mergeResult}</p>}
          <p className="text-2xs text-gray-500 mb-2">
            "Duplicate position" issues are the exact same stock/fund counted twice across portfolios — safe to auto-merge above (combines quantity and average cost, keeps full transaction history). Every other issue below needs your own review — remove it from the Stocks / Mutual Funds holdings table using the ✕ button.
          </p>
          <div className="space-y-1">
            {report.issues.map(issue => (
              <div key={`${issue.holdingId}-${issue.type}`} className="flex items-center justify-between text-2xs bg-surface-hover/60 rounded px-2 py-1.5">
                <span className="text-gray-300 truncate">
                  <span className="text-bear font-medium">{ISSUE_LABELS[issue.type] ?? issue.type}</span>
                  {' — '}{issue.name || issue.symbol}
                </span>
                <span className="font-mono text-gray-400 shrink-0 ml-2">{fmt(issue.currentValue)}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0, notation: n >= 1_00_00_000 ? 'compact' : 'standard' }).format(n || 0);

interface LinkCard { id: number; label: string; desc: string; Icon: typeof TrendingUp; tone: string }

const LINK_CARDS: LinkCard[] = [
  { id: 13, label: 'Stocks',         desc: 'Holdings, BUY/SELL/HOLD signals & technicals', Icon: TrendingUp,  tone: 'from-[#6d5efc] to-[#9b5cf9]' },
  { id: 6,  label: 'Mutual Funds',   desc: 'SIP guidance, redemptions & benchmarks',       Icon: PieChart,    tone: 'from-[#e84fd9] to-[#ff8a5b]' },
  { id: 7,  label: 'Wealth Tools',   desc: 'FDs, loans, other assets & calculators',       Icon: Landmark,    tone: 'from-[#00d68f] to-[#00b8d4]' },
  { id: 12, label: 'Planning',       desc: 'Goals, reminders & tax view',                  Icon: Target,      tone: 'from-[#ffb454] to-[#ff8a5b]' },
  { id: 10, label: 'Income & Expenses', desc: 'Cash flow and monthly spend',               Icon: Receipt,     tone: 'from-[#00c2ff] to-[#6d5efc]' },
  { id: 11, label: 'Cards & Rewards',   desc: 'Credit cards and benefit tracking',         Icon: CreditCard,  tone: 'from-[#f5c451] to-[#ffb454]' },
];

// Landing page — net worth + allocation + quick links only, no recommendations of its own.
// Tab switching in this app is local state (see App.tsx AppShell), not routed — so navigation
// out of this page goes through the same onNavigate(tabId) callback the sidebar uses.
export function DashboardPage({ onNavigate }: { onNavigate: (tabId: number) => void }) {
  const [summary, setSummary] = useState<TrackingSummary | null>(null);
  const [stocksCurrent, setStocksCurrent] = useState(0);
  const [mfCurrent, setMfCurrent] = useState(0);
  const [holdingCount, setHoldingCount] = useState(0);
  const [integrityReport, setIntegrityReport] = useState<IntegrityReport | null>(null);
  const [loading, setLoading] = useState(true);
  // Shared with the Topbar's eye toggle — one privacy switch controls every screen,
  // not a page-local one that resets when you navigate away.
  const masked = usePrivacyStore(s => s.masked);
  const toggleMasked = usePrivacyStore(s => s.toggle);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [{ data: trackingSummary }, { data: portfolios }] = await Promise.all([
        trackingApi.getSummary(),
        portfolioApi.list(),
      ]);
      setSummary(trackingSummary);
      portfolioApi.integrityCheck().then(r => setIntegrityReport(r.data)).catch(() => {});
      if (portfolios.length) {
        const summaries = (await Promise.all(
          portfolios.map(p => portfolioApi.getSummary(p.id).then(r => r.data).catch(() => null))
        )).filter(Boolean) as PortfolioSummary[];
        const allHoldings = summaries.flatMap(s => s.holdings ?? []);
        const totalCurrent = summaries.reduce((s, p) => s + (p.currentValue ?? 0), 0);
        setHoldingCount(allHoldings.length);
        setStocksCurrent(totalCurrent);
        setMfCurrent(allHoldings.filter(h => (h.symbol ?? '').endsWith('.MF')).reduce((s, h) => s + (h.currentValue ?? 0), 0));
      }
    } catch {} finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const fdVal = summary?.totalFdCurrentValue ?? summary?.totalFdPrincipal ?? 0;
  const rdVal = summary?.totalRdCurrentValue ?? 0;
  const loans = summary?.totalLoanOutstanding ?? 0;
  const totalAssets = stocksCurrent + fdVal + rdVal + (summary?.totalOtherAssets ?? 0) + (summary?.totalEpf ?? 0);
  const netWorth = totalAssets - loans;

  if (loading) return (
    <div className="space-y-4">
      <div className="h-32 animate-pulse bg-surface-hover rounded-2xl" />
      <div className="grid grid-cols-3 gap-4">{[0, 1, 2].map(i => <div key={i} className="h-24 animate-pulse bg-surface-hover rounded-2xl" />)}</div>
    </div>
  );

  return (
    <div className="space-y-6">
      {integrityReport && <IntegrityBanner report={integrityReport} onMerged={load} />}
      {/* ── Hero ── */}
      <div className="card-elevated flex flex-wrap items-center justify-between gap-6">
        <div>
          <div className="flex items-center gap-2 text-2xs text-gray-500 uppercase tracking-widest font-semibold mb-2">
            <ShieldCheck size={13} className="text-bull" /> Your net worth today
          </div>
          <div className="flex items-center gap-3">
            <div className="stat-value-hero">{masked ? '••••••' : fmtINR(netWorth)}</div>
            <button onClick={toggleMasked} title={masked ? 'Show net worth' : 'Hide net worth'}
              className="btn-icon shrink-0">
              {masked ? <Eye size={16} /> : <EyeOff size={16} />}
            </button>
          </div>
          <p className="text-xs text-gray-500 mt-1.5">
            {holdingCount > 0
              ? `Across ${holdingCount} holding${holdingCount !== 1 ? 's' : ''} and your tracked assets & liabilities.`
              : 'Add holdings under My Wealth to see your full picture here.'}
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <div className="icon-badge-brand w-11 h-11 rounded-2xl"><Wallet size={20} /></div>
        </div>
      </div>

      <NetWorthBar stocksCurrent={stocksCurrent} mfCurrent={mfCurrent} summary={summary} />

      {/* ── Quick links — every module one tap away, no recommendation content duplicated here ── */}
      <div>
        <h3 className="text-white font-semibold text-sm mb-3">Jump to</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {LINK_CARDS.map(({ id, label, desc, Icon, tone }) => (
            <button key={id} onClick={() => onNavigate(id)} className="card text-left group">
              <div className="flex items-center justify-between mb-3">
                <div className={`w-9 h-9 rounded-xl bg-gradient-to-br ${tone} flex items-center justify-center text-white shadow-lift`}>
                  <Icon size={16} />
                </div>
                <ArrowRight size={14} className="text-gray-600 group-hover:text-brand-light group-hover:translate-x-0.5 transition-all" />
              </div>
              <div className="font-semibold text-white text-sm mb-0.5">{label}</div>
              <p className="text-xs text-gray-500">{desc}</p>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
