import { useState } from 'react';
import { X, Download, Upload, CheckCircle, AlertCircle, Loader } from 'lucide-react';
import { trackingApi } from '../api/tracking';
import { portfolioApi } from '../api/portfolio';

type Tab = 'fd' | 'rd' | 'loan' | 'other' | 'stocks';

const TABS: { key: Tab; label: string; color: string }[] = [
  { key: 'fd',     label: 'Fixed Deposits', color: 'text-brand' },
  { key: 'rd',     label: 'Recurring Deposits', color: 'text-accent' },
  { key: 'loan',   label: 'Loans / EMI', color: 'text-bear' },
  { key: 'other',  label: 'Other Assets', color: 'text-gray-300' },
  { key: 'stocks', label: 'Stocks / MF', color: 'text-bull' },
];

const TEMPLATES: Record<Tab, { header: string; example: string; columns: string[] }> = {
  fd: {
    header: 'Bank,Principal,Rate,Compounding,AutoRenew,StartDate,MaturityDate',
    example: [
      'HDFC Bank,200000,7.5,quarterly,no,2024-01-15,2025-01-15',
      'SBI,150000,7.0,quarterly,yes,2023-06-01,2025-06-01',
      'ICICI Bank,100000,7.25,monthly,no,2024-03-01,2026-03-01',
    ].join('\n'),
    columns: ['Bank', 'Principal (₹)', 'Rate (% p.a.)', 'Compounding (quarterly/monthly/annually)', 'AutoRenew (yes/no)', 'Start Date (YYYY-MM-DD)', 'Maturity Date (YYYY-MM-DD)'],
  },
  rd: {
    header: 'Bank,MonthlyAmount,Rate,StartDate,TenureMonths',
    example: [
      'HDFC Bank,5000,7.0,2024-01-01,24',
      'SBI,10000,6.8,2023-12-01,36',
      'ICICI Bank,2000,7.1,2024-04-01,12',
    ].join('\n'),
    columns: ['Bank', 'Monthly Amount (₹)', 'Rate (% p.a.)', 'Start Date (YYYY-MM-DD)', 'Tenure (months)'],
  },
  loan: {
    header: 'Name,Type,EMI,Outstanding,Rate,RemainingMonths',
    example: [
      'Home Loan – HDFC,Home Loan,28000,3200000,8.5,180',
      'Car Loan – SBI,Car Loan,9000,420000,9.0,48',
      'Personal Loan,Personal Loan,12000,350000,12.0,30',
    ].join('\n'),
    columns: ['Name', 'Type (Home Loan/Car Loan/Personal Loan/Education Loan/Business Loan/Gold Loan/Other)', 'EMI (₹)', 'Outstanding Balance (₹)', 'Rate (% p.a.)', 'Remaining Months'],
  },
  other: {
    header: 'Name,Category,Value,Note',
    example: [
      'SBI PPF Account,ppf,450000,matures 2030',
      'EPF – Company,epf,820000,',
      'NPS Tier 1,nps,250000,',
      'Gold 50g,gold,350000,22 karat',
      'Savings HDFC,savings,180000,emergency fund',
      'Savings SBI,savings,120000,',
    ].join('\n'),
    columns: ['Name', 'Category (ppf/epf/nps/gold/realestate/savings/insurance/usstocks/other)', 'Value (₹)', 'Note (optional)'],
  },
  stocks: {
    header: 'Symbol,Quantity,AvgCost,Date',
    example: [
      'RELIANCE,10,2450,2024-01-15',
      'INFY,25,1800,2024-02-01',
      'HDFCBANK,15,1650,2023-12-10',
      'TCS,5,3900,2024-03-05',
      'KOTAKBANK,20,1750,2024-01-20',
    ].join('\n'),
    columns: ['Symbol (without .NS)', 'Quantity', 'Avg Cost / Buy Price (₹)', 'Purchase Date (YYYY-MM-DD)'],
  },
};

type RowResult = { row: string; ok: boolean; error?: string };

function parseCSV(text: string): string[][] {
  return text
    .split('\n')
    .map(r => r.trim())
    .filter(r => r.length > 0)
    .map(r => r.split(',').map(c => c.trim()));
}

async function importFDs(rows: string[][]): Promise<RowResult[]> {
  const results: RowResult[] = [];
  for (const cols of rows) {
    const [bank, principal, rate, compounding, autoRenew, startDate, maturityDate] = cols;
    const label = `${bank} – ₹${principal}`;
    if (!bank || !principal || !rate) { results.push({ row: label, ok: false, error: 'Missing bank/principal/rate' }); continue; }
    try {
      await trackingApi.addFd({
        bank, principal: Number(principal), rate: Number(rate),
        compounding: compounding || 'quarterly',
        autoRenew: autoRenew?.toLowerCase() === 'yes',
        startDate: startDate || undefined,
        maturityDate: maturityDate || undefined,
      });
      results.push({ row: label, ok: true });
    } catch (e: any) {
      results.push({ row: label, ok: false, error: e?.response?.data?.message || 'Failed' });
    }
  }
  return results;
}

async function importRDs(rows: string[][]): Promise<RowResult[]> {
  const results: RowResult[] = [];
  for (const cols of rows) {
    const [bank, monthlyAmount, rate, startDate, tenureMonths] = cols;
    const label = `${bank} – ₹${monthlyAmount}/mo`;
    if (!bank || !monthlyAmount || !rate) { results.push({ row: label, ok: false, error: 'Missing bank/amount/rate' }); continue; }
    try {
      await trackingApi.addRd({ bank, monthlyAmount: Number(monthlyAmount), rate: Number(rate), startDate: startDate || undefined, tenureMonths: Number(tenureMonths) || 12 });
      results.push({ row: label, ok: true });
    } catch (e: any) {
      results.push({ row: label, ok: false, error: e?.response?.data?.message || 'Failed' });
    }
  }
  return results;
}

async function importLoans(rows: string[][]): Promise<RowResult[]> {
  const results: RowResult[] = [];
  for (const cols of rows) {
    const [name, type, emi, outstanding, rate, remainingMonths] = cols;
    const label = `${name}`;
    if (!name || !emi) { results.push({ row: label, ok: false, error: 'Missing name/EMI' }); continue; }
    try {
      await trackingApi.addLoan({ name, type: type || 'Other', emi: Number(emi), outstanding: Number(outstanding) || 0, rate: Number(rate) || 0, remainingMonths: Number(remainingMonths) || 0 });
      results.push({ row: label, ok: true });
    } catch (e: any) {
      results.push({ row: label, ok: false, error: e?.response?.data?.message || 'Failed' });
    }
  }
  return results;
}

async function importOther(rows: string[][]): Promise<RowResult[]> {
  const results: RowResult[] = [];
  for (const cols of rows) {
    const [name, category, value, note] = cols;
    const label = `${name} (${category})`;
    if (!name || !category || !value) { results.push({ row: label, ok: false, error: 'Missing name/category/value' }); continue; }
    try {
      await trackingApi.addOther({ name, category, value: Number(value), note: note || undefined });
      results.push({ row: label, ok: true });
    } catch (e: any) {
      results.push({ row: label, ok: false, error: e?.response?.data?.message || 'Failed' });
    }
  }
  return results;
}

async function importStocks(rows: string[][]): Promise<RowResult[]> {
  const results: RowResult[] = [];
  // get or create default portfolio
  let portfolioId: number;
  try {
    const { data: list } = await portfolioApi.list();
    if (list.length > 0) {
      portfolioId = list[0].id;
    } else {
      const { data: created } = await portfolioApi.create('My Portfolio');
      portfolioId = created.id;
    }
  } catch {
    return rows.map(c => ({ row: c[0] || '?', ok: false, error: 'Could not load portfolio' }));
  }

  for (const cols of rows) {
    const [symbol, quantity, avgCost, date] = cols;
    const sym = symbol?.toUpperCase().replace('.NS', '');
    const label = `${sym} × ${quantity}`;
    if (!sym || !quantity || !avgCost) { results.push({ row: label, ok: false, error: 'Missing symbol/qty/price' }); continue; }
    try {
      await portfolioApi.addHolding(portfolioId, {
        symbol: `${sym}.NS`, name: sym,
        quantity: Number(quantity), price: Number(avgCost),
        transactionDate: date || new Date().toISOString().slice(0, 10),
      });
      results.push({ row: label, ok: true });
    } catch (e: any) {
      results.push({ row: label, ok: false, error: e?.response?.data?.message || 'Failed' });
    }
  }
  return results;
}

const importers: Record<Tab, (rows: string[][]) => Promise<RowResult[]>> = {
  fd: importFDs, rd: importRDs, loan: importLoans, other: importOther, stocks: importStocks,
};

function downloadTemplate(tab: Tab) {
  const t = TEMPLATES[tab];
  const content = `${t.header}\n${t.example}`;
  const blob = new Blob([content], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `template_${tab}.csv`;
  a.click();
}

interface Props { onClose: () => void; onDone: () => void; }

export function BulkImportModal({ onClose, onDone }: Props) {
  const [tab, setTab] = useState<Tab>('fd');
  const [text, setText] = useState('');
  const [importing, setImporting] = useState(false);
  const [results, setResults] = useState<RowResult[] | null>(null);

  const tmpl = TEMPLATES[tab];

  const handleImport = async () => {
    const raw = parseCSV(text);
    if (!raw.length) return;
    // skip header row if it matches the template header
    const rows = raw[0].join(',').toLowerCase().replace(/\s/g, '') === tmpl.header.toLowerCase().replace(/\s/g, '') ? raw.slice(1) : raw;
    if (!rows.length) return;
    setImporting(true);
    setResults(null);
    const res = await importers[tab](rows);
    setResults(res);
    setImporting(false);
    const anyOk = res.some(r => r.ok);
    if (anyOk) onDone();
  };

  const ok  = results?.filter(r => r.ok).length ?? 0;
  const err = results?.filter(r => !r.ok).length ?? 0;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="bg-surface rounded-xl border border-surface-border shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-surface-border">
          <div>
            <h2 className="font-bold text-white">Bulk Import</h2>
            <p className="text-xs text-gray-500 mt-0.5">Paste CSV data to import multiple entries at once</p>
          </div>
          <button onClick={onClose} className="btn-icon"><X size={16} /></button>
        </div>

        {/* Tabs */}
        <div className="flex gap-1 p-3 border-b border-surface-border overflow-x-auto">
          {TABS.map(t => (
            <button key={t.key} onClick={() => { setTab(t.key); setText(''); setResults(null); }}
              className={`px-3 py-1.5 rounded text-xs font-medium whitespace-nowrap transition-all ${tab === t.key ? `bg-surface-hover ${t.color} border border-surface-border` : 'text-gray-500 hover:text-white'}`}>
              {t.label}
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-3">
          {/* Column guide */}
          <div className="bg-surface-hover rounded-lg p-3">
            <div className="text-xs text-gray-400 font-semibold mb-2">Columns (in order)</div>
            <div className="flex flex-wrap gap-1.5">
              {tmpl.columns.map((c, i) => (
                <span key={i} className="text-2xs bg-surface-border rounded px-2 py-0.5 text-gray-300">{i + 1}. {c}</span>
              ))}
            </div>
          </div>

          {/* Textarea */}
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className="text-xs text-gray-400">Paste your data below (CSV, one row per entry)</label>
              <button onClick={() => downloadTemplate(tab)} className="flex items-center gap-1 text-2xs text-brand hover:text-brand/80">
                <Download size={11} /> Download template
              </button>
            </div>
            <textarea
              value={text}
              onChange={e => { setText(e.target.value); setResults(null); }}
              placeholder={`${tmpl.header}\n${tmpl.example}`}
              rows={8}
              className="w-full bg-surface-hover border border-surface-border rounded-lg p-3 text-xs text-gray-200 font-mono resize-none focus:outline-none focus:border-brand placeholder-gray-700"
            />
          </div>

          {/* Results */}
          {results && (
            <div className="space-y-2">
              <div className="flex items-center gap-3 text-sm">
                {ok > 0 && <span className="flex items-center gap-1 text-bull"><CheckCircle size={14} />{ok} imported</span>}
                {err > 0 && <span className="flex items-center gap-1 text-bear"><AlertCircle size={14} />{err} failed</span>}
              </div>
              <div className="max-h-40 overflow-y-auto space-y-1">
                {results.map((r, i) => (
                  <div key={i} className={`flex items-center gap-2 text-xs rounded px-2 py-1 ${r.ok ? 'bg-bull/10 text-bull' : 'bg-bear/10 text-bear'}`}>
                    {r.ok ? <CheckCircle size={11} /> : <AlertCircle size={11} />}
                    <span className="flex-1 truncate">{r.row}</span>
                    {r.error && <span className="text-2xs opacity-70">{r.error}</span>}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center gap-3 p-4 border-t border-surface-border">
          <button onClick={onClose} className="btn-ghost text-sm flex-1">Close</button>
          <button
            onClick={handleImport}
            disabled={!text.trim() || importing}
            className="btn-primary text-sm flex-1 flex items-center justify-center gap-2"
          >
            {importing ? <><Loader size={14} className="animate-spin" /> Importing…</> : <><Upload size={14} /> Import {tab.toUpperCase()}</>}
          </button>
        </div>
      </div>
    </div>
  );
}
