/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // True near-black with a cool blue-violet cast — Linear/Stripe territory, not a
        // "dark slate dashboard". Cards sit on this as glass panels, not flat rectangles.
        surface: {
          DEFAULT: '#050509',
          card:    '#0e0d16',
          panel:   '#131220',
          border:  '#232032',
          hover:   '#1a1826',
          muted:   '#1e1c2c',
        },
        // Multi-stop indigo → magenta → amber brand identity (Stripe-style), used sparingly
        // as gradients on hero elements, not as a flat fill everywhere.
        brand: {
          DEFAULT: '#6d5efc',
          light:   '#9b8aff',
          dark:    '#4c3adb',
          pink:    '#e84fd9',
          glow:    'rgba(109,94,252,0.25)',
        },
        bull:    '#00d68f',   // emerald
        bear:    '#ff5470',   // rose
        neutral: '#ffb454',   // amber
        accent:  '#00c2ff',   // electric blue — secondary highlight, distinct from brand violet
        gold:    '#f5c451',   // premium/reward accent (cards, rewards, milestones)
        gray: {
          100: '#f6f5fb', 200: '#e6e4f0', 300: '#cdc9dc', 400: '#a9a4c2',
          500: '#8d87ac', 600: '#78729a', 700: '#5f5980',
          800: '#332f4a', 900: '#181628',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Consolas', 'monospace'],
      },
      backgroundImage: {
        'brand-gradient':      'linear-gradient(120deg, #6d5efc 0%, #9b5cf9 45%, #e84fd9 100%)',
        'brand-gradient-soft': 'linear-gradient(120deg, rgba(109,94,252,0.18) 0%, rgba(232,79,217,0.10) 100%)',
        'mesh-glow': 'radial-gradient(circle at 12% -10%, rgba(109,94,252,0.22) 0%, transparent 42%),' +
                      'radial-gradient(circle at 100% 0%, rgba(0,194,255,0.14) 0%, transparent 38%),' +
                      'radial-gradient(circle at 50% 110%, rgba(232,79,217,0.10) 0%, transparent 45%)',
        'bull-gradient': 'linear-gradient(120deg, #00d68f 0%, #00b8d4 100%)',
        'bear-gradient': 'linear-gradient(120deg, #ff5470 0%, #ff8a5b 100%)',
        'gold-gradient': 'linear-gradient(120deg, #f5c451 0%, #ff8a5b 100%)',
      },
      borderRadius: {
        xl2: '18px',
      },
      boxShadow: {
        card:  '0 1px 2px rgba(0,0,0,0.55), inset 0 1px 0 rgba(255,255,255,0.045)',
        panel: '0 12px 48px rgba(0,0,0,0.65)',
        glow:  '0 0 32px rgba(109,94,252,0.32)',
        'glow-bull': '0 0 24px rgba(0,214,143,0.25)',
        'glow-bear': '0 0 24px rgba(255,84,112,0.25)',
        lift:  '0 10px 30px rgba(0,0,0,0.5), 0 0 0 1px rgba(109,94,252,0.15)',
      },
      transitionTimingFunction: {
        snap: 'cubic-bezier(0.16, 1, 0.3, 1)',
      },
    },
  },
  plugins: [],
}
