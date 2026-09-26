import { NativeBridge } from '../src/native.js';
const native = new NativeBridge();
native.on('diagnostic', m => console.error(m));
try { await native.start(); console.log('Native capabilities:', native.capabilities); await native.call('mode', { remote: false }); }
finally { native.close(); }
