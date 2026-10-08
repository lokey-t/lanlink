import { cp, mkdir, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('.', import.meta.url));
await rm(root + 'dist', { recursive: true, force: true });
await mkdir(root + 'dist', { recursive: true });
for (const file of ['index.html', 'style.css', 'app.js', 'locales.js', 'assets']) {
  await cp(root + file, root + 'dist/' + file, { recursive: true });
}
console.log('LanLink website built in website/dist');
