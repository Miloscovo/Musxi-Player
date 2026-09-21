import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// Classic IIFE output works with the existing restricted file:// host without
// enabling file-origin module access or weakening CEF browser security.
export default defineConfig(({ command }) => ({
  base: './',
  plugins: [vue(), {
    name: 'cef-local-html',
    generateBundle() {
      const html = readFileSync(new URL('./index.html', import.meta.url), 'utf8')
        .replace('<head>', `<head>\n<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self'; img-src data:;">\n<link rel="stylesheet" href="./style.css">`)
        .replace('<script type="module" src="/src/main.ts"></script>', '<script defer src="./app.js"></script>');
      this.emitFile({ type: 'asset', fileName: 'index.html', source: html });
    }
  }],
  define: command === 'build' ? { 'process.env.NODE_ENV': JSON.stringify('production') } : {},
  build: {
    target: 'chrome120',
    lib: { entry: 'src/main.ts', name: 'MusxiUI', formats: ['iife'], fileName: () => 'app.js', cssFileName: 'style' }
  }
}));
