//! One shared quiche engine for Windows/macOS (C ABI) and Android (JNI).
//! Network data never carries IPC base64: streams and DATAGRAM payloads are binary.
use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use rand::RngCore;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{collections::{HashMap,VecDeque},ffi::{CStr,CString},net::{SocketAddr,UdpSocket},os::raw::c_char,sync::{Arc,Mutex,mpsc},thread,time::{Duration,Instant}};

const LIMIT:usize=8*1024*1024;
const PART:usize=1024;
struct Write {bytes:Vec<u8>,at:usize}
struct Partial {at:Instant,bytes:Vec<u8>,seen:Vec<bool>,count:usize}
struct VideoWrite {channel:u32,seq:u32,bytes:Vec<u8>,index:usize,at:Instant}
struct State {
  conn:quiche::Connection,cid:Vec<u8>,owner:String,client:bool,ready:bool,pin:String,
  auth_id:Value,next:u64,reads:HashMap<u64,Vec<u8>>,writes:HashMap<u64,Write>,
  requests:HashMap<u64,(Value,Instant)>,parts:HashMap<(u32,u32),Partial>,seq:HashMap<u32,u32>,
  video:VecDeque<VideoWrite>,
}
pub struct Engine {tx:mpsc::SyncSender<Value>,events:Arc<Mutex<VecDeque<Value>>>}
fn emit(events:&Arc<Mutex<VecDeque<Value>>>,v:Value){let mut q=events.lock().unwrap();if q.len()<256{q.push_back(v);}else{q.clear();q.push_back(json!({"event":"overflow"}));}}
fn body(v:&Value)->Result<Vec<u8>,String>{let b=B64.decode(v["data"].as_str().unwrap_or("")).map_err(|_|"invalid base64")?;if b.len()>LIMIT{return Err("payload too large".into())}Ok(b)}
fn encode(kind:u8,data:&[u8])->Vec<u8>{let mut b=Vec::with_capacity(data.len()+1);b.push(kind);b.extend_from_slice(data);b}
fn random()->Vec<u8>{let mut b=vec![0;16];rand::thread_rng().fill_bytes(&mut b);b}
fn hex(bytes:&[u8])->String{bytes.iter().map(|b|format!("{b:02x}")).collect()}
fn config(_dir:&str)->Result<(quiche::Config,String),String>{
  let cert=rcgen::generate_simple_self_signed(vec!["lanlink.local".into()]).map_err(|e|e.to_string())?;
  let fp=hex(&Sha256::digest(cert.cert.der()));
  let result=(||{let mut builder=boring::ssl::SslContextBuilder::new(boring::ssl::SslMethod::tls()).map_err(|e|e.to_string())?;
    let certificate=boring::x509::X509::from_der(cert.cert.der()).map_err(|e|e.to_string())?;
    let key=boring::pkey::PKey::private_key_from_pem(cert.key_pair.serialize_pem().as_bytes()).map_err(|e|e.to_string())?;
    builder.set_certificate(&certificate).map_err(|e|e.to_string())?;builder.set_private_key(&key).map_err(|e|e.to_string())?;
    // Membership is authenticated by the one-use ticket and the pinned DER fingerprint.
    // Loading the system CA store is unnecessary and can fail in restricted processes.
    let mut c=quiche::Config::with_boring_ssl_ctx_builder(quiche::PROTOCOL_VERSION,builder).map_err(|e|e.to_string())?;
    c.verify_peer(false);c.set_application_protos(&[b"lanlink-quic/1"]).map_err(|e|e.to_string())?;
    c.set_max_idle_timeout(15000);c.set_max_recv_udp_payload_size(1350);c.set_max_send_udp_payload_size(1350);
    c.set_initial_max_data(16*1024*1024);c.set_initial_max_stream_data_bidi_local(LIMIT as u64+1);c.set_initial_max_stream_data_bidi_remote(LIMIT as u64+1);
    c.set_initial_max_streams_bidi(40);c.set_disable_active_migration(true);c.enable_dgram(true,256,256);Ok((c,fp))})();
  result
}
fn start(host:&str,dir:&str)->Result<Engine,String>{
  let socket=UdpSocket::bind(host).map_err(|e|e.to_string())?;socket.set_nonblocking(true).map_err(|e|e.to_string())?;
  // A frame consists of many small UDP packets; the OS default buffer can drop bursts.
  #[cfg(unix)] unsafe {use std::os::fd::AsRawFd;let size=2*1024*1024i32;libc::setsockopt(socket.as_raw_fd(),libc::SOL_SOCKET,libc::SO_RCVBUF,&size as *const _ as *const _,std::mem::size_of_val(&size) as _);}
  #[cfg(windows)] unsafe {use std::os::windows::io::AsRawSocket;use windows_sys::Win32::Networking::WinSock::{setsockopt,SOL_SOCKET,SO_RCVBUF};let size=2*1024*1024i32;setsockopt(socket.as_raw_socket() as _,SOL_SOCKET,SO_RCVBUF,&size as *const _ as *const _,std::mem::size_of_val(&size) as _);}
  let local=socket.local_addr().map_err(|e|e.to_string())?;let (cfg,fp)=config(dir)?;
  let (tx,rx)=mpsc::sync_channel(256);let events=Arc::new(Mutex::new(VecDeque::new()));let out=events.clone();
  emit(&events,json!({"event":"ready","port":local.port(),"fingerprint":fp}));thread::spawn(move||run(socket,local,cfg,rx,out));Ok(Engine{tx,events})
}
fn run(socket:UdpSocket,local:SocketAddr,mut cfg:quiche::Config,rx:mpsc::Receiver<Value>,events:Arc<Mutex<VecDeque<Value>>>){
  let mut states:HashMap<u64,State>=HashMap::new();let mut next_conn=1u64;
  let mut tickets:HashMap<String,(String,Instant)>=HashMap::new();let mut packet=vec![0;65536];let mut send=vec![0;1350];let mut stats_at=Instant::now();
  let mut paced:Vec<(Instant,SocketAddr,Vec<u8>)>=Vec::new();
  loop {
    let now=Instant::now();let mut i=0;while i<paced.len(){if paced[i].0<=now{let (_,to,bytes)=paced.swap_remove(i);let _=socket.send_to(&bytes,to);}else{i+=1;}}
    for _ in 0..256 {match rx.try_recv(){Ok(cmd)=>{
      let id=cmd["id"].clone();let op=cmd["op"].as_str().unwrap_or("");
      if op=="stop"{for s in states.values_mut(){let _=s.conn.close(true,0,b"closed");}return;}
      let result:Result<Option<Value>,String>=(||{
        if op=="resetTickets" {tickets.clear();return Ok(Some(json!({})));}
        if op=="ticket" {let token=cmd["ticket"].as_str().ok_or("ticket required")?;if token.len()!=64||tickets.len()>=32{return Err("ticket limit".into())}tickets.insert(token.into(),(cmd["owner"].as_str().unwrap_or("").into(),Instant::now()+Duration::from_secs(6)));return Ok(Some(json!({})));}
        if op=="connect" {
          if states.len()>=16{return Err("connection limit".into())}let remote:SocketAddr=cmd["address"].as_str().ok_or("address required")?.parse().map_err(|_|"invalid address")?;
          let cid=random();let conn=quiche::connect(Some("lanlink.local"),&quiche::ConnectionId::from_ref(&cid),local,remote,&mut cfg).map_err(|e|e.to_string())?;
          let mut s=State{conn,cid,owner:cmd["owner"].as_str().unwrap_or("").into(),client:true,ready:false,pin:cmd["fingerprint"].as_str().unwrap_or("").into(),auth_id:id.clone(),next:4,reads:HashMap::new(),writes:HashMap::new(),requests:HashMap::new(),parts:HashMap::new(),seq:HashMap::new(),video:VecDeque::new()};
          s.writes.insert(0,Write{bytes:encode(4,cmd["ticket"].as_str().unwrap_or("").as_bytes()),at:0});states.insert(next_conn,s);next_conn+=1;return Ok(None);
        }
        let cid=cmd["conn"].as_u64().ok_or("connection required")?;let s=states.get_mut(&cid).ok_or("connection ended")?;
        if op=="close"{s.conn.close(true,0,b"closed").map_err(|e|e.to_string())?;return Ok(Some(json!({})));}
        if !s.ready{return Err("connection not authenticated".into())}
        if op=="request" {if s.requests.len()>=32{return Err("request limit".into())}let bytes=body(&cmd)?;let stream=s.next;s.next+=4;s.writes.insert(stream,Write{bytes:encode(1,&bytes),at:0});s.requests.insert(stream,(id.clone(),Instant::now()+Duration::from_millis(cmd["timeout"].as_u64().unwrap_or(15000).min(120000))));return Ok(None);}
        if op=="reply" {let stream=cmd["stream"].as_u64().ok_or("stream required")?;s.writes.insert(stream,Write{bytes:encode(2,&body(&cmd)?),at:0});return Ok(Some(json!({})));}
        if op=="datagram" {let b=body(&cmd)?;if b.len()>1024{return Err("datagram too large".into())}let mut p=vec![1];p.extend(b);s.conn.dgram_send(&p).map_err(|e|e.to_string())?;return Ok(Some(json!({"queued":s.conn.dgram_send_queue_byte_size()})));}
        if op=="video" {
          let bytes=body(&cmd)?;if bytes.len()<16||bytes.len()>4*1024*1024{return Err("video size".into())}
          let channel=cmd["channel"].as_u64().ok_or("channel required")? as u32;let seq=s.seq.entry(channel).or_insert(0);let frame=*seq;*seq=seq.wrapping_add(1);
          if s.video.len()>=2{return Err("video congested".into())}
          s.video.push_back(VideoWrite{channel,seq:frame,bytes,index:0,at:Instant::now()});
          return Ok(Some(json!({"queued":s.conn.dgram_send_queue_byte_size()+s.video.iter().map(|v|v.bytes.len()-v.index*PART).sum::<usize>()})));
        }
        Err("unknown operation".into())
      })();match result{Ok(Some(value))=>emit(&events,json!({"id":id,"result":value})),Ok(None)=>(),Err(e)=>emit(&events,json!({"id":id,"error":e}))}
    },Err(mpsc::TryRecvError::Empty)=>break,Err(mpsc::TryRecvError::Disconnected)=>return}}
    for _ in 0..512 {match socket.recv_from(&mut packet){Ok((len,from))=>{
      let Ok(h)=quiche::Header::from_slice(&mut packet[..len],16) else{continue};
      let mut key=states.iter().find(|(_,s)|s.cid.as_slice()==h.dcid.as_ref()).map(|(k,_)|*k);
      if key.is_none()&&h.ty==quiche::Type::Initial&&states.len()<16 {
        let cid=h.dcid.as_ref().to_vec();if let Ok(conn)=quiche::accept(&quiche::ConnectionId::from_ref(&cid),None,local,from,&mut cfg){let s=State{conn,cid,owner:String::new(),client:false,ready:false,pin:String::new(),auth_id:Value::Null,next:1,reads:HashMap::new(),writes:HashMap::new(),requests:HashMap::new(),parts:HashMap::new(),seq:HashMap::new(),video:VecDeque::new()};states.insert(next_conn,s);key=Some(next_conn);next_conn+=1;}
      }
      if let Some(k)=key{let s=states.get_mut(&k).unwrap();let result=s.conn.recv(&mut packet[..len],quiche::RecvInfo{from,to:local});if std::env::var_os("LANLINK_QUIC_DEBUG").is_some(){eprintln!("recv {local} {k} {len} {result:?} established {}",s.conn.is_established());}}
    },Err(e)if e.kind()==std::io::ErrorKind::WouldBlock=>break,Err(_)=>break}}
    tickets.retain(|_,(_,until)|*until>Instant::now());
    let stats_due=stats_at.elapsed()>Duration::from_millis(100);if stats_due{stats_at=Instant::now();}let mut remove=Vec::new();
    for (&key,s) in states.iter_mut(){
      if s.conn.is_established()&&s.client&&!s.ready&&!s.pin.is_empty(){let fp=s.conn.peer_cert().map(|b|hex(&Sha256::digest(b))).unwrap_or_default();if fp!=s.pin{let _=s.conn.close(true,1,b"certificate mismatch");}else{s.pin.clear();}}
      let readable:Vec<_>=s.conn.readable().collect();
      for stream in readable {loop {let mut data=vec![0;65536];match s.conn.stream_recv(stream,&mut data){Ok((n,fin))=>{
        let buffered:usize=s.reads.values().map(|b|b.len()).sum();if buffered+n>16*1024*1024||!s.ready&&(stream!=0||n>65){let _=s.conn.close(true,2,b"stream limit");break}let b=s.reads.entry(stream).or_default();if b.len()+n>if s.ready{LIMIT+1}else{65}{let _=s.conn.close(true,2,b"stream limit");break}b.extend_from_slice(&data[..n]);if !fin{continue}let b=s.reads.remove(&stream).unwrap();if b.is_empty(){break}
        if stream==0&&!s.ready {
          if !s.client&&b[0]==4 {let token=String::from_utf8_lossy(&b[1..]);if let Some((owner,until))=tickets.remove(token.as_ref()){if until>Instant::now(){s.owner=owner;s.ready=true;s.writes.insert(0,Write{bytes:vec![4],at:0});emit(&events,json!({"event":"connected","conn":key,"owner":s.owner}));}else{let _=s.conn.close(true,1,b"ticket expired");}}else{let _=s.conn.close(true,1,b"unauthorized");}}
          else if s.client&&b==[4]&&s.pin.is_empty(){s.ready=true;emit(&events,json!({"id":s.auth_id,"result":{"conn":key}}));emit(&events,json!({"event":"connected","conn":key,"owner":s.owner}));}
          else{let _=s.conn.close(true,1,b"unauthorized");}
        }else if s.ready&&b[0]==1{emit(&events,json!({"event":"request","conn":key,"owner":s.owner,"stream":stream,"data":B64.encode(&b[1..])}));}
        else if s.ready&&b[0]==2{if let Some((id,_))=s.requests.remove(&stream){emit(&events,json!({"id":id,"result":{"data":B64.encode(&b[1..])}}));}}
        else{let _=s.conn.close(true,1,b"unauthorized");}break
      },Err(quiche::Error::Done)=>break,Err(_)=>{s.reads.remove(&stream);break}}}}
      let writes:Vec<_>=s.writes.keys().copied().collect();for stream in writes {if s.client&&!s.ready&&!s.pin.is_empty(){continue}let w=s.writes.get_mut(&stream).unwrap();match s.conn.stream_send(stream,&w.bytes[w.at..],true){Ok(n)=>{w.at+=n;if w.at==w.bytes.len(){s.writes.remove(&stream);}},Err(quiche::Error::Done)=>(),Err(_)=>{s.writes.remove(&stream);}}}
      if s.ready {for _ in 0..512 {let mut b=vec![0;1350];let Ok(n)=s.conn.dgram_recv(&mut b) else{break};b.truncate(n);
        if b.first()==Some(&1){emit(&events,json!({"event":"datagram","conn":key,"owner":s.owner,"data":B64.encode(&b[1..])}));}
        else if b.first()==Some(&2)&&b.len()>=17 {
          let channel=u32::from_be_bytes(b[1..5].try_into().unwrap());let seq=u32::from_be_bytes(b[5..9].try_into().unwrap());let index=u16::from_be_bytes(b[9..11].try_into().unwrap()) as usize;let count=u16::from_be_bytes(b[11..13].try_into().unwrap()) as usize;let size=u32::from_be_bytes(b[13..17].try_into().unwrap()) as usize;
          if size<16||size>4*1024*1024||count!=size.div_ceil(PART)||index>=count||b.len()-17!=(size-index*PART).min(PART){continue}
          if std::env::var_os("LANLINK_QUIC_DEBUG").is_some()&&(index==0||index+1==count){eprintln!("video fragment {local} {seq} {index}/{count}");}
          s.parts.retain(|_,p|p.at.elapsed()<Duration::from_millis(150));if s.parts.len()>=4&&!s.parts.contains_key(&(channel,seq)){s.parts.clear();}
          let p=s.parts.entry((channel,seq)).or_insert_with(||Partial{at:Instant::now(),bytes:vec![0;size],seen:vec![false;count],count:0});if p.bytes.len()!=size||p.seen.len()!=count{continue}if !p.seen[index]{p.bytes[index*PART..index*PART+b.len()-17].copy_from_slice(&b[17..]);p.seen[index]=true;p.count+=1;}
          if p.count==count{let p=s.parts.remove(&(channel,seq)).unwrap();emit(&events,json!({"event":"video","conn":key,"owner":s.owner,"channel":channel,"sequence":seq,"data":B64.encode(p.bytes)}));}
        }
      }}
      let expired:Vec<_>=s.requests.iter().filter(|(_,(_,at))|*at<=Instant::now()).map(|(k,_)|*k).collect();for stream in expired{let (id,_)=s.requests.remove(&stream).unwrap();s.writes.remove(&stream);let _=s.conn.stream_shutdown(stream,quiche::Shutdown::Read,3);let _=s.conn.stream_shutdown(stream,quiche::Shutdown::Write,3);emit(&events,json!({"id":id,"error":"QUIC request timeout"}));}
      if s.conn.timeout().is_some_and(|t|t.is_zero()){s.conn.on_timeout();}
      // Reserve DATAGRAM queue space for mouse input and pace large keyframes in pieces.
      while s.ready&&s.conn.dgram_send_queue_len()<240&&s.conn.dgram_send_queue_byte_size()<128*1024 {
        let Some(v)=s.video.front_mut() else{break};if v.at.elapsed()>Duration::from_millis(150){if std::env::var_os("LANLINK_QUIC_DEBUG").is_some(){eprintln!("video expired {} / {}",v.index,v.bytes.len().div_ceil(PART));}s.video.pop_front();continue}
        let count=v.bytes.len().div_ceil(PART);let offset=v.index*PART;let b=&v.bytes[offset..(offset+PART).min(v.bytes.len())];
        let mut p=Vec::with_capacity(17+b.len());p.push(2);p.extend(v.channel.to_be_bytes());p.extend(v.seq.to_be_bytes());p.extend((v.index as u16).to_be_bytes());p.extend((count as u16).to_be_bytes());p.extend((v.bytes.len() as u32).to_be_bytes());p.extend(b);
        if s.conn.dgram_send(&p).is_err(){break}v.index+=1;if v.index==count{s.video.pop_front();}
      }
      while paced.len()<512 {match s.conn.send(&mut send){Ok((n,info))=>{if info.at>Instant::now(){paced.push((info.at,info.to,send[..n].to_vec()));}else{let _=socket.send_to(&send[..n],info.to);}},Err(quiche::Error::Done)=>break,Err(_)=>break}}
      if s.conn.is_closed(){if std::env::var_os("LANLINK_QUIC_DEBUG").is_some(){eprintln!("QUIC closed {local} {key}: local {:?}, remote {:?}",s.conn.local_error(),s.conn.peer_error());}remove.push(key);}
      if stats_due&&s.ready{emit(&events,json!({"event":"stats","conn":key,"queued":s.conn.dgram_send_queue_byte_size()+s.video.iter().map(|v|v.bytes.len()-v.index*PART).sum::<usize>()}));}
    }
    for key in remove {if let Some(s)=states.remove(&key){emit(&events,json!({"event":"closed","conn":key,"owner":s.owner}));if !s.ready&&s.client{emit(&events,json!({"id":s.auth_id,"error":"QUIC authentication failed"}));}for (_, (id,_))in s.requests{emit(&events,json!({"id":id,"error":"QUIC 连接已断开"}));}}}
    thread::sleep(Duration::from_millis(2));
  }
}
#[no_mangle] pub unsafe extern "C" fn ll_quic_start(host:*const c_char,dir:*const c_char)->*mut Engine{
  if host.is_null()||dir.is_null(){return std::ptr::null_mut()}let host=CStr::from_ptr(host).to_string_lossy();let dir=CStr::from_ptr(dir).to_string_lossy();match start(&host,&dir){Ok(e)=>Box::into_raw(Box::new(e)),Err(e)=>{eprintln!("LanLink QUIC initialization: {e}");std::ptr::null_mut()}}
}
#[no_mangle] pub unsafe extern "C" fn ll_quic_command(engine:*mut Engine,input:*const c_char)->i32{
  if engine.is_null()||input.is_null(){return -1}let Ok(v)=serde_json::from_slice(CStr::from_ptr(input).to_bytes())else{return -1};(*engine).tx.try_send(v).map(|_|0).unwrap_or(-1)
}
#[no_mangle] pub unsafe extern "C" fn ll_quic_poll(engine:*mut Engine)->*mut c_char{
  if engine.is_null(){return std::ptr::null_mut()}let mut q=(*engine).events.lock().unwrap();if q.is_empty(){return std::ptr::null_mut()}let values:Vec<_>=q.drain(..).collect();CString::new(serde_json::to_vec(&values).unwrap()).unwrap().into_raw()
}
#[no_mangle] pub unsafe extern "C" fn ll_quic_free(text:*mut c_char){if !text.is_null(){drop(CString::from_raw(text));}}
#[no_mangle] pub unsafe extern "C" fn ll_quic_stop(engine:*mut Engine){if !engine.is_null(){let e=Box::from_raw(engine);let _=e.tx.send(json!({"op":"stop"}));}}

#[cfg(target_os="android")]
mod android {
  use super::*;use jni::{JNIEnv,objects::{JClass,JString},sys::{jlong,jint,jstring}};
  #[no_mangle] pub extern "system" fn Java_com_lanlink_app_QuicNative_start(mut env:JNIEnv,_class:JClass,host:JString,dir:JString)->jlong{let Ok(h)=env.get_string(&host)else{return 0};let Ok(d)=env.get_string(&dir)else{return 0};let h=CString::new(h.to_bytes()).unwrap();let d=CString::new(d.to_bytes()).unwrap();unsafe{ll_quic_start(h.as_ptr(),d.as_ptr()) as jlong}}
  #[no_mangle] pub extern "system" fn Java_com_lanlink_app_QuicNative_command(mut env:JNIEnv,_class:JClass,engine:jlong,data:JString)->jint{let Ok(s)=env.get_string(&data)else{return -1};let s=CString::new(s.to_bytes()).unwrap();unsafe{ll_quic_command(engine as *mut Engine,s.as_ptr())}}
  #[no_mangle] pub extern "system" fn Java_com_lanlink_app_QuicNative_poll(mut env:JNIEnv,_class:JClass,engine:jlong)->jstring{unsafe{let p=ll_quic_poll(engine as *mut Engine);if p.is_null(){return std::ptr::null_mut()}let result=env.new_string(CStr::from_ptr(p).to_string_lossy()).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut());ll_quic_free(p);result}}
  #[no_mangle] pub extern "system" fn Java_com_lanlink_app_QuicNative_stop(_env:JNIEnv,_class:JClass,engine:jlong){unsafe{ll_quic_stop(engine as *mut Engine);}}
}
