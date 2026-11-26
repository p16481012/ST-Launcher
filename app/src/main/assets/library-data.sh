#!/usr/bin/env bash
# Profile library API. No user-supplied paths are shell expressions. The manager
# takes the operation lock and stops the server before trash / undo.
set -eu
if [[ "$1" == library-import && "${6:-}" == cards ]]; then
    [[ "$#" == 9 ]] || exit 64
    reply="$(bash "$(dirname "$0")/share-character.sh" import "$2" "$4" "$5" "$7" "$8" "$9" rename)" || exit $?
    printf '%s\n' "$reply" | sed -n 's/^card_file=/imported_b64=/p'
    exit 0
fi
exec node - "$@" <<'LIBRARY_JS'
'use strict';
const fs=require('node:fs'), path=require('node:path'), crypto=require('node:crypto');
const {execFileSync}=require('node:child_process');
const [action,rootArg,ownerArg,downloads,user64,...args]=process.argv.slice(2);
const C=fs.constants, MB=1024*1024, nofollow=C.O_NOFOLLOW|C.O_NONBLOCK, pageLimit=38*1024;
const h=s=>crypto.createHash('sha256').update(s).digest('hex'), b64=s=>Buffer.from(s).toString('base64');
const utf=b=>new TextDecoder('utf-8',{fatal:true}).decode(b);
function fail(code,message){throw Object.assign(new Error(message),{exit:code});}
function dec(s,limit=4096){
    if(typeof s!=='string'||s.length>Math.ceil(limit/3)*4) fail(64,'선택 정보가 너무 길어요.');
    const b=Buffer.from(s,'base64'); if(b.length>limit||b.toString('base64')!==s)fail(64,'잘못된 선택 정보예요.');
    try{return utf(b);}catch{fail(64,'올바른 UTF-8 이름이 아니에요.');}
}
const safe=s=>typeof s==='string'&&s!=='.'&&s!=='..'&&s.length>0&&Buffer.byteLength(s)<=255&&!/[\\/\x00-\x1f\x7f]/.test(s);
const assetName=s=>safe(s)&&!s.startsWith('.');
const stat=p=>fs.lstatSync(p,{bigint:true,throwIfNoEntry:false});
const partId=s=>[s.dev,s.ino,s.mode,s.size,s.mtimeNs,s.ctimeNs].join(':');
const ino=s=>[s.dev,s.ino].join(':');
function cancel(){if([process.env.ST_CANCEL_REQUEST_FILE,process.env.ST_CANCEL_REQUEST_ID_FILE].some(p=>p&&fs.existsSync(p)))fail(130,'작업을 중단했어요. 완료한 항목은 유지해요.');}
function syncDir(p){if(process.platform==='win32')return;const fd=fs.openSync(p,C.O_DIRECTORY|C.O_RDONLY);try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}}
const isLinux=process.platform==='linux'||process.platform==='android', fds=[];
function pin(p,create=false) {
    if(create&&!stat(p)){try{fs.mkdirSync(p,{mode:0o700});syncDir(path.dirname(p));}catch(e){if(e.code!=='EEXIST')throw e;}}
    const s=stat(p);if(!s?.isDirectory())fail(145,'안전한 폴더를 확인하지 못했어요.');
    if(!isLinux)return p;
    const fd=fs.openSync(p,C.O_DIRECTORY|C.O_RDONLY|C.O_NOFOLLOW);
    if(ino(fs.fstatSync(fd,{bigint:true}))!==ino(s)){fs.closeSync(fd);fail(145,'폴더가 바뀌었어요. 다시 확인해 주세요.');}
    fds.push(fd);return '/proc/'+process.pid+'/fd/'+fd;
}
function dirpath(base,relative,optional=false){
    let p=base;
    for(const part of relative.split('/').filter(Boolean)){
        if(!safe(part))fail(64,'사용할 수 없는 폴더예요.');
        p=path.join(p,part);const s=stat(p);
        if(!s&&optional)return null;
        if(!s?.isDirectory())fail(145,'폴더가 없거나 안전하게 접근할 수 없어요.');
    }
    return p;
}
function entries(d){return d?fs.readdirSync(d,{withFileTypes:true}).filter(e=>assetName(e.name)):[];}
function withFile(p,max,fn){
    const fd=fs.openSync(p,C.O_RDONLY|nofollow);
    try{const st=fs.fstatSync(fd,{bigint:true});if(!st.isFile()||st.size>BigInt(max))fail(145,'파일을 안전하게 읽을 수 없거나 크기 제한을 넘었어요.');
        const r=fn(fd,st);const after=stat(p);
        if(!after||partId(st)!==partId(fs.fstatSync(fd,{bigint:true}))||partId(st)!==partId(after))fail(145,'읽는 동안 파일이 바뀌었어요.');
        return r;
    }finally{fs.closeSync(fd);}
}
function readAt(fd,n,offset){const b=Buffer.alloc(n);for(let i=0;i<n;){const m=fs.readSync(fd,b,i,n-i,offset+i);if(!m)fail(145,'읽는 동안 파일이 바뀌었어요.');i+=m;}return b;}
const read=(p,max)=>withFile(p,max,(fd,s)=>readAt(fd,Number(s.size),0));
function json(p,max){return JSON.parse(utf(read(p,max)));}
const crcTable=Uint32Array.from({length:256},(_,i)=>{let x=i;for(let j=0;j<8;j++)x=(x&1)?0xedb88320^(x>>>1):x>>>1;return x>>>0;});
function crcText(key,data){let c=0xffffffff;for(const b of Buffer.from('tEXt'+key+'\0','latin1'))c=crcTable[(c^b)&255]^(c>>>8);for(const b of data)c=crcTable[(c^b)&255]^(c>>>8);return (~c)>>>0;}
function cardInfo(p){
    // Skip pixel payloads: listing must not decode or hash an image.
    try{return withFile(p,Number.MAX_SAFE_INTEGER,(fd,s)=>{
        const len=Number(s.size);if(len<45||readAt(fd,8,0).toString('hex')!=='89504e470d0a1a0a')return null;
        let pos=8,data=false,ended=false;const tags=new Map();
        for(let i=0;pos+12<=len&&i<65536;i++){
            const hd=readAt(fd,8,pos),n=hd.readUInt32BE(0),t=hd.toString('ascii',4,8);
            if(pos+n+12>len||(i===0&&(t!=='IHDR'||n!==13)))return null;
            if(t==='tEXt'){
                const hdr=readAt(fd,Math.min(80,n),pos+8),nul=hdr.indexOf(0),key=nul>0?hdr.toString('latin1',0,nul):'';
                if(key==='chara'||key==='ccv3'){
                    if(n>8*MB||tags.has(key))return null;
                    const bytes=readAt(fd,n-nul-1,pos+9+nul),encoded=bytes.toString('latin1'),plain=Buffer.from(encoded,'base64');
                    if(plain.toString('base64')!==encoded||crcText(key,bytes)!==readAt(fd,4,pos+n+8).readUInt32BE(0))return null;
                    tags.set(key,JSON.parse(utf(plain)));
                }
            }
            if(t==='IDAT')data=true;pos+=12+n;
            if(t==='IEND'){ended=n===0&&pos===len;break;}
        }
        const c=tags.get('ccv3')||tags.get('chara'),name=c?.data?.name||c?.name;
        if(!data||!ended||typeof name!=='string'||!name.trim())return null;
        return {name:[...name.trim()].slice(0,160).join(''),favorite:[c.fav,c.data?.extensions?.fav].some(x=>x===true||x==='true'),world:c.data?.extensions?.world};
    });}catch(e){if(e.exit===130)throw e;return null;}
}
const jsonFolders={presets:'OpenAI Settings',themes:'themes',quickreplies:'QuickReplies'};
const bgTypes=new Set(['png','jpg','jpeg','webp','gif','bmp','avif']);
const changeKinds=new Set(['backgrounds','worlds','presets','themes','quickreplies']);
const named=k=>k==='themes'||k==='quickreplies';
const obj=x=>x!==null&&typeof x==='object'&&!Array.isArray(x);
const ext=s=>path.extname(s).slice(1).toLowerCase();
function category(r){
    if(typeof r!=='string')return null;const a=r.split('/');if(a.length!==2||!assetName(a[1]))return null;
    const [d,n]=a;
    if(d==='characters'&&n.endsWith('.png'))return 'cards';
    if(d==='chats')return 'orphans';
    if(d==='backgrounds'&&bgTypes.has(ext(n)))return 'backgrounds';
    if(d==='worlds'&&n.endsWith('.json'))return 'worlds';
    if(n.endsWith('.json'))return Object.keys(jsonFolders).find(k=>jsonFolders[k]===d)||null;
    return null;
}
let root,rootName,profile,owner,user;
let cacheReady=false,cacheDirty=false,cacheIdentity,cacheFolder,cacheName,cardCache=new Map();
function openCardCache(){
    if(cacheReady)return;cacheReady=true;
    try{
        cacheIdentity=[rootName,ino(fs.statSync(root,{bigint:true})),user,ino(fs.statSync(profile,{bigint:true}))].join('\0');
        cacheName='library-card-'+h(cacheIdentity)+'.json';cacheFolder=pin(path.join(owner,'cache'),true);
        const from=json(path.join(cacheFolder,cacheName),3*MB);
        if(from.v===1&&from.scope===h(cacheIdentity)&&Array.isArray(from.items)&&from.items.length<=4000){
            for(const rec of from.items)if(Array.isArray(rec)&&rec.length===2&&assetName(rec[0])&&obj(rec[1]))cardCache.set(rec[0],rec[1]);
        }
    }catch{/* private derived cache is entirely optional */}
}
function cachedCard(name,p,s){
    // Never consult this cache when authorizing a live move. It contains
    // display metadata only, and is scoped to one installation+profile inode.
    if(!['library-list','library-usage'].includes(action))return cardInfo(p);
    openCardCache();const old=cardCache.get(name),key=partId(s);
    if(old?.key===key&&(old.info===null||(typeof old.info?.name==='string'&&typeof old.info?.favorite==='boolean')))return old.info;
    const info=cardInfo(p);
    const minimal=info?{name:info.name,favorite:info.favorite,world:typeof info.world==='string'&&info.world.length<=255?info.world:undefined}:null;
    cardCache.delete(name);cardCache.set(name,{key,info:minimal});cacheDirty=true;return minimal;
}
function flushCardCache(){
    if(!cacheDirty||!cacheFolder)return;
    const temp=path.join(cacheFolder,'.library-cache-'+crypto.randomUUID()+'.tmp');
    try{
        let bytes=0;const kept=[];for(const [key,val] of [...cardCache].reverse()){
            const row=[key,val];bytes+=Buffer.byteLength(JSON.stringify(row))+1;if(bytes>2*MB||kept.length>=4000)break;kept.push(row);
        }
        // Rename an exclusive temporary snapshot. Concurrent readers may miss
        // a cache entry but can never publish partial JSON or change live data.
        fs.writeFileSync(temp,JSON.stringify({v:1,scope:h(cacheIdentity),items:kept.reverse()}),{flag:'wx',mode:0o600});
        fs.renameSync(temp,path.join(cacheFolder,cacheName));
    }catch{/* full/read-only storage: library remains usable */}
    finally{try{fs.unlinkSync(temp);}catch{}}
}
function verifyLiveProfile(){
    const p=path.join(rootName,'data',user);
    if(fs.realpathSync(rootArg)!==rootName||!stat(rootArg)?.isDirectory()||
        ino(fs.statSync(root,{bigint:true}))!==ino(stat(rootArg))||!stat(path.join(rootName,'data'))?.isDirectory()||
        !stat(p)?.isDirectory()||ino(stat(p))!==ino(fs.statSync(profile,{bigint:true})))fail(145,'사용자 폴더가 바뀌어 원본을 유지했어요.');
}
function listFiles(kind){
    const folders=Object.hasOwn(jsonFolders,kind)?[jsonFolders[kind]]:{cards:['characters'],orphans:['chats'],backgrounds:['backgrounds'],worlds:['worlds']}[kind];
    if(!Array.isArray(folders))fail(64,'지원하지 않는 목록이에요.');
    const out=[];const cards=kind==='orphans'?dirpath(profile,'characters'):null;
    for(const d of folders){const p=dirpath(profile,d,true);
        for(const e of entries(p)){
            const r=d+'/'+e.name;if(category(r)!==kind)continue;
            if(kind==='orphans'?e.isDirectory()&&!stat(path.join(cards,e.name+'.png')):e.isFile())out.push(r);
        }
    }
    return out.sort();
}
function groups(){
    const members=new Set();let unknown=false;
    try{for(const e of entries(dirpath(profile,'groups',true))){if(!e.name.endsWith('.json'))continue;
        if(!e.isFile()){unknown=true;continue;}try{const g=json(path.join(profile,'groups',e.name),4*MB);if(!Array.isArray(g.members))unknown=true;else g.members.forEach(x=>members.add(x));}catch{unknown=true;}}}catch{unknown=true;}
    return {members,unknown};
}
function chatStat(name){
    const chat=dirpath(profile,'chats',true),d=chat&&path.join(chat,name),st=d&&stat(d),hash=crypto.createHash('sha256');
    if(!st)return {count:0,bytes:0,stamp:0,rev:'none',safe:true};
    if(!st.isDirectory())return {count:0,bytes:0,stamp:0,rev:'unsafe',safe:false};
    let count=0,bytes=0,stamp=0,clean=true;hash.update(partId(st));
    for(const f of fs.readdirSync(d).sort()){cancel();const s=stat(path.join(d,f));
        if(!safe(f)||!s?.isFile()||!f.endsWith('.jsonl')){clean=false;continue;}
        count++;bytes+=Number(s.size);stamp=Math.max(stamp,Number(s.mtimeMs));hash.update(JSON.stringify([f,partId(s)]));
    }
    return {count,bytes,stamp,rev:hash.digest('hex'),safe:clean};
}
function item(r,g){
    const k=category(r),[d,n]=r.split('/');if(!k)fail(64,'지원하지 않는 항목이에요.');
    const par=dirpath(profile,d),p=path.join(par,n),s=stat(p);
    if(k==='orphans'?!s?.isDirectory():!s?.isFile())fail(145,'선택한 항목이 바뀌었어요.');
    let label=n,bytes=k==='orphans'?0:Number(s.size),count=0,when=Number(s.mtimeMs),protectedItem=false,card=null,revisionData='';
    if(k==='cards'||k==='orphans'){
        const name=k==='cards'?n.slice(0,-4):n;
        const chats=chatStat(name);bytes+=chats.bytes;count=chats.count;when=chats.stamp||when;revisionData=chats.rev;
        if(k==='cards'){card=cachedCard(n,p,s);label=card?.name||name;protectedItem=!card||card.favorite||g.unknown||g.members.has(n);}
        else {if(stat(path.join(dirpath(profile,'characters'),n+'.png')))fail(145,'같은 이름의 캐릭터 카드가 생겼어요.');protectedItem=g.unknown||g.members.has(n+'.png');}
        protectedItem ||= !chats.safe;
    }
    return {r,k,n,p,label,bytes,fileBytes:k==='orphans'?0:Number(s.size),count,when,prot:protectedItem,
        artRev:k==='cards'||k==='backgrounds'?h(JSON.stringify([rootName,user,r,partId(s)])):'',
        rev:h(JSON.stringify([rootName,user,r,partId(s),revisionData,protectedItem]))};
}
function list(){
    const kind=args[0],cursor=dec(args[1]||'');
    const all=listFiles(kind),g=kind==='cards'||kind==='orphans'?groups():null;
    let used=0,prev=cursor,next='',scanned=0;const start=Date.now();
    for(const r of all){if(r<=cursor)continue;cancel();
        if(scanned&&(scanned>=3000||Date.now()-start>20000)){next=prev;break;}
        const x=item(r,g);
        const row=['item',b64(r),b64(x.label),x.bytes,x.count,x.when,x.rev,x.prot?1:0,x.fileBytes,x.artRev].join('\t');
        if(used+Buffer.byteLength(row)+1>pageLimit){next=prev;break;}
        console.log(row);used+=Buffer.byteLength(row)+1;prev=r;scanned++;
    }
    console.log('next_cursor='+b64(next));
}
function writeEx(p,data){const fd=fs.openSync(p,'wx',0o600);try{fs.writeFileSync(fd,data);fs.fsyncSync(fd);}finally{fs.closeSync(fd);}}
function noReplace(src,dst){
    if(stat(dst)||fs.statSync(path.dirname(src),{bigint:true}).dev!==fs.statSync(path.dirname(dst),{bigint:true}).dev)fail(145,'같은 이름이 생겼거나 저장 위치가 달라 원래 파일을 유지해요.');
    if(isLinux){
        // Pass the pinned directory descriptors explicitly. An Android child
        // need not be permitted to inspect its parent's /proc/<pid>/fd.
        const l=fs.openSync(path.dirname(src),C.O_DIRECTORY|C.O_RDONLY);
        try{const r=fs.openSync(path.dirname(dst),C.O_DIRECTORY|C.O_RDONLY);
            try{execFileSync('mv',['-nT','--','/proc/self/fd/3/'+path.basename(src),'/proc/self/fd/4/'+path.basename(dst)],{stdio:['ignore','ignore','pipe',l,r]});}
            finally{fs.closeSync(r);}
        }finally{fs.closeSync(l);}
    }else execFileSync('mv',['-nT','--',src,dst],{stdio:'pipe'});
    if(stat(src))fail(145,'같은 이름이 이미 있어 덮어쓰지 않았어요.');
}
function fixedParent(base,rel){let p=base;for(const n of rel.split('/').slice(0,-1)){if(!safe(n))fail(64,'잘못된 폴더예요.');p=pin(path.join(p,n));}return p;}
function closeSince(start){while(fds.length>start)fs.closeSync(fds.pop());}
const uuid=/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/,receipt=/^\d{1,16}-[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/;
function groupPlan(id,folder){
    if(!uuid.test(id))fail(64,'휴지통 작업을 다시 선택해 주세요.');
    const p=json(path.join(folder,id+'.json'),2*MB);
    if(p.format!==1||p.root!==rootName||p.rootInode!==ino(fs.statSync(root,{bigint:true}))||p.user!==user||p.userInode!==ino(fs.statSync(profile,{bigint:true}))||!Array.isArray(p.rows)||p.rows.length>1000)fail(145,'다른 설치에서 만든 복구 기록이에요.');
    for(const m of p.rows)if(!receipt.test(m.id)||!category(m.r)||!/^\d+:\d+$/.test(m.identity))fail(145,'휴지통 기록이 바뀌었어요.');
    return p;
}
function trash(){
    const selections=JSON.parse(dec(args[0],90000)),withChats=(args[1]||'0');
    if(!['0','1'].includes(withChats)||!Array.isArray(selections)||selections.length<1||selections.length>500)fail(64,'한 번에 최대 500개까지 선택해 주세요.');
    const g=groups(),rows=[],seen=new Set(),accepted=[],created=Date.now(),gid=crypto.randomUUID();let skipped=0;
    for(const v of selections){cancel();
        if(!obj(v)||!['cards','orphans'].includes(category(v.path))||!/^([a-f0-9]{64})$/.test(v.revision)||seen.has(v.path))fail(64,'옮길 캐릭터나 채팅을 다시 선택해 주세요.');
        seen.add(v.path);
        let x;try{x=item(v.path,g);}catch(e){if(e.exit!==145)throw e;}
        if(!x||x.prot||x.rev!==v.revision){skipped++;continue;}
        const rr=[x.r],cr='chats/'+x.n.slice(0,-4);
        if(withChats==='1'&&x.k==='cards'&&dirpath(profile,'chats',true)&&stat(path.join(profile,cr)))rr.push(cr);
        const batch=rr.map(r=>{const s=stat(path.join(profile,r));return {r,selected:x.r,identity:ino(s),version:partId(s),
            chatRevision:category(r)==='orphans'?chatStat(path.basename(r)).rev:'',id:created+'-'+crypto.randomUUID()};});
        rows.push(...batch);accepted.push({x,batch});
    }
    if(!rows.length){console.log('moved=0\nskipped='+skipped);return;}
    const journal=pin(path.join(owner,'library-undo'),true),base=pin(path.join(owner,'file-trash'),true);
    // Plan and metadata are durable before the first move. A killed shell may
    // leave planned-but-unmoved records; history only offers actual receipts.
    writeEx(path.join(journal,gid+'.json'),JSON.stringify({format:1,root:rootName,rootInode:ino(fs.statSync(root,{bigint:true})),user,userInode:ino(fs.statSync(profile,{bigint:true})),created,rows}));syncDir(journal);
    let moved=0;const liveGroups=groups();
    for(const {x,batch} of accepted){cancel();let check;try{check=item(x.r,liveGroups);}catch(e){if(e.exit!==145)throw e;}
        if(!check||check.prot||check.rev!==x.rev){skipped++;continue;}
        for(const m of batch){const start=fds.length;try{
            verifyLiveProfile();const source=path.join(fixedParent(profile,m.r),path.basename(m.r));
            if(!stat(source)||ino(stat(source))!==m.identity)fail(145,'항목이 바뀌어 중단했어요. 휴지통에서 완료한 항목을 되돌릴 수 있어요.');
            fs.mkdirSync(path.join(base,m.id),{mode:0o700});syncDir(base);const container=pin(path.join(base,m.id));
            writeEx(path.join(container,'metadata.json'),JSON.stringify({root:rootName,relative:'data/'+user+'/'+m.r,deleted_at:created,libraryGroup:gid}));syncDir(container);
            const live=stat(source);
            if(!live||partId(live)!==m.version||
                (category(m.r)==='orphans'&&(chatStat(path.basename(m.r)).rev!==m.chatRevision||
                 stat(path.join(dirpath(profile,'characters'),path.basename(m.r)+'.png'))))){
                fs.unlinkSync(path.join(container,'metadata.json'));fs.rmdirSync(path.join(base,m.id));syncDir(base);
                fail(145,'옮기기 직전에 카드나 채팅이 바뀌었어요. 이미 옮긴 항목은 휴지통에 있어요.');
            }
            noReplace(source,path.join(container,'item'));syncDir(container);syncDir(path.dirname(source));
        }finally{closeSince(start);}}
        moved++;
    }
    console.log('group_id='+gid+'\nmoved='+moved+'\nskipped='+skipped);
}
function historyOrUndo(){
    const undo=action==='library-undo',journal=dirpath(owner,'library-undo',true),base=dirpath(owner,'file-trash',true);
    if(!journal||!base){if(undo)fail(145,'되돌릴 파일이 없어요.');return;}
    const ids=undo?[args[0]]:entries(journal).filter(e=>e.isFile()&&e.name.endsWith('.json')&&uuid.test(e.name.slice(0,-5))).map(e=>e.name.slice(0,-5));
    const result=[];let restored=0,skipped=0;
    for(const id of ids){let plan;
        try{plan=groupPlan(id,journal);}catch(e){if(undo)throw e;continue;}
        let count=0;
        for(const m of plan.rows){cancel();const start=fds.length;
            try {
                const cp=path.join(base,m.id);if(!stat(cp)?.isDirectory())continue;const cd=pin(cp),stored=path.join(cd,'item'),s=stat(stored);
                const meta=json(path.join(cd,'metadata.json'),8192);
                if(!s){
                    // Interruption after writing durable metadata and before
                    // rename: never move/delete the original that is still live.
                    if(undo&&meta.root===rootName&&meta.libraryGroup===id&&meta.relative==='data/'+user+'/'+m.r&&
                        fs.readdirSync(cd).join('')==='metadata.json'){
                        const dest=path.join(fixedParent(profile,m.r),path.basename(m.r)),live=stat(dest);
                        if(live&&ino(live)===m.identity){fs.unlinkSync(path.join(cd,'metadata.json'));fs.rmdirSync(cp);syncDir(base);}
                    }
                    continue;
                }
                if(meta.root!==rootName||meta.relative!=='data/'+user+'/'+m.r||meta.libraryGroup!==id||ino(s)!==m.identity||(!s.isDirectory()&&!s.isFile())){skipped++;continue;}
                count++;if(!undo)continue;
                verifyLiveProfile();const dest=path.join(fixedParent(profile,m.r),path.basename(m.r));
                if(stat(dest)){skipped++;continue;}
                if(named(category(m.r))){
                    // A newly added theme/QR may have the old internal name
                    // under a different filename. Don't make ST choose between duplicates.
                    let old;if(s.size<=50n*BigInt(MB)){try{old=json(stored,50*MB);}catch(e){if(!(e instanceof SyntaxError)&&e.code!=='ERR_ENCODING_INVALID_ENCODED_DATA')throw e;}}
                    if(typeof old?.name==='string')checkInternalName(path.dirname(dest),old.name.normalize('NFC'),null);
                }
                // Never attach orphan/old conversations to a newly-created card.
                if(category(m.r)==='orphans'){
                    const name=path.basename(m.r)+'.png',live=stat(path.join(dirpath(profile,'characters'),name)),card=plan.rows.find(z=>z.r==='characters/'+name&&z.selected===m.selected);
                    if(live&&(!card||ino(live)!==card.identity)){skipped++;continue;}
                }
                noReplace(stored,dest);syncDir(path.dirname(dest));syncDir(cd);fs.unlinkSync(path.join(cd,'metadata.json'));fs.rmdirSync(cp);syncDir(base);restored++;
            }catch(e){if(e.exit===130)throw e;if(undo)skipped++;}finally{closeSince(start);}
        }
        if(!undo&&count)result.push({id,created:plan.created,count});
        if(undo&&!skipped){fs.unlinkSync(path.join(journal,id+'.json'));syncDir(journal);}
    }
    if(undo)console.log('restored='+restored+'\nskipped='+skipped);
    else for(const g of result.sort((a,b)=>b.created-a.created).slice(0,120))console.log(['group',g.id,g.created,g.count].join('\t'));
}
const contains=(s,k)=>Object.hasOwn(s,k);
function jsonLibrary(v){
    if(!obj(v)||['instruct','context','sysprompt','preset'].filter(k=>obj(v[k])).length>=2)return null;
    const matches=[];
    if(Array.isArray(v.prompts)||typeof v.chat_completion_source==='string'||typeof v.openai_max_context==='number')matches.push('presets');
    if(Array.isArray(v.qrList)||Array.isArray(v.quickReplySlots))matches.push('quickreplies');
    if(typeof v.main_text_color==='string'||typeof v.blur_strength==='number')matches.push('themes');
    return matches.length===1?matches[0]:null;
}
function imageExt(b){
    if(b.length>=33&&b.subarray(0,8).toString('hex')==='89504e470d0a1a0a'&&b.toString('ascii',12,16)==='IHDR')return 'png';
    if(b.length>3&&b[0]===255&&b[1]===216&&b[2]===255)return 'jpg';
    if(b.length>=12&&b.toString('ascii',0,4)==='RIFF'&&b.toString('ascii',8,12)==='WEBP')return 'webp';
    if(b.length>=13&&['GIF87a','GIF89a'].includes(b.toString('ascii',0,6)))return 'gif';
    if(b.length>=26&&b.toString('ascii',0,2)==='BM')return 'bmp';
    if(b.length>=16&&b.toString('ascii',4,8)==='ftyp'){
        const end=Math.min(b.length,b.readUInt32BE(0),1024);
        for(let n=8;n+4<=end;n+=4){if(n!==12&&['avif','avis'].includes(b.toString('ascii',n,n+4)))return 'avif';}
    }return null;
}
function stem(s,tail){
    if(typeof s!=='string'||!s.trim())fail(144,'이름이 없는 파일은 가져올 수 없어요.');
    let clean=s.normalize('NFC').replace(/[\\/:*?"<>|\x00-\x1f\x7f-\x9f]/g,'').trim().replace(/^[. ]+|[. ]+$/g,'');
    if(!clean||/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:[.]|$)/i.test(clean))clean='가져온 파일';
    let out='';for(const c of clean){if(Buffer.byteLength(out+c+tail)>200)break;out+=c;}return out+tail;
}
function importAsset(){
    const [kind,source,rawName,expected]=args,name=dec(rawName);
    if(args.length!==4||!['backgrounds','worlds','presets','themes','quickreplies'].includes(kind)||!/^SillyTavern-File-[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}\.tmp$/.test(source)||
        !/^[a-f0-9]{64}$/.test(expected)||!safe(name))fail(64,'런처에서 가져올 파일을 다시 선택해 주세요.');
    const buf=read(path.join(fs.realpathSync(downloads),source),kind==='backgrounds'?30*MB:50*MB);cancel();
    if(h(buf)!==expected)fail(145,'선택한 파일이 바뀌었어요. 다시 선택해 주세요.');
    let dirName,extension=ext(name),base=path.parse(name).name,renameField=false,doc;
    if(kind==='backgrounds'){
        if(!bgTypes.has(extension)||imageExt(buf)!==(extension==='jpeg'?'jpg':extension))fail(144,'확장자와 실제 그림 형식이 맞지 않아요.');
        dirName=kind;
    }else{
        if(extension!=='json')fail(144,'실리태번 JSON 파일을 선택해 주세요.');
        try{doc=JSON.parse(utf(buf));}catch{fail(144,'JSON 파일을 읽을 수 없어요.');}
        if(kind==='worlds'){
            if(!obj(doc)||contains(doc,'lorebookVersion')||doc.kind==='memory'||doc.type==='risu'||!obj(doc.entries))fail(144,'실리태번 로어북이 아니에요. 다른 형식은 실리태번 화면에서 가져와 주세요.');
            dirName='worlds';
        }else{
            const type=jsonLibrary(doc),labels={presets:'채팅 완성 프리셋',themes:'테마',quickreplies:'빠른 답장(QR)'};
            if(type!==kind)fail(144,type?labels[type]+' 파일이에요. 해당 목록에서 가져와 주세요.':labels[kind]+' 형식이 아니에요. 다른 형식은 실리태번에서 가져와 주세요.');
            dirName=jsonFolders[kind];renameField=kind==='themes'||kind==='quickreplies';
            if(renameField&&typeof doc.name==='string'&&doc.name.trim())base=doc.name;
        }
    }
    verifyLiveProfile();const folder=pin(path.join(profile,dirName),true); // Only known internal category names.
    // Themes and QR sets are selected by their JSON name, not their file name.
    const taken=new Set();
    if(renameField)for(const e of entries(folder)){
        cancel();if(!e.isFile()||!e.name.endsWith('.json'))continue;
        try{const v=json(path.join(folder,e.name),50*MB);if(typeof v?.name==='string')taken.add(v.name);}
        catch(error){if(error instanceof SyntaxError)continue;throw error;}
    }
    for(let i=1;i<=10000;i++){
        cancel();const file=stem(base,(i===1?'':' ('+i+')')+'.'+extension),dest=path.join(folder,file);
        if(stat(dest)||taken.has(path.parse(file).name))continue;
        const tmp=path.join(folder,'.st-launcher-import-'+crypto.randomUUID()+'.tmp');
        const data=renameField?JSON.stringify({...doc,name:path.parse(file).name},null,4):buf;
        try{writeEx(tmp,data);cancel();
            // A successful no-clobber rename is the commit point.
            verifyLiveProfile();
            const liveFolder=stat(path.join(profile,dirName));
            if(!liveFolder?.isDirectory()||ino(liveFolder)!==ino(fs.statSync(folder,{bigint:true})))fail(145,'넣을 폴더가 바뀌었어요.');
            try{noReplace(tmp,dest);}catch(e){if(e.exit===145&&stat(dest))continue;throw e;}
            try{syncDir(folder);}catch{console.error('저장 후 폴더 동기화 결과는 확인하지 못했어요.');}
            console.log('imported_b64='+b64(file));return;
        }finally{if(stat(tmp))fs.unlinkSync(tmp);}
    }fail(145,'같은 이름의 파일이 너무 많아요.');
}
// Usage is explicitly requested for one item. No index/settings/chat contents
// are cached on Android, no queries run on Home/server start. Unknown input
// never means unused. Results concern built-in references, not arbitrary code.
function usageState(r){
    const k=category(r);
    if(!['backgrounds','worlds','presets','themes','quickreplies'].includes(k))fail(64,'사용처를 확인할 수 없는 종류예요.');
    const [folder,n]=r.split('/'),title=path.parse(n).name,p=path.join(dirpath(profile,folder),n);
    if(!stat(p)?.isFile())fail(145,'선택한 파일이 바뀌었어요.');
    let unknown=false,s={},checks=0;const hits=new Set(),start=Date.now();
    const guard=()=>{cancel();return ++checks<=15000&&Date.now()-start<20000;};
    try{s=json(path.join(profile,'settings.json'),8*MB);if(!obj(s))throw Error();}catch{unknown=true;s={};}
    if(k==='presets'||k==='themes'){
        const current=k==='themes'?s.power_user?.theme:s.oai_settings?.preset_settings_openai;
        let effective=title;
        if(k==='themes'){try{effective=json(p,50*MB)?.name;}catch{unknown=true;effective=null;}}
        if(typeof current!=='string'||typeof effective!=='string')unknown=true;
        else if(current===effective)hits.add(k==='themes'?'지금 선택한 테마':'지금 선택한 프리셋');
    }else{
        function bgMatch(value){
            if(typeof value!=='string')return false;
            if(value===n)return true;
            const normalized=value.trim().replace(/^url\((.*)\)$/i,'$1').replace(/^['"]|['"]$/g,'').split(/[?#]/)[0];
            try{return decodeURIComponent(normalized.split('/').at(-1))===n;}catch{unknown=true;return false;}
        }
        let qrName=null;
        const qrUses=config=>typeof qrName==='string'&&Array.isArray(config?.setList)&&config.setList.some(v=>v?.set===qrName);
        if(k==='quickreplies'){
            // 1.19.1 uses name (not the filename), including per-character and per-chat sets.
            // Scripts/other extensions can also look up sets dynamically: never report "unused".
            unknown=true;
            try{qrName=json(p,50*MB)?.name;}catch{ /* unknown */ }
            const qr=s.extension_settings?.quickReplyV2,legacy=s.extension_settings?.quickReply;
            if(qrUses(qr?.config)||(!qr&&(legacy?.selectedPreset??legacy?.name??'Default')===qrName))hits.add('전역 빠른 답장');
            if(Object.values(qr?.characterConfigs||{}).some(qrUses))hits.add('캐릭터 빠른 답장');
            // QR sets can reference another set in their context menu. Do
            // bounded I/O only on this explicit one-item usage/mutation check.
            let budget=8*MB;
            if(!hits.size&&qrName)for(const e of entries(dirpath(profile,folder))){
                if(!guard()||budget<=0)break;
                if(!e.isFile()||e.name===n||!e.name.endsWith('.json'))continue;
                const other=path.join(profile,folder,e.name),st=stat(other);
                if(!st?.isFile()||Number(st.size)>budget)continue;
                budget-=Number(st.size);
                try{
                    const set=json(other,8*MB);
                    if((Array.isArray(set.qrList)&&set.qrList.some(q=>Array.isArray(q?.contextList)&&q.contextList.some(link=>link?.set===qrName)))||
                        (Array.isArray(set.quickReplySlots)&&set.quickReplySlots.some(q=>Array.isArray(q?.contextMenu)&&q.contextMenu.some(link=>link?.preset===qrName)))){
                        hits.add('다른 빠른 답장의 메뉴');break;
                    }
                }catch(e){if(e.exit===130)throw e;}
            }
        }else if(k==='backgrounds'){
            if(typeof s.background?.name!=='string')unknown=true;else if(bgMatch(s.background.name))hits.add('지금 배경');
        }else{
            const world=s.world_info_settings?.world_info;
            if(!obj(world))unknown=true;
            if(Array.isArray(world?.globalSelect)&&world.globalSelect.includes(title))hits.add('전역 로어북');
            if(Array.isArray(world?.charLore)&&world.charLore.some(c=>Array.isArray(c?.extraBooks)&&c.extraBooks.includes(title)))hits.add('캐릭터 추가 로어북');
            if(Object.values(s.power_user?.persona_descriptions||{}).some(c=>c?.lorebook===title))hits.add('페르소나 로어북');
            if(!hits.size||action==='library-usage')for(const cr of listFiles('cards')){if(!guard()){unknown=true;break;}const p=path.join(profile,cr),st=stat(p);const c=st?.isFile()?cachedCard(path.basename(cr),p,st):null;if(!c)unknown=true;else if(c.world===title){hits.add('캐릭터 로어북');if(action!=='library-usage')break;}}
        }
        if(hits.size&&action!=='library-usage')return {state:'used',detail:Array.from(hits).join(' · ')};
        function firstHeader(p){
            return withFile(p,Number.MAX_SAFE_INTEGER,(fd,st)=>{
                const chunks=[];let off=0,ended=false;
                while(off<Number(st.size)&&off<MB){const bytes=readAt(fd,Math.min(8192,Number(st.size)-off,MB-off),off),i=bytes.indexOf(10);off+=bytes.length;
                    if(i>=0){chunks.push(bytes.subarray(0,i));ended=true;break;}chunks.push(bytes);}
                if(!ended&&off<Number(st.size))throw Error();
                const j=JSON.parse(utf(Buffer.concat(chunks)));if(!obj(j)||contains(j,'mes')||!obj(j.chat_metadata))throw Error();return j.chat_metadata;
            });
        }
        function inspectChats(d){for(const e of entries(d)){if(!e.name.endsWith('.jsonl'))continue;
            if(!guard()){unknown=true;return false;}if(!e.isFile()){unknown=true;continue;}
            try{const m=firstHeader(path.join(d,e.name));
                if(k==='quickreplies'){if(qrUses(m.quickReply))hits.add('채팅 빠른 답장');}
                else if(k==='worlds'?m.world_info===title:bgMatch(m.custom_background)||(Array.isArray(m.chat_backgrounds)&&m.chat_backgrounds.some(bgMatch)))hits.add(k==='worlds'?'채팅 로어북':'채팅 배경');
                if(hits.size&&action!=='library-usage')return false;
            }catch(e){if(e.exit===130)throw e;unknown=true;}}
            return true;
        }
        const cs=dirpath(profile,'chats',true);for(const e of entries(cs)){if(!e.isDirectory()){unknown=true;continue;}if(!guard()||!inspectChats(path.join(cs,e.name))){unknown=true;break;}}
        if(guard())inspectChats(dirpath(profile,'group chats',true));else unknown=true;
    }
    const state=hits.size?'used':unknown?'unconfirmed':'none';
    return {state,detail:hits.size?Array.from(hits).join(' · ')+(unknown?' · 일부 확인 못 함':''):unknown?'일부 설정·채팅에서 사용 여부를 확인하지 못했어요.':'검사한 실리태번 기본 설정에서 사용처를 찾지 못했어요.'};
}
function usage(){
    const r=dec(args[0]);
    if(args.length>2||!changeKinds.has(category(r)))fail(64,'지원하지 않는 자료예요.');
    const check=()=>{if(args.length===2&&item(r,null).rev!==args[1])fail(145,'자료가 바뀌었어요. 다시 확인해 주세요.');};
    check();const result=usageState(r);check();
    console.log('state='+result.state+'\ndetail_b64='+b64(result.detail));
}
// JSON.parse/stringify of a whole profile document could change large integers,
// formatting and extension-owned values. Replace only the top-level name token.
function renamedDocument(source,stem){
    const raw=read(source,50*MB),bom=raw.subarray(0,3).toString('hex')==='efbbbf',text=utf(raw);
    let doc;try{doc=JSON.parse(text);}catch{fail(144,'파일 안의 이름을 안전하게 바꿀 수 없는 JSON이에요.');}
    if(!obj(doc))fail(144,'자료의 JSON 형식을 확인할 수 없어요.');
    const endString=i=>{for(let j=i+1;j<text.length;j++){if(text[j]==='\\')j++;else if(text[j]==='"')return j+1;}fail(144,'JSON 문자열을 읽을 수 없어요.');};
    const afterSpace=i=>{while(i<text.length&&/\s/.test(text[i]))i++;return i;};
    let depth=0,range=null;
    for(let i=0;i<text.length;){
        const c=text[i];if(c!=='"'){if(c==='{'||c==='[')depth++;else if(c==='}'||c===']')depth--;i++;continue;}
        const end=endString(i),colon=depth===1?afterSpace(end):end;
        if(depth===1&&text[colon]===':'&&JSON.parse(text.slice(i,end))==='name'){
            const begin=afterSpace(colon+1);
            if(range||text[begin]!=='"')fail(144,'이름 항목이 중복되었거나 올바른 문자열이 아니에요.');
            range=[begin,endString(begin)];i=range[1];
        }else i=end;
    }
    const token=JSON.stringify(stem),at=text.indexOf('{')+1;
    return (bom?'\ufeff':'')+(range?text.slice(0,range[0])+token+text.slice(range[1]):text.slice(0,at)+'"name":'+token+(Object.keys(doc).length?',':'')+text.slice(at));
}
function checkInternalName(folder,stem,ignore){
    for(const e of entries(folder)){cancel();if(e.name===ignore||!e.name.endsWith('.json'))continue;
        if(!e.isFile())fail(145,'목록에 확인할 수 없는 JSON 파일이 있어 이름을 바꾸지 않았어요.');
        let doc;try{doc=json(path.join(folder,e.name),50*MB);}catch(error){if(error instanceof SyntaxError)continue;throw error;}
        if(typeof doc?.name==='string'&&doc.name.normalize('NFC')===stem)fail(145,'파일 이름은 달라도 실리태번에서 같은 이름을 쓰는 자료가 있어요.');
    }
}
function stashAsset(r,source,check){
    const created=Date.now(),gid=crypto.randomUUID(),id=created+'-'+crypto.randomUUID();
    const s=check(),rows=[{r,selected:r,id,identity:ino(s),version:partId(s),chatRevision:''}];
    const journal=pin(path.join(owner,'library-undo'),true),base=pin(path.join(owner,'file-trash'),true);
    writeEx(path.join(journal,gid+'.json'),JSON.stringify({format:1,root:rootName,rootInode:ino(fs.statSync(root,{bigint:true})),user,userInode:ino(fs.statSync(profile,{bigint:true})),created,rows}));syncDir(journal);
    const record=path.join(base,id);fs.mkdirSync(record,{mode:0o700});syncDir(base);
    const container=pin(record);
    writeEx(path.join(container,'metadata.json'),JSON.stringify({root:rootName,relative:'data/'+user+'/'+r,deleted_at:created,libraryGroup:gid}));syncDir(container);
    check();noReplace(source,path.join(container,'item'));syncDir(container);syncDir(path.dirname(source));return gid;
}
function assetChange(){
    const [raw,revision,mode,rawStem,allow]=args,r=dec(raw),k=category(r);
    if(args.length!==5||!changeKinds.has(k)||!/^([a-f0-9]{64})$/.test(revision)||!['rename','copy','trash'].includes(mode)||!['0','1'].includes(allow))fail(64,'변경할 자료를 다시 선택해 주세요.');
    const [dir,n]=r.split('/'),stem=dec(rawStem,240),ext=path.extname(n),name=mode==='trash'?n:stem+ext;
    if(mode==='trash'?stem!=='':!assetName(stem)||stem!==stem.trim()||stem!==stem.normalize('NFC')||stem.endsWith('.')||/[:*?"<>|]/.test(stem)||Buffer.byteLength(name)>240||name===n)fail(64,'사용할 수 없는 이름이에요. 확장자를 제외한 새 이름을 입력해 주세요.');
    cancel();const folder=fixedParent(profile,r),folderId=ino(fs.statSync(folder,{bigint:true})),source=path.join(folder,n),dest=path.join(folder,name);
    const check=()=>{
        verifyLiveProfile();const parent=stat(path.join(profile,dir)),s=stat(source);
        if(!parent?.isDirectory()||ino(parent)!==folderId||!s?.isFile()||h(JSON.stringify([rootName,user,r,partId(s),'',false]))!==revision)fail(145,'선택한 자료가 바뀌었어요. 목록을 새로 확인해 주세요.');
        return s;
    };
    check();
    if(mode!=='copy'){
        const usage=usageState(r);
        if(usage.state==='used')fail(144,usage.detail+'에서 사용 중이에요. 원본을 유지했어요.');
        if(usage.state==='unconfirmed'&&allow!=='1')fail(144,'모든 사용처를 확인하지 못했어요. 원본을 유지하거나 확인 후 진행해 주세요.');
    }
    cancel();check();
    let group='';
    if(mode==='trash')group=stashAsset(r,source,check);
    else{
        if(stat(dest))fail(145,'같은 이름의 파일이 있어요. 덮어쓰지 않았어요.');
        if(named(k))checkInternalName(folder,stem,mode==='rename'?n:null);
        if(mode==='rename'&&!named(k)){cancel();check();noReplace(source,dest);syncDir(folder);}
        else{
            const tmp=path.join(folder,'.st-launcher-asset-'+crypto.randomUUID()+'.tmp');
            const fd=fs.openSync(tmp,'wx',0o600);
            try{
                try{
                    if(named(k))fs.writeFileSync(fd,renamedDocument(source,stem));
                    else withFile(source,Number.MAX_SAFE_INTEGER,(input,s)=>{
                        const buffer=Buffer.allocUnsafe(128*1024);for(let at=0;at<Number(s.size);){
                            cancel();const count=fs.readSync(input,buffer,0,Math.min(buffer.length,Number(s.size)-at),at);
                            if(!count)fail(145,'복사하는 동안 파일이 바뀌었어요.');fs.writeFileSync(fd,buffer.subarray(0,count));at+=count;
                        }
                    });
                    fs.fsyncSync(fd);
                }finally{fs.closeSync(fd);}
                cancel();check();noReplace(tmp,dest);syncDir(folder);
                // If interrupted between publication and the trash move both valid
                // files survive. Never remove the original to complete a failed copy.
                if(mode==='rename')group=stashAsset(r,source,check);
            }finally{if(stat(tmp))fs.unlinkSync(tmp);}
        }
    }
    console.log('action='+mode+'\nname_b64='+b64(name));
    if(group)console.log('group_id='+group+'\nmoved=1\nskipped=0');
}
try{
    user=dec(user64,255);
    if(!assetName(user)||user.startsWith('_'))fail(113,'사용자를 다시 선택해 주세요.');
    if(!stat(rootArg)?.isDirectory()||!stat(ownerArg)?.isDirectory())fail(145,'설치 위치를 안전하게 확인할 수 없어요.');
    rootName=fs.realpathSync(rootArg);root=pin(rootName);owner=pin(fs.realpathSync(ownerArg));profile=pin(path.join(pin(path.join(root,'data')),user));
    if(action==='library-list')list();
    else if(action==='library-trash')trash();
    else if(action==='library-history'||action==='library-undo')historyOrUndo();
    else if(action==='library-usage')usage();
    else if(action==='library-asset-change')assetChange();
    else if(action==='library-import')importAsset();
    else fail(64,'지원하지 않는 작업이에요.');
}catch(e){
    console.error(e.exit?e.message:('데이터 작업을 완료할 수 없어요. ('+(e.code||'잘못된 파일')+')'));
    process.exitCode=e.exit||({ENOSPC:29,ENOENT:145,ELOOP:145}[e.code])||144;
}finally{flushCardCache();closeSince(0);}
LIBRARY_JS
