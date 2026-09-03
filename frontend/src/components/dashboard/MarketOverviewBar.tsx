import { useEffect, useState } from 'react';
import { marketApi } from '../../api/market';
import { IndexTicker } from '../shared/IndexTicker';
import type { MarketOverview } from '../../types';

export function MarketOverviewBar() {
  const [overview, setOverview] = useState<MarketOverview | null>(null);

  useEffect(() => {
    marketApi.getOverview()
      .then(r => setOverview(r.data))
      .catch(() => {});

    const interval = setInterval(() => {
      marketApi.getOverview().then(r => setOverview(r.data)).catch(() => {});
    }, 60_000);

    return () => clearInterval(interval);
  }, []);

  if (!overview) {
    return (
      <div className="h-14 bg-surface-card border-b border-surface-border flex items-center px-6">
        <div className="text-gray-500 text-sm">Loading market data...</div>
      </div>
    );
  }

  return (
    <div className="bg-surface-card border-b border-surface-border flex items-center px-2 overflow-x-auto">
      <IndexTicker index={overview.nifty50} />
      <IndexTicker index={overview.sensex} />
      <IndexTicker index={overview.bankNifty} />
      {overview.niftyMidcap && <IndexTicker index={overview.niftyMidcap} />}
    </div>
  );
}
