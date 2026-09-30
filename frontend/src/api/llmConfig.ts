import { apiClient } from './client';

export type LlmProviderId = 'GEMINI' | 'OLLAMA';
export type PrivacyMode = 'LOCAL' | 'CLOUD' | 'HYBRID' | 'CUSTOM';
export type LlmCapability = 'TEXT' | 'JSON' | 'VISION' | 'PDF' | 'TOOLS';

export interface LlmProviderView {
  id: LlmProviderId;
  label: string;
  cloud: boolean;
  configured: boolean;
  endpoint: string | null;
  model: string;
  temperature: number;
  maxTokens: number | null;
  timeoutSeconds: number;
  apiKeySet: boolean;
  /** SAVED, ENVIRONMENT or NONE — Gemini only. */
  apiKeySource: 'SAVED' | 'ENVIRONMENT' | 'NONE' | null;
  /** "••••abcd" — the key itself is never sent to the browser. */
  apiKeyHint: string | null;
}

export interface LlmTaskView {
  task: string;
  label: string;
  requires: LlmCapability[];
  structured: boolean;
  readsDocuments: boolean;
  provider: LlmProviderId | null;
  model: string | null;
  presetProvider: LlmProviderId | null;
  fallbackProvider: LlmProviderId | null;
}

export interface LlmConfigView {
  aiEnabled: boolean;
  privacyMode: PrivacyMode;
  fallbackEnabled: boolean;
  maxRetries: number;
  saved: boolean;
  updatedAt: string | null;
  providers: LlmProviderView[];
  tasks: LlmTaskView[];
  capabilityLabels: Record<LlmCapability, string>;
  warnings: string[];
}

export interface ProviderUpdate {
  model: string;
  temperature: number;
  maxTokens: number | null;
  timeoutSeconds: number;
  endpoint?: string | null;
}

export interface LlmConfigUpdate {
  aiEnabled: boolean;
  privacyMode: PrivacyMode;
  fallbackEnabled: boolean;
  maxRetries: number;
  gemini: ProviderUpdate;
  ollama: ProviderUpdate;
  routes: { task: string; provider: LlmProviderId | null; model: string | null }[];
  /** Only when a new key was typed; omitted keeps the saved one. */
  geminiApiKey?: string;
  removeGeminiApiKey?: boolean;
}

export interface ModelInfo {
  name: string;
  capabilities: LlmCapability[];
  capabilitiesKnown: boolean;
}

export interface TestStep { label: string; ok: boolean; detail: string }

export interface TestResult {
  provider: LlmProviderId;
  model: string;
  ok: boolean;
  steps: TestStep[];
  capabilities: LlmCapability[];
  capabilitiesKnown: boolean;
  models: ModelInfo[];
  latencyMs: number | null;
}

export interface UsageEvent { at: string; task: string; provider: string; model: string | null; errorCategory: string | null }

export interface UsageGroup {
  task: string; provider: string; model: string | null;
  requests: number; successful: number; failed: number; fallbacks: number; averageLatencyMs: number | null;
}

export interface LlmHealth {
  providers: { provider: LlmProviderId; configured: boolean; connected: boolean | null; detail: string }[];
  usage: {
    requests: number; successful: number; failed: number; fallbacks: number; averageLatencyMs: number | null;
    lastSuccess: UsageEvent | null; lastError: UsageEvent | null; groups: UsageGroup[];
  };
}

export const llmConfigApi = {
  access: () => apiClient.get<{ canConfigure: boolean }>('/api/llm-config/access'),
  get: () => apiClient.get<LlmConfigView>('/api/llm-config'),
  save: (u: LlmConfigUpdate) => apiClient.put<LlmConfigView>('/api/llm-config', u),
  // POST, so a typed-but-unsaved key travels in the body, never the URL.
  test: (provider: LlmProviderId, model?: string, endpoint?: string, apiKey?: string) =>
    apiClient.post<TestResult>('/api/llm-config/test', { provider, model, endpoint, apiKey }),
  models: (provider: LlmProviderId) => apiClient.get<ModelInfo[]>('/api/llm-config/models', { params: { provider } }),
  health: () => apiClient.get<LlmHealth>('/api/llm-config/health'),
};
