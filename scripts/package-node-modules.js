import { cpSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const [output, target] = process.argv.slice(2);
if (!output || !['darwin_arm64', 'darwin_x64', 'win32_x64'].includes(target))
  throw Error('Usage: node scripts/package-node-modules.js <package directory> <target>');
const modules = fileURLToPath(new URL('../node_modules', import.meta.url));
const nativeBuilds = path.join(modules, 'koffi', 'build', 'koffi');
const binding = path.join(nativeBuilds, target, 'koffi.node');
if (!statSync(binding, { throwIfNoEntry: false })?.isFile())
  throw Error(`Missing Koffi native binding for ${target}. Run npm ci --ignore-scripts before packaging.`);
cpSync(modules, path.join(output, 'node_modules'), {
  recursive: true,
  verbatimSymlinks: true,
  filter: source => path.dirname(source) !== nativeBuilds || path.basename(source) === target,
});
