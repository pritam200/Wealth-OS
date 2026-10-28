import type { ReactNode } from 'react';

/**
 * A card face drawn from the card's name and issuer: chip, contactless mark, network and the
 * colours of that card family. Drawn here rather than copied from the bank's artwork.
 */
interface Theme { bg: string; ink: string; accent: string; metal?: boolean }

// Card families first (a bank's premium card looks different from its entry card), then the bank.
const BY_NAME: [RegExp, Theme][] = [
  [/infinia|black|reserve|emeralde|ikigai|magnus burgundy/i, { bg: 'linear-gradient(135deg,#1c1c1e 0%,#3a3a3c 45%,#111 100%)', ink: '#f5e6c4', accent: '#c9a45c', metal: true }],
  [/regalia|diners|privilege|sapphiro|select|signature|atlas|vistara/i, { bg: 'linear-gradient(135deg,#2b2118 0%,#5a4632 50%,#1f1812 100%)', ink: '#f3e2c0', accent: '#d4af6a', metal: true }],
  [/magnus/i, { bg: 'linear-gradient(135deg,#3b0d18 0%,#6d1a2c 55%,#2a0911 100%)', ink: '#f6dfe4', accent: '#e8b4bf' }],
  [/millennia/i, { bg: 'linear-gradient(135deg,#1e3a8a 0%,#6d28d9 60%,#312e81 100%)', ink: '#eef2ff', accent: '#a5b4fc' }],
  [/amazon/i, { bg: 'linear-gradient(135deg,#0f172a 0%,#1e293b 60%,#0b1220 100%)', ink: '#f8fafc', accent: '#ff9900' }],
  [/flipkart/i, { bg: 'linear-gradient(135deg,#2874f0 0%,#1e40af 60%,#172554 100%)', ink: '#fff', accent: '#ffe11b' }],
  [/swiggy|tata neu|neu/i, { bg: 'linear-gradient(135deg,#ea580c 0%,#c2410c 55%,#7c2d12 100%)', ink: '#fff7ed', accent: '#fed7aa' }],
  [/cashback/i, { bg: 'linear-gradient(135deg,#4c1d95 0%,#7e22ce 55%,#3b0764 100%)', ink: '#faf5ff', accent: '#d8b4fe' }],
  [/ace/i, { bg: 'linear-gradient(135deg,#0f766e 0%,#115e59 55%,#042f2e 100%)', ink: '#f0fdfa', accent: '#5eead4' }],
  [/simply|elite|prime/i, { bg: 'linear-gradient(135deg,#be185d 0%,#9d174d 55%,#500724 100%)', ink: '#fdf2f8', accent: '#f9a8d4' }],
  [/rupay|upi/i, { bg: 'linear-gradient(135deg,#065f46 0%,#047857 55%,#022c22 100%)', ink: '#ecfdf5', accent: '#6ee7b7' }],
];

const BY_ISSUER: Record<string, Theme> = {
  HDFC: { bg: 'linear-gradient(135deg,#004c8f 0%,#0b2f63 60%,#06193a 100%)', ink: '#eaf2ff', accent: '#ed232a' },
  SBI: { bg: 'linear-gradient(135deg,#1e88e5 0%,#283593 60%,#1a1f5c 100%)', ink: '#e8f0ff', accent: '#9fd3ff' },
  ICICI: { bg: 'linear-gradient(135deg,#b02a30 0%,#7a1c20 55%,#3b0d0f 100%)', ink: '#fff1f0', accent: '#f58220' },
  Axis: { bg: 'linear-gradient(135deg,#97144d 0%,#6b0f37 55%,#33071a 100%)', ink: '#fff0f6', accent: '#f9a8c9' },
  Amex: { bg: 'linear-gradient(135deg,#5b8fa8 0%,#2f6b85 55%,#163a4a 100%)', ink: '#f0f9ff', accent: '#e0f2fe' },
  Kotak: { bg: 'linear-gradient(135deg,#d71920 0%,#8f1116 55%,#3d0709 100%)', ink: '#fff1f1', accent: '#9cc3ff' },
  'IDFC First': { bg: 'linear-gradient(135deg,#9c1d26 0%,#5e1117 55%,#2b0709 100%)', ink: '#fff1f2', accent: '#fca5a5' },
  'AU Bank': { bg: 'linear-gradient(135deg,#ec6608 0%,#a8470a 55%,#4a2005 100%)', ink: '#fff7ed', accent: '#fdba74' },
  'Standard Chartered': { bg: 'linear-gradient(135deg,#0473ea 0%,#38a169 100%)', ink: '#f0fdf4', accent: '#bbf7d0' },
  'Yes Bank': { bg: 'linear-gradient(135deg,#00518f 0%,#00305a 60%,#001a33 100%)', ink: '#eff6ff', accent: '#e11d48' },
  IndusInd: { bg: 'linear-gradient(135deg,#8b1d2c 0%,#4a0f18 60%,#1f060a 100%)', ink: '#fff1f2', accent: '#fcd34d' },
  RBL: { bg: 'linear-gradient(135deg,#21409a 0%,#142863 60%,#0a1433 100%)', ink: '#eef2ff', accent: '#f97316' },
  'Federal Bank': { bg: 'linear-gradient(135deg,#1d4f91 0%,#0f2c55 60%,#071528 100%)', ink: '#eff6ff', accent: '#fbbf24' },
  HSBC: { bg: 'linear-gradient(135deg,#db0011 0%,#8a000b 55%,#2e0004 100%)', ink: '#fff', accent: '#ffffff' },
  Scapia: { bg: 'linear-gradient(135deg,#14b8a6 0%,#0e7490 55%,#083344 100%)', ink: '#f0fdfa', accent: '#fde68a' },
  OneCard: { bg: 'linear-gradient(135deg,#27272a 0%,#52525b 50%,#18181b 100%)', ink: '#fafafa', accent: '#a1a1aa', metal: true },
  Slice: { bg: 'linear-gradient(135deg,#6d28d9 0%,#4c1d95 60%,#1e0b46 100%)', ink: '#f5f3ff', accent: '#c4b5fd' },
  Citi: { bg: 'linear-gradient(135deg,#056dae 0%,#003b70 60%,#001b33 100%)', ink: '#eff6ff', accent: '#ef4444' },
};

const FALLBACK: Theme = { bg: 'linear-gradient(135deg,#334155 0%,#1e293b 60%,#0f172a 100%)', ink: '#f1f5f9', accent: '#94a3b8' };

function themeFor(name: string, issuer: string): Theme {
  for (const [re, t] of BY_NAME) if (re.test(name)) return t;
  return BY_ISSUER[issuer] ?? FALLBACK;
}

function Chip() {
  return (
    <svg width="38" height="28" viewBox="0 0 38 28" aria-hidden="true">
      <defs>
        <linearGradient id="chipg" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#f6e27a" /><stop offset="0.5" stopColor="#cfa63c" /><stop offset="1" stopColor="#f3d98a" />
        </linearGradient>
      </defs>
      <rect x="0.5" y="0.5" width="37" height="27" rx="5" fill="url(#chipg)" stroke="#8a6d22" strokeWidth="0.6" />
      <path d="M0 9h12M0 19h12M26 9h12M26 19h12M12 0v28M26 0v28M12 14h14" stroke="#8a6d22" strokeWidth="0.7" fill="none" opacity="0.7" />
    </svg>
  );
}

function Contactless({ color }: { color: string }) {
  return (
    <svg width="18" height="20" viewBox="0 0 18 20" aria-hidden="true" fill="none" stroke={color} strokeWidth="1.6" strokeLinecap="round">
      <path d="M3 6.5c1.6 2 1.6 5 0 7" /><path d="M7 4c3 3.5 3 8.5 0 12" /><path d="M11 1.5c4.3 4.9 4.3 12.1 0 17" />
    </svg>
  );
}

/** The network as a plain mark — two overlapping circles for Mastercard, a wordmark otherwise. */
function NetworkMark({ network, ink }: { network: string; ink: string }) {
  const n = network.toLowerCase();
  if (n.includes('master')) {
    return (
      <svg width="40" height="25" viewBox="0 0 40 25" aria-label="Mastercard">
        <circle cx="15" cy="12.5" r="11" fill="#eb001b" /><circle cx="25" cy="12.5" r="11" fill="#f79e1b" fillOpacity="0.9" />
      </svg>
    );
  }
  const label = n.includes('visa') ? 'VISA' : n.includes('rupay') ? 'RuPay' : n.includes('amex') ? 'AMEX' : n.includes('diners') ? 'Diners Club' : network;
  return <span style={{ color: ink }} className={`font-bold tracking-wide ${n.includes('visa') ? 'italic text-lg' : 'text-xs'}`}>{label}</span>;
}

export function CardFace({ name, issuer, network, lastFour, children }: {
  name: string; issuer: string; network: string; lastFour: string | null; children?: ReactNode;
}) {
  const t = themeFor(name, issuer);
  return (
    <div className="relative aspect-[1.586] w-full rounded-xl overflow-hidden shadow-lg select-none" style={{ background: t.bg, color: t.ink }}>
      {/* soft light and a fine diagonal sheen, so the face reads as a physical card */}
      <div className="absolute inset-0 pointer-events-none"
        style={{ background: 'radial-gradient(120% 80% at 85% -10%, rgba(255,255,255,0.28), transparent 55%)' }} />
      <div className="absolute inset-0 pointer-events-none opacity-[0.07]"
        style={{ backgroundImage: 'repeating-linear-gradient(115deg, #fff 0 1px, transparent 1px 7px)' }} />
      {t.metal && <div className="absolute inset-0 pointer-events-none"
        style={{ background: 'linear-gradient(100deg, transparent 30%, rgba(255,255,255,0.12) 45%, transparent 60%)' }} />}
      <div className="absolute -right-10 -bottom-16 w-48 h-48 rounded-full opacity-20" style={{ background: t.accent }} />

      <div className="relative h-full p-4 flex flex-col justify-between">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="text-2xs uppercase tracking-[0.18em] opacity-80 font-semibold">{issuer}</div>
            <div className="text-sm font-semibold leading-tight truncate" style={{ textShadow: '0 1px 2px rgba(0,0,0,0.35)' }}>{name}</div>
          </div>
          {children}
        </div>
        <div className="flex items-center gap-3">
          <Chip />
          <Contactless color={t.ink} />
        </div>
        <div className="flex items-end justify-between">
          <div className="font-mono text-sm tracking-[0.2em]" style={{ textShadow: '0 1px 2px rgba(0,0,0,0.4)' }}>
            •••• •••• •••• {lastFour || '••••'}
          </div>
          <NetworkMark network={network} ink={t.ink} />
        </div>
      </div>
    </div>
  );
}
