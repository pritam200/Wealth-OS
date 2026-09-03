import { RefreshCw } from 'lucide-react';
import { GmailConnect } from '../components/GmailConnect';

export function DataSyncPage() {
  return (
    <div className="space-y-5">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#00c2ff] to-[#6d5efc] flex items-center justify-center text-white shadow-lift shrink-0">
          <RefreshCw size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-0.5">Data Sync</h2>
          <p className="text-gray-500 text-xs">Connect your inbox to auto-import trades, FDs, SIPs and statements from bank/broker emails</p>
        </div>
      </div>

      <GmailConnect />
    </div>
  );
}
