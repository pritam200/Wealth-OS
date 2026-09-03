interface Props {
  signal: 'BUY' | 'SELL' | 'HOLD';
  strength?: 'STRONG' | 'MODERATE' | 'WEAK';
}

export function SignalBadge({ signal, strength }: Props) {
  const cls = {
    BUY:  'bg-bull/20 text-bull border border-bull/30',
    SELL: 'bg-bear/20 text-bear border border-bear/30',
    HOLD: 'bg-neutral/20 text-neutral border border-neutral/30',
  }[signal];

  return (
    <span className={`inline-flex items-center gap-1 text-xs font-bold px-2.5 py-1 rounded-full ${cls}`}>
      {signal}
      {strength && (
        <span className="font-normal opacity-75">· {strength}</span>
      )}
    </span>
  );
}
