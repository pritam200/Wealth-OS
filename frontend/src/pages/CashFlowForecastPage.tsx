import { useCallback, useEffect, useMemo, useState } from 'react';
import { Calendar, Wallet, TrendingDown, AlertTriangle, RefreshCw, Info } from 'lucide-react';
import { cashflowApi } from '../api/cashflow';
import type { DailyProjection, ProjectedEvent } from '../api/cashflow';
import { LoadFailure } from '../components/shared/LoadFailure';
import { formatINR, formatINRCompact } from '../utils/currency';
import { Amount } from '../components/shared/Amount';

const HORIZONS = [30, 90, 180] as const;

const EVENT_TONE: Record<ProjectedEvent['type'], string> = {
  INCOME: 'text-bull',
  FD_MATURITY: 'text-bull',
  RD_MATURITY: 'text-bull',
  EXPENSE: 'text-bear',
  SIP: 'text-bear',
};

function eventSign(e: ProjectedEvent) {
  return e.amount >= 0 ? '+' : '';
}

/** One calendar cell — colour communicates whether the day nets positive/negative and whether
 *  it carries any events at all; a dashed ring flags a day whose balance rests partly on an
 *  estimate rather than a stored schedule. */
function DayCell({ day, selected, onSelect }: {
  day: DailyProjection; selected: boolean; onSelect: () => void;
}) {
  const net = day.events.reduce((sum, e) => sum + e.amount, 0);
  const hasEvents = day.events.length > 0;
  const tone = net > 0 ? 'border-bull/40 bg-bull/5' : net < 0 ? 'border-bear/40 bg-bear/5' : 'border-surface-border bg-surface-hover/30';

  return (
    <button
      onClick={onSelect}
      className={`aspect-square rounded-lg border p-1.5 flex flex-col items-start justify-between text-left transition-all
        ${selected ? 'ring-2 ring-brand-light' : ''} ${hasEvents ? tone : 'border-surface-border bg-surface-hover/10'}`}
    >
      <span className="text-2xs font-mono text-gray-500">{new Date(day.date).getDate()}</span>
      {hasEvents ? (
        <span className={`text-2xs font-mono tabular-nums truncate w-full ${day.hasEstimatedComponent ? 'italic' : ''} ${net >= 0 ? 'text-bull' : 'text-bear'}`}>
          <Amount value={formatINRCompact(day.projectedBalance)} />
        </span>
      ) : (
        <span className="text-2xs font-mono tabular-nums text-gray-600 truncate w-full">
          <Amount value={formatINRCompact(day.projectedBalance)} />
        </span>
      )}
    </button>
  );
}

/** Groups the flat day list into calendar months, each padded to start on the right weekday —
 *  PocketSmith's "calendar, not a bar chart" pattern (see docs/COMPETITOR_RESEARCH §8, §12). */
function useCalendarMonths(days: DailyProjection[]) {
  return useMemo(() => {
    const byMonth = new Map<string, DailyProjection[]>();
    for (const d of days) {
      const date = new Date(d.date);
      const key = `${date.getFullYear()}-${date.getMonth()}`;
      if (!byMonth.has(key)) byMonth.set(key, []);
      byMonth.get(key)!.push(d);
    }
    return Array.from(byMonth.entries()).map(([key, monthDays]) => {
      const [year, month] = key.split('-').map(Number);
      const leadingBlanks = new Date(year, month, 1).getDay();
      const label = new Date(year, month, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
      return { key, label, leadingBlanks, days: monthDays };
    });
  }, [days]);
}

function DayBreakdown({ day }: { day: DailyProjection }) {
  const label = new Date(day.date).toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  return (
    <div className="card-flat">
      <div className="flex items-center justify-between mb-2">
        <p className="text-sm font-semibold text-ink">{label}</p>
        <span className="text-sm font-mono tabular-nums text-ink"><Amount value={formatINR(day.projectedBalance)} /></span>
      </div>
      {day.events.length === 0 ? (
        <p className="text-2xs text-gray-500">No known or estimated cash movement lands on this day.</p>
      ) : (
        <div className="space-y-1.5">
          {day.events.map((e, i) => (
            <div key={i} className="flex items-center justify-between text-2xs">
              <span className="text-gray-300 truncate flex items-center gap-1.5">
                {e.label}
                {e.estimated && (
                  <span className="pill-muted" title="Inferred from history, not an explicit schedule">est.</span>
                )}
              </span>
              <span className={`font-mono tabular-nums shrink-0 ml-2 ${EVENT_TONE[e.type]}`}>
                <Amount value={eventSign(e) + formatINR(e.amount)} />
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function CashFlowForecastPage() {
  const [days, setDays] = useState<DailyProjection[]>([]);
  const [horizon, setHorizon] = useState<number>(90);
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async (h: number) => {
    setLoading(true); setError('');
    try {
      const { data } = await cashflowApi.forecast(h);
      setDays(data);
      setSelectedDate(data[0]?.date ?? null);
    } catch {
      setError('forecast');
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(horizon); }, [load, horizon]);

  const months = useCalendarMonths(days);
  const selectedDay = useMemo(() => days.find(d => d.date === selectedDate) ?? null, [days, selectedDate]);

  const lowest = useMemo(() => {
    if (days.length === 0) return null;
    return days.reduce((min, d) => (d.projectedBalance < min.projectedBalance ? d : min), days[0]);
  }, [days]);

  const anyEstimated = days.some(d => d.hasEstimatedComponent);

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-2xl bg-brand/10 border border-brand/25 flex items-center justify-center text-brand-light shrink-0">
            <Calendar size={20} />
          </div>
          <div>
            <h2 className="text-xl font-bold text-ink mb-0.5">Cash Flow Forecast</h2>
            <p className="text-gray-500 text-sm">Day-by-day projected balance from your income, expenses, SIPs and deposit maturities.</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {HORIZONS.map(h => (
            <button key={h} onClick={() => setHorizon(h)}
              className={h === horizon ? 'btn-primary text-xs py-1.5 px-3' : 'btn-secondary text-xs py-1.5 px-3'}>
              {h}d
            </button>
          ))}
          <button onClick={() => load(horizon)} className="btn-secondary flex items-center gap-1.5 text-xs" disabled={loading}>
            <RefreshCw size={13} className={loading ? 'animate-spin' : ''} /> Refresh
          </button>
        </div>
      </div>

      {loading && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          {[...Array(3)].map((_, i) => <div key={i} className="card animate-pulse h-40 bg-surface-hover" />)}
        </div>
      )}

      {!loading && error && <LoadFailure what="the cash flow forecast" onRetry={() => load(horizon)} />}

      {!loading && !error && days.length > 0 && (
        <>
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div className="card-elevated">
              <h3 className="section-title"><Wallet size={15} className="text-brand-light" /> Today</h3>
              <div className="stat-value-hero"><Amount value={formatINR(days[0].projectedBalance)} /></div>
              <div className="text-2xs text-gray-500 mt-1">Current cash balance across all accounts.</div>
            </div>
            {lowest && (
              <div className="card-elevated">
                <h3 className="section-title"><TrendingDown size={15} className="text-bear" /> Lowest Point</h3>
                <div className={`stat-value-hero ${lowest.projectedBalance < 0 ? 'text-bear' : ''}`}><Amount value={formatINR(lowest.projectedBalance)} /></div>
                <div className="text-2xs text-gray-500 mt-1">
                  Projected on {new Date(lowest.date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}
                </div>
              </div>
            )}
            <div className="card-elevated">
              <h3 className="section-title"><Info size={15} className="text-brand-light" /> Confidence</h3>
              <p className="text-2xs text-gray-400">
                SIP debits and FD/RD maturities use their stored schedule and are shown as known.
                Recurring income and everyday expenses are inferred from your last 3 months of
                history and marked <span className="pill-muted">est.</span> — treat those days as
                a range, not a promise.
              </p>
            </div>
          </div>

          {anyEstimated && (
            <div className="flex items-start gap-2.5 rounded-lg border border-neutral/30 bg-neutral/5 p-3">
              <AlertTriangle size={14} className="text-neutral shrink-0 mt-0.5" />
              <p className="text-2xs text-gray-400">
                Some days ahead rest partly on estimated (not scheduled) income or spend — those
                cells render balances in italics and their breakdown below tags each estimated
                line with <span className="pill-muted">est.</span>
              </p>
            </div>
          )}

          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div className="lg:col-span-2 space-y-4">
              {months.map(month => (
                <div key={month.key} className="card">
                  <h3 className="section-title">{month.label}</h3>
                  <div className="grid grid-cols-7 gap-1.5 mb-1">
                    {['S', 'M', 'T', 'W', 'T', 'F', 'S'].map((w, i) => (
                      <span key={i} className="text-2xs text-gray-600 text-center font-semibold">{w}</span>
                    ))}
                  </div>
                  <div className="grid grid-cols-7 gap-1.5">
                    {Array.from({ length: month.leadingBlanks }).map((_, i) => <div key={`b-${i}`} />)}
                    {month.days.map(d => (
                      <DayCell key={d.date} day={d} selected={d.date === selectedDate} onSelect={() => setSelectedDate(d.date)} />
                    ))}
                  </div>
                </div>
              ))}
            </div>

            <div className="space-y-4">
              {selectedDay ? <DayBreakdown day={selectedDay} /> : (
                <div className="card text-center py-8 text-gray-600 text-xs">Tap a day to see what's hitting it.</div>
              )}
            </div>
          </div>
        </>
      )}

      {!loading && !error && days.length === 0 && (
        <div className="card text-center py-12 text-gray-600">
          <p className="text-sm">No forecast available yet.</p>
        </div>
      )}
    </div>
  );
}
