import { defineConfig } from 'vite';
export default defineConfig({ build: { outDir: process.env.MOSS_FRONTEND_OUT_DIR || 'dist', emptyOutDir: true }, server: { proxy: { '/api': { target: 'http://127.0.0.1:8080' } } } });
