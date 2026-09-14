/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// base '/static/' — только в build: раздача идёт из-под /static/,
// в dev нужен корень. /api проксируется на Ktor (localhost:8080).
export default defineConfig(({ command }) => ({
  plugins: [react()],
  base: command === 'build' ? '/static/' : '/',
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  // Vitest — только чистые функции (без jsdom/RTL)
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
  },
}))
