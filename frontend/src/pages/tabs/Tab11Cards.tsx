import { useEffect, useState } from 'react';
import { CreditCard, Wallet, Coins } from 'lucide-react';
import { CreditCardSection } from '../../components/wealth/CreditCardSection';
import { CardOptimizer } from '../../components/wealth/CardOptimizer';
import { CardBreakEven } from '../../components/wealth/CardBreakEven';
import { cardApi } from '../../api/card';
import type { CardResponse } from '../../api/card';
import { useMaskedText } from '../../components/shared/Amount';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

// Basis for the fee break-even verdict. Stated in the UI rather than hidden, because the
// verdict depends entirely on it and per-card annual spend isn't tracked yet.
const ASSUMED_ANNUAL_SPEND = 300000;

/** Reward wallet — points balance and what it's actually worth, per card. */
function RewardWallet({ cards }: { cards: CardResponse[] }) {
  const maskText = useMaskedText();
  const withPoints = cards.filter(c => (c.pointsBalance ?? 0) > 0);

  const totalPoints = cards.reduce((s, c) => s + (c.pointsBalance ?? 0), 0);
  const totalValue = cards.reduce((s, c) => s + (c.pointsCashValue ?? 0), 0);

  return (
    <div className="card">
      <h3 className="section-title"><Coins size={15} className="text-neutral" /> Reward Wallet</h3>

      <div className="grid grid-cols-2 gap-2 mb-3">
        <div className="card-flat">
          <div className="stat-label mb-0.5">Total points</div>
          <div className="text-lg font-mono tabular-nums font-bold text-ink">
            {new Intl.NumberFormat('en-IN').format(totalPoints)}
          </div>
        </div>
        <div className="card-flat">
          <div className="stat-label mb-0.5">Cash value</div>
          <div className="text-lg font-mono tabular-nums font-bold text-bull">{maskText(fmt(totalValue))}</div>
        </div>
      </div>

      {withPoints.length === 0 ? (
        <p className="text-2xs text-gray-600">
          No points recorded yet. Update a card's balance below to track redemption value.
        </p>
      ) : (
        <div className="space-y-1.5">
          {withPoints.map(c => (
            <div key={c.id} className="flex items-center justify-between gap-2 rounded-lg border border-surface-border bg-surface-hover/40 px-3 py-2">
              <span className="text-xs text-gray-300 truncate min-w-0">{c.name}</span>
              <span className="flex items-center gap-2 shrink-0">
                <span className="text-2xs font-mono tabular-nums text-gray-500">
                  {new Intl.NumberFormat('en-IN').format(c.pointsBalance)} pts
                </span>
                <span className="text-xs font-mono tabular-nums text-bull">{maskText(fmt(c.pointsCashValue))}</span>
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function Tab11Cards() {
  const [cards, setCards] = useState<CardResponse[]>([]);

  useEffect(() => {
    cardApi.list().then(r => setCards(r.data)).catch(() => setCards([]));
  }, []);

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-neutral/10 border border-neutral/25 flex items-center justify-center text-neutral shrink-0">
          <CreditCard size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-ink mb-0.5">Cards &amp; Rewards</h2>
          <p className="text-gray-500 text-xs">Which card to use, what your points are worth, and whether each fee pays for itself</p>
        </div>
      </div>

      {/* The decision first. */}
      <CardOptimizer />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <RewardWallet cards={cards} />

        {cards.length > 0 && (
          <div className="card">
            <h3 className="section-title"><Wallet size={15} className="text-brand-light" /> Fee Viability</h3>
            <p className="text-2xs text-gray-600 mb-3">
              Assuming <span className="font-mono tabular-nums text-gray-400">{fmt(ASSUMED_ANNUAL_SPEND)}</span> of
              annual spend on each card's best-earning category.
            </p>
            <div className="space-y-3">
              {cards.map(c => (
                <div key={c.id} className="rounded-xl border border-surface-border bg-surface-hover/40 p-3">
                  <div className="text-xs text-ink font-semibold mb-2 truncate">{c.name}</div>
                  <CardBreakEven card={c} assumedAnnualSpend={ASSUMED_ANNUAL_SPEND} />
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      <CreditCardSection />
    </div>
  );
}
