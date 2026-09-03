import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface PrivacyState {
  // Masks net worth / holdings values app-wide. Defaults to true (masked) on every fresh
  // browser/device so anyone who opens the app — accidentally or over someone's shoulder —
  // never sees real numbers without deliberately choosing to reveal them.
  masked: boolean;
  toggle: () => void;
}

export const usePrivacyStore = create<PrivacyState>()(
  persist(
    (set) => ({
      masked: true,
      toggle: () => set((s) => ({ masked: !s.masked })),
    }),
    { name: 'privacy-store' }
  )
);
