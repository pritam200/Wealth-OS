/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Light theme. Page sits on a faint cool grey so white cards read as raised panels
        // without needing shadows; separation comes from a crisp hairline border.
        surface: {
          DEFAULT: '#F6F8FB',   // page background
          card:    '#FFFFFF',
          panel:   '#FFFFFF',
          border:  '#E3E8EF',
          hover:   '#F1F5F9',
          muted:   '#F1F5F9',
        },
        // Primary text. Named `ink` rather than reusing `white` so the class actually says
        // what it means — `text-ink` is the highest-emphasis text colour in either theme.
        ink: '#0F172A',

        brand: {
          DEFAULT: '#2563EB',
          light:   '#1D4ED8',   // darker than DEFAULT on purpose: "light" here means the
                                // variant used for text/icons, which needs MORE contrast on
                                // a white background, not less.
          dark:    '#1E40AF',
          pink:    '#2563EB',
          glow:    'rgba(37,99,235,0.18)',
        },
        // Accents darkened from their dark-theme values to hold contrast on white.
        bull:    '#047857',   // emerald — positive / buy
        bear:    '#DC2626',   // rose — negative / sell
        neutral: '#B45309',   // amber — warning / watch
        accent:  '#2563EB',   // blue — primary trigger
        gold:    '#B45309',

        // NOTE: this is a TEXT-EMPHASIS scale, not a lightness scale — a lower number means
        // more prominent, which is how all ~660 existing usages read (gray-500/600 for muted
        // captions, gray-200/300 for near-primary text). Inverting the values here flips the
        // whole app to light mode without touching a single component.
        gray: {
          100: '#0F172A',   // highest emphasis
          200: '#1E293B',
          300: '#334155',
          400: '#475569',
          // Every text tier clears WCAG AA (>=4.5:1) against both the white card and the
          // page background — the faint tiers are used at 0.72rem, where a 3.5:1 grey is
          // genuinely hard to read.
          500: '#5B6B7F',   // muted body/caption   5.45:1
          600: '#647082',   // faint                5.02:1
          700: '#687485',   // faintest / labels    4.75:1
          800: '#E3E8EF',   // borders
          900: '#F1F5F9',   // subtle fills
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Consolas', 'monospace'],
      },
      backgroundImage: {
        'brand-gradient':      'linear-gradient(120deg, #2563EB 0%, #1E40AF 100%)',
        'brand-gradient-soft': 'linear-gradient(120deg, rgba(37,99,235,0.10) 0%, rgba(30,64,175,0.04) 100%)',
        'mesh-glow': 'none',
        'bull-gradient': 'linear-gradient(120deg, #047857 0%, #065F46 100%)',
        'bear-gradient': 'linear-gradient(120deg, #DC2626 0%, #B91C1C 100%)',
        'gold-gradient': 'linear-gradient(120deg, #B45309 0%, #92400E 100%)',
      },
      borderRadius: {
        xl2: '18px',
      },
      boxShadow: {
        card:  '0 1px 2px rgba(15,23,42,0.05)',
        panel: '0 8px 28px rgba(15,23,42,0.10)',
        glow:  '0 0 0 1px rgba(37,99,235,0.35)',
        'glow-bull': '0 0 0 1px rgba(4,120,87,0.30)',
        'glow-bear': '0 0 0 1px rgba(220,38,38,0.30)',
        lift:  '0 4px 14px rgba(15,23,42,0.08), 0 0 0 1px rgba(227,232,239,0.9)',
      },
      transitionTimingFunction: {
        snap: 'cubic-bezier(0.16, 1, 0.3, 1)',
      },
    },
  },
  plugins: [],
}
