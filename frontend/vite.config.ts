/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// base '/static/' — только в build: раздача идёт из-под /static/,
// в dev нужен корень. /api проксируется на Ktor (localhost:8080);
// QA поднимает бэкенд на другом порту — цель переопределяется
// переменной VITE_PROXY_TARGET.
export default defineConfig(({ command }) => ({
  plugins: [react()],
  base: command === 'build' ? '/static/' : '/',
  server: {
    proxy: {
      '/api': process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
    },
  },
  // Vitest — только чистые функции (без jsdom/RTL)
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
  },
}))
