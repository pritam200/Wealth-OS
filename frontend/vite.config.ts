import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    // Allow any ngrok-free.app subdomain so a rotated tunnel URL doesn't need
    // this file edited each time; add the exact host too as a fast path.
    allowedHosts: ['.ngrok-free.app'],
    proxy: {
      '/api': {
        // Overridable so the UI can be pointed at a second backend (e.g. a scratch database).
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
