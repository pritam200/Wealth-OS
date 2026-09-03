import { useState, useEffect, useCallback } from 'react';
import { UploadCloud, Wallet } from 'lucide-react';
import { BulkImportModal } from '../../components/BulkImportModal';
import { DividendSection } from '../../components/wealth/DividendSection';
import { NetWorthTrend } from '../../components/wealth/NetWorthTrend';
import { EpfSection } from '../../components/wealth/EpfSection';
import { FDSection } from '../../components/wealth/FDSection';
import { RDSection } from '../../components/wealth/RDSection';
import { LoanSection } from '../../components/wealth/LoanSection';
import { OtherAssetsSection } from '../../components/wealth/OtherAssetsSection';
import { WealthCalculator } from '../../components/wealth/WealthCalculator';
import { trackingApi } from '../../api/tracking';
import type { TrackingSummary } from '../../api/tracking';
import { PortfolioSection, NetWorthBar } from './Tab7RiskMatrix';

/* ─────────────────────────────────────────────────────────────
   MAIN TAB — MY WEALTH
───────────────────────────────────────────────────────────── */
export function Tab8MyWealth() {
  const [stocksCurrent,  setStocksCurrent]  = useState(0);
  const [mfCurrent,      setMfCurrent]      = useState(0);
  const [summary, setSummary] = useState<TrackingSummary | null>(null);
  const [portfolioLoaded, setPortfolioLoaded] = useState(false);
  const [showImport, setShowImport] = useState(false);

  const loadSummary = useCallback(async () => {
    try { const { data } = await trackingApi.getSummary(); setSummary(data); } catch {}
  }, []);

  useEffect(() => { loadSummary(); }, [loadSummary]);

  const fdVal    = summary?.totalFdCurrentValue ?? summary?.totalFdPrincipal ?? 0;
  const rdVal    = summary?.totalRdCurrentValue ?? 0;
  const otherVal = summary?.totalOtherAssets ?? 0;
  const epfVal   = summary?.totalEpf ?? 0;
  const loans    = summary?.totalLoanOutstanding ?? 0;
  const totalAssets = stocksCurrent + fdVal + rdVal + otherVal + epfVal;
  const netWorth = totalAssets - loans;

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
          <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#00d68f] to-[#00b8d4] flex items-center justify-center text-white shadow-lift shrink-0">
            <Wallet size={20} />
          </div>
          <div>
            <h2 className="text-xl font-bold text-white mb-0.5">My Wealth</h2>
            <p className="text-gray-500 text-xs">Complete financial picture — assets, income, expenses, FDs, loans and more</p>
          </div>
        </div>
        <button
          onClick={() => setShowImport(true)}
          className="btn-secondary flex items-center gap-1.5 text-sm"
        >
          <UploadCloud size={14} /> Bulk Import
        </button>
      </div>

      <NetWorthBar stocksCurrent={stocksCurrent} mfCurrent={mfCurrent} summary={summary} />

      {/* Only snapshot once BOTH async sources (tracking summary + portfolio holdings)
          have reported in — otherwise a partial total gets recorded as "today's"
          net worth and looks like a huge fake overnight loss/gain on the trend chart. */}
      {portfolioLoaded && summary && totalAssets > 0 && <NetWorthTrend record={{ totalAssets, netWorth }} />}

      <PortfolioSection onValues={(_inv, cur, mfCur) => { setStocksCurrent(cur); setMfCurrent(mfCur); setPortfolioLoaded(true); }} />

      <div>
        <h3 className="text-white font-semibold text-sm mb-3">Fixed Income &amp; Retirement</h3>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <FDSection onRefresh={loadSummary} />
          <RDSection onRefresh={loadSummary} />
          <EpfSection onRefresh={loadSummary} />
        </div>
      </div>

      <div>
        <h3 className="text-white font-semibold text-sm mb-3">Other Assets &amp; Liabilities</h3>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <OtherAssetsSection onRefresh={loadSummary} />
          <LoanSection onRefresh={loadSummary} />
        </div>
      </div>

      <div>
        <h3 className="text-white font-semibold text-sm mb-3">Passive Income</h3>
        <DividendSection />
      </div>

      <div>
        <h3 className="text-white font-semibold text-sm mb-3">Wealth Projections</h3>
        <WealthCalculator />
      </div>
    </div>
  );
}
