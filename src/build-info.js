import {readFileSync} from 'node:fs';
let info;
try{const raw=JSON.parse(readFileSync(new URL('../build-info.json',import.meta.url),'utf8'));if(!/^\d+\.\d+\.\d+/.test(raw.version)||!/^[a-f0-9]{64}$/.test(raw.sourceFingerprint))throw Error('Invalid build information');info={version:raw.version,revision:raw.revision,sourceFingerprint:raw.sourceFingerprint,builtAt:raw.builtAt};}
catch{const version=JSON.parse(readFileSync(new URL('../package.json',import.meta.url),'utf8')).version;info={version,revision:'development',sourceFingerprint:'',builtAt:''};}
export const buildInfo=Object.freeze(info);
