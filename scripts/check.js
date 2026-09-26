import { readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
for (const dir of ['src', 'web', 'tests', 'scripts', 'desktop']) for (const file of await readdir(dir)) {
  if (!/\.(js|cjs)$/.test(file)) continue; const r = spawnSync(process.execPath, ['--check', dir + '/' + file], { stdio: 'inherit' }); if (r.status) process.exit(r.status);
}
console.log('JavaScript syntax checks passed');
