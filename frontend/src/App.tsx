import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import { useState } from 'react';
import { LayoutDashboard, Activity, TrendingUp, PieChart, Wallet, Receipt, Target, CreditCard, Brain, Coins, RefreshCw } from 'lucide-react';
import { LoginPage } from './pages/auth/LoginPage';
import { RegisterPage } from './pages/auth/RegisterPage';
import { StockPage } from './pages/StockPage';
import { DashboardPage } from './pages/DashboardPage';
import { DataSyncPage } from './pages/DataSyncPage';
import { Sidebar } from './components/layout/Sidebar';
import { Topbar } from './components/layout/Topbar';
import type { NavSection } from './components/layout/Sidebar';
import { useAuthStore } from './store/authStore';
import { authApi } from './api/auth';
import { useEffect, useRef } from 'react';
import { marketApi } from './api/market';
import type { MarketOverview } from './types';

import { Tab1MarketTrends }    from './pages/tabs/Tab1MarketTrends';
import { Tab2StockAnalysis }   from './pages/tabs/Tab2StockAnalysis';
import { Tab3MarketForecast }  from './pages/tabs/Tab3MarketForecast';
import { Tab4StockProjections } from './pages/tabs/Tab4StockProjections';
import { Tab5NewsAndCatalysts } from './pages/tabs/Tab5NewsAndCatalysts';
import { Tab6MutualFunds }     from './pages/tabs/Tab6MutualFunds';
import { Tab7RiskMatrix, StocksHoldingsSignals } from './pages/tabs/Tab7RiskMatrix';
import { Tab8MyWealth }        from './pages/tabs/Tab8MyWealth';
import { Tab9AiAdvisor }       from './pages/tabs/Tab9AiAdvisor';
import { Tab10Expenses }       from './pages/tabs/Tab10Expenses';
import { Tab11Cards }          from './pages/tabs/Tab11Cards';
import { Tab12Planning }       from './pages/tabs/Tab12Planning';
import { Tab14Dividends }      from './pages/tabs/Tab14Dividends';
import { Tab16TodaysActions }  from './pages/tabs/Tab16TodaysActions';

// Single-responsibility IA: My Wealth is holdings/net-worth only (no recommendations —
// see the badge-free PortfolioSection default). Stocks and Mutual Funds are the only two
// places BUY/SELL/HOLD/BOOK_PROFIT signals render (Holdings & Signals sub-tabs).
const NAV_SECTIONS: readonly NavSection[] = [
  {
    id: 'dashboard', label: 'Dashboard', Icon: LayoutDashboard,
    tabs: [{ id: 0, label: 'Dashboard' }],
  },
  {
    id: 'wealth', label: 'My Wealth', Icon: Wallet,
    tabs: [
      { id: 8, label: 'Portfolio & Assets' },
      { id: 7, label: 'Net Worth & Risk' },
    ],
  },
  {
    id: 'stocks', label: 'Stocks', Icon: TrendingUp,
    tabs: [
      { id: 13, label: 'Holdings & Signals' },
      { id: 2, label: 'Stock Insights' },
      { id: 4, label: 'Price Projections' },
    ],
  },
  {
    id: 'mutualfunds', label: 'Mutual Funds', Icon: PieChart,
    tabs: [
      { id: 6, label: 'Holdings & Signals' },
    ],
  },
  {
    id: 'markets', label: 'Markets', Icon: Activity,
    tabs: [
      { id: 1, label: 'Market Trends' },
      { id: 3, label: 'Market Forecast' },
      { id: 5, label: 'News & Catalysts' },
    ],
  },
  {
    id: 'income', label: 'Income & Expenses', Icon: Receipt,
    tabs: [{ id: 10, label: 'Income & Expenses' }],
  },
  {
    id: 'dividends', label: 'Dividends', Icon: Coins,
    tabs: [{ id: 14, label: 'Dividends' }],
  },
  {
    id: 'planning', label: 'Financial Planning', Icon: Target,
    tabs: [{ id: 12, label: 'Financial Planning' }],
  },
  {
    id: 'cards', label: 'Cards & Rewards', Icon: CreditCard,
    tabs: [{ id: 11, label: 'Cards & Rewards' }],
  },
  {
    id: 'advisor', label: 'AI Advisor', Icon: Brain,
    tabs: [
      { id: 9, label: 'Daily Actions' },
      { id: 16, label: "Today's Investment Actions" },
    ],
  },
  {
    id: 'datasync', label: 'Data Sync', Icon: RefreshCw,
    tabs: [{ id: 15, label: 'Email & Statement Sync' }],
  },
] as const;

function sectionOf(tabId: number) {
  return NAV_SECTIONS.find(s => s.tabs.some(t => t.id === tabId)) ?? NAV_SECTIONS[0];
}

// NSE/BSE cash market hours: Mon–Fri 09:15–15:30 IST
function useMarketStatus() {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const check = () => {
      const ist = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Kolkata' }));
      const day = ist.getDay();
      const mins = ist.getHours() * 60 + ist.getMinutes();
      setOpen(day >= 1 && day <= 5 && mins >= 9 * 60 + 15 && mins <= 15 * 60 + 30);
    };
    check();
    const t = setInterval(check, 30_000);
    return () => clearInterval(t);
  }, []);
  return open;
}

function IndexPill({ label, value, change }: { label: string; value: string; change: string }) {
  const pos = change.startsWith('+');
  return (
    <div className="flex items-center gap-2.5 px-4 border-r border-surface-border last:border-0">
      <div>
        <div className="text-2xs text-gray-600 uppercase tracking-widest mb-0.5">{label}</div>
        <div className="text-sm font-mono font-semibold text-white leading-none">{value}</div>
      </div>
      <span className={`text-xs font-mono font-bold ${pos ? 'text-bull' : 'text-bear'}`}>{change}</span>
    </div>
  );
}

function LiveTicker() {
  const [overview, setOverview] = useState<MarketOverview | null>(null);
  const [pulse, setPulse] = useState(false);
  const timerRef = useRef<any>(null);

  useEffect(() => {
    const load = () => {
      marketApi.getOverview().then(r => {
        setOverview(r.data);
        setPulse(true);
        setTimeout(() => setPulse(false), 400);
      }).catch(() => {});
    };
    load();
    timerRef.current = setInterval(load, 60_000);
    return () => clearInterval(timerRef.current);
  }, []);

  if (!overview) return (
    <div className="flex items-center gap-2 px-4 text-2xs text-gray-600 uppercase tracking-widest animate-pulse">
      Fetching market data…
    </div>
  );

  const fmt = (n: number) => n?.toLocaleString('en-IN', { maximumFractionDigits: 2 }) ?? '—';
  const fmtChg = (n: number) => (n === null || n === undefined ? '—' : `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`);

  return (
    <div className={`flex items-stretch transition-opacity ${pulse ? 'opacity-80' : 'opacity-100'}`}>
      {overview.nifty50    && <IndexPill label="NIFTY 50"   value={fmt(overview.nifty50.value)}    change={fmtChg(overview.nifty50.changePercent)} />}
      {overview.sensex     && <IndexPill label="SENSEX"     value={fmt(overview.sensex.value)}     change={fmtChg(overview.sensex.changePercent)} />}
      {overview.bankNifty  && <IndexPill label="BANK NIFTY" value={fmt(overview.bankNifty.value)}  change={fmtChg(overview.bankNifty.changePercent)} />}
      {overview.niftyMidcap && <IndexPill label="MIDCAP 50" value={fmt(overview.niftyMidcap.value)} change={fmtChg(overview.niftyMidcap.changePercent)} />}
    </div>
  );
}

function TabContent({ tab, onNavigate }: { tab: number; onNavigate: (tabId: number) => void }) {
  switch (tab) {
    case 0: return <DashboardPage onNavigate={onNavigate} />;
    case 1: return <Tab1MarketTrends />;
    case 2: return <Tab2StockAnalysis />;
    case 3: return <Tab3MarketForecast />;
    case 4: return <Tab4StockProjections />;
    case 5: return <Tab5NewsAndCatalysts />;
    case 6: return <Tab6MutualFunds />;
    case 7: return <Tab7RiskMatrix />;
    case 8: return <Tab8MyWealth />;
    case 9: return <Tab9AiAdvisor />;
    case 10: return <Tab10Expenses />;
    case 11: return <Tab11Cards />;
    case 12: return <Tab12Planning />;
    case 13: return <StocksHoldingsSignals />;
    case 14: return <Tab14Dividends />;
    case 15: return <DataSyncPage />;
    case 16: return <Tab16TodaysActions />;
    default: return <Tab1MarketTrends />;
  }
}

function AppShell() {
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState(1); // Markets (Trends) is the home page
  const activeSection = sectionOf(activeTab);
  const marketOpen = useMarketStatus();

  const handleLogout = async () => {
    try { await authApi.logout(); } catch {}
    logout();
    navigate('/login');
  };

  return (
    <div className="flex h-screen overflow-hidden bg-surface">
      <Sidebar sections={NAV_SECTIONS} activeTab={activeTab} onSelect={setActiveTab} />

      <div className="flex flex-col flex-1 overflow-hidden">
        <Topbar
          sectionLabel={activeSection.label}
          userName={user?.name}
          marketOpen={marketOpen}
          onLogout={handleLogout}
          liveTicker={<LiveTicker />}
        />

        <main className="flex-1 overflow-y-auto">
          <div className="p-5 max-w-screen-2xl mx-auto">
            <TabContent tab={activeTab} onNavigate={setActiveTab} />
          </div>
        </main>
      </div>
    </div>
  );
}

function PrivateRoute({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated);
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login"    element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/stock/:symbol" element={
          <PrivateRoute>
            <div className="min-h-screen bg-surface p-6">
              <button onClick={() => window.history.back()}
                className="btn-ghost mb-5 text-xs uppercase tracking-widest">
                ← Back
              </button>
              <StockPage />
            </div>
          </PrivateRoute>
        } />
        <Route path="/*" element={<PrivateRoute><AppShell /></PrivateRoute>} />
      </Routes>
    </BrowserRouter>
  );
}
