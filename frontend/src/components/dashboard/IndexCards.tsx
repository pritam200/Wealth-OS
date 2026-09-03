import { useEffect, useState } from 'react';
import { marketApi } from '../../api/market';
import { IndexTicker } from '../shared/IndexTicker';
import type { MarketOverview } from '../../types';

export function IndexCards() {
  const [overview, setOverview] = useState<MarketOverview | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    marketApi.getOverview()
      .then(r => setOverview(r.data))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      {[...Array(4)].map((_, i) => (
        <div key={i} className="card animate-pulse h-24 bg-surface-hover" />
      ))}
    </div>
  );

  if (!overview) return null;

  return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      <IndexTicker index={overview.nifty50}    size="lg" />
      <IndexTicker index={overview.sensex}     size="lg" />
      <IndexTicker index={overview.bankNifty}  size="lg" />
      {overview.niftyMidcap && <IndexTicker index={overview.niftyMidcap} size="lg" />}
    </div>
  );
}
