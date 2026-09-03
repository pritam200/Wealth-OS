import { useState, useEffect, useCallback } from 'react';
import {
  CreditCard as CardIcon, Plus, Trash2, Sparkles, Gift,
  TrendingUp, Wand2, ChevronDown, ChevronUp, Search, AlertTriangle, Ban,
} from 'lucide-react';
import { cardApi } from '../../api/card';
import type { CardResponse, CatalogEntry, RecommendResult, PointsTip } from '../../api/card';
import { useMaskedText } from '../shared/Amount';

const fmtINR = (n: number) =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(n || 0);

const ISSUER_GRAD: Record<string, string> = {
  HDFC: 'from-blue-600 to-blue-900',
  SBI: 'from-purple-600 to-indigo-900',
  ICICI: 'from-orange-500 to-red-800',
  Axis: 'from-pink-600 to-rose-900',
  Amex: 'from-teal-600 to-emerald-900',
  Kotak: 'from-red-500 to-red-900',
  'IDFC First': 'from-blue-500 to-indigo-800',
  'AU Bank': 'from-amber-500 to-orange-800',
  'Standard Chartered': 'from-emerald-600 to-green-900',
  'Yes Bank': 'from-blue-600 to-cyan-900',
  IndusInd: 'from-purple-500 to-violet-900',
  RBL: 'from-orange-600 to-amber-900',
  'Federal Bank': 'from-blue-700 to-slate-900',
  HSBC: 'from-red-600 to-red-900',
  Scapia: 'from-violet-500 to-purple-900',
  OneCard: 'from-gray-600 to-gray-900',
  Uni: 'from-green-500 to-teal-800',
  Kiwi: 'from-lime-500 to-green-800',
  Slice: 'from-yellow-500 to-orange-700',
  CRED: 'from-neutral-600 to-neutral-900',
  PNB: 'from-blue-500 to-blue-900',
  'Bank of Baroda': 'from-orange-600 to-red-900',
};

const ISSUERS = [
  'HDFC', 'SBI', 'ICICI', 'Axis', 'Amex', 'Kotak', 'IDFC First',
  'AU Bank', 'Standard Chartered', 'Yes Bank', 'IndusInd', 'RBL',
  'Federal Bank', 'HSBC', 'Scapia', 'OneCard', 'Uni', 'Kiwi',
  'Slice', 'CRED', 'PNB', 'Bank of Baroda', 'Citi', 'Other',
];

export function CreditCardSection() {
  const [cards, setCards]     = useState<CardResponse[]>([]);
  const [catalog, setCatalog] = useState<CatalogEntry[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [tips, setTips]       = useState<PointsTip[]>([]);
  const [showAdd, setShowAdd] = useState(false);

  const load = useCallback(async () => {
    const [c, cat, cats, t] = await Promise.all([
      cardApi.list().catch(e => { console.error('Cards list failed:', e); return { data: [] as CardResponse[] }; }),
      cardApi.catalog().catch(e => { console.error('Card catalog failed:', e); return { data: [] as CatalogEntry[] }; }),
      cardApi.categories().catch(e => { console.error('Card categories failed:', e); return { data: [] as string[] }; }),
      cardApi.pointsTips().catch(e => { console.error('Points tips failed:', e); return { data: [] as PointsTip[] }; }),
    ]);
    setCards(c.data); setCatalog(cat.data); setCategories(cats.data); setTips(t.data);
  }, []);
  useEffect(() => { load(); }, [load]);

  return (
    <div className="space-y-4">
      {/* ── Smart Spend Advisor (always on) ── */}
      <SpendAdvisor categories={categories} hasCards={cards.length > 0} />

      {/* ── My Cards ── */}
      <div className="card">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <CardIcon size={14} className="text-brand" />
            <h3 className="font-semibold text-white text-sm">My Cards</h3>
            {cards.length > 0 && <span className="text-2xs bg-brand/20 text-brand px-1.5 py-0.5 rounded-full">{cards.length}</span>}
          </div>
          <button onClick={() => setShowAdd(s => !s)} className="btn-ghost text-xs flex items-center gap-1">
            <Plus size={11} /> Add Card
          </button>
        </div>

        {showAdd && (
          <AddCardForm catalog={catalog} categories={categories}
            onDone={() => { setShowAdd(false); load(); }} onCancel={() => setShowAdd(false)} />
        )}

        {!cards.length
          ? <p className="text-gray-600 text-xs text-center py-6">No cards yet. Add one to track reward points and card bills — or browse benefits of popular cards below.</p>
          : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {cards.map(c => <CardTile key={c.id} card={c} onDelete={async () => { await cardApi.delete(c.id); load(); }} onPoints={load} />)}
            </div>
          )}
      </div>

      {/* ── Rewards & Points (always visible) ── */}
      <RewardsPanel tips={tips} />

      {/* ── Card Benefits Explorer (always visible) ── */}
      <CardBenefitsExplorer catalog={catalog}
        ownedNames={cards.map(c => c.name)}
        onAdd={async (name) => { await cardApi.add({ catalogName: name }); load(); }} />
    </div>
  );
}

/* ── Rewards & points: personalized tips if points exist, else a how-to guide ── */
function RewardsPanel({ tips }: { tips: PointsTip[] }) {
  const maskText = useMaskedText();
  return (
    <div className="card border border-yellow-400/20">
      <div className="flex items-center gap-2 mb-3">
        <Gift size={14} className="text-yellow-400" />
        <h3 className="font-semibold text-white text-sm">Rewards &amp; Points</h3>
      </div>

      {tips.length > 0 ? (
        <div className="space-y-2">
          {tips.map(t => (
            <div key={t.cardId} className="bg-surface-hover rounded-lg p-3">
              <div className="flex items-center justify-between mb-1">
                <span className="text-white text-xs font-medium">{t.cardName}</span>
                <span className="text-2xs text-gray-500">{t.pointsBalance.toLocaleString('en-IN')} pts</span>
              </div>
              <div className="flex items-center gap-4 mb-1.5">
                <div><span className="text-2xs text-gray-600">Cashback: </span><span className="text-xs font-mono text-gray-300">{maskText(fmtINR(t.cashValue))}</span></div>
                <div><span className="text-2xs text-gray-600">Best (transfer): </span><span className="text-xs font-mono text-bull">{maskText(fmtINR(t.bestValue))}</span></div>
              </div>
              <p className="text-2xs text-gray-400 flex gap-1.5"><Sparkles size={10} className="text-yellow-400 shrink-0 mt-0.5" />{t.recommendation}</p>
            </div>
          ))}
        </div>
      ) : (
        <div className="space-y-2">
          <p className="text-xs text-gray-500 mb-2">Add a points balance on any card to get personalised redemption tips. General rules to get the most value:</p>
          {[
            ['Transfer to airline/hotel partners', 'Usually 2–4× the cashback value — the single best use of points on Amex/Axis/HDFC.'],
            ['Redeem for statement credit or vouchers', 'Safe, predictable ~1× value. Good when you have no travel plans.'],
            ['Avoid the merchandise catalogue', 'Products often value points at 0.2–0.3 ₹ — the worst redemption.'],
            ['Hit milestone bonuses', 'Many cards give bonus points at quarterly spend targets — plan big spends around them.'],
          ].map(([h, d]) => (
            <div key={h} className="flex gap-2 text-2xs">
              <Sparkles size={11} className="text-yellow-400 shrink-0 mt-0.5" />
              <span><span className="text-white font-medium">{h}.</span> <span className="text-gray-400">{d}</span></span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/* ── Card Benefits Explorer: browse every popular card's benefits & where to use ── */
function CardBenefitsExplorer({ catalog, ownedNames, onAdd }:
  { catalog: CatalogEntry[]; ownedNames: string[]; onAdd: (name: string) => void }) {
  const [expanded, setExpanded] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  if (!catalog.length) return null;

  const filtered = catalog.filter(c => {
    if (!search) return true;
    const s = search.toLowerCase();
    return c.name.toLowerCase().includes(s) || c.issuer.toLowerCase().includes(s) || c.bestFor?.toLowerCase().includes(s);
  });

  const grouped = filtered.reduce((acc, c) => {
    const key = c.issuer;
    if (!acc[key]) acc[key] = [];
    acc[key].push(c);
    return acc;
  }, {} as Record<string, CatalogEntry[]>);

  const issuerKeys = Object.keys(grouped).sort();

  return (
    <div className="card">
      <div className="flex items-center gap-2 mb-3">
        <CardIcon size={14} className="text-brand" />
        <h3 className="font-semibold text-white text-sm">Card Benefits Guide</h3>
        <span className="text-2xs text-gray-600">{catalog.length} popular cards</span>
      </div>

      {/* Search */}
      <div className="relative mb-3">
        <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-600" />
        <input value={search} onChange={e => setSearch(e.target.value)}
          placeholder="Search cards by name, issuer, or category..."
          className="input-field text-xs w-full pl-8" />
      </div>

      {filtered.length === 0 ? (
        <p className="text-gray-600 text-xs text-center py-4">No cards match "{search}"</p>
      ) : (
        <div className="space-y-4">
          {issuerKeys.map(issuerName => (
            <div key={issuerName}>
              <div className="flex items-center gap-2 mb-2">
                <div className={`w-1 h-4 rounded-full bg-gradient-to-b ${ISSUER_GRAD[issuerName] ?? 'from-gray-600 to-gray-800'}`} />
                <h4 className="text-xs font-medium text-gray-400">{issuerName}</h4>
                <span className="text-2xs text-gray-600">{grouped[issuerName].length}</span>
              </div>
              <div className="space-y-2">
                {grouped[issuerName].map(c => {
                  const owned = ownedNames.includes(c.name);
                  const open = expanded === c.name;
                  const topCats = Object.entries(c.rewardRates || {}).sort((a, b) => b[1] - a[1]).slice(0, 4);
                  return (
                    <div key={c.name} className="bg-surface-hover rounded-lg overflow-hidden">
                      <button onClick={() => setExpanded(open ? null : c.name)}
                        className="w-full flex items-center justify-between p-2.5 text-left">
                        <div className="min-w-0">
                          <div className="flex items-center gap-1.5">
                            <span className="text-white text-xs font-medium">{c.name}</span>
                            {owned && <span className="text-2xs bg-bull/20 text-bull px-1 rounded">owned</span>}
                          </div>
                          <p className="text-2xs text-gray-500 truncate max-w-[240px]">Best for: {c.bestFor}</p>
                        </div>
                        <div className="flex items-center gap-2 shrink-0">
                          <span className="text-2xs text-gray-600">₹{c.annualFee}/yr</span>
                          {open ? <ChevronUp size={13} className="text-gray-500" /> : <ChevronDown size={13} className="text-gray-500" />}
                        </div>
                      </button>
                      {open && (
                        <div className="px-3 pb-3 border-t border-surface-border/40 pt-2">
                          <div className="flex flex-wrap gap-1 mb-2">
                            {topCats.map(([cat, rate]) => (
                              <span key={cat} className="text-2xs bg-surface px-1.5 py-0.5 rounded text-gray-300">
                                {cat} <span className="text-bull font-mono">{rate}%</span>
                              </span>
                            ))}
                          </div>
                          <ul className="space-y-1 mb-2">
                            {c.benefits.map((b, i) => (
                              <li key={i} className="text-2xs text-gray-400 flex gap-1.5"><span className="text-brand">▸</span>{b}</li>
                            ))}
                          </ul>
                          {!owned && (
                            <button onClick={() => onAdd(c.name)} className="btn-ghost text-2xs flex items-center gap-1 text-brand">
                              <Plus size={10} /> Add to my cards
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/* ── Smart Spend Advisor ── */
function SpendAdvisor({ categories, hasCards }: { categories: string[]; hasCards: boolean }) {
  const maskText = useMaskedText();
  const [category, setCategory] = useState('Online Shopping');
  const [merchant, setMerchant] = useState('');
  const [amount, setAmount]     = useState('');
  const [results, setResults]   = useState<RecommendResult[]>([]);
  const [loading, setLoading]   = useState(false);

  useEffect(() => { if (categories.length && !categories.includes(category)) setCategory(categories[0]); }, [categories]);

  const run = async () => {
    if (!amount) return;
    setLoading(true);
    try {
      const { data } = await cardApi.recommend(category, Number(amount), merchant || undefined);
      setResults(data);
    } catch {} finally { setLoading(false); }
  };

  const RANK_COLORS = ['text-yellow-400', 'text-gray-300', 'text-amber-600'];
  const RANK_BG     = ['bg-yellow-400/15 border-yellow-400/30', 'bg-gray-400/10 border-gray-400/20', 'bg-amber-600/10 border-amber-600/20'];

  return (
    <div className="card bg-gradient-to-br from-brand/5 to-transparent border border-brand/20">
      <div className="flex items-center gap-2 mb-3">
        <Wand2 size={14} className="text-brand" />
        <h3 className="font-semibold text-white text-sm">Which card should I use?</h3>
      </div>
      {!hasCards ? (
        <p className="text-gray-500 text-xs">Add your cards below, then type a purchase here to see which card earns the most.</p>
      ) : (
        <>
          <div className="flex flex-wrap items-end gap-2 mb-3">
            <div className="flex-1 min-w-[140px]">
              <label className="stat-label block mb-1 text-2xs">Spending on</label>
              <select value={category} onChange={e => setCategory(e.target.value)} className="input-field text-xs">
                {categories.map(c => <option key={c}>{c}</option>)}
              </select>
            </div>
            <div className="flex-1 min-w-[140px]">
              <label className="stat-label block mb-1 text-2xs">Merchant / Store</label>
              <input value={merchant} onChange={e => setMerchant(e.target.value)}
                placeholder="e.g. Swiggy, Amazon, Blinkit"
                className="input-field text-xs" />
            </div>
            <div className="flex-1 min-w-[120px]">
              <label className="stat-label block mb-1 text-2xs">Amount (₹)</label>
              <input type="number" value={amount} onChange={e => setAmount(e.target.value)}
                placeholder="5000" className="input-field text-xs" onKeyDown={e => e.key === 'Enter' && run()} />
            </div>
            <button onClick={run} disabled={loading || !amount} className="btn-primary text-xs py-2 px-4">
              {loading ? '…' : 'Find Best Card'}
            </button>
          </div>

          {results.length > 0 && (() => {
            const eligibleResults = results.filter(r => r.eligible !== false);
            const ineligibleResults = results.filter(r => r.eligible === false);
            const noneEligible = eligibleResults.length === 0 && ineligibleResults.length > 0;

            return (
              <div className="space-y-2">
                {/* No eligible cards warning */}
                {noneEligible && (
                  <div className="flex items-start gap-3 p-4 rounded-lg bg-bear/10 border border-bear/30">
                    <AlertTriangle size={18} className="text-bear shrink-0 mt-0.5" />
                    <div>
                      <p className="text-sm font-semibold text-bear mb-1">None of your saved cards are eligible for this transaction.</p>
                      <p className="text-2xs text-gray-400">
                        {category === 'UPI'
                          ? 'UPI credit card payments require a RuPay-enabled card. Consider adding a RuPay credit card (e.g. IDFC First WOW, Tata Neu HDFC, Kiwi, AU LIT) to use for UPI.'
                          : 'None of your cards support this payment method or merchant. See details below.'}
                      </p>
                    </div>
                  </div>
                )}

                {/* Eligible cards ranked */}
                {eligibleResults.map((r, i) => {
                  const rank = i + 1;
                  const isTop3 = rank <= 3;
                  const isFirst = rank === 1;
                  return (
                    <div key={r.cardId ?? r.cardName}
                      className={`rounded-lg overflow-hidden ${
                        isFirst
                          ? 'bg-gradient-to-r from-yellow-400/10 via-yellow-400/5 to-transparent border border-yellow-400/30 ring-1 ring-yellow-400/20'
                          : isTop3
                            ? `border ${RANK_BG[Math.min(i, 2)]}`
                            : 'bg-surface-hover'
                      }`}>
                      <div className="flex items-start gap-3 p-3">
                        {isTop3 ? (
                          <div className={`shrink-0 w-8 h-8 rounded-full flex items-center justify-center font-bold text-sm ${RANK_COLORS[Math.min(i, 2)]} bg-surface/60`}>
                            #{rank}
                          </div>
                        ) : (
                          <div className="shrink-0 w-8 h-8 rounded-full flex items-center justify-center text-xs text-gray-600 bg-surface/40">
                            #{rank}
                          </div>
                        )}
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-0.5">
                            <span className={`text-xs font-semibold ${isFirst ? 'text-yellow-400' : 'text-white'}`}>{r.cardName}</span>
                            <span className="text-2xs text-gray-600">{r.issuer}</span>
                            {r.network && <span className="text-2xs text-gray-700">{r.network}</span>}
                            {isFirst && (
                              <span className="text-2xs bg-yellow-400/20 text-yellow-400 px-1.5 py-0.5 rounded font-medium flex items-center gap-1">
                                <Sparkles size={10} /> TOP PICK
                              </span>
                            )}
                          </div>
                          <p className="text-2xs text-gray-400 whitespace-pre-line">{r.reason}</p>
                        </div>
                        <div className="text-right shrink-0 ml-2">
                          <div className={`font-bold font-mono ${isFirst ? 'text-lg text-yellow-400' : isTop3 ? 'text-base text-white' : 'text-sm text-gray-300'}`}>
                            {maskText(fmtINR(r.expectedReward))}
                          </div>
                          <div className="text-2xs text-gray-600">{r.rewardRate}% back</div>
                        </div>
                      </div>
                    </div>
                  );
                })}

                {/* Ineligible cards section */}
                {ineligibleResults.length > 0 && (
                  <>
                    {eligibleResults.length > 0 && (
                      <div className="flex items-center gap-2 pt-2">
                        <div className="flex-1 border-t border-surface-border/50" />
                        <span className="text-2xs text-gray-600 flex items-center gap-1"><Ban size={10} /> Not eligible</span>
                        <div className="flex-1 border-t border-surface-border/50" />
                      </div>
                    )}
                    {ineligibleResults.map(r => (
                      <div key={r.cardId ?? r.cardName}
                        className="rounded-lg bg-surface-hover/50 border border-surface-border/30 opacity-60">
                        <div className="flex items-start gap-3 p-3">
                          <div className="shrink-0 w-8 h-8 rounded-full flex items-center justify-center bg-bear/10">
                            <Ban size={13} className="text-bear/60" />
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2 mb-0.5">
                              <span className="text-xs font-medium text-gray-500">{r.cardName}</span>
                              <span className="text-2xs text-gray-700">{r.issuer}</span>
                              {r.network && <span className="text-2xs text-gray-700">{r.network}</span>}
                            </div>
                            <p className="text-2xs text-bear/80">{r.ineligibilityNote || r.reason}</p>
                          </div>
                        </div>
                      </div>
                    ))}
                  </>
                )}
              </div>
            );
          })()}
        </>
      )}
    </div>
  );
}

/* ── Individual card tile ── */
function CardTile({ card, onDelete, onPoints }: { card: CardResponse; onDelete: () => void; onPoints: () => void }) {
  const maskText = useMaskedText();
  const [expanded, setExpanded] = useState(false);
  const [editPts, setEditPts]   = useState(false);
  const [pts, setPts]           = useState(String(card.pointsBalance));
  const grad = ISSUER_GRAD[card.issuer] ?? 'from-gray-700 to-gray-900';
  const topCats = Object.entries(card.rewardRates || {}).sort((a, b) => b[1] - a[1]).slice(0, 3);

  const savePts = async () => { await cardApi.updatePoints(card.id, Number(pts)); setEditPts(false); onPoints(); };

  return (
    <div className="rounded-xl overflow-hidden border border-surface-border">
      {/* Card face */}
      <div className={`bg-gradient-to-br ${grad} p-4 relative`}>
        <div className="flex justify-between items-start">
          <div>
            <div className="text-white/70 text-2xs uppercase tracking-wider">{card.issuer}</div>
            <div className="text-white font-semibold text-sm">{card.name}</div>
          </div>
          <button onClick={onDelete} className="text-white/50 hover:text-white"><Trash2 size={13} /></button>
        </div>
        <div className="mt-4 flex justify-between items-end">
          <div className="text-white/90 font-mono text-sm tracking-widest">•••• {card.lastFour || '••••'}</div>
          <div className="text-white/70 text-2xs">{card.network}</div>
        </div>
      </div>
      {/* Details */}
      <div className="bg-surface-card p-3">
        {card.currentDue != null && card.currentDue > 0 && (
          <div className="flex items-center justify-between mb-2 bg-bear/10 border border-bear/20 rounded px-2 py-1">
            <span className="text-2xs text-bear font-medium">Bill due{card.currentDueDate ? ` by ${card.currentDueDate}` : ''}</span>
            <span className="text-xs font-mono font-bold text-bear">{maskText(fmtINR(card.currentDue))}</span>
          </div>
        )}
        {card.bestFor && (
          <div className="flex gap-1.5 mb-2">
            <TrendingUp size={11} className="text-brand shrink-0 mt-0.5" />
            <p className="text-2xs text-gray-400"><span className="text-gray-500">Best for:</span> {card.bestFor}</p>
          </div>
        )}
        {topCats.length > 0 && (
          <div className="flex flex-wrap gap-1 mb-2">
            {topCats.map(([cat, rate]) => (
              <span key={cat} className="text-2xs bg-surface-hover px-1.5 py-0.5 rounded text-gray-300">
                {cat} <span className="text-bull font-mono">{rate}%</span>
              </span>
            ))}
          </div>
        )}
        <div className="flex items-center justify-between text-2xs">
          <div className="flex items-center gap-1.5">
            <Gift size={11} className="text-yellow-400" />
            {editPts ? (
              <span className="flex items-center gap-1">
                <input type="number" value={pts} onChange={e => setPts(e.target.value)} className="input-field text-2xs w-20 py-0.5" />
                <button onClick={savePts} className="text-bull">✓</button>
                <button onClick={() => { setEditPts(false); setPts(String(card.pointsBalance)); }} className="text-gray-500">✕</button>
              </span>
            ) : (
              <button onClick={() => setEditPts(true)} className="text-gray-400 hover:text-white">
                {maskText(card.pointsBalance.toLocaleString('en-IN'))} pts · <span className="text-gray-300">{maskText(fmtINR(card.pointsCashValue))}</span>
              </button>
            )}
          </div>
          <button onClick={() => setExpanded(e => !e)} className="text-gray-500 flex items-center gap-0.5">
            Benefits {expanded ? <ChevronUp size={11} /> : <ChevronDown size={11} />}
          </button>
        </div>
        {expanded && card.benefits && (
          <ul className="mt-2 space-y-1 border-t border-surface-border/40 pt-2">
            {card.benefits.split('\n').map((b, i) => (
              <li key={i} className="text-2xs text-gray-400 flex gap-1.5"><span className="text-brand">▸</span>{b}</li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/* ── Add card form ── */
function AddCardForm({ catalog, categories, onDone, onCancel }:
  { catalog: CatalogEntry[]; categories: string[]; onDone: () => void; onCancel: () => void }) {
  const [mode, setMode] = useState<'catalog' | 'custom'>('catalog');
  const [selected, setSelected] = useState('');
  const [lastFour, setLastFour] = useState('');
  const [points, setPoints]     = useState('');
  const [saving, setSaving]     = useState(false);

  // custom
  const [name, setName]       = useState('');
  const [issuer, setIssuer]   = useState('HDFC');
  const [network, setNetwork] = useState('Visa');
  const [rates, setRates]     = useState<Record<string, string>>({});

  const chosen = catalog.find(c => c.name === selected);

  const submit = async () => {
    setSaving(true);
    try {
      if (mode === 'catalog') {
        if (!selected) return;
        await cardApi.add({ catalogName: selected, lastFour, pointsBalance: points ? Number(points) : 0 });
      } else {
        if (!name) return;
        const rr: Record<string, number> = {};
        Object.entries(rates).forEach(([k, v]) => { if (v) rr[k] = Number(v); });
        await cardApi.add({ name, issuer, network, lastFour, pointsBalance: points ? Number(points) : 0, rewardRates: rr });
      }
      onDone();
    } catch {} finally { setSaving(false); }
  };

  return (
    <div className="bg-surface-hover rounded-lg p-3 mb-4 border border-surface-border">
      <div className="flex gap-2 mb-3">
        <button onClick={() => setMode('catalog')} className={`text-xs px-3 py-1 rounded ${mode === 'catalog' ? 'bg-brand text-white' : 'text-gray-400'}`}>From Catalog</button>
        <button onClick={() => setMode('custom')} className={`text-xs px-3 py-1 rounded ${mode === 'custom' ? 'bg-brand text-white' : 'text-gray-400'}`}>Custom</button>
      </div>

      {mode === 'catalog' ? (
        <div className="space-y-2">
          <select value={selected} onChange={e => setSelected(e.target.value)} className="input-field text-xs w-full">
            <option value="">Choose a card…</option>
            {catalog.map(c => <option key={c.name} value={c.name}>{c.name} ({c.issuer})</option>)}
          </select>
          {chosen && (
            <div className="bg-surface rounded p-2 text-2xs text-gray-400">
              <p className="text-gray-300 mb-1">Best for: {chosen.bestFor}</p>
              <ul className="space-y-0.5">{chosen.benefits.slice(0, 3).map((b, i) => <li key={i}>▸ {b}</li>)}</ul>
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-2">
          <div className="grid grid-cols-3 gap-2">
            <input value={name} onChange={e => setName(e.target.value)} placeholder="Card name" className="input-field text-xs" />
            <select value={issuer} onChange={e => setIssuer(e.target.value)} className="input-field text-xs">
              {ISSUERS.map(i => <option key={i}>{i}</option>)}
            </select>
            <select value={network} onChange={e => setNetwork(e.target.value)} className="input-field text-xs">
              {['Visa', 'Mastercard', 'RuPay', 'Amex', 'Diners', 'Other'].map(n => <option key={n}>{n}</option>)}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-1.5">
            {categories.map(cat => (
              <div key={cat} className="flex items-center gap-1">
                <span className="text-2xs text-gray-500 flex-1 truncate">{cat}</span>
                <input type="number" value={rates[cat] ?? ''} onChange={e => setRates(r => ({ ...r, [cat]: e.target.value }))}
                  placeholder="%" className="input-field text-2xs w-14 py-0.5" />
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="grid grid-cols-2 gap-2 mt-2">
        <input value={lastFour} onChange={e => setLastFour(e.target.value.slice(0, 4))} placeholder="Last 4 digits" className="input-field text-xs" />
        <input type="number" value={points} onChange={e => setPoints(e.target.value)} placeholder="Reward points balance" className="input-field text-xs" />
      </div>

      <div className="flex gap-2 mt-3">
        <button onClick={submit} disabled={saving} className="btn-primary text-xs py-1.5 flex-1">{saving ? 'Saving…' : 'Add Card'}</button>
        <button onClick={onCancel} className="btn-ghost text-xs py-1.5 flex-1">Cancel</button>
      </div>
    </div>
  );
}
