import { LogOut, Eye, EyeOff } from 'lucide-react';
import { StockSearch } from '../market/StockSearch';
import { usePrivacyStore } from '../../store/privacyStore';

interface Props {
  sectionLabel: string;
  userName?: string;
  marketOpen: boolean;
  onLogout: () => void;
  liveTicker: React.ReactNode;
}

// Slim top bar — section title, a pill-shaped search (Stripe/Linear cmd-k style instead of
// a bare input), live index strip, and account controls. Navigation itself lives in Sidebar.
export function Topbar({ sectionLabel, userName, marketOpen, onLogout, liveTicker }: Props) {
  const now = new Date();
  const initials = (userName ?? 'U').trim().split(/\s+/).slice(0, 2).map(w => w[0]?.toUpperCase()).join('');
  const { masked, toggle } = usePrivacyStore();
  return (
    <header className="flex items-stretch h-14 bg-surface-card/80 backdrop-blur-md border-b border-surface-border shrink-0">
      <div className="flex items-center px-5 shrink-0">
        <h1 className="text-base font-bold text-white whitespace-nowrap tracking-tight">{sectionLabel}</h1>
      </div>

      <div className="flex items-center px-3">
        <StockSearch />
      </div>

      <div className="flex items-stretch flex-1 overflow-x-auto justify-end pr-2">
        {liveTicker}
      </div>

      <div className="flex items-center gap-3 px-4 border-l border-surface-border/70 shrink-0">
        <div className="flex items-center gap-1.5 px-2 py-1 rounded-full bg-surface-hover/60" title={marketOpen ? 'Market open (09:15–15:30 IST)' : 'Market closed'}>
          <span className={`w-1.5 h-1.5 rounded-full ${marketOpen ? 'bg-bull animate-pulse' : 'bg-gray-600'}`} />
          <span className={`text-2xs uppercase tracking-widest font-semibold ${marketOpen ? 'text-bull' : 'text-gray-600'}`}>{marketOpen ? 'LIVE' : 'CLOSED'}</span>
        </div>
        <div className="text-right hidden sm:block">
          <div className="text-xs font-medium text-gray-300">{userName}</div>
          <div className="text-2xs text-gray-600">{now.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })}</div>
        </div>
        <div className="w-8 h-8 rounded-full bg-brand-gradient text-white text-xs font-bold flex items-center justify-center shrink-0" title={userName}>
          {initials || 'U'}
        </div>
        <button onClick={toggle} title={masked ? 'Show amounts' : 'Hide amounts'}
          className={`p-1.5 rounded-lg transition-colors ${masked ? 'text-gray-500 hover:text-white hover:bg-surface-hover' : 'text-brand-light bg-brand/10'}`}>
          {masked ? <EyeOff size={15} /> : <Eye size={15} />}
        </button>
        <button onClick={onLogout} title="Sign out" className="text-gray-600 hover:text-bear transition-colors p-1.5 rounded-lg hover:bg-bear/10">
          <LogOut size={15} />
        </button>
      </div>
    </header>
  );
}
