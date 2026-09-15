import { TrendingUp } from 'lucide-react';
import type { CardResponse } from '../../api/card';
import { useMaskedText } from '../shared/Amount';

const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n);

export type CardVerdict = 'KEEP' | 'REPLACE' | 'CONSIDER';

/**
 * Fee viability: Annual spend → Rewards → Net value → Break-even.
 *
 * Break-even is the annual spend at which rewards cancel the annual fee, using the card's own
 * best reward rate. A fee-free card has no break-even at all, which is stated rather than
 * shown as an empty bar.
 */
export function cardVerdict(card: CardResponse, assumedAnnualSpend: number): {
  verdict: CardVerdict; bestRate: number; breakEvenSpend: number | null;
  rewardsAtSpend: number; netValue: number; progress: number;
} {
  const rates = Object.values(card.rewardRates ?? {});
  const bestRate = rates.length > 0 ? Math.max(...rates) : 0;
  const fee = card.annualFee ?? 0;

  const breakEvenSpend = fee > 0 && bestRate > 0 ? (fee / (bestRate / 100)) : null;
  const rewardsAtSpend = assumedAnnualSpend * (bestRate / 100);
  const netValue = rewardsAtSpend - fee;

  // How far the assumed spend gets toward covering the fee.
  const progress = breakEvenSpend == null ? 100
    : Math.min((assumedAnnualSpend / breakEvenSpend) * 100, 100);

  let verdict: CardVerdict;
  if (fee === 0) {
    verdict = 'KEEP';                       // nothing to justify
  } else if (netValue > 0) {
    verdict = 'KEEP';
  } else if (bestRate === 0) {
    verdict = 'REPLACE';                    // paying a fee for no earn rate
  } else {
    verdict = 'CONSIDER';                   // fee not yet covered at this spend level
  }

  return { verdict, bestRate, breakEvenSpend, rewardsAtSpend, netValue, progress };
}

export function CardBreakEven({ card, assumedAnnualSpend }: {
  card: CardResponse; assumedAnnualSpend: number;
}) {
  const maskText = useMaskedText();
  const { verdict, bestRate, breakEvenSpend, rewardsAtSpend, netValue, progress } =
    cardVerdict(card, assumedAnnualSpend);

  const pill = verdict === 'KEEP' ? 'pill-bull' : verdict === 'REPLACE' ? 'pill-bear' : 'pill-neutral';
  const label = verdict === 'KEEP' ? 'Worth keeping'
    : verdict === 'REPLACE' ? 'Consider replacing' : 'Fee not covered';

  if (bestRate === 0 && (card.annualFee ?? 0) === 0) {
    return (
      <div className="text-2xs text-gray-600">
        No reward rates recorded for this card — add them to see fee viability.
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-2">
        <span className="stat-label flex items-center gap-1.5">
          <TrendingUp size={11} /> Fee viability
        </span>
        <span className={pill}>{label}</span>
      </div>

      <div className="grid grid-cols-3 gap-2 mb-2.5">
        <div>
          <div className="stat-label mb-0.5">Rewards</div>
          <div className="text-xs font-mono tabular-nums text-gray-200">{maskText(fmt(rewardsAtSpend))}</div>
        </div>
        <div>
          <div className="stat-label mb-0.5">Annual fee</div>
          <div className="text-xs font-mono tabular-nums text-gray-200">{fmt(card.annualFee ?? 0)}</div>
        </div>
        <div>
          <div className="stat-label mb-0.5">Net value</div>
          <div className={`text-xs font-mono tabular-nums font-semibold ${netValue >= 0 ? 'text-bull' : 'text-bear'}`}>
            {netValue >= 0 ? '+' : ''}{maskText(fmt(netValue))}
          </div>
        </div>
      </div>

      {breakEvenSpend == null ? (
        <p className="text-2xs text-gray-500">
          No annual fee — every reward earned is net gain.
        </p>
      ) : (
        <>
          <div className="w-full h-1.5 rounded-full bg-surface-hover overflow-hidden">
            <div className={`h-full rounded-full ${netValue >= 0 ? 'bg-bull' : 'bg-neutral'}`}
                 style={{ width: `${progress}%` }} />
          </div>
          <p className="text-2xs text-gray-500 mt-1.5">
            Break even at <span className="font-mono tabular-nums text-gray-300">{fmt(breakEvenSpend)}</span> annual
            spend at <span className="font-mono tabular-nums">{bestRate.toFixed(2)}%</span>
            {netValue < 0 && (
              <> — <span className="text-neutral font-mono tabular-nums">{fmt(breakEvenSpend - assumedAnnualSpend)}</span> more needed</>
            )}
          </p>
        </>
      )}
    </div>
  );
}
