import { usePrivacyStore } from '../../store/privacyStore';

const MASK = '••••••';

// The one place that decides how a masked money/percentage value renders — every screen
// that shows a rupee amount, a P&L, a return %, or a chart value should go through this
// instead of rendering `value` directly, so the global Hide/Show Wealth toggle has no gaps.
export function Amount({ value, className }: { value: string | number; className?: string }) {
  const masked = usePrivacyStore(s => s.masked);
  return <span className={className}>{masked ? MASK : value}</span>;
}

// For call sites that need the raw masked string (e.g. inside a chart tooltip formatter,
// an SVG <text>, or a title attribute) rather than a wrapped element.
export function useMaskedText() {
  const masked = usePrivacyStore(s => s.masked);
  return (value: string | number) => (masked ? MASK : String(value));
}

// Rupee figures inside free text the backend writes (advisor answers, data-gap notes,
// reconciliation issue descriptions): "₹1,200.50", "−₹500", "₹-50000".
const RUPEES_IN_TEXT = /[+−-]?₹\s?[−-]?[\d,]+(?:\.\d+)?/g;

// For sentences that contain amounts, so privacy mode hides the figures but keeps the words.
export function useMaskedSentence() {
  const masked = usePrivacyStore(s => s.masked);
  return (text: string | null | undefined) => (text == null ? '' : masked ? text.replace(RUPEES_IN_TEXT, MASK) : text);
}

export function MaskedSentence({ text }: { text: string | null | undefined }) {
  const mask = useMaskedSentence();
  return <>{mask(text)}</>;
}
