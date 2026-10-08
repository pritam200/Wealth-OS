import { useState } from 'react';
import {
  Wallet, TrendingUp, Landmark, PiggyBank, Layers, PartyPopper, ArrowRight, ArrowLeft, X, Loader,
  FileSpreadsheet, CheckCircle2, Lightbulb, MapPin, ListChecks, Sparkles,
} from 'lucide-react';
import { ledgerApi } from '../../api/ledger';
import { portfolioApi } from '../../api/portfolio';
import { trackingApi } from '../../api/tracking';
import { marketApi } from '../../api/market';
import { amfiNavApi } from '../../api/amfiNav';
import {
  CATEGORIES, OTHER_CATEGORIES, validateAll, initialValues, todayIso,
  type CategoryDef, type CategoryId, type FieldDef,
} from './onboardingConfig';

type Screen = 'welcome' | 'hub' | CategoryId | 'done';

/** App tab index of the Data Platform page (see App.tsx TabContent). */
const DATA_PLATFORM_TAB = 23;

const ICONS: Record<CategoryId, typeof Wallet> = { mf: Layers, stock: TrendingUp, fd: Landmark, rd: PiggyBank, other: Wallet };

interface Props {
  userName: string;
  onFinish: (openTab?: number) => void;
}

/**
 * First-time setup, organised by what the person owns. Each category explains what to bring, where
 * to find it, the exact format of every field (required or optional), shows a valid example, checks
 * the answers with plain-language messages and says what happens after saving. Everything is
 * optional: a category can be skipped, and nothing here is needed to reach the dashboard.
 *
 * It writes through the same APIs as the wealth-tab "Add" forms (portfolio, tracking, ledger), not a
 * separate onboarding-only path.
 */
export function OnboardingModal({ userName, onFinish }: Props) {
  const [screen, setScreen] = useState<Screen>('welcome');
  const [added, setAdded] = useState<Record<CategoryId, string[]>>({ mf: [], stock: [], fd: [], rd: [], other: [] });

  const totalAdded = Object.values(added).reduce((n, l) => n + l.length, 0);
  const def = CATEGORIES.find(c => c.id === screen);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="relative bg-surface rounded-2xl border border-surface-border shadow-2xl w-full max-w-2xl max-h-[92vh] overflow-y-auto">
        <div className="p-6">
          {screen === 'welcome' && (
            <div className="text-center">
              <div className="icon-badge-brand w-14 h-14 mx-auto mb-4"><Wallet size={24} /></div>
              <h2 className="text-xl font-bold text-ink mb-2">Welcome, {userName.split(' ')[0]}</h2>
              <p className="text-sm text-gray-500 mb-4">
                Let's put what you own into one place. Your dashboard then shows your real net worth from the first screen instead of zero.
              </p>
              <ol className="text-left text-sm text-gray-400 bg-surface-hover rounded-lg p-4 mb-6 space-y-2 list-decimal list-inside">
                <li>Upload one statement for all your mutual funds, and one for all your shares.</li>
                <li>Add fixed deposits, recurring deposits and cards if you have them.</li>
                <li>Everything is optional and takes about five minutes. You can stop and come back any time.</li>
              </ol>
              <button onClick={() => onFinish(24)} className="btn-primary w-full py-2.5">Start guided setup <ArrowRight size={15} /></button>
              <button onClick={() => setScreen('hub')} className="btn-secondary w-full mt-2 justify-center text-sm">I'd rather type my holdings in</button>
              <button onClick={() => onFinish()} className="btn-ghost w-full mt-2 justify-center text-sm">Skip, I'll do this later</button>
            </div>
          )}

          {screen === 'hub' && (
            <Hub added={added} totalAdded={totalAdded}
              onPick={setScreen} onImport={() => onFinish(DATA_PLATFORM_TAB)} onDone={() => setScreen('done')} />
          )}

          {def && (
            <CategoryScreen key={def.id} def={def}
              onBack={() => setScreen('hub')}
              onSaved={(summary, another) => {
                setAdded(a => ({ ...a, [def.id]: [...a[def.id], summary] }));
                if (!another) setScreen('hub');
              }} />
          )}

          {screen === 'done' && (
            <div className="text-center">
              <div className="icon-badge-bull w-14 h-14 mx-auto mb-4"><PartyPopper size={22} /></div>
              <h2 className="text-xl font-bold text-ink mb-2">{totalAdded > 0 ? "You're all set" : 'Nothing added yet, and that is fine'}</h2>
              {totalAdded > 0 ? (
                <div className="text-left bg-surface-hover rounded-lg p-3 mb-5 space-y-1">
                  {CATEGORIES.flatMap(c => added[c.id].map((a, i) => (
                    <div key={`${c.id}${i}`} className="text-sm text-gray-300 flex gap-2"><CheckCircle2 size={14} className="text-bull mt-0.5 shrink-0" /><span><b>{c.title}:</b> {a}</span></div>
                  )))}
                </div>
              ) : (
                <p className="text-sm text-gray-500 mb-5">
                  You can add everything later from My Wealth and Investments, or import a statement from the Data Platform.
                </p>
              )}
              <p className="text-xs text-gray-500 mb-5">
                Items you typed in yourself are marked <b>unverified</b>. Import a statement from your broker, fund house or bank
                in the Data Platform and they are checked against it.
              </p>
              <button onClick={() => onFinish()} className="btn-primary w-full py-2.5">Go to Dashboard</button>
              <button onClick={() => setScreen('hub')} className="btn-ghost w-full mt-2 justify-center text-sm">Add more</button>
            </div>
          )}
        </div>

        {screen !== 'welcome' && screen !== 'done' && (
          <button aria-label="Close setup" onClick={() => onFinish()} className="absolute top-4 right-4 btn-icon"><X size={16} /></button>
        )}
      </div>
    </div>
  );
}

/* ───────────────────────── hub ───────────────────────── */

function Hub({ added, totalAdded, onPick, onImport, onDone }: {
  added: Record<CategoryId, string[]>; totalAdded: number;
  onPick: (c: CategoryId) => void; onImport: () => void; onDone: () => void;
}) {
  return (
    <div>
      <h2 className="text-lg font-semibold text-ink mb-1">What do you own?</h2>
      <p className="text-sm text-gray-500 mb-4">Open each category you have. Skip the ones you don't. Each takes about a minute.</p>

      <button onClick={onImport} className="w-full text-left rounded-xl border border-brand/40 bg-surface-hover p-3 mb-3 hover:border-brand">
        <div className="flex items-center gap-2 text-sm font-semibold text-ink"><FileSpreadsheet size={15} /> Have many holdings? Import a statement instead</div>
        <p className="text-xs text-gray-500 mt-1">
          Upload a CSV or Excel file from your broker or fund house. It needs at least a <b>Date</b> column and a <b>Type</b> column
          (BUY, SELL, SIP...), plus the fund or stock name, units and amount, one transaction per row. Imported data is marked verified.
        </p>
      </button>

      <div className="grid sm:grid-cols-2 gap-3">
        {CATEGORIES.map(c => {
          const Icon = ICONS[c.id];
          const n = added[c.id].length;
          return (
            <button key={c.id} onClick={() => onPick(c.id)}
              className="text-left rounded-xl border border-surface-border bg-surface-hover p-3 hover:border-brand/50">
              <div className="flex items-center justify-between">
                <span className="flex items-center gap-2 text-sm font-semibold text-ink"><Icon size={15} /> {c.title}</span>
                {n > 0 && <span className="text-2xs text-bull font-semibold">{n} added</span>}
              </div>
              <p className="text-xs text-gray-500 mt-1">{c.blurb}</p>
            </button>
          );
        })}
      </div>

      <button onClick={onDone} className="btn-primary w-full py-2.5 mt-5">
        {totalAdded > 0 ? 'Finish setup' : 'Skip for now'}
      </button>
    </div>
  );
}

/* ───────────────────────── one category ───────────────────────── */

function CategoryScreen({ def, onBack, onSaved }: {
  def: CategoryDef; onBack: () => void; onSaved: (summary: string, another: boolean) => void;
}) {
  const [values, setValues] = useState<Record<string, string>>(() => initialValues(def));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);
  const Icon = ICONS[def.id];

  const set = (k: string, v: string) => {
    setValues(s => ({ ...s, [k]: v }));
    if (errors[k]) setErrors(e => { const n = { ...e }; delete n[k]; return n; });
  };
  const fillExample = () => setValues(Object.fromEntries(def.fields.map(f => [f.key, f.kind === 'checkbox' ? '' : f.example])));

  const submit = async (another: boolean) => {
    const errs = validateAll(def, values);
    setErrors(errs); setFormError('');
    if (Object.keys(errs).length > 0) { setFormError('Please fix the highlighted fields.'); return; }
    setSaving(true);
    try {
      const summary = await save(def.id, values, setErrors);
      onSaved(summary, another);
      if (another) { setValues(initialValues(def)); setErrors({}); }
    } catch (e: unknown) {
      setFormError(messageOf(e));
    } finally { setSaving(false); }
  };

  return (
    <div>
      <button onClick={onBack} className="text-xs text-gray-500 hover:text-ink flex items-center gap-1 mb-3"><ArrowLeft size={13} /> All categories</button>
      <h2 className="text-lg font-semibold text-ink mb-3 flex items-center gap-2"><Icon size={17} /> {def.title}</h2>

      <div className="grid sm:grid-cols-2 gap-3 mb-4">
        <Panel icon={ListChecks} title="What you need" items={def.needs} />
        <Panel icon={MapPin} title="Where to find it" items={def.whereToFind} />
      </div>

      <div className="flex items-center justify-between mb-2">
        <p className="text-xs text-gray-500"><span className="text-bear">*</span> required · others are optional</p>
        <button type="button" onClick={fillExample} className="text-xs text-brand-light hover:underline flex items-center gap-1"><Sparkles size={12} /> Fill with an example</button>
      </div>

      {formError && <p className="text-xs text-bear bg-bear/10 rounded px-3 py-2 mb-3" role="alert">{formError}</p>}

      <div className="space-y-3">
        {def.fields.map(f => <Field key={f.key} def={def} f={f} value={values[f.key] ?? ''} error={errors[f.key]} onChange={v => set(f.key, v)} />)}
      </div>

      <Panel icon={Lightbulb} title="After you save" items={def.afterSave} className="mt-4" />

      <div className="flex flex-col sm:flex-row gap-2 mt-5">
        <button onClick={onBack} disabled={saving} className="btn-ghost text-sm sm:flex-1 justify-center disabled:opacity-50">Skip this category</button>
        <button onClick={() => submit(true)} disabled={saving} className="btn-ghost text-sm sm:flex-1 justify-center disabled:opacity-50">Save and add another</button>
        <button onClick={() => submit(false)} disabled={saving} className="btn-primary text-sm sm:flex-1 disabled:opacity-50">
          {saving ? <><Loader size={14} className="animate-spin" /> Saving...</> : 'Save and continue'}
        </button>
      </div>
    </div>
  );
}

function Panel({ icon: Icon, title, items, className = '' }: { icon: typeof Wallet; title: string; items: string[]; className?: string }) {
  return (
    <div className={`rounded-lg bg-surface-hover border border-surface-border p-3 ${className}`}>
      <h3 className="text-xs font-semibold text-ink flex items-center gap-1.5 mb-1.5"><Icon size={13} /> {title}</h3>
      <ul className="text-xs text-gray-500 space-y-1 list-disc list-inside">{items.map((t, i) => <li key={i}>{t}</li>)}</ul>
    </div>
  );
}

function Field({ def, f, value, error, onChange }: { def: CategoryDef; f: FieldDef; value: string; error?: string; onChange: (v: string) => void }) {
  const id = `ob-${def.id}-${f.key}`;
  const cls = `w-full bg-surface-hover border rounded-lg px-3 py-2 text-sm text-gray-200 outline-none focus:border-brand/50 ${error ? 'border-bear' : 'border-surface-border'}`;
  const otherHint = def.id === 'other' && f.key === 'category' ? OTHER_CATEGORIES.find(c => c.value === value) : undefined;
  return (
    <div>
      <label htmlFor={id} className="text-xs font-medium text-gray-300 flex items-center gap-2 mb-1">
        {f.label}
        <span className={`text-2xs ${f.required ? 'text-bear' : 'text-gray-500'}`}>{f.required ? 'required' : 'optional'}</span>
      </label>
      {f.kind === 'select' ? (
        <select id={id} value={value} onChange={e => onChange(e.target.value)} className={cls}>
          {f.options?.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      ) : f.kind === 'checkbox' ? (
        <label className="flex items-center gap-2 text-sm text-gray-400"><input id={id} type="checkbox" checked={value === 'true'} onChange={e => onChange(e.target.checked ? 'true' : '')} /> Yes</label>
      ) : (
        <input id={id} value={value} onChange={e => onChange(e.target.value)}
          type={f.kind === 'date' ? 'date' : 'text'} inputMode={f.kind === 'number' ? 'decimal' : undefined}
          placeholder={f.kind === 'date' ? undefined : `e.g. ${f.example}`} max={f.kind === 'date' && f.key !== 'maturityDate' ? todayIso() : undefined}
          aria-invalid={!!error} aria-describedby={`${id}-help`} className={cls} />
      )}
      {error
        ? <p id={`${id}-help`} className="text-xs text-bear mt-1" role="alert">{error}</p>
        : <p id={`${id}-help`} className="text-2xs text-gray-500 mt-1">{otherHint ? otherHint.hint : f.format}</p>}
    </div>
  );
}

/* ───────────────────────── saving ───────────────────────── */

type SetErrors = (e: Record<string, string>) => void;

function messageOf(e: unknown): string {
  const r = (e as { response?: { data?: { message?: string; validationErrors?: Record<string, string> } } })?.response;
  if (r?.data?.validationErrors) return Object.values(r.data.validationErrors).join(' ');
  if (r?.data?.message) return r.data.message;
  if (e instanceof Error && e.message) return e.message;
  return 'Could not save that right now. Check your connection and try again; you can also add it later from My Wealth.';
}

async function ensurePortfolioId(): Promise<number> {
  const { data } = await portfolioApi.list();
  return data.length > 0 ? data[0].id : (await portfolioApi.create('My Portfolio')).data.id;
}

const inr = (n: number) => `₹${n.toLocaleString('en-IN')}`;

class FieldProblem extends Error {}

/** Writes one category's answers and returns a one-line summary for the recap. Field-level problems
 *  found only on the server side (unknown fund or ticker) are put under the right field. */
async function save(id: CategoryId, v: Record<string, string>, setErrors: SetErrors): Promise<string> {
  const fail = (field: string, msg: string): never => { setErrors({ [field]: msg }); throw new FieldProblem('Please fix the highlighted field.'); };

  switch (id) {
    case 'mf': {
      let official: string;
      try {
        official = (await amfiNavApi.lookup(v.name.trim())).data.schemeName;
      } catch (e) {
        const status = (e as { response?: { status?: number } })?.response?.status;
        if (status === 404 || status === 400) return fail('name', `We couldn't find "${v.name.trim()}" in the official AMFI fund list. Copy the name exactly from your statement or app, including Direct/Regular and Growth/IDCW, or try a shorter name such as "HDFC Flexi Cap Direct Growth".`);
        return fail('name', 'We could not check the fund list right now. Check your connection and press Save again.');
      }
      const base = official.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 30);
      await portfolioApi.addHolding(await ensurePortfolioId(), {
        symbol: `${base}.MF`, name: official, quantity: Number(v.units), price: Number(v.avgNav),
        transactionDate: v.date || todayIso(), folio: v.folio?.trim() || undefined,
      });
      return `${official}, ${Number(v.units)} units`;
    }
    case 'stock': {
      const t = v.symbol.trim().toUpperCase();
      const sym = t.endsWith('.NS') || t.endsWith('.BO') ? t : `${t}.NS`;
      try {
        const { data: q } = await marketApi.getQuote(sym);
        if (!q || !(Number(q.currentPrice) > 0)) throw new Error('no quote');
      } catch {
        return fail('symbol', `We couldn't find "${sym}" on the exchange. Use the short NSE code, not the company name (for example INDIGO, not InterGlobe; SBIN, not SBI). Search the company on nseindia.com to see its code.`);
      }
      await portfolioApi.addHolding(await ensurePortfolioId(), {
        symbol: sym, name: sym.replace(/\.(NS|BO)$/, ''), quantity: Number(v.quantity), price: Number(v.avgPrice),
        transactionDate: v.date || todayIso(),
      });
      return `${sym.replace(/\.(NS|BO)$/, '')} × ${v.quantity}`;
    }
    case 'fd': {
      await trackingApi.addFd({
        bank: v.bank.trim(), principal: Number(v.principal), rate: Number(v.rate),
        compounding: v.compounding || 'quarterly', autoRenew: v.autoRenew === 'true',
        startDate: v.startDate, maturityDate: v.maturityDate,
      });
      return `${v.bank.trim()} FD, ${inr(Number(v.principal))} at ${v.rate}%`;
    }
    case 'rd': {
      await trackingApi.addRd({
        bank: v.bank.trim(), monthlyAmount: Number(v.monthlyAmount), rate: Number(v.rate),
        startDate: v.startDate, tenureMonths: Number(v.tenureMonths),
      });
      return `${v.bank.trim()} RD, ${inr(Number(v.monthlyAmount))}/month for ${v.tenureMonths} months`;
    }
    case 'other': {
      if (v.category === 'savings') {
        await ledgerApi.addAccount({ name: v.name.trim(), bank: v.name.trim(), accountType: 'SAVINGS', balance: Number(v.value) });
      } else {
        await trackingApi.addOther({ name: v.name.trim(), category: v.category, value: Number(v.value), asOf: v.asOf || undefined });
      }
      return `${v.name.trim()}, ${inr(Number(v.value))}`;
    }
  }
}
