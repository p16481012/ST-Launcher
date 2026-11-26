#!/data/data/com.termux/files/usr/bin/bash
# Accept only the private validated index produced by manager.sh.
set -Eeuo pipefail
exec node --input-type=commonjs - "$@" <<'NODE'
const fs = require('fs'), path = require('path'), crypto = require('crypto'), zlib = require('zlib');
const { spawnSync } = require('child_process');
const [action, ...argv] = process.argv.slice(2);
const fail = (code, msg) => { throw Object.assign(new Error(msg), { exitCode: code }); };
const b64 = s => Buffer.from(s).toString('base64');
const hash = s => crypto.createHash('sha256').update(s).digest('hex');
const stat = p => { try { return fs.lstatSync(p,{bigint:true}); } catch(e) { if(e.code==='ENOENT')return null; throw e; } };
const revision = s => s ? [s.dev,s.ino,s.mode,s.size,s.mtimeNs,s.ctimeNs].join(':') : '-';
const id = s => s ? [s.dev,s.ino,s.mode].join(':') : '-';
const ownFile = s => s ? [s.dev,s.ino,s.mode,s.size,s.mtimeNs].join(':') : '-';
const part = s => typeof s==='string' && !!s && !s.startsWith('.') && s!=='..' && !/[\\\x00-\x1f\x7f:/]/.test(s) && Buffer.byteLength(s)<=255;
const profile = s => part(s) && !s.startsWith('_') && !['constructor','prototype'].includes(s);
const dirs = new Map(['OpenAI Settings','TextGen Settings','KoboldAI Settings','NovelAI Settings','instruct','context','sysprompt','reasoning','themes'].map(k=>[k,'presets']));
for(const [k,v] of Object.entries({'characters':'characters','chats':'chats','group chats':'group_chats','groups':'groups','worlds':'worlds','User Avatars':'avatars','backgrounds':'backgrounds'})) dirs.set(k,v);
function category(rel) {
    const a=rel.split('/'), kind=dirs.get(a[0]);
    if (!kind || !a.every(part) || a.length!==(kind==='chats'?3:2)) return '';
    const file=a.at(-1);
    if(kind==='characters'||kind==='avatars')return /\.png$/i.test(file)?kind:'';
    if(kind==='chats'||kind==='group_chats')return /\.jsonl$/i.test(file)?kind:'';
    if(kind==='backgrounds')return /\.(?:png|jpe?g|webp|bmp|gif|avif)$/i.test(file)?kind:'';
    return /\.json$/i.test(file)?kind:'';
}
function archiveStamp(p) {
    const s=stat(p); if(!s || !s.isFile())fail(72,'선택한 ZIP 파일을 확인할 수 없어요.');
    return hash(path.basename(p)+':'+revision(s));
}
function readJson(p,lim=268435456) {
    const s=stat(p); if(!s?.isFile()||s.size>BigInt(lim))fail(153,'가져오기 기록을 안전하게 확인할 수 없어요.');
    return JSON.parse(fs.readFileSync(p,'utf8'));
}
function save(p,obj) {
    const fd=fs.openSync(p,'wx',0o600);try{fs.writeFileSync(fd,JSON.stringify(obj));fs.fsyncSync(fd);}finally{fs.closeSync(fd);}
}
function syncDir(p) { const fd=fs.openSync(p,fs.constants.O_DIRECTORY|fs.constants.O_NOFOLLOW);try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);} }
const phaseStart=Math.floor(Date.now()/1000);let lastPulse=0,lastLogged=0;
function pulse(phase,n,total,file) {
    const now=Date.now(), dest=process.env.ST_PC_PROGRESS, op=process.env.ST_PC_OPERATION;
    if(!dest||!['import-merge','import-preview'].includes(op)||(now-lastPulse<600&&n<total))return;
    lastPulse=now;
    const fields={percent:total?Math.floor(n*100/total):0,phase,detail:'현재 파일과 진행 수를 표시해요.',status:'running',operation:op,error_code:'',progress_mode:'files',completed_files:n,total_files:total,completed_bytes:0,total_bytes:0,current_item_b64:b64(file.slice(-240)),heartbeat_at:Math.floor(now/1000),activity_at:Math.floor(now/1000),phase_started_at:phaseStart,operation_started_at:process.env.ST_PC_STARTED||0};
    const tmp=dest+'.pc.'+process.pid;
    fs.writeFileSync(tmp,Object.entries(fields).map(([k,v])=>k+'='+v+'\n').join(''),{mode:0o600});fs.renameSync(tmp,dest);
    if(process.env.ST_PC_LOG&&(now-lastLogged>=2500||n===total)){lastLogged=now;fs.appendFileSync(process.env.ST_PC_LOG,'['+new Date(now).toLocaleTimeString('en-GB',{hour12:false})+'] '+phase+' · '+n+' / '+total+' · '+file.slice(-200)+'\n');}
}
const table=Array.from({length:256},(_,i)=>{for(let j=0;j<8;j++)i=(i>>>1)^((i&1)?0xedb88320:0);return i>>>0;});
const crc=(b,old=0)=>{if(zlib.crc32)return zlib.crc32(b,old);let n=old^(-1);for(const x of b)n=(n>>>8)^table[(n^x)&255];return (n^(-1))>>>0;};
function manifest(fd,e) {
    if(e.bytes>65536||e.compressedBytes>131072||e.isDirectory)fail(23,'ZIP 백업 범위 정보가 올바르지 않아요.');
    let b=Buffer.alloc(e.compressedBytes);
    if(b.length!==fs.readSync(fd,b,0,b.length,e.dataOffset))fail(20,'ZIP 백업 정보를 읽을 수 없어요.');
    if(e.method===8)b=zlib.inflateRawSync(b,{maxOutputLength:65537});
    if(b.length!==e.bytes||crc(b)!==e.crc32)fail(20,'ZIP 백업 정보가 손상되었어요.');
    const lines=b.toString('utf8').split(/\r?\n/);
    const item=lines.filter(l=>l.startsWith('items='));
    if(!lines.includes('format=st-launcher-backup-v1')||item.length!==1)fail(23,'지원하지 않는 런처 백업 범위예요.');
    return item[0].slice(6).split(',');
}
// Only inflate metadata or a bounded same-name card for an identity check.
// Unverifiable metadata is not a reason to reject other, independent assets.
const groupLimit=4*1024*1024, cardLimit=32*1024*1024;
function zipBytes(fd,e,limit) {
    if(!e||e.isDirectory||e.bytes>limit||e.compressedBytes>limit+65536)return null;
    const raw=Buffer.alloc(e.compressedBytes);
    if(raw.length!==fs.readSync(fd,raw,0,raw.length,e.dataOffset))fail(20,'ZIP 항목을 읽지 못했어요.');
    let b;try{b=e.method===8?zlib.inflateRawSync(raw,{maxOutputLength:Math.max(e.bytes,1)}):raw;}
    catch{fail(20,'ZIP 항목의 압축 데이터가 손상되었어요.');}
    if(b.length!==e.bytes||crc(b)!==e.crc32)fail(20,'ZIP 항목의 내용이 손상되었어요.');
    return b;
}
function groupInfo(bytes,stem) {
    if(!bytes)return null;
    try{
        const g=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
        if(!g||typeof g!=='object'||Array.isArray(g))return null;
        const key=x=>typeof x==='string'?x:(Number.isSafeInteger(x)&&x>=0?String(x):'');
        const ids=new Set();
        const chats=g.chats===undefined?[]:g.chats;if(!Array.isArray(chats)||chats.length>16384)return null;
        for(const v of [...chats,...(g.chat_id===undefined?[]:[g.chat_id])]){
            const id=key(v);if(!part(id)||!part(id+'.jsonl'))return null;ids.add(id);
        }
        if(!ids.size)ids.add(stem); // Older groups use their ID for the default chat.
        const disabled=g.disabled_members===undefined?[]:g.disabled_members;
        const valid=Array.isArray(g.members)&&Array.isArray(disabled)&&g.members.length+disabled.length<=8192;
        const members=valid?[...new Set([...g.members,...disabled])]:[];
        return {ids,members,valid:valid&&(g.id===undefined||key(g.id)===stem)&&members.every(x=>part(x)&&/\.png$/i.test(x))};
    }catch{return null;}
}
function omittedKind(rel) {
    if(['settings.json','stats.json','secrets.json'].includes(rel))return 'settings';
    if(/^characters\/[^/]+\/.+/.test(rel))return 'expressions';
    if(/^(?:user\/)?images\//.test(rel))return 'chat_images';
    if(/^(?:extensions|public\/scripts\/extensions)\//.test(rel))return 'extensions';
    return 'other';
}
function classifyZip(entries,fd) {
    // Walk only a unique consecutive wrapper chain; never guess between two installations.
    const manifestPaths=entries.filter(e=>e.path.split('/').at(-1)==='.st-launcher-manifest');
    if(manifestPaths.length>1)fail(23,'런처 백업 정보가 여러 곳에 있어요. 원본 ZIP을 다시 선택해 주세요.');
    let named=entries.map(e=>({e,p:e.path}));
    for(let depth=0;depth<128;depth++) {
        const top=new Set(named.map(x=>x.p.split('/',1)[0]));
        const marked=named.find(x=>x.p==='.st-launcher-manifest');
        const legacy=named.some(x=>/^public\/(?:characters|chats|group chats|groups|worlds|settings\.json|stats\.json)(\/|$)/.test(x.p));
        if(legacy) return {kind:'legacy_public',rows:[],sources:[]};
        const flat=[...top].some(k=>dirs.has(k)||k==='settings.json');
        const data=top.has('data');
        if(!marked && manifestPaths.length && (data||flat||top.size!==1))fail(23,'런처 백업 정보 위치가 모호해요. 원본 ZIP을 다시 선택해 주세요.');
        const native=marked ? manifest(fd,marked.e) : null;
        if(native && !native.some(k=>['full','user_data'].includes(k))) return {kind:'launcher_backup',rows:[],sources:[]};
        // data/ zipped from its parent, the contents of data/, a named single
        // profile directory, and a native user backup with no profile wrapper.
        const namedUsers=new Set(named.filter(x=>{const a=x.p.split('/');return a.length>=2&&profile(a[0])&&(dirs.has(a[1])||a[1]==='settings.json');}).map(x=>x.p.split('/')[0]));
        if(data || flat || namedUsers.size || native) {
            const kind=native?'launcher_backup':(data&&top.has('server.js')&&top.has('package.json')?'full_candidate':'user_data');
            const rows=[],sources=new Set(),excluded=new Map();
            for(const {e,p} of named) {
                let a=p.split('/'),u;
                if(data){if(a[0]!=='data'){if(!e.isDirectory)excluded.set(e.path,omittedKind(p));continue;}a.shift();u=a.shift();}
                else if(flat){u='default-user';}
                else {u=a.shift();if(!namedUsers.has(u))continue;}
                if(!profile(u)||!a.length)continue;
                const k=category(a.join('/')); if(dirs.has(a[0])||a[0]==='settings.json')sources.add(u);
                if(k&&!e.isDirectory) rows.push({e,u,rel:a.join('/'),k});
                else if(!e.isDirectory)excluded.set(e.path,omittedKind(a.join('/')));
            }
            // Only keep normalized paths for the optional inventory. The classification
            // above already rejected legacy public/ and identified the wrapper chain.
            const impactScope=!native&&process.env.ST_PC_REPLACE_IMPACT==='1'&&
                (data?'data':top.has('default-user')?'profiles':
                ['characters','chats','settings.json'].some(k=>top.has(k))?'raw':null);
            return {kind,rows,sources:[...sources].sort(),excluded,impact:impactScope?{scope:impactScope,named}:null};
        }
        if([...top].some(k=>['config.yaml','public','third-party'].includes(k)))return {kind:'config_extensions',rows:[],sources:[]};
        if(top.size!==1)break;
        const only=[...top][0];if(!part(only))break;
        named=named.filter(x=>x.p.startsWith(only+'/')).map(x=>({e:x.e,p:x.p.slice(only.length+1)}));if(!named.length)break;
    }
    return {kind:'unknown',rows:[],sources:[]};
}
// Pin one directory at a time. On Linux/Android, lookups (including rollback)
// stay anchored even when another local process replaces a parent with a link.
function at(root,rel,cb,guard=null) {
    const a=rel.split('/');
    if(!a.every(part))fail(21,'안전하지 않은 파일 이름이에요.');
    let fd;
    try {
        let cur=root,level=0;
        for(const component of ['',...a.slice(0,-1)]) {
            if(component)cur=path.join(cur,component);
            const s=stat(cur);if(!s)return cb(null,null,'absent');
            if(!s.isDirectory())return cb(null,null,'blocked');
            const next=fs.openSync(cur,fs.constants.O_RDONLY|fs.constants.O_DIRECTORY|fs.constants.O_NOFOLLOW);
            if(fd!==undefined)fs.closeSync(fd);fd=next;
            if(id(s)!==id(fs.fstatSync(fd,{bigint:true})))fail(73,'대상 폴더가 바뀌었어요.');
            if(guard && ((level===0&&id(s)!==guard.rootId)||(level===2&&id(s)!==guard.profiles[a[1]])))fail(73,'대상 사용자 폴더가 바뀌었어요.');
            level++;
            cur='/proc/self/fd/'+fd;
        }
        return cb(path.join('/proc/self/fd/'+fd,a.at(-1)),fd,'ok');
    } finally {if(fd!==undefined)fs.closeSync(fd);}
}
const info=(root,rel)=>at(root,rel,(p,_,state)=>p?{s:stat(p),blocked:false}:{s:null,blocked:state==='blocked'});
function liveBytes(root,rel,expected,limit) {
    try{return at(root,rel,p=>{
        if(!p||!expected?.isFile()||expected.size>BigInt(limit))return null;
        const fd=fs.openSync(p,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW);try{
            if(revision(fs.fstatSync(fd,{bigint:true}))!==revision(expected))fail(73,'비교 도중 카드 파일이 바뀌었어요.');
            const b=Buffer.alloc(Number(expected.size));
            for(let n=0;n<b.length;){const got=fs.readSync(fd,b,n,b.length-n,n);if(!got)fail(73,'비교 중 파일이 바뀌었어요.');n+=got;}
            if(revision(fs.fstatSync(fd,{bigint:true}))!==revision(expected))fail(73,'비교 도중 카드 파일이 바뀌었어요.');
            return b;
        } finally{fs.closeSync(fd);}
    });}catch(e){if(e.code==='EACCES'||e.code==='EPERM')return null;throw e;}
}
// lstat on the entry, then enumerate through a pinned no-follow directory fd.
function withDir(root,relative,fn) {
    try{return at(root,relative+'/probe',(_,fd,state)=>state==='ok'?fn('/proc/self/fd/'+fd):state);}
    catch(e){if(e.code==='EACCES'||e.code==='EPERM')return 'blocked';throw e;}
}
function relationships(install,fd,rows,mapping,update) {
    const cards=new Map(),ownership=new Map(),orphans=new Map(),groupOwners=new Map(),groups=new Map();
    const key=(u,s)=>u+'/'+s, stem=f=>f.slice(0,-4);
    for(const r of rows)if(r.k==='characters')cards.set(key(r.u,r.rel.slice(11)),r.e);
    function orphan(to,name) {
        const atKey=key(to,name);if(orphans.has(atKey))return orphans.get(atKey);
        const folder='data/'+to+'/chats/'+stem(name);
        const result=withDir(install,folder,p=>{const h=fs.opendirSync(p);try{return h.readSync()?'nonempty':'empty';}finally{h.closeSync();}});
        update('chat-dir',folder,result);const conflict=result!=='empty'&&result!=='absent';orphans.set(atKey,conflict);return conflict;
    }
    function card(u,name) {
        if(!part(name))return {ok:false,known:false};
        const k=key(u,name);if(ownership.has(k))return ownership.get(k);
        const to=mapping.get(u),rel='data/'+to+'/characters/'+name,t=info(install,rel),e=cards.get(k);
        let ok=!t.blocked,known=false;
        if(ok&&t.s){
            ok=false;
            if(t.s.isFile()&&e&&e.bytes===Number(t.s.size)&&e.bytes<=cardLimit){
                const onPhone=liveBytes(install,rel,t.s,cardLimit);
                // A ZIP central-directory CRC alone is not proof the character is the same.
                if(onPhone&&crc(onPhone)===e.crc32){const pc=zipBytes(fd,e,cardLimit);ok=!!pc&&onPhone.equals(pc);}
            }
            known=ok;
        }else if(ok){ok=!orphan(to,name);known=ok&&!!e;}
        const state={ok,known};update('owner',rel,revision(t.s),ok,known);ownership.set(k,state);return state;
    }
    // Index the ZIP group->chat edges individually. Ambiguous ownership protects
    // only the affected groups/chats, not all groups of the same account.
    let groupBudget=128*1024*1024;
    let scanned=0;const groupRows=rows.filter(r=>r.k==='groups'&&mapping.get(r.u));
    for(const r of groupRows){
        pulse('그룹과 대화 연결 확인',++scanned,groupRows.length,r.rel);
        const name=r.rel.slice(7,-5),k=key(r.u,name);
        const b=r.e.bytes<=groupBudget?zipBytes(fd,r.e,groupLimit):null;groupBudget-=b?.length||0;
        const parsed=groupInfo(b,name),ids=parsed?.ids||new Set([name]);
        groups.set(k,{r,name,parsed,ids,ok:false});
        for(const chat of ids){const c=key(r.u,chat);groupOwners.set(c,groupOwners.has(c)?null:k);}
    }
    // A different phone group may already own the same chat ID, even if its
    // .jsonl doesn't exist yet. Read only mapped profiles with incoming groups.
    const liveOwners=new Map(),unknownPhone=new Set();
    for(const u of new Set([...groups.values()].map(g=>g.r.u))){
        const to=mapping.get(u),rel='data/'+to+'/groups';
        let budget=64*1024*1024,count=0;
        const response=withDir(install,rel,p=>{
            const h=fs.opendirSync(p);
            try{let ent;while((ent=h.readSync())){if(!/\.json$/i.test(ent.name))continue;
                if(!part(ent.name)||!ent.isFile()||++count>8192)return 'unconfirmed';
                const gr=rel+'/'+ent.name,s=info(install,gr).s;
                if(!s?.isFile()||s.size>BigInt(Math.min(groupLimit,budget)))return 'unconfirmed';
                budget-=Number(s.size);const name=ent.name.slice(0,-5),g=groupInfo(liveBytes(install,gr,s,groupLimit),name);
                if(!g)return 'unconfirmed';
                for(const cid of g.ids){const c=key(u,cid);if(!groupOwners.has(c))continue;
                    liveOwners.set(c,liveOwners.has(c)?null:key(u,name));}
            }}finally{h.closeSync();}return 'known';
        });
        if(response!=='known'&&response!=='absent')unknownPhone.add(u);
    }
    for(const [k,g] of groups){
        const {r,parsed}=g,to=mapping.get(r.u),rel='data/'+to+'/'+r.rel,t=info(install,rel);
        let ok=!!parsed?.valid&&!t.blocked&&!unknownPhone.has(r.u);
        if(ok&&t.s){
            // An identical group definition may receive missing chats. A
            // different definition at the same path never receives PC chats.
            const bytes=t.s.isFile()&&Number(t.s.size)===r.e.bytes&&liveBytes(install,rel,t.s,groupLimit);
            const pc=bytes&&zipBytes(fd,r.e,groupLimit);ok=!!pc&&bytes.equals(pc);
        }
        if(ok)ok=parsed.members.every(name=>card(r.u,name).known);
        if(ok)for(const chat of g.ids){
            const c=key(r.u,chat);
            if(groupOwners.get(c)!==k||(liveOwners.has(c)&&liveOwners.get(c)!==k)){ok=false;break;}
            if(!t.s){const gc=info(install,'data/'+to+'/group chats/'+chat+'.jsonl');if(gc.blocked||gc.s){ok=false;break;}}
        }
        g.ok=ok;update('group',rel,revision(t.s),ok);
    }
    return {card,orphan,group:(u,name)=>!!groups.get(key(u,name))?.ok,
        groupChat:(u,name)=>!!groups.get(groupOwners.get(key(u,name)))?.ok};
}
// Informational only: replacement ignores the merge account mapping. Never use
// these counts to authorize a write. Only report layouts whose data/ root we
// can identify exactly, and never follow a symlink or return a partial count.
function previewReplacementImpact(install, layout) {
    const unknown=()=>console.log('replace_impact\tunknown');
    try {
        if(!layout.impact)return unknown();
        const {scope,named}=layout.impact, incoming=new Set(),users=new Set();
        let cards=0,chats=0,accounts=0,remaining=50000,matched=false;const until=Date.now()+3000;
        const step=()=>{if(--remaining<0||Date.now()>until)throw Error();};
        if(named.length>remaining)return unknown();
        for(const {e,p} of named){
            step();
            if(('/'+e.path+'/').includes('/.st-launcher-manifest/'))return unknown();
            const slash=p.indexOf('/'), first=slash<0?p:p.slice(0,slash), dir=slash>=0||e.isDirectory;
            if((scope==='data'&&first==='data'&&dir)||(scope==='profiles'&&first==='default-user'&&dir)||
                (scope==='raw'&&((first==='settings.json'&&!dir)||(['chats','characters'].includes(first)&&dir))))matched=true;
            const rel=scope==='data'?(first==='data'&&slash>=0?p.slice(slash+1):''):scope==='raw'?'default-user/'+p:p;
            if(!rel)continue;
            const s=rel.indexOf('/');
            if(s>=0 || e.isDirectory)users.add(s<0?rel:rel.slice(0,s));
            if(!e.isDirectory)incoming.add(rel);
        }
        if(!matched)return unknown();
        function children(dir,visit){
            at(install,'data/'+(dir?dir+'/':'')+'probe',(p,fd,state)=>{
                if(state==='absent')return;
                if(state!=='ok')throw Error();
                const h=fs.opendirSync(path.dirname(p));try{let e;
                    while((e=h.readSync())){step();visit(e);}
                }finally{h.closeSync();}
            });
        }
        children('',u=>{
            if(u.name.startsWith('_')||u.name.startsWith('.'))return;
            if(u.isSymbolicLink())throw Error();
            if(!u.isDirectory())return;
            if(!profile(u.name))throw Error();
            if(!users.has(u.name))accounts++;
            const files=(d,ext,increment)=>children(u.name+'/'+d,e=>{
                if(!e.name.toLowerCase().endsWith(ext))return;
                if(!e.isFile())throw Error();
                if(!incoming.has(u.name+'/'+d+'/'+e.name))increment();
            });
            files('characters','.png',()=>cards++);
            files('group chats','.jsonl',()=>chats++);
            children(u.name+'/chats',e=>{
                if(e.isSymbolicLink())throw Error();
                if(e.isDirectory())files('chats/'+e.name,'.jsonl',()=>chats++);
            });
        });
        console.log(['replace_impact','known',cards,chats,accounts].join('\t'));
    }catch{return unknown();}
}
function decodeMapping(raw) {
    if(!raw)return null;
    if(raw.length>49152||!/^[A-Za-z0-9+/]*={0,2}$/.test(raw))fail(151,'사용자 선택을 다시 확인해 주세요.');
    const bytes=Buffer.from(raw,'base64');if(bytes.toString('base64')!==raw)fail(151,'사용자 선택이 올바르지 않아요.');
    let value;try{value=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));}catch{fail(151,'사용자 선택이 올바르지 않아요.');}
    if(!value||Array.isArray(value)||typeof value!=='object'||Object.keys(value).length>128)fail(151,'사용자 선택이 올바르지 않아요.');
    return new Map(Object.entries(value));
}
function plan([install,archive,indexPath,enc,expected,expectedTarget,work,free]) {
    const z=readJson(indexPath),stamp=archiveStamp(archive);
    if(stamp!==expected)fail(72,'선택한 ZIP이 바뀌었어요.');
    const fd=fs.openSync(archive,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW);
    try{return planOpen(install,archive,z,fd,stamp,enc,expectedTarget,work,free);}finally{fs.closeSync(fd);}
}
function planOpen(install,archive,z,fd,stamp,enc,expectedTarget,work,free) {
    const layout=classifyZip(z.entries,fd);
    const targets=at(install,'data',(p)=>{
        if(!p||!stat(p)?.isDirectory())return [];
        return fs.readdirSync(p,{withFileTypes:true}).filter(d=>d.isDirectory()&&profile(d.name)).map(d=>d.name).sort();
    });
    if(layout.sources.length>128||targets.length>128||Buffer.byteLength(JSON.stringify([...layout.sources,...targets]))>18000)fail(151,'한 번에 가져올 사용자 목록이 너무 커요.');
    const mapping=decodeMapping(enc) || new Map(layout.sources.map(u=>[u,targets.includes(u)?u:'']));
    const destinations=new Set();
    for(const [src,to] of mapping) {
        if(!layout.sources.includes(src)||typeof to!=='string'||(to&&(!targets.includes(to)||destinations.has(to))))fail(151,'대상 사용자를 하나씩 다르게 선택해 주세요.');
        if(to)destinations.add(to);
    }
    const h=crypto.createHash('sha256'), counts={}, selected=[], snapshot=[], reasons={};
    const rootId=id(stat(install));
    const includeAvatars=process.env.ST_PC_INCLUDE_AVATARS!=='0';
    const update=(...parts)=>h.update(JSON.stringify(parts)+'\n');update(rootId,includeAvatars);
    for(const src of layout.sources){const to=mapping.get(src)||'';update(src,to,to?id(info(install,'data/'+to).s):'');}
    const owners=relationships(install,fd,layout.rows,mapping,update);
    let kept=0, bytes=0, relations=0, tried=0, candidateAvatars=0, omittedAvatars=0;
    for(const r of layout.rows) {
        pulse(action==='recheck'?'적용 직전 다시 확인':'새로 넣을 항목 확인',++tried,layout.rows.length,r.rel);
        const to=mapping.get(r.u);if(!to)continue;
        const rel='data/'+to+'/'+r.rel, t=info(install,rel);let preserve=!!(t.s||t.blocked),why=t.blocked?'blocked':'';
        const targetRev=t.blocked?'blocked':revision(t.s);
        if(r.k==='avatars'){
            if(!preserve)candidateAvatars++;
            if(!includeAvatars){omittedAvatars++;update(r.e.path,rel,targetRev,'skip-avatars');continue;}
        }
        if(!preserve){
            if(r.k==='characters'&&owners.orphan(to,r.rel.slice(11)))why='character_conflict';
            if(r.k==='chats'&&!owners.card(r.u,r.rel.split('/')[1]+'.png').ok)why='character_conflict';
            if(r.k==='groups'&&!owners.group(r.u,r.rel.slice(7,-5)))why='group_conflict';
            if(r.k==='group_chats'&&!owners.groupChat(r.u,r.rel.slice(12,-6)))why='group_conflict';
            if(why){preserve=true;relations++;reasons[r.k]=(reasons[r.k]||0)+1;}
        }
        update(r.e.path,rel,targetRev,preserve,why);
        const c=counts[r.k]||(counts[r.k]=[0,0]);
        if(preserve){kept++;c[1]++;continue;}
        bytes+=r.e.bytes;c[0]++;selected.push({...r.e,mode:0o600});
        snapshot.push({src:r.e.path,rel,bytes:r.e.bytes});
    }
    const digest=h.digest('hex');
    if(archiveStamp(archive)!==stamp)fail(72,'ZIP을 확인하는 중 파일이 바뀌었어요.');
    if(action==='plan'&&!work&&process.env.ST_PC_REPLACE_IMPACT==='1')previewReplacementImpact(install,layout);
    const mergeable=layout.rows.length>0;
    if(expectedTarget && digest!==expectedTarget)fail(73,'미리보기 이후 대상이 바뀌었어요. 목록을 다시 확인해 주세요.');
    if(action==='recheck')return;
    // Include per-file allocation and scratch/receipt records, not just ZIP sizes.
    const required=bytes+268435456+selected.length*12288+selected.reduce((n,e)=>n+Buffer.byteLength(e.path)*8,0);
    if(work){
        if(!mergeable)fail(150,'이 ZIP에서 안전하게 합칠 사용자 파일을 찾지 못했어요.');
        if(!destinations.size)fail(151,'넣을 사용자를 선택해 주세요.');
        if(!/^\d+$/.test(free)||BigInt(free)<BigInt(required))fail(29,'새 파일과 복구 기록을 보관할 공간이 부족해요. 필요: '+required+'바이트');
        save(path.join(work,'selected.json'),{archive:z.archive,entries:selected});
        save(path.join(work,'plan.json'),{rootId,snapshot,kept,profiles:Object.fromEntries([...destinations].map(u=>[u,id(info(install,'data/'+u).s)]))});
    } else {
        for(const [k,v] of Object.entries({layout_kind:layout.kind,mergeable:mergeable?1:0,archive_revision:stamp,installation_revision:digest,add_count:selected.length,keep_count:kept,relationship_skipped:relations,add_bytes:bytes,required_bytes:required,avatars_included:includeAvatars?1:0,candidate_avatars:candidateAvatars,excluded_count:z.entries.filter(e=>!e.isDirectory).length-layout.rows.length+omittedAvatars}))console.log(k+'='+v);
        if(/^\d+$/.test(process.env.ST_PC_AVAILABLE_BYTES||''))console.log('available_bytes='+process.env.ST_PC_AVAILABLE_BYTES);
        for(const u of layout.sources)console.log(['profile',b64(u),b64(mapping.get(u)||'')].join('\t'));
        for(const u of targets)console.log('target\t'+b64(u));
        for(const [k,c] of Object.entries(counts))console.log(['kind',k,...c].join('\t'));
        for(const [k,n] of Object.entries(reasons))console.log(['relation',k,n].join('\t'));
        const included=new Set(layout.rows.map(r=>r.e.path)),excluded={};
        if(omittedAvatars)excluded.avatars=omittedAvatars;
        for(const e of z.entries)if(!e.isDirectory&&!included.has(e.path)){
            const k=layout.excluded?.get(e.path)||'other';excluded[k]=(excluded[k]||0)+1;
        }
        for(const [k,n] of Object.entries(excluded))console.log(['excluded',k,n].join('\t'));
    }
}
function permitted(rel,directory) {
    const a=rel.split('/');
    if(!a.every(part)||a[0]!=='data'||!profile(a[1])||!dirs.has(a[2]))return false;
    return directory ? a.length===3 || (a[2]==='chats'&&a.length===4) : !!category(a.slice(2).join('/'));
}
function prepare(install,work,requestId=crypto.randomBytes(16).toString('hex')){
    if(!/^[a-f0-9]{32}$/.test(requestId))fail(153,'합치기 작업 번호를 확인할 수 없어요.');
    const plan=readJson(path.join(work,'plan.json')), nodes=[],folders=new Map();
    if(id(stat(install))!==plan.rootId)fail(73,'설치가 바뀌었어요.');
    fs.mkdirSync(path.join(work,'new-dirs'),{mode:0o700});
    let ready=0;
    for(const f of plan.snapshot){
        if(!permitted(f.rel,false))fail(150,'합칠 수 없는 경로예요.');
        const a=f.rel.split('/');
        for(let n=3;n<a.length;n++){
            const prefix=a.slice(0,n).join('/');
            if(folders.has(prefix))continue;
            const live=info(install,prefix);
            if(live.blocked || (live.s&&!live.s.isDirectory()))fail(73,'파일을 넣을 폴더가 바뀌었어요.');
            if(live.s){folders.set(prefix,null);continue;}
            const src='new-dirs/'+folders.size;
            fs.mkdirSync(path.join(work,src),{mode:0o700});
            const item={dir:true,rel:prefix,src,identity:id(stat(path.join(work,src)))};
            folders.set(prefix,item);nodes.push(item);
        }
    }
    for(const f of plan.snapshot){
        const src='files/'+f.src,p=path.join(work,src);
        const fd=fs.openSync(p,fs.constants.O_RDWR|fs.constants.O_NOFOLLOW);
        try {
            const s=fs.fstatSync(fd,{bigint:true});
            if(!s.isFile()||s.size!==BigInt(f.bytes))fail(20,'압축 해제한 파일을 확인할 수 없어요.');
            fs.fsyncSync(fd);
            nodes.push({dir:false,src,rel:f.rel,identity:ownFile(s)});
            pulse('합치기 전 복구 기록 준비',++ready,plan.snapshot.length,f.rel);
        } finally{fs.closeSync(fd);}
    }
    save(path.join(work,'guard','journal.json'),{format:'st-pc-merge-v1',requestId,rootId:plan.rootId,profiles:plan.profiles,work:path.basename(work),workId:id(stat(work)),nodes,kept:plan.kept});
    if(stat(path.join(work,'guard','journal.json')).size>268435456n)fail(29,'안전하게 복구 기록을 보관하기에는 파일이 너무 많아요. 더 작은 ZIP으로 나누어 주세요.');
    syncDir(path.join(work,'guard'));
}
function journal(install,pending,checkTarget=true){
    if(!stat(pending)?.isDirectory()||path.basename(pending)!=='pc-merge-pending')fail(153,'합치기 복구 기록 위치가 올바르지 않아요.');
    const j=readJson(path.join(pending,'journal.json'));
    if(j.format!=='st-pc-merge-v1'||(checkTarget&&j.rootId!==id(stat(install)))||!/^pc-merge-work-[0-9]+$/.test(j.work)||!Array.isArray(j.nodes)||j.nodes.length>1100000||!j.profiles||typeof j.profiles!=='object')fail(153,'합치기 복구 대상이 바뀌었어요.');
    for(const [u,expected] of Object.entries(j.profiles))if(!profile(u)||(checkTarget&&id(info(install,'data/'+u).s)!==expected))fail(153,'합칠 사용자 폴더가 바뀌었어요.');
    const seen=new Set();
    for(const n of j.nodes){
        if(typeof n?.rel!=='string'||typeof n.dir!=='boolean'||!permitted(n.rel,n.dir)||!Object.hasOwn(j.profiles,n.rel.split('/')[1])||seen.has(n.rel)||typeof n.identity!=='string'||!/^([0-9]+:){2,4}-?[0-9]+$/.test(n.identity))fail(153,'합치기 복구 항목을 확인할 수 없어요.');
        if(typeof n.src!=='string'||!n.src.split('/').every(part)||(n.dir?!/^new-dirs\/[0-9]+$/.test(n.src):!n.src.startsWith('files/')))fail(153,'복구 기록의 원래 경로가 올바르지 않아요.');
        seen.add(n.rel);
    }
    const w=path.join(path.dirname(pending),j.work);
    if(stat(w)&&id(stat(w))!==j.workId)fail(153,'임시 작업 위치가 바뀌었어요.');
    return {...j,w};
}
function publish(src,dst,srcFd,dstFd,directory){
    if(fs.fstatSync(srcFd,{bigint:true}).dev!==fs.fstatSync(dstFd,{bigint:true}).dev)fail(73,'서로 다른 저장소에는 안전하게 합칠 수 없어요.');
    if(!directory){
        // Cheap and atomic when allowed. Some Android builds forbid hard links.
        try {fs.linkSync(src,dst);return;} catch(e){if(!['EPERM','EACCES','ENOTSUP','EOPNOTSUPP','EMLINK'].includes(e.code))throw e;}
    }
    const r=spawnSync('mv',['-Tn','--','/proc/self/fd/3/'+path.basename(src),'/proc/self/fd/4/'+path.basename(dst)],{stdio:['ignore','ignore','pipe',srcFd,dstFd],timeout:10000});
    if(r.error||r.status!==0||stat(src))fail(73,'같은 이름의 파일이나 폴더가 생겨 기존 항목을 유지했어요.');
}
function committed(pending,j){
    const p=path.join(pending,'committed.json');
    if(!stat(p))return false;
    const receipt=readJson(p,4096);
    // v1 journals from older installations have no requestId or rootId in their receipt.
    if(receipt.workId!==j.workId || receipt.requestId!==j.requestId ||
        (j.requestId!==undefined && (!/^[a-f0-9]{32}$/.test(j.requestId)||receipt.rootId!==j.rootId)))fail(153,'완료 기록이 바뀌었어요.');
    return receipt;
}
function verifyCurrentCommit(install,pending,work,requestId){
    // The nonce comes from the current lock owner, not from a recovered folder name.
    // Never infer a successful import from the existence of a marker or a worker exit alone.
    if(!/^[a-f0-9]{32}$/.test(requestId)||path.basename(pending)!=='pc-merge-pending'||
        !stat(pending)?.isDirectory()||!stat(work)?.isDirectory()||!stat(install)?.isDirectory())fail(153,'합치기 완료 대상을 확인할 수 없어요.');
    const j={requestId,workId:id(stat(work)),rootId:id(stat(install))};
    const receipt=committed(pending,j);
    if(!receipt||![receipt.added,receipt.kept].every(n=>Number.isSafeInteger(n)&&n>=0))fail(153,'이번 작업의 완료 기록을 확인할 수 없어요.');
    // Handles worker loss after rename but before syncDir/return. The marker file itself was fsynced before rename.
    syncDir(pending);
    console.log('added='+receipt.added+'\nkept='+receipt.kept);
}
function apply(install,pending){
    const j=journal(install,pending);if(committed(pending,j))fail(153,'이미 완료된 합치기예요.');
    let applied=0;
    for(const n of j.nodes){
        if(id(stat(install))!==j.rootId)fail(73,'설치 폴더가 바뀌었어요.');
        at(j.w,n.src,(src,srcFd)=>{
            if(!src||(n.dir?id:ownFile)(stat(src))!==n.identity)fail(73,'압축 해제 결과가 바뀌었어요.');
            at(install,n.rel,(dst,dstFd)=>{
                if(!dst||stat(dst))fail(73,'대상 파일이 바뀌어 덮어쓰지 않았어요.');
                publish(src,dst,srcFd,dstFd,n.dir);
                if((n.dir?id:ownFile)(stat(dst))!==n.identity)fail(73,'새로 넣은 파일을 확인할 수 없어요.');
                fs.fsyncSync(dstFd);
                pulse('새 파일 넣기',++applied,j.nodes.length,n.rel);
            },j);
        });
    }
    // Atomic commit marker: after this, neither a restart nor an error while
    // cleaning scratch space may roll back a successfully imported file.
    const tmp=path.join(pending,'done.tmp');
    const added=j.nodes.filter(n=>!n.dir).length;
    save(tmp,{workId:j.workId,requestId:j.requestId,rootId:j.rootId,added,kept:j.kept});fs.renameSync(tmp,path.join(pending,'committed.json'));syncDir(pending);
    console.log('added='+added+'\nkept='+j.kept);
}
function recover(install,pending){
    const j=journal(install,pending,false);
    if(committed(pending,j))return;
    const done=path.join(pending,'rolled-back.json');
    if(stat(done)){if(readJson(done,4096).workId!==j.workId)fail(153,'되돌리기 기록이 바뀌었어요.');return;}
    journal(install,pending,true);
    // Never remove an unrelated file even after interrupted rollback, edits or inode replacement.
    for(const n of [...j.nodes].reverse()){
        at(install,n.rel,(dst,fd,state)=>{
            if(state==='blocked')fail(153,'복구 중 대상 폴더가 바뀌어 자동 복구를 멈췄어요.');
            if(!dst)return;
            const s=stat(dst);if(!s)return;
            if((n.dir?id:ownFile)(s)!==n.identity){
                // A competing creator won the no-replace race; if our original
                // staged inode is still present, that winner was never our file.
                const staged=stat(path.join(j.w,n.src));if((n.dir?id:ownFile)(staged)===n.identity)return;
                fail(153,'합친 뒤 변경된 항목이 있어 그대로 보존했어요.');
            }
            try { if(n.dir)fs.rmdirSync(dst);else fs.unlinkSync(dst); }
            catch(e){if(e.code==='ENOTEMPTY')fail(153,'새 파일이 들어 있는 폴더는 지우지 않았어요.'); throw e;}
            fs.fsyncSync(fd);
        },j);
    }
    const tmp=path.join(pending,'rollback-'+crypto.randomUUID()+'.tmp');
    save(tmp,{workId:j.workId});fs.renameSync(tmp,done);syncDir(pending);
}
function verifyRecordedCommit(install,pending){
    if(!committed(pending,journal(install,pending,false)))fail(153,'합치기 완료 기록이 없어요.');
}
function discard(install,pending){
    const j=journal(install,pending,false);
    if(!committed(pending,j)&&readJson(path.join(pending,'rolled-back.json'),4096).workId!==j.workId)
        fail(153,'정리할 수 있는 완료 기록이 없어요.');
    if(stat(j.w))fs.rmSync(j.w,{force:false,recursive:true});
}
try {
    switch(action){
        case 'stamp':console.log(archiveStamp(argv[0]));break;
        case 'plan':case 'recheck':plan(argv);break;
        case 'prepare':prepare(...argv);break;
        case 'apply':apply(...argv);break;
        case 'verify-current-commit':verifyCurrentCommit(...argv);break;
        case 'verify-recorded-commit':verifyRecordedCommit(...argv);break;
        case 'recover':recover(...argv);break;
        case 'discard-work':discard(...argv);break;
        default:fail(64,'알 수 없는 가져오기 명령이에요.');
    }
} catch(e){ console.error(e.message); process.exitCode=e.exitCode || (e.code==='ENOSPC'?29:150); }
NODE
