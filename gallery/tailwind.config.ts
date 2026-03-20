import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        phosphor: '#33FF33',
        'phosphor-dim': '#1A6B1A',
        cyan: '#00FFFF',
        'cyan-dim': '#006666',
        amber: '#FFB000',
        'amber-dim': '#664600',
        crt: '#0A0A0A',
      },
      fontFamily: {
        mono: ['"Courier New"', 'Courier', 'monospace'],
      },
    },
  },
  plugins: [],
} satisfies Config
