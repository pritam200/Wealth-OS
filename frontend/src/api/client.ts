import axios from 'axios';

// Same-origin by default: the Vite dev server (see vite.config.ts) and nginx/Caddy in production both
// forward /api to the backend. A hard-coded http://localhost:8080 would point a browser on any other
// machine at *that machine's* own localhost. Set VITE_API_URL only to target a different backend.
const API_BASE = import.meta.env.VITE_API_URL ?? '';

export const apiClient = axios.create({
  baseURL: API_BASE,
  // Without this, ngrok's free tier serves an HTML "visit site" warning page
  // (still HTTP 200) instead of the real JSON response on tunneled requests.
  headers: { 'Content-Type': 'application/json', 'ngrok-skip-browser-warning': 'true' },
});

// Attach JWT on every request
apiClient.interceptors.request.use(config => {
  const token = localStorage.getItem('access_token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// Auto-refresh on 401
apiClient.interceptors.response.use(
  res => res,
  async error => {
    const original = error.config;
    if (error.response?.status === 401 && !original._retry) {
      original._retry = true;
      const refreshToken = localStorage.getItem('refresh_token');
      if (refreshToken) {
        try {
          const { data } = await axios.post(`${API_BASE}/api/auth/refresh`, { refreshToken }, {
            headers: { 'ngrok-skip-browser-warning': 'true' },
          });
          localStorage.setItem('access_token', data.accessToken);
          original.headers.Authorization = `Bearer ${data.accessToken}`;
          return apiClient(original);
        } catch {
          localStorage.clear();
          window.location.href = '/login';
        }
      }
    }
    return Promise.reject(error);
  }
);

/*
 * Every database round trip costs ~100 ms (the database is remote), so the heavy summaries take
 * seconds each, and pages kept asking for the same ones: several sections of one page at once,
 * and again on every page switch. Identical GETs in flight share one request, and the summaries
 * below are reused for a short while. Any change (POST/PUT/PATCH/DELETE) empties the reuse
 * cache, so what a page shows after an edit is never older than the edit.
 */
const REUSE_MS = 20_000;
const REUSABLE = [
  /^\/api\/wealth\/summary$/,
  /^\/api\/portfolios$/,
  /^\/api\/portfolios\/combined-summary$/,
  /^\/api\/portfolios\/\d+\/summary$/,
  /^\/api\/tracking\/summary$/,
  /^\/api\/market\/overview$/,
];
const inFlight = new Map<string, Promise<unknown>>();
const reused = new Map<string, { at: number; res: unknown }>();
const rawGet = apiClient.get.bind(apiClient);

apiClient.get = ((url: string, config?: Parameters<typeof rawGet>[1]) => {
  // A cancellable or non-JSON request is the caller's own; it is never shared.
  if (config?.signal || (config?.responseType && config.responseType !== 'json')) return rawGet(url, config);
  const key = url + (config?.params ? `?${JSON.stringify(config.params)}` : '');
  const kept = reused.get(key);
  if (kept && Date.now() - kept.at < REUSE_MS) return Promise.resolve(kept.res);
  const pending = inFlight.get(key);
  if (pending) return pending;
  const p = rawGet(url, config)
    .then(res => {
      if (!config?.params && REUSABLE.some(r => r.test(url))) reused.set(key, { at: Date.now(), res });
      return res;
    })
    .finally(() => inFlight.delete(key));
  inFlight.set(key, p);
  return p;
}) as typeof apiClient.get;

apiClient.interceptors.request.use(config => {
  if (config.method && config.method.toLowerCase() !== 'get') reused.clear();
  return config;
});

// And again once the change has landed, so a summary read while it ran is not kept.
const afterChange = (method?: string) => { if (method && method.toLowerCase() !== 'get') reused.clear(); };
apiClient.interceptors.response.use(
  res => { afterChange(res.config?.method); return res; },
  error => { afterChange(error.config?.method); return Promise.reject(error); },
);
