// Bundles src/main.ts into one classic (non-module) ES2017 script so the same dist/ works from
// file:// on Tizen, from the Android TV WebView asset loader, and in a desktop browser.
import * as esbuild from 'esbuild';
import { cpSync, mkdirSync } from 'node:fs';

const serve = process.argv.includes('--serve');
mkdirSync('dist', { recursive: true });
cpSync('static', 'dist', { recursive: true });

const options = {
  entryPoints: ['src/main.ts'],
  bundle: true,
  format: 'iife',
  target: 'es2017',
  outfile: 'dist/app.js',
  sourcemap: true,
  logLevel: 'info',
};

if (serve) {
  const ctx = await esbuild.context(options);
  await ctx.watch();
  const { port } = await ctx.serve({ servedir: 'dist', port: 8080, host: '0.0.0.0' });
  console.log(`TV receiver dev server: http://localhost:${port}/  (add ?phone=<ip> to auto-connect)`);
} else {
  await esbuild.build({ ...options, minify: true });
}
