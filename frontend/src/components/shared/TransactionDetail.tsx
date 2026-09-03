import { X } from 'lucide-react';
import { useMaskedText } from './Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(n || 0);

interface Field {
  label: string;
  value: string | null | undefined;
}

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  badgeColor: string;
  badgeLabel: string;
  amount: number;
  fields: Field[];
}

export function TransactionDetail({ open, onClose, title, badgeColor, badgeLabel, amount, fields }: Props) {
  const maskText = useMaskedText();
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-surface rounded-xl border border-surface-border shadow-2xl w-full max-w-md mx-4 overflow-hidden"
           onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between p-4 border-b border-surface-border">
          <div className="flex items-center gap-2">
            <span className="px-2 py-0.5 rounded text-2xs font-semibold" style={{ backgroundColor: badgeColor + '22', color: badgeColor }}>
              {badgeLabel}
            </span>
            <h3 className="text-white font-bold text-sm">{title}</h3>
          </div>
          <button onClick={onClose} className="btn-icon text-gray-500 hover:text-white"><X size={16} /></button>
        </div>

        <div className="p-4">
          <div className="text-center mb-4">
            <div className="text-2xs text-gray-500 mb-1">Amount</div>
            <div className="text-2xl font-bold font-mono" style={{ color: badgeColor }}>
              {maskText(fmtINR(amount))}
            </div>
          </div>

          <div className="space-y-2.5">
            {fields.filter(f => f.value).map(f => (
              <div key={f.label} className="flex justify-between items-start gap-4 py-1.5 border-b border-surface-border/30 last:border-0">
                <span className="text-2xs text-gray-500 shrink-0 w-28">{f.label}</span>
                <span className="text-xs text-white text-right break-words">{f.value}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
