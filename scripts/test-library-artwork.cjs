const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto'),zlib=require('node:zlib');
const {spawnSync}=require('node:child_process');
const helper=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/library-artwork.sh'),'utf8').split("<<'ARTWORK_JS'")[1].split(/\r?\nARTWORK_JS/)[0];
const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'st-artwork-')));
const profile=path.join(root,'data','default-user'),b64=s=>Buffer.from(s).toString('base64');
function chunk(name,body){
    const t=Buffer.from(name),size=Buffer.alloc(4),crc=Buffer.alloc(4);size.writeUInt32BE(body.length);
    let c=0xffffffff;for(const n of Buffer.concat([t,body])){c^=n;for(let i=0;i<8;i++)c=c&1?(c>>>1)^0xedb88320:c>>>1;}
    crc.writeUInt32BE((~c)>>>0);return Buffer.concat([size,t,body,crc]);
}
function png(w,h,tag=false){
    const hdr=Buffer.alloc(13);hdr.writeUInt32BE(w);hdr.writeUInt32BE(h,4);hdr[8]=8;hdr[9]=2;
    const row=Buffer.alloc(1+w*3);for(let x=0;x<w;x++){row[1+x*3]=x%255;row[2+x*3]=170;row[3+x*3]=90;}
    return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',hdr),...(tag?[chunk('tEXt',Buffer.from('chara\0NOT_FOR_THE_CALLBACK'))]:[]),
        chunk('IDAT',zlib.deflateSync(Buffer.concat(Array(h).fill(row)))),chunk('IEND',Buffer.alloc(0))]);
}
function add(r,data){const p=path.join(profile,r);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,data);return p;}
function ref(r){
    const s=fs.lstatSync(path.join(profile,r),{bigint:true});
    const stamp=[s.dev,s.ino,s.mode,s.size,s.mtimeNs,s.ctimeNs].join(':');
    return [r,crypto.createHash('sha256').update(JSON.stringify([root,'default-user',r,stamp])).digest('hex')];
}
function run(rows,mode='list',user='default-user',expected=0){
    const input=process.env.ST_ARTWORK_DEBUG?helper.replace('}catch{/* invalid or changed image;', '}catch(e){console.error(e);/* invalid or changed image;'):helper;
    const result=spawnSync(process.execPath,['-',root,b64(user),mode,b64(JSON.stringify(rows))],{input,encoding:'utf8',timeout:35_000});
    if(process.env.ST_ARTWORK_DEBUG&&result.stderr)console.warn(result.stderr);
    assert.equal(result.status,expected,result.error?.message||result.stderr);
    assert(Buffer.byteLength(result.stdout)<40*1024);
    const arts=new Map;for(const line of result.stdout.trim().split('\n')){const [t,id,data]=line.split('\t');if(t==='art')arts.set(+id,Buffer.from(data.trim(),'base64'));}
    return arts;
}
try{
    const r='characters/A.png',bg='backgrounds/B.png';
    const card=add(r,png(80,120,true));add(bg,png(120,60));
    const group=run([ref(r),[r,'0'.repeat(64)],ref(bg),['../../elsewhere','0'.repeat(64)]]);
    assert.deepEqual([...group.keys()],[0,2]);assert(!group.get(0).includes('NOT_FOR_THE_CALLBACK'));
    assert(fs.readFileSync(card).includes('NOT_FOR_THE_CALLBACK'));
    // A current SillyTavern cache is preferred for the list, never for uncropped detail.
    const cached=add('thumbnails/avatar/A.png',png(24,26));
    fs.utimesSync(cached,new Date(),new Date(Date.now()+1000));
    assert.equal(run([ref(r)]).get(0).readUInt32BE(16),24);
    assert.equal(run([ref(r)],'detail').get(0).readUInt32BE(16),80);
    const old=ref(r);fs.appendFileSync(card,'changed');assert.equal(run([old]).size,0);
    run(Array(5).fill(ref(bg)),'list','default-user',64);run([ref(bg),ref(bg)],'detail','default-user',64);
    run([ref(bg)],'list','../escape',64);
    if(process.platform!=='win32'){
        fs.symlinkSync(card,path.join(profile,'characters','Link.png'));
        assert.equal(run([ref('characters/Link.png')]).size,0);
        fs.symlinkSync(profile,path.join(root,'data','linked'));run([ref(bg)],'list','linked',64);
    }
    if(process.env.ST_ARTWORK_CODEC_ROOT){
        const deps=path.resolve(process.env.ST_ARTWORK_CODEC_ROOT,'node_modules');
        fs.cpSync(deps,path.join(root,'node_modules'),{recursive:true,dereference:true});
        const wide='backgrounds/🌙 wide.png';add(wide,png(1024,640));
        const detail=run([ref(wide)],'detail').get(0);assert(detail,'large original PNG must decode, resize and encode');
        assert.equal(detail.subarray(0,3).toString('hex'),'ffd8ff');
        const jpeg='backgrounds/original.jpg';add(jpeg,detail);
        const sample=run([ref(wide),ref(jpeg)]);assert.equal(sample.size,2,'decoded JPEG and PNG');
        const {imageSize}=require(path.join(deps,'image-size'));
        for(const b of sample.values()){
            const {width,height}=imageSize(b);assert(width<=192&&height<=192&&Math.abs(width/height-1.6)<.03);assert(b.length<=6144);
        }
        const {width,height}=imageSize(detail);assert(width===576&&height===360);
    }
    console.log('Private artwork: bounded batches, metadata stripping, cache, uncropped detail, revision and path checks passed'+(process.env.ST_ARTWORK_CODEC_ROOT?' with real SillyTavern codecs.':'.'));
}finally{fs.rmSync(root,{recursive:true,force:true});}
