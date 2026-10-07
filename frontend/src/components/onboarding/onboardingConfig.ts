/**
 * What the first-time setup asks for, per category, and how each answer is checked. Kept free of
 * React so the wording and the validation rules live in one place and can be read (and tested)
 * without the UI. Every message below is shown to a beginner: it says what is wrong and what a
 * correct value looks like.
 */

export type CategoryId = 'mf' | 'stock' | 'fd' | 'rd' | 'other';

export type FieldKind = 'text' | 'number' | 'date' | 'select' | 'checkbox';

export interface FieldDef {
  key: string;
  label: string;
  required: boolean;
  kind: FieldKind;
  /** One line under the box: the exact format expected. */
  format: string;
  /** Shown greyed inside the box and used by "Fill example". */
  example: string;
  options?: { value: string; label: string }[];
  /** Pre-filled value (e.g. today's date). */
  initial?: string;
  /** Returns an error message, or null when the value is fine. Receives all current values. */
  validate?: (value: string, all: Record<string, string>) => string | null;
}

export interface CategoryDef {
  id: CategoryId;
  title: string;
  /** One sentence on the hub card. */
  blurb: string;
  /** What the user needs in front of them before starting. */
  needs: string[];
  /** Where to get it. */
  whereToFind: string[];
  fields: FieldDef[];
  /** What happens after Save. */
  afterSave: string[];
}

export const todayIso = (): string => new Date().toISOString().slice(0, 10);

const isNum = (v: string) => v.trim() !== '' && Number.isFinite(Number(v));

export function positiveNumber(what: string, opts: { max?: number; integer?: boolean; decimals?: number } = {}) {
  return (v: string): string | null => {
    if (v.trim() === '') return `Enter ${what}.`;
    if (!isNum(v)) return `${what[0].toUpperCase()}${what.slice(1)} must be a number like 125.50, with no ₹ sign, commas or letters.`;
    const n = Number(v);
    if (n <= 0) return `${what[0].toUpperCase()}${what.slice(1)} must be greater than zero.`;
    if (opts.integer && !Number.isInteger(n)) return `${what[0].toUpperCase()}${what.slice(1)} must be a whole number (no decimals).`;
    if (opts.decimals != null) {
      const frac = v.trim().split('.')[1];
      if (frac && frac.length > opts.decimals) return `Use at most ${opts.decimals} decimal places.`;
    }
    if (opts.max != null && n > opts.max) return `${what[0].toUpperCase()}${what.slice(1)} cannot be more than ${opts.max}.`;
    return null;
  };
}

/** Real calendar date, in YYYY-MM-DD (what the date picker produces; typed text is checked too). */
export function validDate(what: string, opts: { notFuture?: boolean } = {}) {
  return (v: string): string | null => {
    if (!v) return `Choose ${what}.`;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(v) || Number.isNaN(Date.parse(v))) return `${what[0].toUpperCase()}${what.slice(1)} must be a real date written as YYYY-MM-DD, for example 2024-03-15.`;
    if (opts.notFuture && v > todayIso()) return `${what[0].toUpperCase()}${what.slice(1)} cannot be in the future.`;
    return null;
  };
}

export const requiredText = (what: string, min = 2) => (v: string): string | null =>
  v.trim().length < min ? `Enter ${what}.` : null;

/** NSE/BSE ticker: letters/digits, optionally &/-, optional .NS or .BO. Not a company name. */
export function validTicker(v: string): string | null {
  const t = v.trim().toUpperCase();
  if (!t) return 'Enter the stock ticker, for example RELIANCE.';
  if (/\s/.test(t)) return 'A ticker has no spaces. It is a short code such as INFY or TATAMOTORS, not the company name.';
  if (!/^[A-Z0-9&-]{1,20}(\.(NS|BO))?$/.test(t)) return 'Use only letters and digits, for example HDFCBANK. Add .BO only for BSE-only stocks.';
  return null;
}

export const OTHER_CATEGORIES: { value: string; label: string; hint: string; example: string }[] = [
  { value: 'ppf', label: 'PPF', hint: 'Balance in your PPF passbook or bank app.', example: 'PPF - SBI' },
  { value: 'epf', label: 'EPF / PF', hint: 'Balance in the EPFO member passbook (passbook.epfindia.gov.in) or the UMANG app.', example: 'EPF - Infosys' },
  { value: 'nps', label: 'NPS', hint: 'Total value on your NPS statement (NSDL/Protean or KFintech) or your NPS app.', example: 'NPS Tier 1' },
  { value: 'gold', label: 'Gold / Silver', hint: 'Grams you own × today\'s price per gram (any jeweller or MCX price). Sovereign Gold Bonds: units × current price.', example: 'Gold jewellery 80 g' },
  { value: 'realestate', label: 'Real Estate', hint: 'Your best estimate of today\'s market value, not the purchase price.', example: 'Flat - Pune' },
  { value: 'savings', label: 'Savings / Cash', hint: 'Current balance from your bank app or passbook.', example: 'HDFC Savings' },
  { value: 'insurance', label: 'Insurance', hint: 'Surrender or fund value shown in your policy statement (not the sum assured).', example: 'LIC Jeevan Anand' },
  { value: 'usstocks', label: 'US Stocks / Crypto', hint: 'Current value converted to rupees, as shown in your app.', example: 'US stocks - Vested' },
  { value: 'other', label: 'Other', hint: 'Anything else you own that has a value.', example: 'Vehicle' },
];

export const CATEGORIES: CategoryDef[] = [
  {
    id: 'mf',
    title: 'Mutual funds',
    blurb: 'Funds you hold through an app, AMC or SIP.',
    needs: ['The fund\'s full name, including Direct/Regular and Growth/IDCW', 'How many units you hold', 'Your average cost per unit (NAV)'],
    whereToFind: [
      'Your fund app (Groww, Zerodha Coin, Kuvera, the AMC app): open Portfolio or Holdings. Units and "Avg NAV" are listed per fund.',
      'No app? Get a free Consolidated Account Statement (CAS) emailed from mfcentral.com or camsonline.com. It lists every fund, folio and unit count.',
      'Average NAV = total amount invested ÷ units. If you only know the amount invested, divide it by the units.',
    ],
    fields: [
      { key: 'name', label: 'Fund name', required: true, kind: 'text', format: 'Copy it exactly as written on your statement or app.',
        example: 'HDFC Flexi Cap Fund - Direct Plan - Growth', validate: requiredText('the fund name', 5) },
      { key: 'units', label: 'Units held', required: true, kind: 'number', format: 'A number with up to 3 decimals, for example 123.456.',
        example: '123.456', validate: positiveNumber('the number of units', { decimals: 3 }) },
      { key: 'avgNav', label: 'Average cost per unit (₹)', required: true, kind: 'number', format: 'Rupees per unit, for example 45.20. No ₹ sign or commas.',
        example: '45.20', validate: positiveNumber('the average cost per unit', { decimals: 4 }) },
      { key: 'date', label: 'Date of purchase', required: false, kind: 'date', format: 'Pick it from the calendar. If you bought over time, use your first purchase. Left blank, today is used, which can make tax and returns look wrong.',
        example: '2023-06-15', validate: v => (v ? validDate('the purchase date', { notFuture: true })(v) : null) },
      { key: 'folio', label: 'Folio number', required: false, kind: 'text', format: 'Digits as printed on your statement (optional).',
        example: '12345678/90', validate: v => (v && !/^[A-Za-z0-9/ -]{3,30}$/.test(v.trim()) ? 'A folio number is 3 to 30 letters, digits, "/" or "-".' : null) },
    ],
    afterSave: [
      'The fund is checked against the official AMFI list; if it cannot be found you are asked to fix the name.',
      'It appears under Mutual Funds with today\'s official NAV, refreshed daily, and counts toward your net worth.',
      'It is marked unverified until you import a statement that confirms it.',
    ],
  },
  {
    id: 'stock',
    title: 'Stocks',
    blurb: 'Shares you hold in a demat account.',
    needs: ['The NSE ticker (a short code, not the company name)', 'How many shares you hold', 'Your average buy price per share'],
    whereToFind: [
      'Your broker app (Zerodha Console, Groww, Upstox, Angel One): open Holdings. Each row shows the ticker, "Qty" and "Avg. cost".',
      'Not sure of the ticker? Search the company on nseindia.com. The code beside the name is the ticker (for example INDIGO for InterGlobe Aviation, SBIN for State Bank of India).',
      'No broker app? Your CDSL or NSDL monthly statement lists quantities; use the price from your contract notes for the average.',
    ],
    fields: [
      { key: 'symbol', label: 'Ticker', required: true, kind: 'text', format: 'Capital letters and digits only, for example RELIANCE. We add .NS (NSE) for you.',
        example: 'RELIANCE', validate: validTicker },
      { key: 'quantity', label: 'Number of shares', required: true, kind: 'number', format: 'A whole number, for example 25.',
        example: '25', validate: positiveNumber('the number of shares', { integer: true }) },
      { key: 'avgPrice', label: 'Average buy price (₹ per share)', required: true, kind: 'number', format: 'Rupees per share, for example 2450.75. No ₹ sign or commas.',
        example: '2450.75', validate: positiveNumber('the average buy price', { decimals: 2 }) },
      { key: 'date', label: 'Buy date', required: false, kind: 'date', format: 'Pick it from the calendar. If you bought over time, use your first purchase. Left blank, today is used.',
        example: '2023-08-10', validate: v => (v ? validDate('the buy date', { notFuture: true })(v) : null) },
    ],
    afterSave: [
      'The ticker is checked against the live market feed; an unknown ticker is rejected so it never sits in your portfolio without a price.',
      'The stock appears under Stocks with its live price, gain/loss and a signal when one can be validated.',
      'It is marked unverified until you import a broker statement that confirms it.',
    ],
  },
  {
    id: 'fd',
    title: 'Fixed deposits (FD)',
    blurb: 'Bank or company fixed deposits.',
    needs: ['The bank name', 'The amount deposited', 'The interest rate', 'The start date and maturity date'],
    whereToFind: [
      'Your FD receipt or advice (PDF or email from the bank). It states amount, rate, start and maturity dates.',
      'Or your bank\'s net banking / app: Deposits → Fixed Deposits → select the FD.',
      'Rate is the yearly rate printed on the receipt, for example 7.10 (not the maturity amount).',
    ],
    fields: [
      { key: 'bank', label: 'Bank name', required: true, kind: 'text', format: 'Plain text, for example State Bank of India.',
        example: 'State Bank of India', validate: requiredText('the bank name') },
      { key: 'principal', label: 'Amount deposited (₹)', required: true, kind: 'number', format: 'Rupees, for example 100000. No ₹ sign or commas.',
        example: '100000', validate: positiveNumber('the amount deposited', { decimals: 2 }) },
      { key: 'rate', label: 'Interest rate (% per year)', required: true, kind: 'number', format: 'A number between 0.01 and 30, for example 7.10.',
        example: '7.10', validate: positiveNumber('the interest rate', { max: 30, decimals: 3 }) },
      { key: 'startDate', label: 'Start date', required: true, kind: 'date', format: 'Pick it from the calendar: the day the FD was opened (on your receipt).',
        example: '2025-01-05', validate: validDate('the start date', { notFuture: true }) },
      { key: 'maturityDate', label: 'Maturity date', required: true, kind: 'date', format: 'Pick it from the calendar: the day the FD matures, after the start date.',
        example: '2026-01-05',
        validate: (v, all) => validDate('the maturity date')(v) ?? (all.startDate && v <= all.startDate ? 'The maturity date must be after the start date.' : null) },
      { key: 'compounding', label: 'Interest is paid / compounded', required: false, kind: 'select', format: 'Most banks compound quarterly; check your receipt.',
        example: 'quarterly', initial: 'quarterly',
        options: [{ value: 'quarterly', label: 'Quarterly (most common)' }, { value: 'monthly', label: 'Monthly' }, { value: 'annually', label: 'Yearly' }] },
      { key: 'autoRenew', label: 'Renews automatically on maturity', required: false, kind: 'checkbox', format: 'Tick if your receipt says auto-renewal.', example: 'false' },
    ],
    afterSave: [
      'The FD appears under My Wealth → Fixed Deposits with its current value and the maturity value.',
      'You get a reminder before it matures, and a renewal is linked to this FD automatically.',
    ],
  },
  {
    id: 'rd',
    title: 'Recurring deposits (RD)',
    blurb: 'Monthly deposits you make into a bank RD.',
    needs: ['The bank name', 'The monthly instalment', 'The interest rate', 'The start date and how many months it runs'],
    whereToFind: [
      'Your RD passbook or the bank\'s net banking / app: Deposits → Recurring Deposits.',
      'The tenure is in months: 1 year = 12, 2 years = 24, 5 years = 60.',
    ],
    fields: [
      { key: 'bank', label: 'Bank name', required: true, kind: 'text', format: 'Plain text, for example HDFC Bank.',
        example: 'HDFC Bank', validate: requiredText('the bank name') },
      { key: 'monthlyAmount', label: 'Monthly instalment (₹)', required: true, kind: 'number', format: 'Rupees per month, for example 5000.',
        example: '5000', validate: positiveNumber('the monthly instalment', { decimals: 2 }) },
      { key: 'rate', label: 'Interest rate (% per year)', required: true, kind: 'number', format: 'A number between 0.01 and 30, for example 6.80.',
        example: '6.80', validate: positiveNumber('the interest rate', { max: 30, decimals: 3 }) },
      { key: 'startDate', label: 'Start date', required: true, kind: 'date', format: 'Pick it from the calendar: the day of the first instalment.',
        example: '2025-04-01', validate: validDate('the start date', { notFuture: true }) },
      { key: 'tenureMonths', label: 'Tenure (months)', required: true, kind: 'number', format: 'A whole number from 1 to 360, for example 24.',
        example: '24', validate: positiveNumber('the tenure in months', { integer: true, max: 360 }) },
    ],
    afterSave: [
      'The RD appears under My Wealth → Recurring Deposits with months paid, total deposited and the projected maturity amount.',
      'It is counted in your net worth at its current value, not the final maturity value.',
    ],
  },
  {
    id: 'other',
    title: 'Other investments & assets',
    blurb: 'PPF, EPF, NPS, gold, property, insurance, cash and more.',
    needs: ['What kind of asset it is', 'A name you will recognise', 'Its current value in rupees'],
    whereToFind: [
      'Pick the type below and the hint under it tells you exactly where to find the value (passbook, EPFO portal, NPS statement, etc.).',
      'For things without a statement (gold, property), use your best estimate of today\'s market value. You can update it later.',
    ],
    fields: [
      { key: 'category', label: 'Type', required: true, kind: 'select', format: 'Choose the closest match.', example: 'ppf', initial: 'ppf',
        options: OTHER_CATEGORIES.map(c => ({ value: c.value, label: c.label })) },
      { key: 'name', label: 'Name', required: true, kind: 'text', format: 'A label you will recognise, for example "PPF - SBI".',
        example: 'PPF - SBI', validate: requiredText('a name') },
      { key: 'value', label: 'Current value (₹)', required: true, kind: 'number', format: 'Rupees, for example 250000. Use 0 if it is empty. No ₹ sign or commas.',
        example: '250000', validate: v => {
          if (v.trim() === '') return 'Enter the current value in rupees (use 0 if empty).';
          if (!isNum(v)) return 'The value must be a number like 250000, with no ₹ sign, commas or letters.';
          return Number(v) < 0 ? 'The value cannot be negative.' : null;
        } },
      { key: 'asOf', label: 'Value as of', required: false, kind: 'date', format: 'Pick it from the calendar. Left blank, today is used.',
        example: todayIso(), validate: v => (v ? validDate('the date', { notFuture: true })(v) : null) },
    ],
    afterSave: [
      'The asset appears under My Wealth → Other Assets and is added to your net worth.',
      'Savings / Cash balances are added as a bank account so they count as cash.',
      'Update the value any time as it changes; nothing here is fetched automatically.',
    ],
  },
];

export function validateAll(def: CategoryDef, values: Record<string, string>): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const f of def.fields) {
    const v = values[f.key] ?? '';
    if (f.required && f.kind !== 'checkbox' && v.trim() === '') {
      errors[f.key] = f.kind === 'select' ? `Choose ${f.label.toLowerCase()}.` : `${f.label} is required.`;
      continue;
    }
    const msg = f.validate ? f.validate(v, values) : null;
    if (msg) errors[f.key] = msg;
  }
  return errors;
}

export function initialValues(def: CategoryDef): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of def.fields) out[f.key] = f.initial ?? '';
  return out;
}
