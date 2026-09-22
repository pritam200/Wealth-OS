import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  ChevronLeft, ChevronRight, ShoppingCart, Fuel, Plane, Home as HomeIcon,
  ChevronDown, ChevronUp, ArrowRightLeft, Pencil, Check, X, PiggyBank, ClipboardList,
  Wallet, Target, TrendingDown, Sparkles, HeartPulse, Sofa, Receipt,
} from 'lucide-react';
import {
  plannerApi, sinkingFundApi, moveExpenseToCategory,
} from '../../api/planner';
import type {
  MonthlyPlanResponse, CategoryPlanLine, PlanTransaction, GroupSummary,
  SinkingFundResponse, SinkingFundLedgerResponse, ReflectionResponse, PlanStatus,
} from '../../api/planner';
import { useMaskedText } from '../../components/shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

const STATUS_LABEL: Record<PlanStatus, string> = {
  WITHIN_PLAN: 'Within Plan', NEAR_LIMIT: 'Near Limit', LIMIT_REACHED: 'Limit Reached', OVER_LIMIT: 'Over Limit',
};
// On the gradient hero the pill sits on a dark violet field, so these are white-on-translucent
// rather than the app's usual tinted-on-white pills.
const STATUS_TONE_ON_HERO: Record<PlanStatus, string> = {
  WITHIN_PLAN: 'bg-white/20 text-white border-white/40',
  NEAR_LIMIT: 'bg-gold-vivid/30 text-white border-gold-vivid/60',
  LIMIT_REACHED: 'bg-orange-400/35 text-white border-orange-200/60',
  OVER_LIMIT: 'bg-bear-vivid/40 text-white border-white/50',
};

/* ── Per-group colour identity ───────────────────────────────────────────────────────────
   Each month-end group from the booklet gets its own hue, carried through the section
   heading, every category box's edge strip, icon badge and progress bar. Colour here is
   navigational (which section am I in), never the only carrier of meaning — over-budget
   state is always ALSO shown as a number and a label. */
interface Tone { badge: string; bar: string; text: string; soft: string; edge: string; Icon: any }

const TONES: Record<string, Tone> = {
  indigo:  { badge: 'bg-brand-gradient',  bar: 'bg-brand-gradient',  text: 'text-brand-light', soft: 'bg-brand/10',        edge: '#6366F1', Icon: HomeIcon },
  emerald: { badge: 'bg-bull-gradient',   bar: 'bg-bull-gradient',   text: 'text-bull',        soft: 'bg-bull/10',         edge: '#10B981', Icon: ShoppingCart },
  cyan:    { badge: 'bg-accent-gradient', bar: 'bg-accent-gradient', text: 'text-accent',      soft: 'bg-accent/10',       edge: '#06B6D4', Icon: Fuel },
  violet:  { badge: 'bg-violet-gradient', bar: 'bg-violet-gradient', text: 'text-brand-pink',  soft: 'bg-brand-pink/10',   edge: '#A855F7', Icon: Sofa },
  amber:   { badge: 'bg-gold-gradient',   bar: 'bg-gold-gradient',   text: 'text-gold',        soft: 'bg-gold/10',         edge: '#F59E0B', Icon: HeartPulse },
  // Savings sections deliberately avoid the bear/rose family: a travel fund sitting at 100%
  // of its planned contribution is good news, and red would read as an overspend alarm.
  teal:    { badge: 'bg-teal-gradient',   bar: 'bg-teal-gradient',   text: 'text-teal-700',    soft: 'bg-teal-500/10',     edge: '#14B8A6', Icon: Plane },
  rose:    { badge: 'bg-bear-gradient',   bar: 'bg-bear-gradient',   text: 'text-bear',        soft: 'bg-bear/10',         edge: '#F43F5E', Icon: Receipt },
};

const GROUP_TONE: Record<string, string> = {
  'Housing & Bills': 'indigo',
  'Food & Household': 'emerald',
  'Transport': 'cyan',
  'Lifestyle & Personal': 'violet',
  'Health & Maintenance/Misc': 'amber',
  'Travel Funds (Savings)': 'teal',
};
const TONE_ORDER = ['indigo', 'emerald', 'cyan', 'violet', 'amber', 'teal', 'rose'];

/** Groups the booklet doesn't name still get a stable colour, derived from the name so it
 *  doesn't shuffle between renders. */
function toneFor(groupName: string, fallbackIndex: number): Tone {
  const named = GROUP_TONE[groupName];
  if (named) return TONES[named];
  return TONES[TONE_ORDER[fallbackIndex % TONE_ORDER.length]];
}

export function Tab17FinancialPlanner() {
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);

  const goPrev = () => { if (month === 1) { setMonth(12); setYear(y => y - 1); } else setMonth(m => m - 1); };
  const goNext = () => { if (month === 12) { setMonth(1); setYear(y => y + 1); } else setMonth(m => m + 1); };

  return (
    <div className="space-y-6 animate-fade-rise">
      <PlanHeaderSection year={year} month={month} monthLabel={`${MONTH_NAMES[month - 1]} ${year}`}
        onPrev={goPrev} onNext={goNext} />
      <CategoryBoxGrid year={year} month={month} />
      <TravelFundTracker />
      <MonthEndReview year={year} month={month} />
    </div>
  );
}

/* ── Section 1: Monthly Plan hero ── */
function PlanHeaderSection({ year, month, monthLabel, onPrev, onNext }: {
  year: number; month: number; monthLabel: string; onPrev: () => void; onNext: () => void;
}) {
  const maskText = useMaskedText();
  const [plan, setPlan] = useState<MonthlyPlanResponse | null>(null);
  const [editingLimit, setEditingLimit] = useState(false);
  const [limitInput, setLimitInput] = useState('');

  const load = useCallback(async () => {
    try { setPlan((await plannerApi.getPlan(year, month)).data); } catch { /* ignore */ }
  }, [year, month]);

  useEffect(() => { load(); }, [load]);

  const saveLimit = async () => {
    const v = parseFloat(limitInput);
    if (!Number.isNaN(v)) {
      await plannerApi.updateSettings(v);
      setEditingLimit(false);
      load();
    }
  };

  const spentPct = plan && plan.monthlyLimit > 0
    ? Math.min(100, Math.round((plan.actualTotal / plan.monthlyLimit) * 100)) : 0;
  const plannedPct = plan && plan.monthlyLimit > 0
    ? Math.min(100, Math.round((plan.plannedTotal / plan.monthlyLimit) * 100)) : 0;
  const over = plan ? plan.actualTotal > plan.monthlyLimit : false;

  return (
    <div className="relative overflow-hidden rounded-3xl bg-brand-gradient text-white shadow-panel">
      {/* Decorative blooms — purely ornamental, no data encoded here. */}
      <div className="pointer-events-none absolute -top-24 -right-16 w-80 h-80 rounded-full bg-white/10 blur-2xl" />
      <div className="pointer-events-none absolute -bottom-28 left-10 w-72 h-72 rounded-full bg-cyan-300/20 blur-2xl" />

      <div className="relative p-6">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-2xl bg-white/20 border border-white/30 backdrop-blur flex items-center justify-center shrink-0">
              <ClipboardList size={22} className="text-white" />
            </div>
            <div>
              <h2 className="text-2xl font-extrabold tracking-tight leading-tight">Household Financial Plan</h2>
              <p className="text-white/75 text-xs mt-0.5">
                Your monthly budget booklet, live — planned vs actual, section by section
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {plan && (
              <span className={`text-xs font-bold px-3 py-1.5 rounded-full border backdrop-blur ${STATUS_TONE_ON_HERO[plan.status]}`}>
                {STATUS_LABEL[plan.status]}
              </span>
            )}
            <div className="flex items-center gap-1 rounded-full bg-white/20 border border-white/25 backdrop-blur px-1.5 py-1">
              <button onClick={onPrev} className="p-1.5 rounded-full text-white/80 hover:text-white hover:bg-white/20 transition-colors">
                <ChevronLeft size={16} />
              </button>
              <span className="text-sm font-bold w-32 text-center tracking-tight">{monthLabel}</span>
              <button onClick={onNext} className="p-1.5 rounded-full text-white/80 hover:text-white hover:bg-white/20 transition-colors">
                <ChevronRight size={16} />
              </button>
            </div>
          </div>
        </div>

        {!plan ? (
          <div className="mt-6 h-24 rounded-2xl bg-white/10 animate-pulse" />
        ) : (
          <>
            <div className="mt-6 grid grid-cols-2 lg:grid-cols-4 gap-3">
              <HeroMetric Icon={Target} label="Absolute Monthly Limit" value={maskText(fmtINR(plan.monthlyLimit))}
                onEdit={() => { setLimitInput(String(plan.monthlyLimit)); setEditingLimit(true); }} />
              <HeroMetric Icon={ClipboardList} label="Total Planned" value={maskText(fmtINR(plan.plannedTotal))} />
              <HeroMetric Icon={Receipt} label="Actual Spent" value={maskText(fmtINR(plan.actualTotal))}
                emphasis={over ? 'warn' : undefined} />
              <HeroMetric Icon={plan.remaining < 0 ? TrendingDown : Wallet} label="Remaining vs Limit"
                value={maskText(fmtINR(plan.remaining))} emphasis={plan.remaining < 0 ? 'warn' : 'good'} />
            </div>

            {/* Actual against the absolute limit, with a tick marking where the plan sits. */}
            <div className="mt-5">
              <div className="relative h-2.5 rounded-full bg-white/20 overflow-hidden">
                <div className={`h-full rounded-full transition-all duration-700 ease-snap ${over ? 'bg-bear-vivid' : 'bg-white'}`}
                  style={{ width: `${spentPct}%` }} />
              </div>
              <div className="relative h-4 mt-1">
                {/* Clamped to 8–88% so the centred label can't hang off either end of the
                    bar when the plan sits at 0% or at the full limit. */}
                <div className="absolute -translate-x-1/2 text-2xs text-white/70 whitespace-nowrap"
                  style={{ left: `${Math.min(88, Math.max(8, plannedPct))}%` }}>
                  ↑ plan {plannedPct}%
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-2xs text-white/80 mt-1">
                <span>{spentPct}% of limit used</span>
                <span className="inline-flex items-center gap-1">
                  <Sparkles size={11} /> Buffer {maskText(fmtINR(plan.buffer))}
                </span>
                {plan.biggestExpenseCategory && (
                  <span>Biggest so far: <span className="font-semibold text-white">{plan.biggestExpenseCategory}</span></span>
                )}
              </div>
            </div>
          </>
        )}

        {editingLimit && (
          <div className="mt-4 flex items-center gap-2 rounded-xl bg-white/20 border border-white/25 backdrop-blur p-2.5">
            <span className="text-xs text-white/80">New monthly limit ₹</span>
            <input autoFocus type="number" value={limitInput} onChange={e => setLimitInput(e.target.value)}
              className="w-32 text-sm rounded-lg bg-white/90 text-ink px-2.5 py-1.5 outline-none" />
            <button className="p-1.5 rounded-lg bg-white text-brand-light hover:bg-white/90 transition-colors" onClick={saveLimit}>
              <Check size={14} />
            </button>
            <button className="p-1.5 rounded-lg text-white/80 hover:bg-white/20 transition-colors" onClick={() => setEditingLimit(false)}>
              <X size={14} />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

function HeroMetric({ Icon, label, value, emphasis, onEdit }: {
  Icon: any; label: string; value: string; emphasis?: 'good' | 'warn'; onEdit?: () => void;
}) {
  return (
    <div className="rounded-2xl bg-white/10 border border-white/20 backdrop-blur p-3 transition-colors hover:bg-white/20">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5 text-2xs uppercase tracking-wider text-white/75 font-semibold">
          <Icon size={12} /> {label}
        </div>
        {onEdit && (
          <button onClick={onEdit} title="Edit monthly limit"
            className="p-0.5 rounded text-white/70 hover:text-white hover:bg-white/20 transition-colors">
            <Pencil size={11} />
          </button>
        )}
      </div>
      <div className={`font-mono font-extrabold text-xl mt-1 tabular-nums ${
        emphasis === 'warn' ? 'text-rose-100' : emphasis === 'good' ? 'text-emerald-100' : 'text-white'}`}>
        {value}
      </div>
    </div>
  );
}

/* ── Section 2: PDF-style category boxes, grouped and colour-coded ── */
function CategoryBoxGrid({ year, month }: { year: number; month: number }) {
  const maskText = useMaskedText();
  const [plan, setPlan] = useState<MonthlyPlanResponse | null>(null);
  const [expandedKey, setExpandedKey] = useState<string | null>(null);

  const load = useCallback(async () => {
    try { setPlan((await plannerApi.getPlan(year, month)).data); } catch { /* ignore */ }
  }, [year, month]);

  useEffect(() => { load(); }, [load]);

  const grouped = useMemo(() => {
    if (!plan) return [];
    const byGroup = new Map<string, CategoryPlanLine[]>();
    for (const c of plan.categories) {
      const g = c.groupName || 'Ungrouped';
      if (!byGroup.has(g)) byGroup.set(g, []);
      byGroup.get(g)!.push(c);
    }
    return Array.from(byGroup.entries());
  }, [plan]);

  if (!plan) return null;

  return (
    <div className="space-y-5">
      {grouped.map(([groupName, cats], gi) => {
        const tone = toneFor(groupName, gi);
        const planned = cats.reduce((s, c) => s + c.planned, 0);
        const actual = cats.reduce((s, c) => s + c.actual, 0);
        return (
          <div key={groupName}>
            <div className="flex items-center gap-2.5 mb-2.5">
              <div className={`icon-badge icon-badge-sm ${tone.badge}`}>
                <tone.Icon size={12} />
              </div>
              <h3 className="text-sm font-bold text-ink tracking-tight">{groupName}</h3>
              <div className="h-px flex-1" style={{ background: `linear-gradient(90deg, ${tone.edge}55, transparent)` }} />
              <span className={`text-2xs font-semibold px-2 py-0.5 rounded-full ${tone.soft} ${tone.text}`}>
                {maskText(fmtINR(actual))} / {maskText(fmtINR(planned))}
              </span>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              {cats.map(c => (
                <CategoryBox
                  key={c.key} line={c} tone={tone}
                  expanded={expandedKey === c.key}
                  onToggle={() => setExpandedKey(expandedKey === c.key ? null : c.key)}
                  allCategories={plan.categories}
                  onChanged={load}
                />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function CategoryBox({ line, tone, expanded, onToggle, allCategories, onChanged }: {
  line: CategoryPlanLine; tone: Tone; expanded: boolean; onToggle: () => void;
  allCategories: CategoryPlanLine[]; onChanged: () => void;
}) {
  const maskText = useMaskedText();
  const pct = line.planned > 0 ? Math.min(100, Math.round((line.actual / line.planned) * 100)) : 0;
  // "Over" is an overspend warning, which only makes sense for consumption categories —
  // contributing more than planned to a savings fund is not a problem to flag in red.
  const over = !line.linkedToSinkingFund && line.actual > line.planned;
  const nearLimit = !line.linkedToSinkingFund && pct >= 90;

  return (
    <div className="card p-0 overflow-hidden">
      {/* Group-coloured edge strip: the box's section identity at a glance. */}
      <div className="h-1 w-full" style={{ background: `linear-gradient(90deg, ${tone.edge}, ${tone.edge}33)` }} />
      <div className="p-4">
        <div className="flex items-start justify-between gap-2 mb-2.5">
          <div className="flex items-center gap-2 min-w-0">
            <div className={`icon-badge ${over ? 'bg-bear-gradient' : tone.badge}`}>
              <tone.Icon size={14} />
            </div>
            <div className="min-w-0">
              <div className="font-bold text-sm text-ink truncate">{line.name}</div>
              <div className="text-2xs text-gray-500">
                {line.linkedToSinkingFund
                  ? 'Annual savings fund'
                  : `${line.transactions.length} transaction${line.transactions.length === 1 ? '' : 's'}`}
              </div>
            </div>
          </div>
          <div className="flex items-center gap-1 shrink-0">
            <span className={`text-2xs font-bold px-1.5 py-0.5 rounded-md ${
              over ? 'bg-bear/10 text-bear' : nearLimit ? 'bg-gold/10 text-gold' : `${tone.soft} ${tone.text}`}`}>
              {pct}%
            </span>
            {!line.linkedToSinkingFund && line.transactions.length > 0 && (
              <button className="btn-icon p-1" onClick={onToggle} title={expanded ? 'Hide transactions' : 'Show transactions'}>
                {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
              </button>
            )}
          </div>
        </div>

        <div className="meter mb-3">
          <div className={`meter-fill ${over ? 'bg-bear-gradient' : tone.bar}`} style={{ width: `${pct}%` }} />
        </div>

        <div className="grid grid-cols-3 gap-2">
          <MiniStat label="Planned" value={maskText(fmtINR(line.planned))} />
          <MiniStat label="Actual" value={maskText(fmtINR(line.actual))} tone={over ? 'bear' : undefined} />
          <MiniStat label="Remaining" value={maskText(fmtINR(line.remaining))} tone={line.remaining < 0 ? 'bear' : 'bull'} />
        </div>

        {line.linkedToSinkingFund && (
          <div className="mt-2.5 text-2xs text-gray-500 flex items-center gap-1">
            <PiggyBank size={11} className="text-brand-pink" /> Tracked in the Annual Travel Fund Tracker below
          </div>
        )}

        {expanded && !line.linkedToSinkingFund && (
          <div className="mt-3 pt-2.5 border-t border-surface-border space-y-1 max-h-56 overflow-y-auto">
            {line.transactions.map(t => (
              <TransactionRow key={t.expenseId} txn={t} currentKey={line.key} allCategories={allCategories} onChanged={onChanged} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function MiniStat({ label, value, tone }: { label: string; value: string; tone?: 'bull' | 'bear' }) {
  return (
    <div className="rounded-lg bg-surface-hover/70 px-2 py-1.5">
      <div className="text-2xs text-gray-600">{label}</div>
      <div className={`font-mono text-xs font-bold tabular-nums truncate ${
        tone === 'bear' ? 'text-bear' : tone === 'bull' ? 'text-bull' : 'text-ink'}`}>
        {value}
      </div>
    </div>
  );
}

function TransactionRow({ txn, currentKey, allCategories, onChanged }: {
  txn: PlanTransaction; currentKey: string; allCategories: CategoryPlanLine[]; onChanged: () => void;
}) {
  const maskText = useMaskedText();
  const [moving, setMoving] = useState(false);

  const move = async (newKey: string) => {
    if (newKey === currentKey) { setMoving(false); return; }
    await moveExpenseToCategory(txn.expenseId, newKey);
    setMoving(false);
    onChanged();
  };

  return (
    <div className="flex items-center justify-between gap-2 text-2xs rounded-lg px-1.5 py-1 hover:bg-surface-hover transition-colors">
      <div className="min-w-0 flex-1">
        <div className="text-ink font-medium truncate">{txn.merchant || txn.description || 'Transaction'}</div>
        <div className="text-gray-600 flex items-center gap-1.5 flex-wrap">
          <span>{txn.date}</span>
          {txn.overridden && (
            <span className="px-1.5 py-0.5 rounded-full bg-gold/10 text-gold border border-gold/30 font-semibold">
              moved from {txn.aiCategoryKey}
            </span>
          )}
        </div>
      </div>
      <div className="font-mono font-bold text-ink shrink-0 tabular-nums">{maskText(fmtINR(txn.amount))}</div>
      {moving ? (
        <select autoFocus className="input-field text-2xs py-0.5 w-36" defaultValue={currentKey}
          onChange={e => move(e.target.value)} onBlur={() => setMoving(false)}>
          {allCategories.map(c => <option key={c.key} value={c.key}>{c.name}</option>)}
        </select>
      ) : (
        <button className="btn-icon p-1 shrink-0" title="Move to a different section" onClick={() => setMoving(true)}>
          <ArrowRightLeft size={12} />
        </button>
      )}
    </div>
  );
}

/* ── Section 3: Annual Travel Fund Tracker ── */
function TravelFundTracker() {
  const maskText = useMaskedText();
  const [funds, setFunds] = useState<SinkingFundResponse[]>([]);
  const [ledgers, setLedgers] = useState<Record<number, SinkingFundLedgerResponse>>({});
  const [expandedFund, setExpandedFund] = useState<number | null>(null);
  const year = new Date().getFullYear();

  const load = useCallback(async () => {
    try {
      const list = (await sinkingFundApi.list()).data;
      setFunds(list);
      const entries = await Promise.all(list.map(f => sinkingFundApi.ledger(f.id, year)));
      const map: Record<number, SinkingFundLedgerResponse> = {};
      list.forEach((f, i) => { map[f.id] = entries[i].data; });
      setLedgers(map);
    } catch { /* ignore */ }
  }, [year]);

  useEffect(() => { load(); }, [load]);

  const currentYm = new Date().toISOString().slice(0, 7);

  const saveMonth = async (fundId: number, yearMonth: string, added: string, used: string) => {
    await sinkingFundApi.upsertLedgerEntry(fundId, yearMonth, added ? parseFloat(added) : undefined, used ? parseFloat(used) : undefined);
    load();
  };

  if (funds.length === 0) return null;

  return (
    <div className="card-elevated">
      <div className="flex items-center gap-2.5 mb-4">
        <div className="icon-badge icon-badge-violet"><PiggyBank size={15} /></div>
        <div>
          <h3 className="font-bold text-sm text-ink">Annual Travel Fund Tracker</h3>
          <p className="text-2xs text-gray-500">Multi-month ledger for dedicated annual savings — not monthly consumption spend</p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {funds.map((fund, i) => {
          const ledger = ledgers[fund.id];
          const currentRow = ledger?.rows.find(r => r.yearMonth === currentYm);
          const expanded = expandedFund === fund.id;
          const tone = TONES[i % 2 === 0 ? 'violet' : 'cyan'];
          const target = fund.annualTarget || 0;
          const pct = target > 0 && ledger ? Math.min(100, Math.round((ledger.endingBalance / target) * 100)) : 0;

          return (
            <div key={fund.id} className="rounded-2xl border overflow-hidden" style={{ borderColor: `${tone.edge}40` }}>
              <div className="h-1 w-full" style={{ background: `linear-gradient(90deg, ${tone.edge}, ${tone.edge}33)` }} />
              <div className="p-3.5" style={{ background: `linear-gradient(180deg, ${tone.edge}0D, transparent 60%)` }}>
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center gap-2 min-w-0">
                    <div className={`icon-badge icon-badge-sm ${tone.badge}`}><Plane size={12} /></div>
                    <div className="min-w-0">
                      <div className="font-bold text-sm text-ink truncate">{fund.name}</div>
                      <div className="text-2xs text-gray-600">
                        {maskText(fmtINR(fund.monthlyPlanned))}/mo · target {maskText(fmtINR(target))}
                      </div>
                    </div>
                  </div>
                  <button className="btn-icon p-1 shrink-0" onClick={() => setExpandedFund(expanded ? null : fund.id)}
                    title={expanded ? 'Hide 12-month ledger' : 'Show 12-month ledger'}>
                    {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                  </button>
                </div>

                {ledger && (
                  <>
                    <div className="flex items-end justify-between mt-3 mb-1.5">
                      <div>
                        <div className="text-2xs text-gray-600 uppercase tracking-wider font-semibold">Balance so far</div>
                        <div className="font-mono text-xl font-extrabold text-ink tabular-nums">
                          {maskText(fmtINR(ledger.endingBalance))}
                        </div>
                      </div>
                      <span className={`text-2xs font-bold px-2 py-0.5 rounded-full ${tone.soft} ${tone.text}`}>
                        {pct}% of target
                      </span>
                    </div>
                    <div className="meter">
                      <div className={`meter-fill ${tone.bar}`} style={{ width: `${pct}%` }} />
                    </div>
                  </>
                )}

                {expanded && ledger && (
                  <div className="mt-3 max-h-64 overflow-y-auto rounded-xl bg-surface-card border border-surface-border">
                    <table className="w-full text-2xs">
                      <thead className="sticky top-0 bg-surface-card">
                        <tr className="text-gray-600">
                          <th className="text-left py-1.5 px-2 font-semibold uppercase tracking-wider">Month</th>
                          <th className="text-right px-1 font-semibold uppercase tracking-wider">Planned</th>
                          <th className="text-right px-1 font-semibold uppercase tracking-wider">Added</th>
                          <th className="text-right px-1 font-semibold uppercase tracking-wider">Used</th>
                          <th className="text-right px-2 font-semibold uppercase tracking-wider">Balance</th>
                          <th />
                        </tr>
                      </thead>
                      <tbody>
                        {ledger.rows.map(row => (
                          <FundMonthRow key={row.yearMonth} fundId={fund.id} row={row}
                            editable={row.yearMonth === currentYm} onSave={saveMonth} maskText={maskText} />
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {!expanded && currentRow && (
                  <div className="mt-2 text-2xs text-gray-600">
                    This month added: <span className="font-mono font-bold text-ink">{maskText(fmtINR(currentRow.added))}</span>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function FundMonthRow({ fundId, row, editable, onSave, maskText }: {
  fundId: number; row: { yearMonth: string; planned: number; added: number; used: number; balance: number };
  editable: boolean; onSave: (fundId: number, ym: string, added: string, used: string) => void; maskText: (s: string) => string;
}) {
  const [editing, setEditing] = useState(false);
  const [added, setAdded] = useState(String(row.added));
  const [used, setUsed] = useState(String(row.used));

  return (
    <tr className={`border-t border-surface-border/70 ${editable ? 'bg-brand/5' : ''}`}>
      <td className={`py-1.5 px-2 ${editable ? 'font-bold text-brand-light' : 'text-gray-400'}`}>{row.yearMonth}</td>
      <td className="text-right px-1 font-mono text-gray-500">{maskText(fmtINR(row.planned))}</td>
      <td className="text-right px-1 font-mono">
        {editing ? <input className="input-field w-16 text-2xs py-0.5 text-right" value={added} onChange={e => setAdded(e.target.value)} /> : maskText(fmtINR(row.added))}
      </td>
      <td className="text-right px-1 font-mono">
        {editing ? <input className="input-field w-16 text-2xs py-0.5 text-right" value={used} onChange={e => setUsed(e.target.value)} /> : maskText(fmtINR(row.used))}
      </td>
      <td className="text-right px-2 font-mono font-bold text-ink">{maskText(fmtINR(row.balance))}</td>
      <td className="pr-1 w-6">
        {editable && (editing ? (
          <button aria-label="Confirm" className="btn-icon p-0.5" onClick={() => { setEditing(false); onSave(fundId, row.yearMonth, added, used); }}><Check size={11} /></button>
        ) : (
          <button className="btn-icon p-0.5" onClick={() => setEditing(true)}><Pencil size={11} /></button>
        ))}
      </td>
    </tr>
  );
}

/* ── Section 4: Month-End Review ── */
const FINAL_STATUS_LABEL: Record<string, string> = { UNDER: 'Under Limit', EXACT: 'Exactly at Limit', OVER: 'Over Limit' };
const FINAL_STATUS_TONE: Record<string, string> = {
  UNDER: 'bg-bull-gradient shadow-glow-bull',
  EXACT: 'bg-brand-gradient shadow-glow',
  OVER: 'bg-bear-gradient shadow-glow-bear',
};

function MonthEndReview({ year, month }: { year: number; month: number }) {
  const maskText = useMaskedText();
  const [plan, setPlan] = useState<MonthlyPlanResponse | null>(null);
  const [reflection, setReflection] = useState<ReflectionResponse | null>(null);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({ biggestExpenseNote: '', overspendNote: '', underspendNote: '', oneOffNote: '', adjustmentsNote: '', finalStatus: '' });

  const load = useCallback(async () => {
    try {
      const [planRes, reflRes] = await Promise.all([plannerApi.getPlan(year, month), plannerApi.getReflection(year, month)]);
      setPlan(planRes.data);
      setReflection(reflRes.data);
      setForm({
        biggestExpenseNote: reflRes.data.biggestExpenseNote || '',
        overspendNote: reflRes.data.overspendNote || '',
        underspendNote: reflRes.data.underspendNote || '',
        oneOffNote: reflRes.data.oneOffNote || '',
        adjustmentsNote: reflRes.data.adjustmentsNote || '',
        finalStatus: reflRes.data.finalStatus || reflRes.data.suggestedStatus,
      });
    } catch { /* ignore */ }
  }, [year, month]);

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    setSaving(true);
    try { await plannerApi.updateReflection(year, month, form); await load(); } finally { setSaving(false); }
  };

  if (!plan || !reflection) return null;

  const totalDiff = plan.monthlyLimit - plan.actualTotal;

  return (
    <div className="card-elevated">
      <div className="flex items-center gap-2.5 mb-4">
        <div className="icon-badge icon-badge-brand"><Target size={15} /></div>
        <div>
          <h3 className="font-bold text-sm text-ink">Month-End Review</h3>
          <p className="text-2xs text-gray-500">Group rollup, reflection notes and the month's final verdict</p>
        </div>
      </div>

      <div className="mb-5 overflow-x-auto rounded-xl border border-surface-border">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left bg-surface-hover/80">
              <th className="py-2 px-3 text-2xs uppercase tracking-wider font-semibold text-gray-600">Category Group</th>
              <th className="py-2 px-3 text-right text-2xs uppercase tracking-wider font-semibold text-gray-600">Budget</th>
              <th className="py-2 px-3 text-right text-2xs uppercase tracking-wider font-semibold text-gray-600">Actual</th>
              <th className="py-2 px-3 text-right text-2xs uppercase tracking-wider font-semibold text-gray-600">Difference</th>
            </tr>
          </thead>
          <tbody>
            {plan.groupSummaries.map((g: GroupSummary, gi: number) => {
              const tone = toneFor(g.groupName, gi);
              return (
                <tr key={g.groupName} className="border-t border-surface-border/70 hover:bg-surface-hover/60 transition-colors">
                  <td className="py-2 px-3">
                    <span className="inline-flex items-center gap-2">
                      <span className="w-1.5 h-1.5 rounded-full" style={{ background: tone.edge }} />
                      <span className="font-medium text-ink">{g.groupName}</span>
                    </span>
                  </td>
                  <td className="py-2 px-3 text-right font-mono text-gray-400">{maskText(fmtINR(g.planned))}</td>
                  <td className="py-2 px-3 text-right font-mono font-semibold text-ink">{maskText(fmtINR(g.actual))}</td>
                  <td className={`py-2 px-3 text-right font-mono font-semibold ${g.difference < 0 ? 'text-bear' : 'text-bull'}`}>
                    {maskText(fmtINR(g.difference))}
                  </td>
                </tr>
              );
            })}
            <tr className="border-t-2 font-bold bg-brand/5" style={{ borderColor: 'rgba(99,102,241,0.3)' }}>
              <td className="py-2.5 px-3 text-ink">Total Monthly Limit</td>
              <td className="py-2.5 px-3 text-right font-mono text-ink">{maskText(fmtINR(plan.monthlyLimit))}</td>
              <td className="py-2.5 px-3 text-right font-mono text-ink">{maskText(fmtINR(plan.actualTotal))}</td>
              <td className={`py-2.5 px-3 text-right font-mono ${totalDiff < 0 ? 'text-bear' : 'text-bull'}`}>
                {maskText(fmtINR(totalDiff))}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <h4 className="section-title">Monthly Reflection &amp; Insights</h4>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-2.5">
        <ReflectionField label="Biggest Expense Item / Category" value={form.biggestExpenseNote} placeholder={plan.biggestExpenseCategory || ''}
          onChange={v => setForm(f => ({ ...f, biggestExpenseNote: v }))} />
        <ReflectionField label="Where did we overspend this month?" value={form.overspendNote} onChange={v => setForm(f => ({ ...f, overspendNote: v }))} />
        <ReflectionField label="Where did we save or stay well under budget?" value={form.underspendNote} onChange={v => setForm(f => ({ ...f, underspendNote: v }))} />
        <ReflectionField label="Unexpected / One-off Expenses" value={form.oneOffNote} onChange={v => setForm(f => ({ ...f, oneOffNote: v }))} />
        <div className="lg:col-span-2">
          <ReflectionField label="What adjustments should we make next month?" value={form.adjustmentsNote} onChange={v => setForm(f => ({ ...f, adjustmentsNote: v }))} />
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-xs font-bold text-ink mr-1">Final Status:</span>
          {(['UNDER', 'EXACT', 'OVER'] as const).map(s => {
            const active = form.finalStatus === s;
            return (
              <button key={s} type="button" onClick={() => setForm(f => ({ ...f, finalStatus: s }))}
                className={`text-xs font-semibold px-3 py-1.5 rounded-full border transition-all duration-200 ease-snap ${
                  active
                    ? `text-white border-transparent ${FINAL_STATUS_TONE[s]}`
                    : 'bg-surface-hover text-gray-400 border-surface-border hover:text-brand-light hover:border-brand/40'}`}>
                {FINAL_STATUS_LABEL[s]}
              </button>
            );
          })}
          {reflection.suggestedStatus && (
            <span className="text-2xs text-gray-600">
              (calculated: <span className="font-semibold text-ink">{FINAL_STATUS_LABEL[reflection.suggestedStatus]}</span>)
            </span>
          )}
        </div>
        <button className="btn-primary px-4 py-2 text-xs" onClick={save} disabled={saving}>
          {saving ? 'Saving…' : 'Save Review'}
        </button>
      </div>
    </div>
  );
}

function ReflectionField({ label, value, placeholder, onChange }: { label: string; value: string; placeholder?: string; onChange: (v: string) => void }) {
  return (
    <div className="card-flat p-3 h-full">
      <div className="text-2xs font-bold text-ink mb-1.5">{label}</div>
      <textarea className="input-field w-full text-xs bg-surface-card" rows={2} value={value} placeholder={placeholder}
        onChange={e => onChange(e.target.value)} />
    </div>
  );
}
