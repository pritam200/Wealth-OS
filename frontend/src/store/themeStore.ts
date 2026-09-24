import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type Theme = 'light' | 'dark' | 'system';

function systemPrefersDark(): boolean {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
}

// Tailwind's `dark:` variants (and our CSS-variable palette in index.css) are keyed off a
// `dark` class on <html> — this is the one place that class gets added/removed, so every
// caller (the store's own rehydration, the toggle, and a live OS-preference listener) goes
// through here instead of touching classList directly.
function applyTheme(theme: Theme) {
  const isDark = theme === 'dark' || (theme === 'system' && systemPrefersDark());
  document.documentElement.classList.toggle('dark', isDark);
}

interface ThemeState {
  theme: Theme;
  setTheme: (theme: Theme) => void;
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set) => ({
      theme: 'system',
      setTheme: (theme) => {
        applyTheme(theme);
        set({ theme });
      },
    }),
    {
      name: 'theme-store',
      onRehydrateStorage: () => (state) => {
        applyTheme(state?.theme ?? 'system');
      },
    }
  )
);

// Fresh browser with nothing persisted yet: apply the system preference immediately rather
// than waiting for the first render, so there's no light-mode flash for a dark-OS user.
applyTheme(useThemeStore.getState().theme);

window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener('change', () => {
  if (useThemeStore.getState().theme === 'system') applyTheme('system');
});
