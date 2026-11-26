#!/usr/bin/env bash
# A read-only artwork batch. Nothing is staged in Android shared storage.
set -eu
[[ "$#" == 4 ]] || exit 64
exec timeout -k 3 35 node --max-old-space-size=160 - "$@" <<'ARTWORK_JS'
'use strict';
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const {createRequire}=require('node:module'), {pathToFileURL,fileURLToPath}=require('node:url');
const [home,user64,mode,input64]=process.argv.slice(2);
const detail=mode==='detail', limit=detail?27648:6144, edge=detail?576:192;
const pinned=[], C=fs.constants;
function guard(ok){if(!ok)throw Error('unavailable');}
function dec(s,max){
    guard(typeof s==='string' && s.length<=Math.ceil(max/3)*4);
    const b=Buffer.from(s,'base64');guard(b.length<=max&&b.toString('base64')===s);
    return new TextDecoder('utf-8',{fatal:true}).decode(b);
}
const safe=s=>typeof s==='string' && s.length>0 && !s.startsWith('.') && Buffer.byteLength(s)<=255 && !/[\\/\x00-\x1f\x7f]/.test(s);
const stat=p=>fs.lstatSync(p,{bigint:true});
const id=s=>[s.dev,s.ino,s.mode,s.size,s.mtimeNs,s.ctimeNs].join(':');
function pin(p){
    const before=stat(p);guard(before.isDirectory());
    if(process.platform==='win32')return p;
    const fd=fs.openSync(p,C.O_NOFOLLOW|C.O_RDONLY|C.O_DIRECTORY);pinned.push(fd);
    const after=fs.fstatSync(fd,{bigint:true});guard(before.dev===after.dev&&before.ino===after.ino);
    return '/proc/self/fd/'+fd;
}
function read(p,expected,max){
    guard(expected.isFile() && expected.size>0n && expected.size<=BigInt(max));
    const fd=fs.openSync(p,C.O_NOFOLLOW|C.O_RDONLY|C.O_NONBLOCK);
    try{
        guard(id(fs.fstatSync(fd,{bigint:true}))===id(expected));
        // Bound allocation even if another writer grows the original.
        const buf=Buffer.alloc(Number(expected.size));
        for(let off=0,n;off<buf.length;off+=n){n=fs.readSync(fd,buf,off,buf.length-off,off);guard(n>0);}
        guard(id(stat(p))===id(expected)&&id(fs.fstatSync(fd,{bigint:true}))===id(expected));
        return buf;
    }finally{fs.closeSync(fd);}
}
const pngSig='89504e470d0a1a0a';
// Drop card JSON, text, profiles and animation frames before any PNG decode.
function pngPixels(b){
    if(b.length<33||b.subarray(0,8).toString('hex')!==pngSig)return null;
    const keep=[b.subarray(0,8)];let pos=8, w=0,h=0,hasPixels=false;
    for(let i=0;i<65536&&pos+12<=b.length;i++){
        const n=b.readUInt32BE(pos),tag=b.toString('ascii',pos+4,pos+8),end=pos+12+n;guard(end<=b.length);
        if(!i){guard(tag==='IHDR'&&n===13);w=b.readUInt32BE(pos+8);h=b.readUInt32BE(pos+12);}
        if(['IHDR','PLTE','tRNS','IDAT','IEND'].includes(tag))keep.push(b.subarray(pos,end));
        if(tag==='IDAT')hasPixels=true;
        if(tag==='IEND'){guard(hasPixels&&n===0&&end===b.length);return {buf:Buffer.concat(keep),w,h};}
        pos=end;
    }
    throw Error('invalid png');
}
// GIF decoders may expand every animation frame. Only pass the first one.
function firstGif(b){
    if(b.toString('ascii',0,3)!=='GIF')return b;
    const colorTable=packed=>(packed&128)?3*(1<<((packed&7)+1)):0;
    let p=13+colorTable(b[10]),frames=0;
    function subBlocks(){for(;;){guard(p<b.length);const n=b[p++];if(!n)return;guard(p+n<=b.length);p+=n;}}
    while(p<b.length&&frames++<65536){
        const tag=b[p++];
        if(tag===0x21){p++;subBlocks();}
        else if(tag===0x2c){
            guard(p+9<=b.length&&b.readUInt16LE(p+4)*b.readUInt16LE(p+6)<=12_000_000);
            p+=9+colorTable(b[p+8])+1;subBlocks();return Buffer.concat([b.subarray(0,p),Buffer.from([0x3b])]);
        }else throw Error('invalid gif');
    }
    throw Error('invalid gif');
}
let req,installRoot;
function installed(p){
    const base=path.join(installRoot,'node_modules');
    guard(stat(base).isDirectory());
    const actual=fs.realpathSync(p);
    guard(actual.startsWith(base+path.sep));
    return actual;
}
const codecs=new Map();
const wasmRoots=new Set();
// Upstream browser-first codecs fetch their bundled WASM with file: URLs.
// Node fetch cannot open these; allow only installed codec binaries, never a URL from an image or the network.
globalThis.fetch=async input=>{
    const url=new URL(input instanceof Request?input.url:input);
    guard(url.protocol==='file:');
    const p=fs.realpathSync(fileURLToPath(url));
    guard(path.extname(p)==='.wasm'&&[...wasmRoots].some(dir=>p.startsWith(dir+path.sep)));
    return new Response(read(p,stat(p),16*1024*1024),{headers:{'Content-Type':'application/wasm'}});
};
async function getCodec(type){
    const names={png:'@jimp/wasm-png',jpg:'@jimp/wasm-jpeg',webp:'@jimp/wasm-webp',
        gif:'@jimp/js-gif',bmp:'@jimp/js-bmp',heif:'@jimp/wasm-avif',avif:'@jimp/wasm-avif'};
    if(!codecs.has(type))codecs.set(type,(async()=>{
        // wasm codecs in ST 1.19.x have an import-only export. Resolve the
        // package's own ESM entry from the installation, never cwd/our PATH.
        async function esm(pkg){
            const meta=installed(req.resolve(pkg+'/package.json')),entry=req(meta).exports['.'].import;
            if(pkg.startsWith('@jimp/wasm-')){
                const own=createRequire(meta),squash='@jsquash/'+pkg.slice('@jimp/wasm-'.length)+'/package.json';
                wasmRoots.add(path.dirname(installed(own.resolve(squash))));
            }
            return import(pathToFileURL(installed(path.resolve(path.dirname(meta),typeof entry==='string'?entry:entry.default))).href);
        }
        const [{createJimp},resize,jpeg]=await Promise.all([esm('@jimp/core'),esm('@jimp/plugin-resize'),esm(names.jpg)]);
        const format=type==='jpg'?jpeg:await esm(names[type]);
        const formats=type==='jpg'?[jpeg.default]:[jpeg.default,format.default];
        if(type==='bmp'&&format.msBmp)formats.push(format.msBmp);
        return createJimp({formats,plugins:[resize.methods]});
    })());
    return codecs.get(type);
}
async function render(bytes){
    const png=pngPixels(bytes);
    if(png)bytes=png.buf;
    const dimensions=png||req(installed(req.resolve('image-size'))).imageSize(bytes);
    const w=png?png.w:dimensions.width,h=png?png.h:dimensions.height;
    guard(Number.isInteger(w)&&Number.isInteger(h)&&w>0&&h>0&&w*h<=12_000_000&&Math.max(w,h)<=16384);
    if(png&&Math.max(w,h)<=edge&&bytes.length<=limit)return bytes;
    if(!png)guard(['jpg','webp','gif','bmp','heif','avif'].includes(dimensions.type));
    if(dimensions.type==='webp'&&bytes.toString('ascii',12,16)==='VP8X')guard(!(bytes[20]&2));
    if(dimensions.type==='heif'||dimensions.type==='avif')guard(!bytes.subarray(0,512).includes('avis'));
    bytes=firstGif(bytes);
    // Only load the installed codec modules, never server.js, a plugin or a
    // user-editable installation script just to display a thumbnail.
    const image=await (await getCodec(png?'png':dimensions.type)).read(bytes);
    const iw=image.bitmap.width,ih=image.bitmap.height;
    for(const [side,q] of [[edge,72],[Math.floor(edge*0.70),54],[Math.floor(edge*0.48),44]]){
        const ratio=Math.min(1,side/Math.max(iw,ih));
        const width=Math.max(1,Math.round(iw*ratio)),height=Math.max(1,Math.round(ih*ratio));
        if(width!==image.bitmap.width||height!==image.bitmap.height)image.resize({w:width,h:height});
        const out=await image.getBuffer('image/jpeg',{quality:q,jpegColorSpace:'ycbcr'});
        if(out.length<=limit)return out;
    }
    throw Error('thumbnail too large');
}
async function main(){
    guard(['list','detail'].includes(mode));
    const user=dec(user64,255),items=JSON.parse(dec(input64,8192));guard(safe(user)&&!user.startsWith('_'));
    guard(Array.isArray(items)&&items.length>0&&items.length<=(detail?1:4));
    guard(stat(home).isDirectory());
    const root=fs.realpathSync(home),base=pin(root),profile=pin(path.join(pin(path.join(base,'data')),user));
    installRoot=root;req=createRequire(path.join(root,'package.json'));
    for(let i=0;i<items.length;i++){
        const mark=pinned.length;
        try{
            const [r,rev]=items[i],parts=r.split('/');guard(parts.length===2&&/^[0-9a-f]{64}$/.test(rev));
            const [folder,file]=parts;
            guard(safe(file)&&((folder==='characters'&&file.endsWith('.png'))||(folder==='backgrounds'&&/\.(jpe?g|png|webp|gif|bmp|avif)$/i.test(file))));
            const source=path.join(pin(path.join(profile,folder)),file),s=stat(source);
            guard(s.isFile()&&crypto.createHash('sha256').update(JSON.stringify([root,user,r,id(s)])).digest('hex')===rev);
            let result=null;
            if(!detail)try{
                const cached=path.join(pin(path.join(pin(path.join(profile,'thumbnails')),folder==='characters'?'avatar':'bg')),file),c=stat(cached);
                if(c.mtimeNs>=s.mtimeNs)result=await render(read(cached,c,1024*1024));
            }catch{/* original is the fallback, no cache is required */}
            result??=await render(read(source,s,32*1024*1024));
            guard(id(stat(source))===id(s));
            console.log('art\t'+i+'\t'+result.toString('base64'));
        }catch{/* invalid or changed image; preserve the rest of this batch */}
        finally{while(pinned.length>mark)fs.closeSync(pinned.pop());}
    }
}
main().catch(()=>{process.exitCode=64;}).finally(()=>{while(pinned.length)fs.closeSync(pinned.pop());});
ARTWORK_JS
