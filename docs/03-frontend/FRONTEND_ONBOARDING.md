# Frontend Onboarding

## Stack (verified)

React 19, TypeScript, Vite 8, Tailwind CSS 3, Zustand, Recharts, react-router-dom, lucide-react.

## Quick start

```bash
cd frontend
npm install
npm run dev
```

Default dev server: Vite (`http://localhost:5173`). Production build serves via nginx on `:3000`
in Docker Compose (`VITE_API_URL` baked in at build time — Vite env vars are compile-time only,
so changing the backend URL requires a rebuild, not just an env var change at runtime).

## Environment

`VITE_API_URL` — the only frontend-specific env var, defaults to `http://localhost:8080` if
unset (`src/api/client.ts`). See `04-devops/ENVIRONMENT_VARIABLES.md`.

## Verify it's talking to the backend

1. Start the backend first (`02-backend/BACKEND_ONBOARDING.md`).
2. `npm run dev`, open the printed localhost URL.
3. Register a user via the UI, confirm login redirects to the dashboard (`/`).

## First things to read, in order

1. `FRONTEND_STRUCTURE.md` — routing (tabs are client state, not routes — this trips up new
   engineers), component hierarchy, design system tokens.
2. `STATE_AND_DATA_FETCHING.md` — there is **no React Query/SWR**; every fetch is manual. Know
   this before reaching for a data-fetching library out of habit.
3. `ROUTING_AND_AUTH.md` — the JWT refresh interceptor pattern (single-retry, not a mutex queue).
4. `UI_COMPONENTS_GUIDE.md` — the tab-by-tab component map, and two known dead-code items
   (`PortfolioPage.tsx`, `MfHoldingsSignals`) not to build on top of by mistake.

## Known gotchas

- **Tabs are not routes.** Switching between "Dashboard", "My Wealth", "Markets", etc. is a
  `useState<number>` in `AppShell` (`App.tsx`), not `react-router` navigation. A page refresh
  always returns to tab 1 (Market Trends), not wherever the user was.
- **Tab IDs 13 and 15 have no dedicated file.** Tab 13 renders `StocksHoldingsSignals`, exported
  from `Tab7RiskMatrix.tsx`. Tab 15 renders `DataSyncPage` from `src/pages/`, not
  `src/pages/tabs/`.
- **Privacy masking is convention-enforced, not type-enforced.** Any component displaying a
  rupee figure is expected to route through `<Amount>`/`useMaskedText()` — nothing stops a new
  component from bypassing it and leaking a real number when "hide wealth" is toggled on.
- **The gray color scale is inverted from typical Tailwind convention.** `gray.100` is the
  *darkest*/highest-emphasis color, `gray.900` is the lightest/subtlest fill — this was done
  deliberately so the whole app could flip color schemes without touching component code. Don't
  "fix" this to match Tailwind's usual convention without understanding why.
