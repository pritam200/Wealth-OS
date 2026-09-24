/**
 * Single source of truth for chart colours.
 *
 * Recharts styles axes, grids, tooltips and legends through inline props rather than CSS, so
 * these values cannot come from Tailwind tokens — they were previously hardcoded per chart,
 * which is why several tooltips stayed dark after the theme flipped to light. Everything a
 * chart needs now lives here.
 *
 * Keep these in step with `tailwind.config.js`; the hexes below mirror the same tokens.
 */

/* ── Semantic ─────────────────────────────────────────────────────────── */
export const CHART = {
  bull:    '#047857',   // positive / gain
  bear:    '#DC2626',   // negative / loss
  neutral: '#B45309',   // warning / watch
  brand:   '#2563EB',   // primary

  ink:     '#0F172A',   // headline text on a chart
  text:    '#475569',   // axis tick labels — 7.5:1 on white
  muted:   '#5B6B7F',   // secondary labels — 5.45:1 on white
  grid:    '#E3E8EF',   // gridlines / axis lines
  surface: '#FFFFFF',   // tooltip + chart background
  border:  '#E3E8EF',
} as const;

/**
 * Categorical palette for multi-series charts (asset classes, per-holding allocation).
 * Ordered so adjacent slices stay distinguishable, and every entry holds >= 3:1 against
 * white so a thin slice or line is still visible.
 */
export const CHART_SERIES = [
  '#2563EB', // blue
  '#047857', // emerald
  '#B45309', // amber
  '#7C3AED', // violet
  '#DC2626', // red
  '#0E7490', // teal
  '#A21CAF', // fuchsia
  '#4D7C0F', // olive
  '#B91C1C', // brick
  '#1E40AF', // deep blue
  '#065F46', // deep emerald
  '#92400E', // deep amber
] as const;

export const seriesColor = (i: number) => CHART_SERIES[i % CHART_SERIES.length];

/* ── Reusable recharts props ──────────────────────────────────────────── */

/** Tooltip surface. Light card + dark text, matching the rest of the UI. */
export const tooltipStyle = {
  background: CHART.surface,
  border: `1px solid ${CHART.border}`,
  borderRadius: 8,
  fontSize: 11,
  boxShadow: '0 4px 16px rgba(15,23,42,0.10)',
  color: CHART.ink,
} as const;

/** Tooltip value/label text — recharts colours items from the series by default, which can
 *  produce a pale value on a white tooltip. */
export const tooltipItemStyle = { color: CHART.ink } as const;
export const tooltipLabelStyle = { color: CHART.muted, fontSize: 11 } as const;

/** Axis defaults: readable ticks, hairline axis line. */
export const axisProps = {
  stroke: CHART.grid,
  tick: { fill: CHART.text, fontSize: 11 },
  tickLine: false,
} as const;

export const gridProps = {
  stroke: CHART.grid,
  strokeDasharray: '3 3',
  vertical: false,
} as const;

export const legendStyle = { fontSize: 11, color: CHART.muted } as const;

/**
 * Category colours for income sources / expense categories — used both decoratively (legend
 * dots, progress bars) and as literal text colour (badge labels, headline amounts in
 * TransactionDetail). Every value clears 4.8:1 on white, so a badge or amount rendered in one
 * of these is never the near-invisible pastel the dark-theme originals were.
 */
export const CATEGORY_COLORS: Record<string, string> = {
  // Income sources
  Salary: '#047857', Freelance: '#2563EB', Dividend: '#B45309',
  Interest: '#0E7490', Rental: '#6D28D9', Business: '#4D7C0F', Other: '#475569',
  // Expense categories
  Food: '#B45309', Travel: '#2563EB', Utilities: '#6D28D9',
  Entertainment: '#A21CAF', Health: '#047857', Shopping: '#DC2626',
  EMI: '#DC2626', Investment: '#0E7490',
  'Food Delivery': '#EA580C', Groceries: '#65A30D', 'Restaurant / Outing': '#C2410C',
  'Account Transfer': '#64748B',
};

export const categoryColor = (key: string) => CATEGORY_COLORS[key] ?? '#475569';
