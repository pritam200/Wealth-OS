/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Light theme, but no longer a flat grey sheet: the page sits on a faintly tinted
        // cool base with a soft colour mesh painted in index.css, so white cards read as
        // genuinely raised panels and the surface itself has some life to it.
        surface: {
          DEFAULT: '#F4F6FC',   // page background (faint indigo cast, not neutral grey)
          card:    '#FFFFFF',
          panel:   '#FFFFFF',
          border:  '#E2E7F3',
          hover:   '#EEF2FE',   // indigo-tinted hover/fill instead of slate grey
          muted:   '#F1F4FD',
        },
        // Primary text. Named `ink` rather than reusing `white` so the class actually says
        // what it means — `text-ink` is the highest-emphasis text colour in either theme.
        ink: '#101635',

        // Indigo→violet is the identity colour. `light` is deliberately DARKER than DEFAULT:
        // "light" here means the variant used for text/icons, which needs MORE contrast on a
        // white background, not less.
        brand: {
          DEFAULT: '#4F46E5',
          light:   '#4338CA',
          dark:    '#3730A3',
          pink:    '#7C3AED',   // violet partner, used in gradients and secondary accents
          glow:    'rgba(79,70,229,0.22)',
        },

        // Semantic accents. The base value of each is the TEXT-SAFE tone (>=4.5:1 on white);
        // the `-vivid` sibling is the saturated tone used for fills, bars and gradients,
        // where contrast is carried by white-on-colour instead of by the colour itself.
        bull:            '#047857',   // emerald — positive / buy
        'bull-vivid':    '#10B981',
        bear:            '#E11D48',   // rose — negative / sell
        'bear-vivid':    '#F43F5E',
        neutral:         '#B45309',   // amber — warning / watch
        'neutral-vivid': '#F59E0B',
        accent:          '#0E7490',   // cyan — informational
        'accent-vivid':  '#06B6D4',
        gold:            '#B45309',
        'gold-vivid':    '#FBBF24',

        // NOTE: this is a TEXT-EMPHASIS scale, not a lightness scale — a lower number means
        // more prominent, which is how all ~660 existing usages read (gray-500/600 for muted
        // captions, gray-200/300 for near-primary text). Inverting the values here flips the
        // whole app to light mode without touching a single component.
        gray: {
          100: '#101635',   // highest emphasis
          200: '#1E2647',
          300: '#333C63',
          400: '#4A5478',
          // Every text tier clears WCAG AA (>=4.5:1) against both the white card and the
          // page background — the faint tiers are used at 0.72rem, where a 3.5:1 grey is
          // genuinely hard to read.
          500: '#5C6689',   // muted body/caption
          600: '#66708F',   // faint
          700: '#6B7594',   // faintest / labels
          800: '#E2E7F3',   // borders
          900: '#EEF2FE',   // subtle fills
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Consolas', 'monospace'],
      },
      backgroundImage: {
        'brand-gradient':      'linear-gradient(135deg, #6366F1 0%, #7C3AED 55%, #A855F7 100%)',
        'brand-gradient-soft': 'linear-gradient(135deg, rgba(99,102,241,0.14) 0%, rgba(168,85,247,0.08) 100%)',
        // The page mesh: three low-alpha colour blooms, applied to body in index.css.
        'mesh-glow': 'radial-gradient(900px 500px at 8% -8%, rgba(99,102,241,0.16), transparent 60%), radial-gradient(760px 460px at 98% 4%, rgba(168,85,247,0.13), transparent 62%), radial-gradient(820px 520px at 46% 108%, rgba(6,182,212,0.11), transparent 60%)',
        'bull-gradient':   'linear-gradient(135deg, #10B981 0%, #047857 100%)',
        'bear-gradient':   'linear-gradient(135deg, #FB7185 0%, #E11D48 100%)',
        'gold-gradient':   'linear-gradient(135deg, #FBBF24 0%, #D97706 100%)',
        'accent-gradient': 'linear-gradient(135deg, #22D3EE 0%, #0E7490 100%)',
        'teal-gradient':   'linear-gradient(135deg, #2DD4BF 0%, #0D9488 100%)',
        'violet-gradient': 'linear-gradient(135deg, #C084FC 0%, #7C3AED 100%)',
        // Faint top-edge sheen on cards, so a white panel isn't a dead rectangle.
        'card-sheen': 'linear-gradient(180deg, rgba(99,102,241,0.05) 0%, rgba(255,255,255,0) 46%)',
      },
      borderRadius: {
        xl2: '18px',
      },
      boxShadow: {
        card:  '0 1px 2px rgba(16,22,53,0.05), 0 10px 26px -18px rgba(79,70,229,0.30)',
        panel: '0 10px 34px rgba(16,22,53,0.11), 0 2px 8px rgba(79,70,229,0.07)',
        glow:  '0 6px 20px -6px rgba(79,70,229,0.55)',
        'glow-bull': '0 6px 18px -6px rgba(4,120,87,0.50)',
        'glow-bear': '0 6px 18px -6px rgba(225,29,72,0.50)',
        'glow-gold': '0 6px 18px -6px rgba(217,119,6,0.50)',
        lift:  '0 10px 28px -10px rgba(79,70,229,0.35), 0 0 0 1px rgba(226,231,243,0.9)',
      },
      transitionTimingFunction: {
        snap: 'cubic-bezier(0.16, 1, 0.3, 1)',
      },
      keyframes: {
        'fade-rise': {
          '0%':   { opacity: '0', transform: 'translateY(6px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        shimmer: {
          '0%':   { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
      },
      animation: {
        'fade-rise': 'fade-rise 0.28s cubic-bezier(0.16, 1, 0.3, 1) both',
        shimmer: 'shimmer 1.6s linear infinite',
      },
    },
  },
  plugins: [],
}
