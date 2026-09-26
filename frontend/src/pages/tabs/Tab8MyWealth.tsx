import { useState, useEffect, useCallback } from 'react';
import { UploadCloud, Wallet } from 'lucide-react';
import { BulkImportModal } from '../../components/BulkImportModal';
import { DividendSection } from '../../components/wealth/DividendSection';
import { NetWorthTrend } from '../../components/wealth/NetWorthTrend';
import { EpfSection } from '../../components/wealth/EpfSection';
import { FDSection } from '../../components/wealth/FDSection';
import { RDSection } from '../../components/wealth/RDSection';
import { LoanSection } from '../../components/wealth/LoanSection';
import { InsuranceSection } from '../../components/wealth/InsuranceSection';
import { OtherAssetsSection } from '../../components/wealth/OtherAssetsSection';
import { WealthCalculator } from '../../components/wealth/WealthCalculator';
import { CashAccountsSection } from '../../components/wealth/CashAccountsSection';
import { ChunkRebalancingTracker } from '../../components/wealth/ChunkRebalancingTracker';
import { ConcentrationRiskCard } from '../../components/wealth/ConcentrationRiskCard';
import { trackingApi } from '../../api/tracking';
import type { TrackingSummary } from '../../api/tracking';
import { wealthApi } from '../../api/wealth';
import type { PortfolioContext } from '../../api/wealth';
import { PortfolioSection, NetWorthBar } from './Tab7RiskMatrix';
import { LoadFailure } from '../../components/shared/LoadFailure';

/* ─────────────────────────────────────────────────────────────
   MAIN TAB — MY WEALTH
───────────────────────────────────────────────────────────── */
export function Tab8MyWealth() {
  const [summary, setSummary] = useState<TrackingSummary | null>(null);
  const [wealth, setWealth] = useState<PortfolioContext | null>(null);
  const [showImport, setShowImport] = useState(false);

  // Both loads used to swallow their error, leaving wealth null — which renders a net worth of
  // ₹0 and silently hides the trend chart, indistinguishable from a genuinely empty account.
  const [failed, setFailed] = useState(false);
  const loadSummary = useCallback(async () => {
    let anyFailed = false;
    try { const { data } = await trackingApi.getSummary(); setSummary(data); } catch { anyFailed = true; }
    try { const { data } = await wealthApi.getSummary(); setWealth(data); } catch { anyFailed = true; }
    setFailed(anyFailed);
  }, []);

  useEffect(() => { loadSummary(); }, [loadSummary]);

  const totalAssets = wealth?.totalAssets ?? 0;

  return (
    <div className="space-y-5">
      {showImport && (
        <BulkImportModal
          onClose={() => setShowImport(false)}
          onDone={() => { loadSummary(); setShowImport(false); }}
        />
      )}
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl bg-bull/10 border border-bull/25 flex items-center justify-center text-bull shrink-0">
            <Wallet size={20} />
          </div>
          <div>
            <h2 className="text-xl font-bold text-ink mb-0.5">My Wealth</h2>
            <p className="text-gray-500 text-xs">What you own and owe, by asset class — cash, equity, funds, deposits and liabilities</p>
          </div>
        </div>
        <button
          onClick={() => setShowImport(true)}
          className="btn-secondary flex items-center gap-1.5 text-sm"
        >
          <UploadCloud size={14} /> Bulk Import
        </button>
      </div>

      {failed
        ? <LoadFailure what="your wealth summary" onRetry={loadSummary} />
        : <NetWorthBar wealth={wealth} emi={summary?.totalMonthlyEmi ?? 0} />}

      {!failed && totalAssets > 0 && <NetWorthTrend />}

      {/* Asset breakdown — what you own, by class. Kept separate from the transaction
          ledger below it, which is the record of how it got that way. */}
      <div>
        <h3 className="text-ink font-semibold text-sm mb-3">Liquid Assets</h3>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <CashAccountsSection />
          <ChunkRebalancingTracker />
        </div>
      </div>

      <div>
        <h3 className="text-ink font-semibold text-sm mb-3">Equity &amp; Funds</h3>
        <PortfolioSection onValues={() => {}} />
      </div>

      <ConcentrationRiskCard />

      <div>
        <h3 className="text-ink font-semibold text-sm mb-3">Fixed Income &amp; Retirement</h3>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <FDSection onRefresh={loadSummary} />
          <RDSection onRefresh={loadSummary} />
          <EpfSection onRefresh={loadSummary} />
        </div>
      </div>

      <div>
        <h3 className="text-ink font-semibold text-sm mb-3">Other Assets &amp; Liabilities</h3>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <OtherAssetsSection onRefresh={loadSummary} />
          <LoanSection onRefresh={loadSummary} />
          <InsuranceSection onRefresh={loadSummary} />
        </div>
      </div>

      <div>
        <h3 className="text-ink font-semibold text-sm mb-3">Passive Income</h3>
        <DividendSection />
      </div>

      <div>
        <h3 className="text-ink font-semibold text-sm mb-3">Wealth Projections</h3>
        <WealthCalculator />
      </div>
    </div>
  );
}
