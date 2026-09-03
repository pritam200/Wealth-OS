import { CreditCard } from 'lucide-react';
import { CreditCardSection } from '../../components/wealth/CreditCardSection';

export function Tab11Cards() {
  return (
    <div className="space-y-5">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-[#f5c451] to-[#ffb454] flex items-center justify-center text-white shadow-lift shrink-0">
          <CreditCard size={20} />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-0.5">Cards &amp; Rewards</h2>
          <p className="text-gray-500 text-xs">Your cards, their benefits, the best card for each spend, and reward-point optimisation</p>
        </div>
      </div>

      <CreditCardSection />
    </div>
  );
}
