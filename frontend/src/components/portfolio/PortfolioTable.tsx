import { Trash2 } from 'lucide-react';
import { useMaskedText } from '../shared/Amount';
import type { HoldingDto } from '../../types';
import { format, parseISO } from 'date-fns';

interface Props {
  holdings: HoldingDto[];
  onRemove?: (id: number) => void;
}

const fmt = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(n);

export function PortfolioTable({ holdings, onRemove }: Props) {
  const maskText = useMaskedText();
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-surface-border text-left">
            <th className="stat-label pb-3 pr-4">Stock</th>
            <th className="stat-label pb-3 pr-4">Buy Date</th>
            <th className="stat-label pb-3 pr-4 text-right">Qty</th>
            <th className="stat-label pb-3 pr-4 text-right">Avg Cost</th>
            <th className="stat-label pb-3 pr-4 text-right">CMP</th>
            <th className="stat-label pb-3 pr-4 text-right">Invested</th>
            <th className="stat-label pb-3 pr-4 text-right">Current</th>
            <th className="stat-label pb-3 pr-4 text-right">P&L</th>
            <th className="stat-label pb-3 pr-4 text-right">Wt%</th>
            {onRemove && <th className="pb-3 w-8" />}
          </tr>
        </thead>
        <tbody>
          {holdings.map(h => {
            const isPnlPositive = h.pnl >= 0;
            return (
              <tr key={h.id} className="border-b border-surface-border/50 hover:bg-surface-hover transition-colors">
                <td className="py-3 pr-4">
                  <div className="font-semibold text-white font-mono">{h.symbol}</div>
                  <div className="text-xs text-gray-500 truncate max-w-[120px]">{h.name}</div>
                </td>
                <td className="py-3 pr-4 text-xs text-gray-400 whitespace-nowrap">
                  {h.buyDate ? format(parseISO(h.buyDate), 'dd MMM yyyy') : '—'}
                </td>
                <td className="py-3 pr-4 text-right text-gray-300">{maskText(String(h.quantity))}</td>
                <td className="py-3 pr-4 text-right text-gray-300">{maskText(fmt(h.averageCost))}</td>
                <td className="py-3 pr-4 text-right text-white font-medium">{maskText(fmt(h.currentPrice ?? h.averageCost))}</td>
                <td className="py-3 pr-4 text-right text-gray-300">{maskText(fmt(h.investedValue))}</td>
                <td className="py-3 pr-4 text-right text-white">{maskText(fmt(h.currentValue))}</td>
                <td className="py-3 pr-4 text-right">
                  <div className={isPnlPositive ? 'value-bull' : 'value-bear'}>
                    {maskText(`${isPnlPositive ? '+' : ''}${fmt(h.pnl)}`)}
                  </div>
                  <div className={`text-xs ${isPnlPositive ? 'text-bull' : 'text-bear'}`}>
                    {maskText(`${isPnlPositive ? '+' : ''}${h.pnlPercent?.toFixed(2)}%`)}
                  </div>
                </td>
                <td className="py-3 pr-4 text-right text-gray-400">{maskText(`${h.weightPercent?.toFixed(1)}%`)}</td>
                {onRemove && (
                  <td className="py-3">
                    <button
                      onClick={() => onRemove(h.id)}
                      className="text-gray-600 hover:text-bear transition-colors p-1"
                    >
                      <Trash2 size={14} />
                    </button>
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
