#!/usr/bin/env bash
# Commands receive opaque UTF-8 names, never shell fragments. Node's bounded byte
# buffer is both the verified snapshot and the published bytes.
exec node - "$@" <<'CARD_JS'
'use strict';
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const [action, home, downloads, userArg, ...args] = process.argv.slice(2);
const limit = 20 * 1024 * 1024, b64 = s => Buffer.from(s).toString('base64');
const fail = (code, message) => { throw Object.assign(new Error(message), {exit:code}); };
const decode = s => {
    if (typeof s !== 'string' || s.length > 8192 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(s)) fail(110,'올바르지 않은 입력이에요.');
    return new TextDecoder('utf-8',{fatal:true}).decode(Buffer.from(s,'base64'));
};
const dir = s => { if (!fs.lstatSync(s, {throwIfNoEntry:false})?.isDirectory()) fail(113,'대상 사용자 폴더를 확인해 주세요.'); return s; };
const safeUser = s => !!s && s !== '.' && !s.startsWith('.') && !s.startsWith('_') && !/[\\/\x00-\x1f\x7f]/.test(s) && Buffer.byteLength(s)<=255;
const rows = new Uint32Array(256);
for (let n=0;n<256;n++){let c=n;for(let b=0;b<8;b++)c=(c&1)?0xedb88320^(c>>>1):c>>>1;rows[n]=c;}
function cardName(buf){
    const invalid = ()=>fail(110,'캐릭터 정보가 없는 그림이에요. 원본 PNG 파일을 다시 받아 주세요.');
    if (buf.length<45 || buf.subarray(0,8).toString('hex')!=='89504e470d0a1a0a') invalid();
    let p=8, seenData=false, ended=false, chunks=0; const tags=new Map();
    while (p+12<=buf.length && ++chunks<=65536){
        const len=buf.readUInt32BE(p), type=buf.toString('latin1',p+4,p+8), end=p+8+len;
        if(end+4>buf.length || (p===8 && (type!=='IHDR'||len!==13)) || (p!==8 && type==='IHDR'))invalid();
        let crc=0xffffffff; for(let i=p+4;i<end;i++)crc=rows[(crc^buf[i])&255]^(crc>>>8);
        if(((~crc)>>>0)!==buf.readUInt32BE(end))invalid();
        if(type==='tEXt'){
            const body=buf.subarray(p+8,end), nul=body.subarray(0,80).indexOf(0);
            const key=nul>0?body.toString('latin1',0,nul):'';
            if(key==='chara'||key==='ccv3'){if(tags.has(key))invalid();tags.set(key,body.subarray(nul+1));}
        }
        if(type==='IDAT')seenData=true;
        p=end+4;
        if(type==='IEND'){ended=len===0 && p===buf.length;break;}
    }
    if(!ended||!seenData||!tags.size)invalid();
    const bytes=tags.get('ccv3')??tags.get('chara'), encoded=bytes.toString('latin1');
    // A character at a time rather than a recursive regex over a multi-MB string.
    if(encoded.length%4!==0 || /[^A-Za-z0-9+/=]/.test(encoded))invalid();
    const decoded=Buffer.from(encoded,'base64');if(decoded.toString('base64')!==encoded)invalid();
    let json;try{json=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(decoded));}catch{invalid();}
    const v= json?.data?.name ?? json?.name;
    if(typeof v!=='string'||!v.trim())invalid();return v.trim();
}
function stem(s){
    let clean=s.replace(/[\\/:*?"<>|\x00-\x1f\x7f-\x9f]/g,'').trim().replace(/^[. ]+|[. ]+$/g,'').replace(/\.png$/i,'').trim().replace(/[. ]+$/g,'');
    if(!clean || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:[.]|$)/i.test(clean))clean='캐릭터';
    return clean;
}
function makeName(s,n){let tail=(n===1?'':' ('+n+')')+'.png', base='';for(const c of stem(s)){if(Buffer.byteLength(base+c+tail)>200)break;base+=c;}return base+tail;}
function exists(n){return !!fs.lstatSync(n,{throwIfNoEntry:false});}
function choice(s,taken){for(let n=1;n<=10000;n++){let name=makeName(s,n);if(!exists(name)&&!taken.has(name))return name;}fail(112,'같은 이름의 카드가 너무 많아요.');}
try{
    const root=dir(path.join(fs.realpathSync(dir(home)),'data'));
    if(action==='list-users'){
        for(const entry of fs.readdirSync(root,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))){
            if(safeUser(entry.name)&&entry.isDirectory()) console.log(['user',b64(entry.name),fs.lstatSync(path.join(root,entry.name,'characters'),{throwIfNoEntry:false})?.isDirectory()?1:0].join('\t'));
        }
    } else {
        const user=decode(userArg);if(!safeUser(user))fail(113,'사용자를 다시 선택해 주세요.');
        const folder=dir(path.join(dir(path.join(root,user)),'characters'));process.chdir(folder);
        const identity=fs.statSync('.',{bigint:true});
        const unchanged=()=>{const now=fs.lstatSync(folder,{bigint:true});if(!now.isDirectory()||now.ino!==identity.ino||now.dev!==identity.dev||fs.realpathSync(folder)!==folder)fail(113,'대상 폴더가 바뀌었어요.');};
        unchanged();
        if(action==='plan'){
            if(args.length>20)fail(110,'한 번에 20개까지 넣을 수 있어요.');const taken=new Set();
            for(const raw of args){const desired=decode(raw), name=choice(desired,taken);taken.add(name);console.log(['plan',name===makeName(desired,1)?0:1,b64(name)].join('\t'));}
        } else if(action==='import'){
            const [source,rawName,sha,policy]=args;
            if(!/^SillyTavern-File-[0-9a-f-]{36}\.tmp$/.test(source)||!/^[0-9a-f]{64}$/.test(sha)||!['rename','skip'].includes(policy))fail(110,'올바르지 않은 요청이에요.');
            const fd=fs.openSync(path.join(downloads,source),fs.constants.O_RDONLY|fs.constants.O_NONBLOCK|fs.constants.O_NOFOLLOW);let buf;
            try{const stat=fs.fstatSync(fd);if(!stat.isFile())fail(110,'일반 파일만 가져올 수 있어요.');if(stat.size>limit)fail(111,'파일이 너무 커요.');buf=Buffer.alloc(stat.size);let pos=0;while(pos<buf.length){let n=fs.readSync(fd,buf,pos,buf.length-pos,pos);if(!n)fail(110,'임시 파일이 바뀌었어요.');pos+=n;}if(fs.fstatSync(fd).size!==pos)fail(110,'파일이 바뀌었어요.');}finally{fs.closeSync(fd);}
            if(crypto.createHash('sha256').update(buf).digest('hex')!==sha)fail(110,'확인한 뒤 파일이 바뀌었어요. 다시 공유해 주세요.');
            const card=cardName(buf), desired=decode(rawName)||card, tmp='.st-launcher-card-'+crypto.randomUUID()+'.tmp';
            const out=fs.openSync(tmp,'wx',0o600);
            try{
                try{fs.writeFileSync(out,buf);fs.fsyncSync(out);}finally{fs.closeSync(out);}
                let published=false;
                for(let race=0;race<12;race++){
                    unchanged();const name=policy==='skip'?makeName(desired,1):choice(desired,new Set());
                    if(policy==='skip'&&exists(name))fail(112,'같은 이름의 카드가 있어 넣지 않았어요.');
                    if([process.env.ST_CANCEL_REQUEST_FILE,process.env.ST_CANCEL_REQUEST_ID_FILE].some(p=>p&&exists(p)))fail(130,'가져오기를 중단했어요.');
                    // GNU coreutils no-clobber on the same private filesystem. Do not use hard links on Android.
                    execFileSync('mv',['-nT','--',tmp,name],{stdio:'pipe'});
                    if(exists(tmp)){if(policy==='skip')fail(112,'같은 이름의 카드가 있어 넣지 않았어요.');continue;}
                    // Already committed. A directory-sync failure must not tell the user to retry and duplicate it.
                    try{const d=fs.openSync('.','r');try{fs.fsyncSync(d);}finally{fs.closeSync(d);}}
                    catch{console.error('카드는 저장했지만 폴더 동기화 결과를 확인하지 못했어요.');}
                    console.log('card_file='+b64(name));console.log('card_name='+b64([...card].slice(0,200).join('')));published=true;break;
                }
                if(!published)fail(112,'저장할 이름이 계속 바뀌었어요. 다시 시도해 주세요.');
            }finally{if(exists(tmp))fs.unlinkSync(tmp);}
        } else fail(110,'알 수 없는 카드 작업이에요.');
    }
}catch(e){console.error(e.exit?e.message:'캐릭터 파일을 읽거나 저장하지 못했어요. 저장공간과 권한을 확인해 주세요.');process.exitCode=e.exit||1;}
CARD_JS
