// Disposable emulator integration: native desktop ADB backend -> real shell helper.
// No personal phone is targeted. All credentials are generated for this run.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { EventEmitter } from 'node:events';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { loadConfig } from '../src/identity.js';
import { Service } from '../src/service.js';
import { NativeBridge } from '../src/native.js';
import { AdbManager, findAdb, boundHelperPids } from '../src/adb.js';

const exec = promisify(execFile), serial = process.env.LANLINK_SERIAL || 'emulator-5580';
const adb = await findAdb();
if (!adb) throw Error('Install Android SDK Platform-Tools first');
const device = (await exec(adb, ['-s', serial, 'shell', 'getprop', 'ro.hardware'], { windowsHide: true })).stdout.trim();
if (!/ranchu|goldfish/.test(device)) throw Error('Only the disposable Android emulator is permitted');
await exec(adb,['-s',serial,'shell','cmd','statusbar','collapse'],{windowsHide:true});
class FakeNative extends EventEmitter {
  capabilities = { input: false, host: false, clipboard: false, screen: false };
  async start() {}
  async call() { return {}; }
  close() {}
}
const dir = await mkdtemp(path.join(os.tmpdir(), 'lanlink-adb-smoke-'));
const config = await loadConfig(dir);
config.permissions = { input: false, clipboard: false, screen: false, shutdown: false };
const service = new Service(config, dir, new FakeNative(), { port: 0, host: '0.0.0.0', noDiscovery: true });
let instrument, peer, realNative;
// Fixture-only methods are registered on its TCP listener, not the production QUIC handler.
const testRequest = (...args) => (peer.tcpRequest || peer.request.bind(peer))(...args);
try {
  await service.start();
  instrument = exec(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'adbSmokePort', String(service.port), '-e', 'adbSmokeToken', config.token, 'com.lanlink.app.test/com.lanlink.app.PermissionFlowTest'], { windowsHide: true, timeout: 100000, maxBuffer: 1024 * 1024 }).then(r => ({ output: r.stdout }), () => ({ failed: true }));
  const deadline = Date.now() + 20000;
  while (!(peer = [...service.peers.values()].find(p => p.info?.platform === 'android'))) {
    if (Date.now() > deadline) throw Error('Android fixture did not connect to desktop service');
    await new Promise(r => setTimeout(r, 100));
  }
  const id = peer.remote.id;
  if(process.argv.includes('--auto-match')) { const match=await service.adb.match(id);if(!match.connected || match.serial!==serial)throw Error('Group identity did not match connected emulator');console.log('PASS: automatic USB matching verifies the paired Android identity.'); }
  let result;
  if (process.argv.includes('--reuse-helper')) {
    const end = Date.now() + 10000;
    while (!(await peer.request('adbStatus')).ready) {
      if (Date.now() > end) throw Error('Persisted helper did not reconnect after application process restart');
      await new Promise(r => setTimeout(r, 200));
    }
    result = { mode: 'adb/root' };
  } else if(process.argv.includes('--local-setup')) {
    // Deliberately use an unrelated group with no network peer or group key.
    const local={config:{group:{id:'other-group'},deviceGrants:{}}}, manager=new AdbManager(local);
    const before=JSON.stringify(local.config), bootstrap=await manager.localCall(adb,serial,'bootstrap');
    const processes=await manager.command(adb,['-s',serial,'shell','ps','-A','-o','PID,ARGS']);
    for(const pid of boundHelperPids(processes,bootstrap))await manager.command(adb,['-s',serial,'shell','kill',pid]);
    await new Promise(r=>setTimeout(r,500));
    await manager.authorizeLocal(serial);
    if(before!==JSON.stringify(local.config))throw Error('Local setup changed membership or grants');
    result={mode:'adb/root'};console.log('PASS: physical ADB setup works independently of group identity and preserves control grants.');
  } else result = await new AdbManager(service).authorize({ id, serial });
  if (result.mode !== 'adb/root') throw Error('ADB enhanced mode did not activate');
  if (!(await testRequest('testReconnect', {}, 15000)).reconnected) throw Error('Persistent helper did not reconnect');
  const permissions = await peer.request('permission-status');
  if (permissions.permissions.input.systemGranted !== true || permissions.permissions.input.peerAllowed !== true || permissions.permissions.screen.systemGranted !== true || permissions.permissions.shutdown.supported !== false) throw Error('Android permission status did not match actual helper/capture/consent');
  if (!(await testRequest('testCapture', {}, 30000)).captured) throw Error('ADB capture/restart failed');
  console.log('PASS: ADB-authenticated H.264 capture without MediaProjection, stop and restart.');
  if (process.argv.includes('--dual-link')) {
    await service.links.candidate(id, null, serial);
    if (!peer.fastLink?.serial) throw Error('Encrypted ADB auxiliary route was not selected');
    console.log('PASS: encrypted ADB control tunnel supplements the existing LAN peer.');
    await testRequest('testStreamEnable', { enabled: true });
    try {
      await service.openStream(id);
      const frame = await service.videoViewer.next(id);
      if (frame.width < 100 || !service.videoViewer.sessions.get(id).route) throw Error('ADB video forwarding was not used');
      console.log('PASS: Android H.264 capture -> encrypted ADB forward -> desktop decoder.');
    } finally { await service.closeStream(id); await testRequest('testStreamEnable', { enabled: false }); }
  }
  await service.switchTo(id, 'right', false, true);
  const target = await testRequest('testTarget');
  await peer.request('input', {kind:'move',x:target.x,y:target.y});
  await peer.request('input', {kind:'button',button:0,down:true});
  await peer.request('input', {kind:'button',button:0,down:false});
  await peer.request('input', {kind:'key',code:'KeyA',down:true});
  await peer.request('input', {kind:'key',code:'KeyA',down:false});
  await peer.request('input', {kind:'button',button:0,down:true});
  await peer.request('input', {kind:'move',x:target.x+0.04,y:target.y});
  await peer.request('input', {kind:'button',button:0,down:false});
  await new Promise(r=>setTimeout(r,200));
  const received=await testRequest('testTarget');
  if(received.clicks<1 || received.keys<2 || received.moves<1)throw Error('Android did not receive mouse click/drag/keyboard: '+JSON.stringify(received));
  if (received.externalMouse) throw Error('Remote-window control must not register a system mouse');
  for(let round=0;round<3;round++) {
    const burst=[];for(let i=0;i<12;i++)for(const down of [true,false])burst.push(peer.request('input',{kind:'key',code:'Digit1',down}));
    await Promise.all(burst);
  }
  await new Promise(r=>setTimeout(r,1200));
  const digits=await testRequest('testTarget');
  if(digits.digitEvents!=='DU'.repeat(36) || digits.badKeyTimes!==0)throw Error('Candidate key press/release ordering or timestamps invalid: '+JSON.stringify(digits));
  console.log('PASS: 36 rapid Digit1 presses arrive down/up in order with matching timestamps; no extra repeats after release.');
  {
    service.native.capabilities.host = true; // FakeNative performs no computer input capture.
    await service.returnLocal();
    service.config.master = true; service.config.edges = { right: id }; service.config.dwell = 100;
    const clock = service.edge.now; let now = clock()+1000; service.edge.now=()=>now;
    try {
      await service.nativeEvent({type:'cursor',x:1000,y:540,width:1920,height:1080});
      await service.nativeEvent({type:'cursor',x:1919,y:540,width:1920,height:1080});now+=150;
      await service.nativeEvent({type:'cursor',x:1919,y:540,width:1920,height:1080});
    } finally { service.edge.now=clock; service.edge.cooldown=0; }
    if(service.active!==id || !service.capture) throw Error('Edge dwell did not acquire phone control');
    let pointerState;for(let i=0;i<10;i++){pointerState=await testRequest('testTarget');if(pointerState.externalMouse)break;await new Promise(r=>setTimeout(r,60));}

    if (!pointerState.externalMouse || !pointerState.externalKeyboard) throw Error('Native mouse and keyboard must both be registered: '+JSON.stringify(pointerState));
    const caps=peer.info.capabilities;
    const speed = pointerState.externalMouse ? 1.8 : 1;
    await service.nativeEvent({type:'input',kind:'move',dx:(target.x-service.position.x)*caps.width/speed,dy:(target.y-service.position.y)*caps.height/speed});
    while(service.moving) await new Promise(resolve=>setTimeout(resolve,10));
    const driveNativeCursor=async (x,y) => {
      for (let attempt=0;attempt<12;attempt++) {
        const dump=(await exec(adb,['-s',serial,'shell','dumpsys','input'],{windowsHide:true,maxBuffer:4*1024*1024})).stdout;
        const moves=[...dump.matchAll(/source=MOUSE[\s\S]{0,400}?xCursorPosition=([\d.]+), yCursorPosition=([\d.]+)/g)];
        const last=moves.at(-1);
        if (!last && attempt<3) {
          await peer.request('input',{kind:'move',x,y,dx:1,dy:0});
          await new Promise(resolve=>setTimeout(resolve,60));
          continue;
        }
        if (!last) throw Error('Native mouse did not generate a system cursor event: '+JSON.stringify({pointerState,device:dump.includes('LanLink Virtual Mouse'),mouseEvents:dump.includes('source=MOUSE'),position:service.position}));
        const errorX=x*caps.width-Number(last[1]),errorY=y*caps.height-Number(last[2]);
        if (Math.abs(errorX)<45 && Math.abs(errorY)<45) return;
        await peer.request('input',{kind:'move',x,y,dx:Math.round(errorX/4),dy:Math.round(errorY/4)});
      }
      throw Error('Native cursor could not reach test target');
    };
    if (pointerState.externalMouse) await driveNativeCursor(target.x,target.y);
    await service.nativeEvent({type:'input',kind:'button',button:0,down:true});
    await service.nativeEvent({type:'input',kind:'button',button:0,down:false});
    let clicked;
    for (let i=0;i<20;i++) { await new Promise(resolve=>setTimeout(resolve,100)); clicked=await testRequest('testTarget'); if(clicked.clicks>received.clicks)break; }
    if(clicked.clicks<=received.clicks) {
      const dump=await exec(adb,['-s',serial,'shell','dumpsys','input'],{maxBuffer:4*1024*1024});await writeFile('.tools/adb-pointer-failure.txt',dump.stdout);
      const shot=await exec(adb,['-s',serial,'exec-out','screencap','-p'],{encoding:'buffer',maxBuffer:8*1024*1024});await writeFile('.tools/adb-pointer-failure.png',shot.stdout);
      throw Error('Phone did not receive the click after edge switching and relative movement: '+JSON.stringify(clicked));
    }
    console.log('PASS: edge dwell -> '+(pointerState.externalMouse?'native external mouse and keyboard':'fallback pointer')+' -> relative movement -> actual phone click.');
    if (pointerState.externalMouse) {
      await peer.request('input',{kind:'key',code:'KeyA',down:true});
      await peer.request('input',{kind:'key',code:'KeyA',down:false});
      await new Promise(resolve=>setTimeout(resolve,200));
      const externalKeyProbe=await testRequest('testTarget');
      if(externalKeyProbe.externalKeys<1) {
        const dump=(await exec(adb,['-s',serial,'shell','dumpsys','input'],{windowsHide:true,maxBuffer:4*1024*1024})).stdout;
        const keyEvents=[...dump.matchAll(/KeyEvent\([^\n]+/g)].slice(-5).map(m=>m[0]);
        throw Error('Android did not identify the key event as coming from the external keyboard: '+JSON.stringify({externalKeyProbe,keyEvents}));
      }
      if (!process.argv.includes('--control-only')) {
      await exec(adb,['-s',serial,'shell','cmd','statusbar','expand-settings'],{windowsHide:true});
      await new Promise(resolve=>setTimeout(resolve,400));
      const shade=await testRequest('testSystemUi');
      if(shade.package!=='com.android.systemui' || !Number.isFinite(shade.gearX)) throw Error('Quick settings did not open for native mouse test: '+JSON.stringify(shade));
      await driveNativeCursor(shade.gearX,shade.gearY);
      await peer.request('input',{kind:'button',button:0,down:true});
      await peer.request('input',{kind:'button',button:0,down:false});
      let settings;
      for(let i=0;i<8;i++){await new Promise(resolve=>setTimeout(resolve,300));settings=await testRequest('testSystemUi');if(settings.package==='com.android.settings')break;}
      if(settings.package!=='com.android.settings') throw Error('Native mouse could not click quick-settings gear: '+JSON.stringify(settings));
      console.log('PASS: native mouse clicks a SystemUI quick-settings control.');
      }
    }
    await service.switchTo(id, 'right', false);
    let afterReturn;
    for (let i=0;i<15;i++) {
      afterReturn=await testRequest('testTarget');
      if (!afterReturn.externalMouse && !afterReturn.externalKeyboard) break;
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    if (afterReturn.externalMouse || afterReturn.externalKeyboard) throw Error('Returning to remote-window mode must unregister virtual input: '+JSON.stringify(afterReturn));
  }
  await testRequest('testBusyUi');
  const started = performance.now();
  await peer.request('input', {kind:'key',code:'KeyA',down:true});
  await peer.request('input', {kind:'key',code:'KeyA',down:false});
  if (performance.now() - started > 1000) throw Error('Input waited for the blocked Android UI');
  await new Promise(resolve => setTimeout(resolve, 1600));
  await peer.request('input', { kind: 'key', code: 'ShiftLeft', down: true });
  await peer.request('input', { kind: 'key', code: 'ShiftLeft', down: false });
  await peer.request('input', { kind: 'navigation', action: 'home' });
  if (!process.argv.includes('--control-only')) {
    await service.switchTo(id,'right',false);
    const swipe=async()=>{
      await peer.request('input',{kind:'move',x:.85,y:.005});
      await peer.request('input',{kind:'button',button:0,down:true});
      for(let i=1;i<=14;i++){await peer.request('input',{kind:'move',x:.85,y:.005+.06*i});await new Promise(r=>setTimeout(r,18));}
      await peer.request('input',{kind:'button',button:0,down:false});await new Promise(r=>setTimeout(r,500));
    };
    await swipe();await swipe();
    const shade=await testRequest('testSystemUi');
    console.log('Quick-settings target:', JSON.stringify(shade));
    if(shade.package!=='com.android.systemui' || !Number.isFinite(shade.gearX) || shade.gearY<=0)throw Error('Status-bar touch gestures did not open quick settings: '+JSON.stringify(shade));
    await peer.request('input',{kind:'move',x:shade.gearX,y:shade.gearY});await new Promise(r=>setTimeout(r,100));
    await peer.request('input',{kind:'button',button:0,down:true});await peer.request('input',{kind:'button',button:0,down:false});
    let settings;for(let i=0;i<8;i++){await new Promise(r=>setTimeout(r,400));settings=await testRequest('testSystemUi');if(settings.package==='com.android.settings')break;}
    if(settings.package!=='com.android.settings')throw Error('Quick-settings gear did not receive the remote click: '+JSON.stringify(settings));
    console.log('PASS: remote-window touch drag opens the status bar and quick settings; remote gear click opens Settings. Protected target also receives clicks.');
  }
  await service.returnLocal();
  if (process.argv.includes('--dual-link')) {
    if (!peer.fastLink?.serial) throw Error('USB route disconnected during input exercise');
    service.config.permissions.input = service.config.permissions.screen = true;
    service.config.deviceGrants[id] = { input: true, screen: true };
    let inputs = 0;
    const realMac = process.argv.includes('--real-mac');
    if (realMac) {
      realNative = new NativeBridge(); await realNative.start();
      if(!realNative.capabilities.input || !realNative.capabilities.screen)throw Error('Real Mac accessibility and screen permissions are required');
      const call = realNative.call.bind(realNative);
      realNative.call = async (method, ...args) => { if(method==='input')inputs++;return call(method,...args); };
      service.native = realNative;
    } else {
      service.native.capabilities.input = service.native.capabilities.screen = true;
      service.native.call = async method => { if (method === 'input') inputs++; return {}; };
      service.options.videoSource = (_native, _h264, emit) => {
        const timer = setInterval(() => emit({ codec: 'jpeg', width: 20, height: 10, data: Buffer.from('fixture'), key: true }), 20);
        return { close() { clearInterval(timer); } };
      };
    }
    if (!(await testRequest('testReverseLink', {realVideo:realMac}, 15000)).ok || inputs !== 1) throw Error('Phone-to-desktop cable input/video failed');
    console.log(realMac ? 'PASS: Android receives and decodes the real Mac screen; Android input reaches the native Mac bridge.' : 'PASS: phone-to-desktop auxiliary input and authenticated ADB reverse video.');
    await service.links.drop(peer.fastLink);
    await service.switchTo(id, 'right', false);
    await peer.request('input', { kind: 'navigation', action: 'home' });
    await service.returnLocal();
    console.log('PASS: cable channel removal falls back to LAN without reconnecting the device.');
  }
  await testRequest('testDone');
  const report = await instrument;
  if (report.failed || !/PASS: encrypted ADB bootstrap/.test(report.output) || /FAIL|Exception|INSTRUMENTATION_FAILED/.test(report.output)) throw Error('Android ADB instrumentation did not pass');
  console.log('PASS: explicit emulator transport, authenticated bootstrap, persistent binding + authenticated reconnect without ADB, real click/drag/key delivery, nonblocking input during a 1.5 second UI stall, Home navigation and release.');
} finally {
  if (peer && !peer.closed) await testRequest('testDone').catch(() => {});
  await service.close();
  realNative?.close();
  if (instrument) await instrument;
  await rm(dir, { recursive: true, force: true });
}
