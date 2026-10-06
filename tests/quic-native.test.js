import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {randomBytes,randomUUID} from 'node:crypto';
import {once} from 'node:events';
import {QuicNative,quicPath} from '../src/quic-native.js';
import {MotionSender,MotionReceiver} from '../src/quic-motion.js';

test('native QUIC carries mouse Datagram and a reliable click position barrier', {skip:!quicPath(),timeout:15000}, async()=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-quic-'));
  const a=new QuicNative(),b=new QuicNative(),hold=setInterval(()=>{},1000);
  try {
    await a.start(path.join(dir,'a'),'127.0.0.1:0');
    const info=await b.start(path.join(dir,'b'),'127.0.0.1:0');
    const ticket=randomBytes(32).toString('hex');
    await b.call('ticket',{ticket,owner:randomUUID()});
    const connected=once(b,'connected');
    const link=await a.call('connect',{address:'127.0.0.1:'+info.port,ticket,fingerprint:info.fingerprint,owner:randomUUID()},5000);
    await connected;
    const sender=new MotionSender(),receiver=new MotionReceiver();
    sender.move({kind:'relative',dx:8,dy:3}); // Deliberately lose this packet.
    const move=sender.move({kind:'relative',dx:11,dy:-1});
    const arrived=once(b,'datagram');
    await a.call('datagram',{conn:link.conn,data:Buffer.from(JSON.stringify(move)).toString('base64')});
    const [event]=await arrived;
    assert.deepEqual(receiver.accept(JSON.parse(Buffer.from(event.data,'base64'))),[{kind:'relative',dx:19,dy:2}]);
    sender.move({kind:'relative',dx:7,dy:0}); // Final move also lost; click corrects it.
    let clickMotion=[{kind:'relative',dx:7,dy:0}];
    b.on('request',event=>{
      const click=JSON.parse(Buffer.from(event.data,'base64'));
      assert.deepEqual(receiver.accept(click.__motion),clickMotion);
      b.call('reply',{conn:event.conn,stream:event.stream,data:Buffer.from('clicked').toString('base64')}).catch(()=>{});
    });
    const reply=await a.call('request',{conn:link.conn,data:Buffer.from(JSON.stringify({kind:'button',__motion:sender.snapshot})).toString('base64')},5000);
    assert.equal(Buffer.from(reply.data,'base64').toString(),'clicked');
    assert.deepEqual(receiver.accept(sender.snapshot),[]);
    sender.reset();receiver.reset();receiver.accept(sender.move({kind:'move',x:.5,y:.5}));
    sender.move({kind:'move',x:.5,y:1,dx:0,dy:500}); // Lost while normalized position is clamped.
    const raw=sender.move({kind:'move',x:.5,y:1,dx:0,dy:80});
    const rawArrived=once(b,'datagram',{signal:AbortSignal.timeout(3000)});
    await a.call('datagram',{conn:link.conn,data:Buffer.from(JSON.stringify(raw)).toString('base64')});
    const [rawEvent]=await rawArrived;
    assert.deepEqual(receiver.accept(JSON.parse(Buffer.from(rawEvent.data,'base64'))),[{kind:'move',x:.5,y:1,dx:0,dy:580}]);
    sender.move({kind:'move',x:.5,y:1,dx:0,dy:65});
    clickMotion=[{kind:'move',x:.5,y:1,dx:0,dy:65}];
    await a.call('request',{conn:link.conn,data:Buffer.from(JSON.stringify({kind:'button',__motion:sender.snapshot})).toString('base64')},5000);
    assert.deepEqual(receiver.accept(sender.snapshot),[]);
    const frame=Buffer.alloc(320*1024,37),video=once(b,'video',{signal:AbortSignal.timeout(3000)});
    await a.call('video',{conn:link.conn,channel:9,data:frame.toString('base64')});
    const [received]=await video;assert.equal(received.channel,9);assert.deepEqual(Buffer.from(received.data,'base64'),frame);
  } finally {a.close();b.close();clearInterval(hold);await rm(dir,{recursive:true,force:true});}
});

test('native QUIC refuses an unpinned certificate before sending mouse input',{skip:!quicPath(),timeout:10000},async()=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'lanlink-quic-pin-'));
  const a=new QuicNative(),b=new QuicNative(),hold=setInterval(()=>{},1000);let authenticated=false;
  try{
    await a.start(path.join(dir,'a'),'127.0.0.1:0');const info=await b.start(path.join(dir,'b'),'127.0.0.1:0');
    const ticket=randomBytes(32).toString('hex');await b.call('ticket',{ticket,owner:randomUUID()});b.on('connected',()=>{authenticated=true;});
    await assert.rejects(a.call('connect',{address:'127.0.0.1:'+info.port,ticket,fingerprint:'0'.repeat(64),owner:randomUUID()},3000));
    assert.equal(authenticated,false);
  }finally{a.close();b.close();clearInterval(hold);await rm(dir,{recursive:true,force:true});}
});
