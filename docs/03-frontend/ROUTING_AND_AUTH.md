# Routing & Auth

## `PrivateRoute` (in `App.tsx`)

```tsx
function PrivateRoute({ children }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated);
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />;
}
```

Reads `isAuthenticated` straight from the persisted Zustand store — **no token-expiry check at
the route level**. Token validity is enforced downstream by the axios response interceptor (a
request that actually fails with 401 triggers the refresh-or-redirect flow below), not by this
guard proactively checking expiry.

## `src/store/authStore.ts` (40 lines)

- `login(response: AuthResponse)` — writes `access_token`/`refresh_token` to `localStorage`, sets
  `user` (userId/name/email/roles) and `isAuthenticated: true`.
- `logout()` — clears both localStorage tokens, resets `user: null`, `isAuthenticated: false`.
- Persisted (Zustand `persist` middleware), key `auth-store`.

## `src/api/client.ts` (43 lines) — the interceptor chain

```ts
const API_BASE = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';
export const apiClient = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json', 'ngrok-skip-browser-warning': 'true' },
});
```

**Request interceptor**: reads `access_token` from `localStorage`, sets
`Authorization: Bearer <token>` on every outgoing request.

**Response interceptor (401 handling)** — a single-retry pattern, not a mutex/queue:

```
on 401 AND !original._retry:
    mark original._retry = true
    read refresh_token from localStorage
    POST /api/auth/refresh { refreshToken }
    on success: store new accessToken, set header on original, retry via apiClient(original)
    on failure (or no refresh token): localStorage.clear(); window.location.href = '/login'
```

**Known limitation**: concurrent 401s each independently attempt their own refresh call — there
is no shared in-flight refresh promise. If multiple requests fail with 401 simultaneously, each
triggers its own `/api/auth/refresh` call rather than sharing one. `[PARTIALLY IMPLEMENTED —
works correctly for the common case (one failed request at a time) but is not optimal under
concurrent 401s.]`

## Auth pages

`src/pages/auth/LoginPage.tsx` / `RegisterPage.tsx` — self-contained forms, local `useState` for
fields/error/loading, no shared form library. On submit: `authApi.login`/`register` → on success,
call the store's `login(data)` → `navigate('/')`. Errors read from
`err.response?.data?.message`; `RegisterPage` additionally parses a `validationErrors` object
(matching the backend's `MethodArgumentNotValidException` handler shape) into a joined string.

## Role-based access on the frontend

`user.roles` is stored in `authStore` but **no route or component in the frontend was found
gating on role** — role-based UI restriction is `[PLANNED / NOT IMPLEMENTED]` on the frontend,
consistent with the backend's `/api/admin/**` being a reserved-but-unused security rule.
