import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  plugins: [{
    name: 'isolated-wallpaper-page',
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'player.html', source: readFileSync(new URL('./src/wallpaper/player.html', import.meta.url), 'utf8') });
    },
  }],
  build: {
    target: 'chrome120', outDir: 'dist/wallpaper', emptyOutDir: false,
    lib: { entry: 'src/wallpaper/renderer.ts', name: 'MusxiWallpaper', formats: ['iife'], fileName: () => 'renderer.js', cssFileName: 'renderer' },
  },
});
