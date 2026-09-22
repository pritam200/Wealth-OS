import { useState, useEffect, useCallback } from 'react';
import { TrendingUp, PieChart, ArrowRight, Wallet, Landmark, ShieldCheck, Target, Receipt, CreditCard, Eye, EyeOff, AlertTriangle } from 'lucide-react';
import { portfolioApi } from '../api/portfolio';
import type { IntegrityReport } from '../api/portfolio';
import { trackingApi } from '../api/tracking';
import type { TrackingSummary } from '../api/tracking';
import { wealthApi } from '../api/wealth';
import type { PortfolioContext } from '../api/wealth';
import { reconciliationApi } from '../api/reconciliation';
import type { ReconciliationIssue } from '../api/reconciliation';
import type { PortfolioSummary } from '../types';
import { NetWorthBar } from './tabs/Tab7RiskMatrix';
import { usePrivacyStore } from '../store/privacyStore';
import { LoadFailure } from '../components/shared/LoadFailure';
import { formatINR } from '../utils/currency';

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

/* FD/RD/net-worth issues from the generalized reconciliation layer — same "flag it, never
   silently show a wrong number" pattern as IntegrityBanner above, for the domains that
   previously had no equivalent check at all. Manual review only; no auto-fix action here. */
function ReconciliationBanner({ issues }: { issues: ReconciliationIssue[] }) {
  if (!issues.length) return null;
  const highSeverity = issues.filter(i => i.severity === 'HIGH').length;
  return (
    <div className="card border border-neutral/30 bg-neutral/5">
      <div className="flex items-start gap-3">
        <AlertTriangle size={18} className="text-neutral shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-semibold text-neutral mb-1.5">
            {issues.length} reconciliation issue{issues.length !== 1 ? 's' : ''} found
            {highSeverity > 0 ? ` (${highSeverity} high priority)` : ''}
          </p>
          <div className="space-y-1">
            {issues.map((issue, i) => (
              <div key={`${issue.domain}-${issue.type}-${issue.referenceId}-${i}`}
                className="text-2xs bg-surface-hover/60 rounded px-2 py-1.5 text-gray-300">
                <span className={issue.severity === 'HIGH' ? 'text-bear font-medium' : 'text-neutral font-medium'}>
                  {issue.domain}
                </span>
                {' — '}{issue.description}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// The hero used to switch to compact notation above ₹1 crore while <NetWorthBar/>, rendered
// directly below it, printed the same figure in full — the one net worth appearing twice on one
// screen as two different-looking numbers. Both now use the shared whole-rupee formatter.
const fmtINR = formatINR;

interface LinkCard { id: number; label: string; desc: string; Icon: typeof TrendingUp; tone: string }

const LINK_CARDS: LinkCard[] = [
  { id: 13, label: 'Stocks',         desc: 'Holdings, BUY/SELL/HOLD signals & technicals', Icon: TrendingUp,  tone: 'bg-brand/10 border border-brand/25 text-brand-light' },
  { id: 6,  label: 'Mutual Funds',   desc: 'SIP guidance, redemptions & benchmarks',       Icon: PieChart,    tone: 'bg-bull/10 border border-bull/25 text-bull' },
  { id: 7,  label: 'Wealth Tools',   desc: 'FDs, loans, other assets & calculators',       Icon: Landmark,    tone: 'bg-bull/10 border border-bull/25 text-bull' },
  { id: 12, label: 'Planning',       desc: 'Goals, reminders & tax view',                  Icon: Target,      tone: 'bg-neutral/10 border border-neutral/25 text-neutral' },
  { id: 10, label: 'Income & Expenses', desc: 'Cash flow and monthly spend',               Icon: Receipt,     tone: 'bg-brand/10 border border-brand/25 text-brand-light' },
  { id: 11, label: 'Cards & Rewards',   desc: 'Credit cards and benefit tracking',         Icon: CreditCard,  tone: 'bg-neutral/10 border border-neutral/25 text-neutral' },
];

// Landing page — net worth + allocation + quick links only, no recommendations of its own.
// Tab switching in this app is local state (see App.tsx AppShell), not routed — so navigation
// out of this page goes through the same onNavigate(tabId) callback the sidebar uses.
export function DashboardPage({ onNavigate }: { onNavigate: (tabId: number) => void }) {
  const [summary, setSummary] = useState<TrackingSummary | null>(null);
  const [wealth, setWealth] = useState<PortfolioContext | null>(null);
  const [holdingCount, setHoldingCount] = useState(0);
  const [integrityReport, setIntegrityReport] = useState<IntegrityReport | null>(null);
  const [otherIssues, setOtherIssues] = useState<ReconciliationIssue[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  // Shared with the Topbar's eye toggle — one privacy switch controls every screen,
  // not a page-local one that resets when you navigate away.
  const masked = usePrivacyStore(s => s.masked);
  const toggleMasked = usePrivacyStore(s => s.toggle);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [{ data: trackingSummary }, { data: portfolios }, { data: wealthSummary }] = await Promise.all([
        trackingApi.getSummary(),
        portfolioApi.list(),
        wealthApi.getSummary(),
      ]);
      setSummary(trackingSummary);
      setWealth(wealthSummary);
      setFailed(false);
      portfolioApi.integrityCheck().then(r => setIntegrityReport(r.data)).catch(() => {});
      // Portfolio-domain issues already surface via the banner above (with its own merge
      // action) — only show FD/RD/net-worth issues here, so nothing is flagged twice.
      reconciliationApi.getReport()
        .then(r => setOtherIssues(r.data.issues.filter(i => i.domain !== 'PORTFOLIO')))
        .catch(() => {});
      if (portfolios.length) {
        const summaries = (await Promise.all(
          portfolios.map(p => portfolioApi.getSummary(p.id).then(r => r.data).catch(() => null))
        )).filter(Boolean) as PortfolioSummary[];
        setHoldingCount(summaries.flatMap(s => s.holdings ?? []).length);
      }
    } catch {
      // Without this the hero rendered ₹0 as a statement of fact, under copy inviting the user to
      // "add holdings" — when the real situation is that we could not reach the server.
      setFailed(true);
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const netWorth = wealth?.netWorth ?? 0;

  if (loading) return (
    <div className="space-y-4">
      <div className="h-32 animate-pulse bg-surface-hover rounded-2xl" />
      <div className="grid grid-cols-3 gap-4">{[0, 1, 2].map(i => <div key={i} className="h-24 animate-pulse bg-surface-hover rounded-2xl" />)}</div>
    </div>
  );

  return (
    <div className="space-y-6">
      {integrityReport && <IntegrityBanner report={integrityReport} onMerged={load} />}
      <ReconciliationBanner issues={otherIssues} />
      {/* ── Hero ── */}
      <div className="card-elevated flex flex-wrap items-center justify-between gap-6">
        <div>
          <div className="flex items-center gap-2 text-2xs text-gray-500 uppercase tracking-widest font-semibold mb-2">
            <ShieldCheck size={13} className="text-bull" /> Your net worth today
          </div>
          <div className="flex items-center gap-3">
            <div className="stat-value-hero">{failed ? '—' : masked ? '••••••' : fmtINR(netWorth)}</div>
            <button onClick={toggleMasked} title={masked ? 'Show net worth' : 'Hide net worth'}
              className="btn-icon shrink-0">
              {masked ? <Eye size={16} /> : <EyeOff size={16} />}
            </button>
          </div>
          <p className="text-xs text-gray-500 mt-1.5">
            {failed
              ? 'Your figures could not be loaded — this is a connection problem, not an empty portfolio.'
              : holdingCount > 0
              ? `Across ${holdingCount} holding${holdingCount !== 1 ? 's' : ''} and your tracked assets & liabilities.`
              : 'Add holdings under My Wealth to see your full picture here.'}
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <div className="icon-badge-brand w-11 h-11 rounded-2xl"><Wallet size={20} /></div>
        </div>
      </div>

      {failed
        ? <LoadFailure what="your net worth and holdings" onRetry={load} />
        : <NetWorthBar wealth={wealth} emi={summary?.totalMonthlyEmi ?? 0} />}

      {/* ── Quick links — every module one tap away, no recommendation content duplicated here ── */}
      <div>
        <h3 className="text-ink font-semibold text-sm mb-3">Jump to</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {LINK_CARDS.map(({ id, label, desc, Icon, tone }) => (
            <button key={id} onClick={() => onNavigate(id)} className="card text-left group">
              <div className="flex items-center justify-between mb-3">
                <div className={`w-9 h-9 rounded-xl ${tone} flex items-center justify-center shrink-0`}>
                  <Icon size={16} />
                </div>
                <ArrowRight size={14} className="text-gray-600 group-hover:text-brand-light group-hover:translate-x-0.5 transition-all" />
              </div>
              <div className="font-semibold text-ink text-sm mb-0.5">{label}</div>
              <p className="text-xs text-gray-500">{desc}</p>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
