import { defineConfig } from 'vite';
import dts from 'vite-plugin-dts';

export default defineConfig({
  build: {
    lib: {
      entry: 'src/index.ts',
      name: 'CoreGlobe',
      formats: ['es', 'umd'],
      fileName: (format) => `core-globe.${format}.js`,
    },
    rollupOptions: {
      external: ['three'],
      output: {
        globals: { three: 'THREE' },
      },
    },
  },
  // public/data/*.geojson are copied verbatim to dist/data/ — the renderer fetches them
  // at runtime (same "small core bundle + fetched data" split as the Android WebView,
  // which intercepts requests for the same two files from its own assets).
  plugins: [dts({ rollupTypes: true })],
});
