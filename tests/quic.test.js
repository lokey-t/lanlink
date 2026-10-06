import test from 'node:test';import assert from 'node:assert/strict';
import {MotionSender,MotionReceiver} from '../src/quic-motion.js';
test('mouse Datagram recovers lost relative distance and ignores duplicates and reordering',()=>{
  const send=new MotionSender(),receive=new MotionReceiver();send.move({kind:'relative',dx:5,dy:2});const latest=send.move({kind:'relative',dx:8,dy:-1});assert.deepEqual(receive.accept(latest),[{kind:'relative',dx:13,dy:1}]);assert.deepEqual(receive.accept(latest),[]);assert.deepEqual(receive.accept({...latest,seq:1}),[]);
  const next=send.move({kind:'relative',dx:1000,dy:0});send.move({kind:'relative',dx:1000,dy:0});const barrier=send.move({kind:'relative',dx:300,dy:0});assert.equal(receive.accept(barrier).reduce((n,i)=>n+i.dx,0),2300);assert.deepEqual(receive.accept(next),[]);
});
test('reliable click snapshot corrects the final position before delayed Datagram arrives',()=>{
  const send=new MotionSender(),receive=new MotionReceiver();const move=send.move({kind:'move',x:.8,y:.3});assert.deepEqual(receive.accept(send.snapshot),[{kind:'move',x:.8,y:.3}]);assert.deepEqual(receive.accept(move),[]);send.reset();assert.throws(()=>receive.accept(send.move({kind:'relative',dx:1,dy:1})),/会话/);receive.reset();assert.equal(receive.accept(send.snapshot).length,1);
});
test('phone raw mouse keeps moving beyond the sender logical bottom after packet loss',()=>{
  const send=new MotionSender(),receive=new MotionReceiver();
  receive.accept(send.move({kind:'move',x:.5,y:.5}));
  send.move({kind:'move',x:.5,y:1,dx:0,dy:300}); // Lost while logical position reaches the bottom.
  const last=send.move({kind:'move',x:.5,y:1,dx:0,dy:50});
  assert.deepEqual(receive.accept(last),[{kind:'move',x:.5,y:1,dx:0,dy:350}]);
  const next=send.move({kind:'move',x:.5,y:1,dx:0,dy:80});
  assert.deepEqual(receive.accept(next),[{kind:'move',x:.5,y:1,dx:0,dy:80}]);
  assert.deepEqual(receive.accept(last),[]);
  send.move({kind:'move',x:.5,y:1,dx:0,dy:25});
  assert.equal(receive.accept(send.snapshot)[0].dy,25); // Reliable click corrects the last missing packet once.
  assert.deepEqual(receive.accept(send.snapshot),[]);
});
test('raw mouse retains all four directions and fractional distance under burst loss',()=>{
  for(const [dx,dy,x,y] of [[.25,13.5,1,1],[-.25,-13.5,0,0],[13.5,-.25,1,0],[-13.5,.25,0,1]]){
    const send=new MotionSender(),receive=new MotionReceiver();let actualX=0,actualY=0,older;
    for(let i=0;i<300;i++){
      const m=send.move({kind:'move',x,y,dx,dy});
      if(i%17!==16&&i!==299){older=m;continue;}
      for(const input of receive.accept(m)){actualX+=input.dx;actualY+=input.dy;assert.ok(Math.abs(input.dx)<=1000&&Math.abs(input.dy)<=1000);}
      assert.deepEqual(receive.accept(m),[]);assert.deepEqual(receive.accept(older),[]);
    }
    assert.equal(actualX,dx*300);assert.equal(actualY,dy*300);
  }
  const send=new MotionSender();assert.throws(()=>send.move({kind:'move',x:1,y:1,dx:1}),/位移/);assert.throws(()=>send.move({kind:'move',x:1,y:1,dx:NaN,dy:0}),/位移/);
});
test('prolonged raw mouse loss discards excessive stale distance and resumes live motion',()=>{
  const send=new MotionSender(),receive=new MotionReceiver();receive.accept(send.move({kind:'move',x:.5,y:.5}));
  for(let i=0;i<40;i++)send.move({kind:'move',x:1,y:1,dx:0,dy:1000});
  assert.deepEqual(receive.accept(send.snapshot),[{kind:'move',x:1,y:1,dx:0,dy:0}]);
  assert.deepEqual(receive.accept(send.move({kind:'move',x:1,y:1,dx:0,dy:25})),[{kind:'move',x:1,y:1,dx:0,dy:25}]);
  assert.deepEqual(receive.accept(send.move({kind:'move',x:1,y:.99,dx:0,dy:-30})),[{kind:'move',x:1,y:.99,dx:0,dy:-30}]);
});
