import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertCircle, Check, Cpu, Loader2, RefreshCw, Save, ShieldCheck, X, Zap } from 'lucide-react';
import { llmConfigApi } from '../../api/llmConfig';
import type {
  LlmCapability, LlmConfigView, LlmHealth, LlmProviderId, LlmProviderView, ModelInfo, PrivacyMode, TestResult,
} from '../../api/llmConfig';

const PRIVACY_MODES: { id: PrivacyMode; label: string; detail: string }[] = [
  { id: 'LOCAL', label: 'Local AI', detail: 'Every task on the local model. Nothing is sent to a cloud provider, fallback included.' },
  { id: 'CLOUD', label: 'Cloud AI', detail: 'Every task on Gemini. Documents are sent to Google.' },
  { id: 'HYBRID', label: 'Hybrid', detail: 'Classification, email alerts, the advisor and the assistant stay local; statements, scans and analysis go to Gemini.' },
  { id: 'CUSTOM', label: 'Custom', detail: 'Choose the provider and model for each task below.' },
];

const PROVIDER_LABEL: Record<LlmProviderId, string> = { GEMINI: 'Gemini', OLLAMA: 'Ollama' };

const TASK_NAMES: Record<string, string> = {
  EMAIL_CLASSIFICATION: 'Email classification',
  EMAIL_EXTRACTION: 'Email extraction',
  DOCUMENT_EXTRACTION: 'Document extraction',
  DOCUMENT_TRANSCRIPTION: 'Scan reading',
  AI_ADVISOR: 'AI Advisor',
  FINANCIAL_ANALYSIS: 'Financial analysis & market research',
  GENERAL_ASSISTANT: 'General assistant',
};

interface ProviderForm { model: string; temperature: string; maxTokens: string; timeoutSeconds: string; endpoint: string }
interface RouteForm { provider: LlmProviderId | null; model: string }

function toForm(p: LlmProviderView): ProviderForm {
  return {
    model: p.model, temperature: String(p.temperature), maxTokens: p.maxTokens == null ? '' : String(p.maxTokens),
    timeoutSeconds: String(p.timeoutSeconds), endpoint: p.endpoint ?? '',
  };
}

function errorText(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

function CapabilityChips({ caps, labels, required }: { caps: LlmCapability[]; labels: Record<string, string>; required?: LlmCapability[] }) {
  const all: LlmCapability[] = ['TEXT', 'JSON', 'VISION', 'PDF', 'TOOLS'];
  return (
    <div className="flex flex-wrap gap-1">
      {all.map(c => {
        const has = caps.includes(c);
        const needed = required?.includes(c);
        return (
          <span key={c} title={labels[c]}
            className={has ? 'pill-bull' : needed ? 'pill-bear' : 'pill-muted'}>
            {has ? <Check size={9} /> : <X size={9} />} {labels[c] ?? c}
          </span>
        );
      })}
    </div>
  );
}

function TestSteps({ result }: { result: TestResult }) {
  return (
    <ul className="space-y-1 text-xs">
      {result.steps.map(s => (
        <li key={s.label} className="flex items-start gap-1.5">
          {s.ok ? <Check size={13} className="text-bull shrink-0 mt-0.5" /> : <X size={13} className="text-bear shrink-0 mt-0.5" />}
          <span><span className={s.ok ? 'text-ink' : 'text-bear'}>{s.label}</span>
            <span className="text-gray-500"> — {s.detail}</span></span>
        </li>
      ))}
    </ul>
  );
}

/**
 * Settings → LLM Configuration. Admin-only (the server enforces it; the tab is hidden otherwise).
 * The API key field is write-only: the saved key never reaches the browser, only its last four.
 */
export function LlmConfigurationPanel() {
  const [view, setView] = useState<LlmConfigView | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [health, setHealth] = useState<LlmHealth | null>(null);
  const [healthLoading, setHealthLoading] = useState(false);

  const [aiEnabled, setAiEnabled] = useState(true);
  const [mode, setMode] = useState<PrivacyMode>('CUSTOM');
  const [fallback, setFallback] = useState(false);
  const [retries, setRetries] = useState('1');
  const [gemini, setGemini] = useState<ProviderForm | null>(null);
  const [ollama, setOllama] = useState<ProviderForm | null>(null);
  const [routes, setRoutes] = useState<Record<string, RouteForm>>({});
  const [newKey, setNewKey] = useState('');
  const [removeKey, setRemoveKey] = useState(false);

  const [models, setModels] = useState<Partial<Record<LlmProviderId, ModelInfo[]>>>({});
  const [modelsError, setModelsError] = useState<Partial<Record<LlmProviderId, string>>>({});
  const [refreshing, setRefreshing] = useState(false);
  const [tests, setTests] = useState<Partial<Record<LlmProviderId, TestResult>>>({});
  const [testing, setTesting] = useState<LlmProviderId | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveNotes, setSaveNotes] = useState<string[] | null>(null);

  const apply = useCallback((v: LlmConfigView) => {
    setView(v);
    setAiEnabled(v.aiEnabled);
    setMode(v.privacyMode);
    setFallback(v.fallbackEnabled);
    setRetries(String(v.maxRetries));
    setGemini(toForm(v.providers.find(p => p.id === 'GEMINI')!));
    setOllama(toForm(v.providers.find(p => p.id === 'OLLAMA')!));
    const r: Record<string, RouteForm> = {};
    v.tasks.forEach(t => { r[t.task] = { provider: t.provider, model: t.model ?? '' }; });
    setRoutes(r);
    setNewKey('');
    setRemoveKey(false);
  }, []);

  const loadHealth = useCallback(() => {
    setHealthLoading(true);
    llmConfigApi.health().then(r => setHealth(r.data)).catch(() => setHealth(null)).finally(() => setHealthLoading(false));
  }, []);

  const refreshModels = useCallback(async (only?: LlmProviderId) => {
    setRefreshing(true);
    const ids: LlmProviderId[] = only ? [only] : ['OLLAMA', 'GEMINI'];
    await Promise.all(ids.map(async id => {
      try {
        const { data } = await llmConfigApi.models(id);
        setModels(m => ({ ...m, [id]: data }));
        setModelsError(e => ({ ...e, [id]: undefined }));
      } catch (err) {
        setModelsError(e => ({ ...e, [id]: errorText(err, 'Could not list models') }));
      }
    }));
    setRefreshing(false);
  }, []);

  useEffect(() => {
    llmConfigApi.get()
      // Local AI never contacts the cloud provider, not even to list its models.
      .then(r => { apply(r.data); refreshModels(r.data.privacyMode === 'LOCAL' ? 'OLLAMA' : undefined); })
      .catch(err => setLoadError(errorText(err, 'Could not load the LLM configuration')));
    loadHealth();
  }, [apply, loadHealth, refreshModels]);

  const providerView = (id: LlmProviderId) => view?.providers.find(p => p.id === id);
  const labels = view?.capabilityLabels ?? ({} as Record<LlmCapability, string>);

  const modelInfo = (id: LlmProviderId, name: string) =>
    models[id]?.find(m => m.name === name || m.name === `${name}:latest` || `${m.name}:latest` === name);

  const test = async (id: LlmProviderId) => {
    setTesting(id);
    const f = id === 'GEMINI' ? gemini : ollama;
    try {
      const { data } = await llmConfigApi.test(id, f?.model, id === 'OLLAMA' ? f?.endpoint : undefined,
        id === 'GEMINI' && newKey.trim() ? newKey.trim() : undefined);
      setTests(t => ({ ...t, [id]: data }));
      if (data.models.length) setModels(m => ({ ...m, [id]: data.models }));
    } catch (err) {
      setTests(t => ({ ...t, [id]: { provider: id, model: f?.model ?? '', ok: false, capabilities: [], capabilitiesKnown: false,
        models: [], latencyMs: null, steps: [{ label: 'Test', ok: false, detail: errorText(err, 'The test could not run') }] } }));
    } finally {
      setTesting(null);
    }
  };

  const save = async () => {
    if (!view || !gemini || !ollama) return;
    setSaving(true);
    setSaveError(null);
    setSaveNotes(null);
    const num = (s: string) => (s.trim() === '' ? null : Number(s));
    try {
      const { data } = await llmConfigApi.save({
        aiEnabled, privacyMode: mode, fallbackEnabled: fallback, maxRetries: Number(retries),
        gemini: { model: gemini.model, temperature: Number(gemini.temperature), maxTokens: num(gemini.maxTokens), timeoutSeconds: Number(gemini.timeoutSeconds) },
        ollama: { model: ollama.model, temperature: Number(ollama.temperature), maxTokens: num(ollama.maxTokens), timeoutSeconds: Number(ollama.timeoutSeconds), endpoint: ollama.endpoint },
        routes: view.tasks.map(t => ({ task: t.task, provider: routes[t.task]?.provider ?? null, model: routes[t.task]?.model || null })),
        ...(newKey.trim() ? { geminiApiKey: newKey.trim() } : {}),
        ...(removeKey ? { removeGeminiApiKey: true } : {}),
      });
      apply(data);
      setSaveNotes(data.warnings.length ? data.warnings : ['Saved. New work uses this configuration; nothing already recorded is re-read or changed.']);
      loadHealth();
    } catch (err) {
      setSaveError(errorText(err, 'Could not save the configuration'));
    } finally {
      setSaving(false);
    }
  };

  // In a preset mode the provider comes from the mode (the model stays selectable). For the saved
  // mode, what the server resolved — it switches a task off when no allowed model can do it.
  const effectiveProvider = (task: string): LlmProviderId | null => {
    if (mode === 'CUSTOM') return routes[task]?.provider ?? null;
    if (view && mode === view.privacyMode) return view.tasks.find(t => t.task === task)?.provider ?? null;
    return presetFor(mode, task);
  };

  const extraction = useMemo(() => {
    const p = effectiveProvider('DOCUMENT_EXTRACTION');
    if (!p) return null;
    const model = routes.DOCUMENT_EXTRACTION?.provider === p && routes.DOCUMENT_EXTRACTION.model
      ? routes.DOCUMENT_EXTRACTION.model : (p === 'GEMINI' ? gemini?.model : ollama?.model);
    return { provider: p, model };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, routes, gemini, ollama, view]);

  if (loadError) {
    return <div className="card text-xs text-bear flex items-center gap-1.5"><AlertCircle size={13} /> {loadError}</div>;
  }
  if (!view || !gemini || !ollama) return <div className="card animate-pulse h-64 bg-surface-hover" />;

  const statusOf = (id: LlmProviderId) => health?.providers.find(p => p.provider === id);
  const activeStatus = extraction ? statusOf(extraction.provider) : undefined;
  const u = health?.usage;

  return (
    <div className="space-y-4">
      {/* Summary + actions */}
      <div className="card">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-x-8 gap-y-2 text-sm">
            <div><div className="stat-label">Active provider (documents)</div>
              <div className="text-ink font-semibold">{extraction ? PROVIDER_LABEL[extraction.provider] : 'Off'}</div></div>
            <div><div className="stat-label">Model</div>
              <div className="text-ink font-mono text-xs mt-0.5">{extraction?.model ?? '—'}</div></div>
            <div><div className="stat-label">Status</div>
              <div className="text-xs mt-0.5">
                {healthLoading ? <span className="text-gray-500">Checking…</span>
                  : activeStatus?.connected ? <span className="text-bull flex items-center gap-1"><Check size={12} /> {activeStatus.detail}</span>
                  : activeStatus ? <span className="text-bear flex items-center gap-1"><X size={12} /> {activeStatus.detail}</span>
                  : <span className="text-gray-500">—</span>}
              </div></div>
          </div>
          <div className="flex flex-wrap gap-2">
            <button onClick={() => extraction && test(extraction.provider)} disabled={!extraction || testing !== null}
              className="btn-secondary text-xs px-3 py-1.5 flex items-center gap-1.5 disabled:opacity-50">
              {testing ? <Loader2 size={12} className="animate-spin" /> : <Zap size={12} />} Test Connection
            </button>
            <button onClick={() => { refreshModels(mode === 'LOCAL' ? 'OLLAMA' : undefined); loadHealth(); }} disabled={refreshing}
              className="btn-secondary text-xs px-3 py-1.5 flex items-center gap-1.5 disabled:opacity-50">
              <RefreshCw size={12} className={refreshing ? 'animate-spin' : ''} /> Refresh Models
            </button>
            <button onClick={save} disabled={saving} className="btn-primary text-xs px-3 py-1.5 flex items-center gap-1.5">
              {saving ? <Loader2 size={12} className="animate-spin" /> : <Save size={12} />} Save Configuration
            </button>
          </div>
        </div>
        {!view.saved && (
          <p className="text-2xs text-gray-500 mt-3">Not saved yet — these are the values from the server's environment settings.</p>
        )}
        {saveError && <div className="text-xs text-bear flex items-start gap-1.5 mt-3"><AlertCircle size={13} className="shrink-0 mt-0.5" /> {saveError}</div>}
        {saveNotes && <ul className="text-xs text-gray-400 mt-3 space-y-0.5">{saveNotes.map(n => <li key={n}>• {n}</li>)}</ul>}
      </div>

      {/* Privacy mode */}
      <div className="card">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-bold text-ink flex items-center gap-2"><ShieldCheck size={15} className="text-brand-light" /> Privacy mode</h3>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={aiEnabled} onChange={e => setAiEnabled(e.target.checked)} /> AI enabled
          </label>
        </div>
        <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-2">
          {PRIVACY_MODES.map(m => (
            <label key={m.id} className={`rounded-lg border p-3 cursor-pointer text-xs transition-colors ${mode === m.id ? 'border-brand bg-brand/5' : 'border-surface-border hover:bg-surface-hover'}`}>
              <div className="flex items-center gap-2 mb-1">
                <input type="radio" name="privacy-mode" checked={mode === m.id} onChange={() => setMode(m.id)} />
                <span className="font-semibold text-ink">{m.label}</span>
              </div>
              <p className="text-gray-500 leading-snug">{m.detail}</p>
            </label>
          ))}
        </div>
      </div>

      {/* Providers */}
      <div className="grid lg:grid-cols-2 gap-4">
        {(['GEMINI', 'OLLAMA'] as LlmProviderId[]).map(id => {
          const pv = providerView(id)!;
          const f = id === 'GEMINI' ? gemini : ollama;
          const setF = id === 'GEMINI' ? setGemini : setOllama;
          const list = models[id];
          const info = modelInfo(id, f.model);
          const result = tests[id];
          const blocked = id === 'GEMINI' && mode === 'LOCAL';
          return (
            <div key={id} className={`card space-y-3 ${blocked ? 'opacity-60' : ''}`}>
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-bold text-ink flex items-center gap-2"><Cpu size={15} className="text-brand-light" /> {pv.label}</h3>
                <span className={pv.cloud ? 'pill-neutral' : 'pill-bull'}>{pv.cloud ? 'Cloud' : 'On this machine'}</span>
              </div>
              {blocked && <p className="text-2xs text-gray-500">Not used in Local AI mode.</p>}

              {id === 'GEMINI' ? (
                <div>
                  <label className="stat-label block mb-1">API key</label>
                  <input type="password" autoComplete="off" value={newKey} onChange={e => { setNewKey(e.target.value); setRemoveKey(false); }}
                    placeholder={pv.apiKeySet ? `${pv.apiKeyHint ?? '••••'} ${pv.apiKeySource === 'ENVIRONMENT' ? '(from environment)' : 'saved'} — type to replace` : 'Paste an API key'}
                    className="input-field font-mono" />
                  <div className="flex items-center justify-between mt-1">
                    <p className="text-2xs text-gray-500">Stored encrypted. Never shown again after saving.</p>
                    {pv.apiKeySource === 'SAVED' && (
                      <label className="text-2xs text-gray-400 flex items-center gap-1">
                        <input type="checkbox" checked={removeKey} onChange={e => { setRemoveKey(e.target.checked); setNewKey(''); }} /> Remove saved key
                      </label>
                    )}
                  </div>
                </div>
              ) : (
                <div>
                  <label className="stat-label block mb-1">Base URL</label>
                  <input value={f.endpoint} onChange={e => setF({ ...f, endpoint: e.target.value })} className="input-field font-mono" placeholder="http://localhost:11434" />
                </div>
              )}

              <div>
                <label className="stat-label block mb-1">Default model</label>
                <input list={`models-${id}`} value={f.model} onChange={e => setF({ ...f, model: e.target.value })} className="input-field font-mono" />
                <datalist id={`models-${id}`}>{list?.map(m => <option key={m.name} value={m.name} />)}</datalist>
                {modelsError[id] && !blocked && <p className="text-2xs text-bear mt-1">{modelsError[id]}</p>}
                {list && !list.some(m => m.name === f.model || m.name === `${f.model}:latest`) && (
                  <p className="text-2xs text-neutral mt-1">{f.model} is not among the models {id === 'OLLAMA' ? 'installed' : 'offered to this key'}.</p>
                )}
                {info && <div className="mt-2"><CapabilityChips caps={info.capabilities} labels={labels} />
                  {!info.capabilitiesKnown && <p className="text-2xs text-gray-500 mt-1">This Ollama doesn't report capabilities; only text and JSON are assumed.</p>}</div>}
              </div>

              {id === 'OLLAMA' && list && list.length > 0 && (
                <div>
                  <div className="stat-label mb-1">Available models</div>
                  <ul className="space-y-1">
                    {list.map(m => (
                      <li key={m.name} className="flex items-center justify-between gap-2 text-xs">
                        <button onClick={() => setF({ ...f, model: m.name })} className={`font-mono text-left ${m.name === f.model ? 'text-brand-light font-semibold' : 'text-ink hover:text-brand-light'}`}>{m.name}</button>
                        <span className="text-2xs text-gray-500">{m.capabilities.map(c => labels[c]?.split(' ')[0] ?? c).join(' · ') || 'no chat capability'}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              <div className="grid grid-cols-3 gap-2">
                <div><label className="stat-label block mb-1">Temperature</label>
                  <input type="number" step="0.1" min={0} max={2} value={f.temperature} onChange={e => setF({ ...f, temperature: e.target.value })} className="input-field" /></div>
                <div><label className="stat-label block mb-1">Max tokens</label>
                  <input type="number" min={1024} placeholder="default" value={f.maxTokens} onChange={e => setF({ ...f, maxTokens: e.target.value })} className="input-field" /></div>
                <div><label className="stat-label block mb-1">Timeout (s)</label>
                  <input type="number" min={5} max={600} value={f.timeoutSeconds} onChange={e => setF({ ...f, timeoutSeconds: e.target.value })} className="input-field" /></div>
              </div>
              <p className="text-2xs text-gray-500">Temperature applies to the assistant only. Classification, extraction and scan reading always run at 0, so the same document reads the same way every time.</p>

              <div className="flex items-center gap-2">
                <button onClick={() => test(id)} disabled={testing !== null || blocked}
                  className="btn-secondary text-xs px-3 py-1.5 flex items-center gap-1.5 disabled:opacity-50">
                  {testing === id ? <Loader2 size={12} className="animate-spin" /> : <Zap size={12} />} Test Connection
                </button>
                <button onClick={() => refreshModels(id)} disabled={refreshing || blocked}
                  className="btn-ghost text-xs px-2 py-1.5 flex items-center gap-1.5 disabled:opacity-50">
                  <RefreshCw size={12} className={refreshing ? 'animate-spin' : ''} /> Refresh models
                </button>
              </div>
              {result && (
                <div className="rounded-lg border border-surface-border p-3 space-y-2">
                  <TestSteps result={result} />
                  {result.capabilities.length > 0 && <CapabilityChips caps={result.capabilities} labels={labels} />}
                  {result.latencyMs != null && <p className="text-2xs text-gray-500">Structured-output check took {(result.latencyMs / 1000).toFixed(1)}s</p>}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Task routing */}
      <div className="card">
        <h3 className="text-sm font-bold text-ink mb-1">Model per task</h3>
        <p className="text-2xs text-gray-500 mb-3">
          {mode === 'CUSTOM' ? 'Choose a provider and model for each task.' : 'The privacy mode sets the provider; the model can still be chosen.'}
          {' '}A model is only accepted for a task it can do.
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead><tr className="text-left">
              <th className="stat-label pb-2 pr-3">Task</th><th className="stat-label pb-2 pr-3">Needs</th>
              <th className="stat-label pb-2 pr-3">Provider</th><th className="stat-label pb-2 pr-3">Model</th><th className="stat-label pb-2">Check</th>
            </tr></thead>
            <tbody>
              {view.tasks.map(t => {
                const provider = effectiveProvider(t.task);
                const r = routes[t.task] ?? { provider: null, model: '' };
                const modelName = r.provider === provider && r.model ? r.model : '';
                const shown = modelName || (provider === 'GEMINI' ? gemini.model : provider === 'OLLAMA' ? ollama.model : '');
                const info = provider ? modelInfo(provider, shown) : undefined;
                const missing = info ? t.requires.filter(c => !info.capabilities.includes(c)) : [];
                return (
                  <tr key={t.task} className="border-t border-surface-border align-top">
                    <td className="py-2 pr-3 text-ink">{TASK_NAMES[t.task] ?? t.label}
                      {t.readsDocuments && <div className="text-2xs text-gray-500">reads your documents</div>}</td>
                    <td className="py-2 pr-3 text-gray-400">{t.requires.map(c => labels[c]?.split(' ')[0] ?? c).join(', ')}</td>
                    <td className="py-2 pr-3">
                      <select value={provider ?? ''} disabled={mode !== 'CUSTOM'} className="input-field py-1 disabled:opacity-70"
                        onChange={e => setRoutes(rs => ({ ...rs, [t.task]: { provider: (e.target.value || null) as LlmProviderId | null, model: '' } }))}>
                        <option value="GEMINI">Gemini</option>
                        <option value="OLLAMA">Ollama</option>
                        <option value="">Off</option>
                      </select>
                    </td>
                    <td className="py-2 pr-3">
                      {provider ? (
                        <select value={modelName} className="input-field py-1 font-mono"
                          onChange={e => setRoutes(rs => ({ ...rs, [t.task]: { provider, model: e.target.value } }))}>
                          <option value="">Default ({provider === 'GEMINI' ? gemini.model : ollama.model})</option>
                          {(models[provider] ?? []).map(m => (
                            <option key={m.name} value={m.name} disabled={!t.requires.every(c => m.capabilities.includes(c))}>
                              {m.name}{t.requires.every(c => m.capabilities.includes(c)) ? '' : ' — lacks ' + t.requires.filter(c => !m.capabilities.includes(c)).join(', ')}
                            </option>
                          ))}
                        </select>
                      ) : <span className="text-gray-500">Switched off</span>}
                    </td>
                    <td className="py-2">
                      {!provider ? <span className="text-gray-500">—</span>
                        : !info ? <span className="text-gray-500" title="Capabilities not known until the provider is reachable">?</span>
                        : missing.length === 0 ? <span className="text-bull flex items-center gap-1"><Check size={12} /> OK</span>
                        : <span className="text-bear">Lacks {missing.map(c => labels[c]?.split(' ')[0] ?? c).join(', ')}</span>}
                      {fallback && provider && <div className="text-2xs text-gray-500">fallback: {mode === 'LOCAL' && provider === 'OLLAMA' ? 'none (local only)' : PROVIDER_LABEL[provider === 'GEMINI' ? 'OLLAMA' : 'GEMINI']}</div>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        <div className="flex flex-wrap items-center gap-6 mt-4 pt-3 border-t border-surface-border text-xs">
          <label className="flex items-center gap-2 text-ink">
            <input type="checkbox" checked={fallback} onChange={e => setFallback(e.target.checked)} />
            Fall back to the other provider when a task's provider fails
          </label>
          <label className="flex items-center gap-2 text-gray-400">
            Retries
            <select value={retries} onChange={e => setRetries(e.target.value)} className="input-field py-1 w-16">
              {['0', '1', '2', '3'].map(n => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
        </div>
        <p className="text-2xs text-gray-500 mt-2">
          With fallback off, a failed call is reported and the email or document waits to be retried — it is never quietly handed to another provider.
        </p>
      </div>

      {/* Health */}
      <div className="card">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-bold text-ink">LLM processing — today</h3>
          <button onClick={loadHealth} disabled={healthLoading} className="btn-ghost text-xs px-2 py-1 flex items-center gap-1">
            <RefreshCw size={11} className={healthLoading ? 'animate-spin' : ''} /> Refresh
          </button>
        </div>
        <div className="grid sm:grid-cols-2 gap-2 mb-4">
          {health?.providers.map(p => (
            <div key={p.provider} className="rounded-lg border border-surface-border p-2.5 text-xs flex items-center justify-between">
              <span className="text-ink font-semibold">{PROVIDER_LABEL[p.provider]}</span>
              <span className={p.connected ? 'text-bull' : p.connected === false ? 'text-bear' : 'text-gray-500'}>
                {p.connected ? '✓ ' : p.connected === false ? '✗ ' : ''}{p.detail}</span>
            </div>
          ))}
        </div>
        {u && (
          <>
            <div className="grid grid-cols-2 sm:grid-cols-5 gap-3 mb-4">
              {[
                ['Requests', u.requests], ['Successful', u.successful], ['Failed', u.failed], ['Fallbacks', u.fallbacks],
                ['Avg latency', u.averageLatencyMs != null ? `${(u.averageLatencyMs / 1000).toFixed(1)}s` : '—'],
              ].map(([k, v]) => (
                <div key={k as string}><div className="stat-label">{k}</div><div className="text-ink font-semibold font-mono">{v}</div></div>
              ))}
            </div>
            <div className="grid sm:grid-cols-2 gap-3 text-xs mb-4">
              <div><div className="stat-label">Last successful request</div>
                <div className="text-ink">{u.lastSuccess ? `${new Date(u.lastSuccess.at).toLocaleString('en-IN')} · ${u.lastSuccess.provider}:${u.lastSuccess.model ?? ''} · ${TASK_NAMES[u.lastSuccess.task] ?? u.lastSuccess.task}` : '—'}</div></div>
              <div><div className="stat-label">Last error</div>
                <div className={u.lastError ? 'text-bear' : 'text-ink'}>{u.lastError ? `${new Date(u.lastError.at).toLocaleString('en-IN')} · ${u.lastError.provider} · ${(u.lastError.errorCategory ?? '').replace(/_/g, ' ').toLowerCase()}` : '—'}</div></div>
            </div>
            {u.groups.length > 0 && (
              <table className="w-full text-xs">
                <thead><tr className="text-left">
                  <th className="stat-label pb-1.5">Task</th><th className="stat-label pb-1.5">Provider / model</th>
                  <th className="stat-label pb-1.5 text-right">Requests</th><th className="stat-label pb-1.5 text-right">Failed</th>
                  <th className="stat-label pb-1.5 text-right">Fallbacks</th><th className="stat-label pb-1.5 text-right">Avg</th>
                </tr></thead>
                <tbody>{u.groups.map(g => (
                  <tr key={`${g.task}-${g.provider}-${g.model}`} className="border-t border-surface-border">
                    <td className="py-1.5 text-ink">{TASK_NAMES[g.task] ?? g.task}</td>
                    <td className="py-1.5 font-mono text-gray-400">{g.provider}:{g.model}</td>
                    <td className="py-1.5 text-right font-mono">{g.requests}</td>
                    <td className={`py-1.5 text-right font-mono ${g.failed ? 'text-bear' : ''}`}>{g.failed}</td>
                    <td className="py-1.5 text-right font-mono">{g.fallbacks}</td>
                    <td className="py-1.5 text-right font-mono">{g.averageLatencyMs != null ? `${(g.averageLatencyMs / 1000).toFixed(1)}s` : '—'}</td>
                  </tr>
                ))}</tbody>
              </table>
            )}
            <p className="text-2xs text-gray-500 mt-3">Only metadata is kept here (task, provider, model, prompt version, duration, outcome) — never prompts or document contents.</p>
          </>
        )}
      </div>

      <p className="text-2xs text-gray-500 px-1">
        The model only reads documents. Whatever it returns goes through the same validation, duplicate checks and reconciliation whichever
        provider answered, and nothing it says is written without them. Changing the provider or model never re-imports or changes
        recorded transactions; if a document is read again by a different model and a line differs, it goes to review.
      </p>
    </div>
  );
}

function presetFor(mode: PrivacyMode, task: string): LlmProviderId | null {
  if (mode === 'LOCAL') return 'OLLAMA';
  if (mode === 'CLOUD') return 'GEMINI';
  if (mode === 'HYBRID') return ['DOCUMENT_EXTRACTION', 'DOCUMENT_TRANSCRIPTION', 'FINANCIAL_ANALYSIS'].includes(task) ? 'GEMINI' : 'OLLAMA';
  return null;
}
