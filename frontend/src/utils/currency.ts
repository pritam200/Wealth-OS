/**
 * The one place rupee amounts are formatted.
 *
 * Every screen used to define its own `fmtINR`, and the copies had drifted: some showed paise and
 * some whole rupees, one switched to compact notation above ₹1 crore while the tile rendered 200px
 * below it did not — so the same net worth appeared twice on one screen as "₹1.2Cr" and
 * "₹1,23,45,678" — and a null amount rendered as "—" in some tables and "₹0" in others, where a
 * real zero and a missing value are indistinguishable.
 *
 * The formatter instances are module-level on purpose: these run inside table-cell render loops,
 * and constructing an Intl.NumberFormat per call is the expensive part.
 */

const WHOLE = new Intl.NumberFormat('en-IN', {
  style: 'currency', currency: 'INR', maximumFractionDigits: 0,
});

const PAISE = new Intl.NumberFormat('en-IN', {
  style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2,
});

/** Whole rupees — the app-wide default. A null/undefined amount counts as zero. */
export const formatINR = (n: number | null | undefined): string => WHOLE.format(n || 0);

/** Two decimals. Only for per-unit prices, where paise genuinely change the meaning. */
export const formatINRPrecise = (n: number | null | undefined): string => PAISE.format(n || 0);

/**
 * Em dash for a missing amount. Use this wherever "we don't have this figure" must not be
 * presentable as ₹0 — an unpriced holding, an un-synced balance.
 */
export const formatINROrDash = (n: number | null | undefined): string =>
  n == null ? '—' : WHOLE.format(n);

/**
 * Indian short scale: ₹1.2Cr, ₹45.3L, ₹12.5k. For axis labels and dense tiles where the full
 * figure will not fit — never mixed with `formatINR` for the same number on the same screen.
 */
export const formatINRCompact = (n: number | null | undefined): string => {
  const v = n || 0;
  const sign = v < 0 ? '-' : '';
  const a = Math.abs(v);
  if (a >= 1_00_00_000) return `${sign}₹${(a / 1_00_00_000).toFixed(2)}Cr`;
  if (a >= 1_00_000) return `${sign}₹${(a / 1_00_000).toFixed(2)}L`;
  if (a >= 1_000) return `${sign}₹${(a / 1_000).toFixed(1)}k`;
  return `${sign}₹${a.toFixed(0)}`;
};

/** Signed percentage, e.g. "+12.40%". */
export const formatPct = (n: number | null | undefined): string =>
  `${(n || 0) >= 0 ? '+' : ''}${(n || 0).toFixed(2)}%`;
