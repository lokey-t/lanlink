// Package the approved artwork; never regenerate a different design during builds.
// Portable, dependency-free PNG decoding, resampling, ICNS/ICO and Android output.
import { deflateSync, inflateSync } from 'node:zlib';
import { mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const out = 'assets/icons', res = 'android/app/src/main/res';
await mkdir(out, { recursive: true });
function decodePNG(bytes) {
  if (bytes.subarray(0,8).toString('hex') !== '89504e470d0a1a0a') throw new Error('Invalid source PNG');
  const width=bytes.readUInt32BE(16),height=bytes.readUInt32BE(20);
  if(bytes[24]!==8 || bytes[25]!==6 || bytes[28]!==0) throw new Error('Source must be noninterlaced RGBA8 PNG');
  const blocks=[];
  for(let p=8;p<bytes.length;) { const n=bytes.readUInt32BE(p),type=bytes.toString('ascii',p+4,p+8);if(type==='IDAT')blocks.push(bytes.subarray(p+8,p+8+n));p+=n+12; }
  const raw=inflateSync(Buffer.concat(blocks)),stride=width*4,pixels=Buffer.alloc(width*height*4);
  if(raw.length!==(stride+1)*height)throw new Error('Invalid PNG scanlines');
  const paeth=(a,b,c)=>{const p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c;};
  for(let y=0;y<height;y++) { const filter=raw[y*(stride+1)];if(filter>4)throw new Error('Unsupported PNG filter');for(let x=0;x<stride;x++){const k=y*stride+x,a=x>=4?pixels[k-4]:0,b=y?pixels[k-stride]:0,c=y&&x>=4?pixels[k-stride-4]:0;pixels[k]=(raw[y*(stride+1)+1+x]+[0,a,b,Math.floor((a+b)/2),paeth(a,b,c)][filter])&255;} }
  return {width,height,pixels};
}
function crc(b){let n=0xffffffff;for(const v of b){n^=v;for(let i=0;i<8;i++)n=(n>>>1)^((n&1)?0xedb88320:0);}return(n^0xffffffff)>>>0;}
function chunk(type,data){const body=Buffer.concat([Buffer.from(type),data]),size=Buffer.alloc(4),sum=Buffer.alloc(4);size.writeUInt32BE(data.length);sum.writeUInt32BE(crc(body));return Buffer.concat([size,body,sum]);}
function png(width,height,pixels){const header=Buffer.alloc(13);header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=6;const rows=Buffer.alloc((width*4+1)*height);for(let y=0;y<height;y++)pixels.copy(rows,y*(width*4+1)+1,y*width*4,(y+1)*width*4);return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',header),chunk('IDAT',deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]);}
const source=decodePNG(await readFile(`${out}/source.png`));
if(source.width!==1254||source.height!==1254)throw new Error('Artwork dimensions changed; review the approved tile bounds');
// Standard launcher silhouette trims exterior generation residue without altering artwork.
// Bounds are measured on the approved source; all foreground pixels are retained.
const tile={x:78,y:79,size:1098,radius:217};
function sample(x,y) {
  const ix=Math.floor(x),iy=Math.floor(y),fx=x-ix,fy=y-iy,rgba=[0,0,0,0];
  for(let j=0;j<2;j++)for(let i=0;i<2;i++){const k=(Math.max(0,Math.min(source.height-1,iy+j))*source.width+Math.max(0,Math.min(source.width-1,ix+i)))*4,weight=(i?fx:1-fx)*(j?fy:1-fy),a=source.pixels[k+3]/255;for(let c=0;c<3;c++)rgba[c]+=source.pixels[k+c]*a*weight;rgba[3]+=a*weight;}
  if(rgba[3])for(let c=0;c<3;c++)rgba[c]/=rgba[3];return rgba;
}
function render(size,mode='color') {
  const output=Buffer.alloc(size*size*4),ss=size<=128?8:size<=512?3:2;
  for(let y=0;y<size;y++)for(let x=0;x<size;x++){
    const accum=[0,0,0,0];
    for(let sy=0;sy<ss;sy++)for(let sx=0;sx<ss;sx++){
      const u=((x+(sx+.5)/ss)/size-.0625)/.875,v=((y+(sy+.5)/ss)/size-.0625)/.875;
      const qx=Math.abs(u-.5)*tile.size-(tile.size/2-tile.radius),qy=Math.abs(v-.5)*tile.size-(tile.size/2-tile.radius);
      if(Math.hypot(Math.max(qx,0),Math.max(qy,0))+Math.min(Math.max(qx,qy),0)>tile.radius)continue;
      const color=sample(tile.x+u*tile.size,tile.y+v*tile.size);
      if(mode!=='color'){
        // Small monochrome system glyphs use the device/cursor silhouette, omitting faint waves.
        const max=Math.max(...color.slice(0,3)),min=Math.min(...color.slice(0,3));
        color[3]*=Math.max(0,Math.min(1,((max-min)/Math.max(1,max)-.3)/.12));
        const shade=mode==='white'?255:0;color[0]=color[1]=color[2]=shade;
      }
      for(let c=0;c<3;c++)accum[c]+=color[c]*color[3];accum[3]+=color[3];
    }
    const k=(y*size+x)*4;for(let c=0;c<3;c++)output[k+c]=accum[3]?Math.round(accum[c]/accum[3]):0;output[k+3]=Math.round(255*accum[3]/(ss*ss));
  }return output;
}
const images=new Map();
for(const size of [16,24,32,48,64,128,256,512,1024]){const bytes=png(size,size,render(size));images.set(size,bytes);await writeFile(`${out}/lanlink-${size}.png`,bytes);}
await writeFile('desktop/icon.png',images.get(256));
const sizes=[16,24,32,48,64,128,256],head=Buffer.alloc(6+16*sizes.length);head.writeUInt16LE(1,2);head.writeUInt16LE(sizes.length,4);let offset=head.length;
for(let i=0;i<sizes.length;i++){const size=sizes[i],b=images.get(size),k=6+16*i;head[k]=head[k+1]=size===256?0:size;head.writeUInt16LE(1,k+4);head.writeUInt16LE(32,k+6);head.writeUInt32LE(b.length,k+8);head.writeUInt32LE(offset,k+12);offset+=b.length;}
await writeFile(`${out}/LanLink.ico`,Buffer.concat([head,...sizes.map(s=>images.get(s))]));
const icns=[];for(const [type,size]of [['icp4',16],['icp5',32],['icp6',64],['ic07',128],['ic08',256],['ic09',512],['ic10',1024],['ic11',32],['ic12',64],['ic13',256],['ic14',512]]){const h=Buffer.alloc(8);h.write(type);h.writeUInt32BE(8+images.get(size).length,4);icns.push(h,images.get(size));}const ih=Buffer.alloc(8);ih.write('icns');ih.writeUInt32BE(8+icns.reduce((s,b)=>s+b.length,0),4);await writeFile(`${out}/LanLink.icns`,Buffer.concat([ih,...icns]));
for(const size of [18,36])await writeFile(`${out}/MenuBarTemplate${size===36?'@2x':''}.png`,png(size,size,render(size,'black')));
await mkdir(`${res}/drawable-nodpi`,{recursive:true});
await writeFile(`${res}/drawable-nodpi/lanlink_artwork.png`,images.get(1024));
await writeFile(`${res}/drawable-nodpi/lanlink_monochrome.png`,png(432,432,render(432,'white')));
await writeFile(`${res}/drawable/lanlink_icon_background.xml`,'<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="#F6F8FE"/></shape>\n');
for(const mono of [false,true])await writeFile(`${res}/drawable/lanlink_icon_${mono?'monochrome':'foreground'}.xml`,`<inset xmlns:android="http://schemas.android.com/apk/res/android" android:inset="9%" android:drawable="@drawable/${mono?'lanlink_monochrome':'lanlink_artwork'}"/>\n`);
await writeFile(`${res}/drawable/lanlink_icon.xml`,'<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/lanlink_artwork" android:filter="true"/>\n');
await rm(`${res}/drawable/lanlink_notification.xml`,{force:true});
for(const [density,size]of [['mdpi',24],['hdpi',36],['xhdpi',48],['xxhdpi',72],['xxxhdpi',96]]){await mkdir(`${res}/drawable-${density}`,{recursive:true});await writeFile(`${res}/drawable-${density}/lanlink_notification.png`,png(size,size,render(size,'white')));}
for(const v of ['anydpi-v26','anydpi-v33']){await mkdir(`${res}/mipmap-${v}`,{recursive:true});await writeFile(`${res}/mipmap-${v}/ic_launcher.xml`,`<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n <background android:drawable="@drawable/lanlink_icon_background"/>\n <foreground android:drawable="@drawable/lanlink_icon_foreground"/>\n${v.endsWith('33')?' <monochrome android:drawable="@drawable/lanlink_icon_monochrome"/>\n':''}</adaptive-icon>\n`);}
// Remove earlier rejected design exports so consumers cannot accidentally reuse them.
for(const name of ['lanlink.svg','lanlink-mark.svg'])await rm(`${out}/${name}`,{force:true});
const preview=Buffer.alloc(800*400*4);for(let y=0;y<400;y++)for(let x=0;x<800;x++)preview.set(x<400?[242,243,249,255]:[28,29,38,255],(y*800+x)*4);
const pixels=render(256);for(const left of [72,472])for(let y=0;y<256;y++)for(let x=0;x<256;x++){const a=(y*256+x)*4,b=((y+72)*800+x+left)*4,f=pixels[a+3]/255;for(let c=0;c<3;c++)preview[b+c]=Math.round(pixels[a+c]*f+preview[b+c]*(1-f));}
await writeFile(`${out}/preview.png`,png(800,400,preview));
console.log('Packaged approved LANLink artwork for macOS, Windows and Android.');
