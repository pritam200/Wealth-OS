// Turns terse imported MF symbols (e.g. HDFC-SMALLCAP-IDCW-D.MF) into readable
// fund names, providers and category tags for a nicer UI.

const PROVIDERS: Record<string, string> = {
  HDFC: 'HDFC', SBI: 'SBI', ICICIPRU: 'ICICI Prudential', ICICI: 'ICICI Prudential',
  NIPPON: 'Nippon India', AXIS: 'Axis', KOTAK: 'Kotak', UTI: 'UTI', DSP: 'DSP',
  MIRAE: 'Mirae Asset', PARAG: 'Parag Parikh', QUANT: 'Quant', MOTILAL: 'Motilal Oswal',
};

const CATEGORY: Record<string, string> = {
  SMALLCAP: 'Small Cap', SMALLCAP2: 'Small Cap', SMALLCAP3: 'Small Cap',
  MIDCAP: 'Mid Cap', LARGECAP: 'Large Cap', LARGECAP2: 'Large Cap',
  FLEXICAP: 'Flexi Cap', MULTICAP: 'Multi Cap', LARGMID: 'Large & Mid Cap',
  LARGEMID: 'Large & Mid Cap', EQHYBRID: 'Equity Hybrid', BFS: 'Banking & Financial',
  MFG: 'Manufacturing', OPP: 'Opportunities', INDIA: 'India',
  NIFTY150MID: 'Nifty 150 Midcap Index', NIFTY250SMALL: 'Nifty 250 Smallcap Index',
};

const PLAN: Record<string, string> = {
  D: 'Direct', DG: 'Direct · Growth', G: 'Growth', R: 'Regular', REG: 'Regular',
  IDCW: 'IDCW',
};

export function baseSymbol(symbol: string): string {
  return (symbol || '').replace('.MF', '').replace('.NS', '');
}

export function fundProvider(symbol: string): string {
  const tok = baseSymbol(symbol).split('-')[0].toUpperCase();
  return PROVIDERS[tok] ?? tok;
}

/** e.g. HDFC-SMALLCAP-IDCW-D → "HDFC Small Cap · IDCW · Direct" */
export function prettyFundName(symbol: string): string {
  const parts = baseSymbol(symbol).split('-');
  if (parts.length === 0) return symbol;
  const provider = PROVIDERS[parts[0].toUpperCase()] ?? parts[0];
  const rest = parts.slice(1);
  const cats: string[] = [];
  const plans: string[] = [];
  for (const raw of rest) {
    const t = raw.toUpperCase();
    if (CATEGORY[t]) cats.push(CATEGORY[t]);
    else if (PLAN[t]) plans.push(PLAN[t]);
    else cats.push(raw.charAt(0) + raw.slice(1).toLowerCase());
  }
  const cat = cats.join(' ');
  const plan = plans.join(' · ');
  return [provider, cat].filter(Boolean).join(' ') + (plan ? ` · ${plan}` : '');
}

export function fundCategory(symbol: string): string {
  const parts = baseSymbol(symbol).split('-').slice(1);
  for (const raw of parts) {
    const t = raw.toUpperCase();
    if (CATEGORY[t]) return CATEGORY[t];
  }
  return 'Equity';
}

export const PROVIDER_COLORS: Record<string, string> = {
  'HDFC': '#e8a020', 'SBI': '#2563eb', 'ICICI Prudential': '#f03e3e',
  'Nippon India': '#00b8d9', 'Axis': '#8b5cf6', 'Kotak': '#00c47a', 'UTI': '#f59e0b',
};
export function providerColor(p: string): string { return PROVIDER_COLORS[p] ?? '#6b7280'; }
