#!/data/data/com.termux/files/usr/bin/bash
# Only fixed keys and scalar values cross the launcher boundary; never emit YAML.
exec node - "$@" <<'NODE'
'use strict';
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const {isDeepStrictEqual} = require('node:util');
const [action, install, launcher, expected, payload] = process.argv.slice(2);
const keys = ['thumbnails.enabled','performance.lazyLoadCharacters','backups.chat.maxTotalBackups','extensions.autoUpdate','enableServerPlugins','enableCorsProxy'];
const limit = 1024 * 1024, handles = [], flags = fs.constants;
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const fail = code => { throw Object.assign(new Error(), {status:code}); };
const errors = {120:'SERVER_CONFIG_INVALID',121:'SERVER_CONFIG_CHANGED',122:'SERVER_CONFIG_KEY_UNSUPPORTED',123:'SERVER_CONFIG_SAVE_FAILED',130:'OPERATION_CANCELLED'};
function directory(p, create = false) {
    if (create) { try { fs.mkdirSync(p,{mode:0o700}); } catch (e) { if (e.code !== 'EEXIST') throw e; } }
    if (!fs.lstatSync(p).isDirectory()) fail(123);
    const fd = fs.openSync(p, flags.O_RDONLY | flags.O_DIRECTORY | flags.O_NOFOLLOW);
    handles.push(fd);
    return {fd, at:process.platform === 'linux' || process.platform === 'android' ? '/proc/self/fd/' + fd : p};
}
function bounded(p) {
    const fd = fs.openSync(p, flags.O_RDONLY | flags.O_NOFOLLOW | flags.O_NONBLOCK);
    try {
        const stat = fs.fstatSync(fd,{bigint:true});
        if (!stat.isFile() || stat.size > BigInt(limit)) fail(120);
        const bytes = Buffer.alloc(Number(stat.size));
        let n = 0;
        while (n < bytes.length) { const r = fs.readSync(fd,bytes,n,bytes.length-n,null); if (!r) fail(121); n += r; }
        if (fs.fstatSync(fd,{bigint:true}).ctimeNs !== stat.ctimeNs) fail(121);
        return {bytes,stat};
    } finally { fs.closeSync(fd); }
}
function exclusive(p, bytes) {
    const fd = fs.openSync(p,'wx',0o600);
    try { fs.writeFileSync(fd,bytes); fs.fsyncSync(fd); }
    catch (e) { fs.unlinkSync(p); throw e; } finally { fs.closeSync(fd); }
}
function cancel() { if ([process.env.ST_CANCEL_REQUEST_FILE,process.env.ST_CANCEL_REQUEST_ID_FILE].some(p=>p && fs.existsSync(p))) fail(130); }
function syncDir(d) { if (process.platform !== 'win32') fs.fsyncSync(d.fd); }
function settingsDir() { return directory(path.join(directory(path.join(directory(launcher).at,'backups'),true).at,'network-settings'),true); }
function saveCopy(d, bytes) {
    const date = new Date(), pad = v=>String(v).padStart(2,'0');
    const stem = 'config-' + date.getFullYear()+pad(date.getMonth()+1)+pad(date.getDate())+'-'+pad(date.getHours())+pad(date.getMinutes())+pad(date.getSeconds());
    for (let i=0; i<5; i++) {
        const name = stem + (i ? '-'+crypto.randomBytes(4).toString('hex') : '')+'.yaml';
        try { exclusive(path.join(d.at,name),bytes); syncDir(d); return name; } catch(e) { if(e.code!=='EEXIST') throw e; }
    } fail(123);
}
try {
    const root = directory(install);
    if (action === 'usage') {
        const data = directory(path.join(root.at,'data'));
        let count=0, bytes=0, visited=0, complete=true; const end=Date.now()+10000;
        let users=0;
        outer: for(const user of fs.readdirSync(data.at,{withFileTypes:true})) {
            if (!user.isDirectory() || user.name.startsWith('_')) continue;
            if (++users>100 || Date.now()>end) { complete=false; break; }
            const anchor=handles.length, priorCount=count, priorBytes=bytes;
            try {
            const home=directory(path.join(data.at,user.name)); let backups;
            try { backups=directory(path.join(home.at,'backups')); } catch(e) { if (e.code==='ENOENT') continue; throw e; }
            const entries=fs.opendirSync(backups.at);
            try { for(let item; (item=entries.readSync());) {
                if(++visited>100000 || Date.now()>end) {complete=false; break outer;}
                if(item.isFile() && /^chat_.*\.jsonl$/.test(item.name)){
                    const s=fs.lstatSync(path.join(backups.at,item.name)); if(s.isFile()) {count++;bytes+=s.size;}
                }
            }} finally {entries.closeSync();}
            if (!/[\p{C}]/u.test(user.name)) console.log(['user',Buffer.from(user.name).toString('base64'),count-priorCount,bytes-priorBytes].join('\t'));
            } finally {while(handles.length>anchor) fs.closeSync(handles.pop());}
        }
        console.log('count='+count+'\nbytes='+bytes+'\ncomplete='+Number(complete));
    } else {
        const YAML = require('node:module').createRequire(path.join(install,'package.json'))('yaml');
        const parse = bytes => { try {
            const doc=YAML.parseDocument(new TextDecoder('utf-8',{fatal:true}).decode(bytes),{logLevel:'silent'});
            if(doc.errors.length || doc.warnings.length || !YAML.isMap(doc.contents)) fail(120);
            return {doc, obj:doc.toJS({maxAliasCount:50})};
        } catch(e) {fail(120);} };
        const livePath=path.join(root.at,'config.yaml'), original=bounded(livePath), rev=hash(original.bytes);
        const at=(o,k)=>k.split('.').reduce((v,p)=>v && typeof v==='object' && Object.hasOwn(v,p)?v[p]:undefined,o);
        const usable=(k,v)=> k===keys[2]?Number.isSafeInteger(v):typeof v==='boolean';
        let output;
        if (action==='restore') {
            if(!/^config-\d{8}-\d{6}(?:-[a-f0-9]{8})?\.yaml$/.test(expected) || !/^[a-f0-9]{64}$/.test(payload)) fail(122);
            output=bounded(path.join(settingsDir().at,expected)).bytes;
            if(hash(output)!==payload) fail(121);
            parse(output);
        } else {
            const live=parse(original.bytes), defaults=parse(bounded(path.join(directory(path.join(root.at,'default')).at,'config.yaml')).bytes);
            if (action==='read') {
                const version=JSON.parse(bounded(path.join(root.at,'package.json')).bytes).version;
                console.log('config_revision='+rev+'\nst_version='+(/^[0-9][a-zA-Z0-9.+-]{0,63}$/.test(version)?version:''));
                for(const key of keys){
                    const def=at(defaults.obj,key), value=at(live.obj,key);
                    if(!usable(key,def) || (value!==undefined && !usable(key,value))) console.log(['key',key,0,'null','null',0].join('\t'));
                    else console.log(['key',key,1,JSON.stringify(value??def),JSON.stringify(def),Number(value!==undefined)].join('\t'));
                }
            } else {
                if (action!=='write' || !/^[a-f0-9]{64}$/.test(expected) || expected!==rev) fail(121);
                if (!payload || payload.length>512 || !/^[A-Za-z0-9+/]+={0,2}$/.test(payload)) fail(122);
                const decoded=Buffer.from(payload,'base64'); if(decoded.toString('base64')!==payload) fail(122);
                const updates=new Set(), intended=structuredClone(live.obj);
                for(const entry of decoded.toString('utf8').split(',')){
                    const [key,value,...extra]=entry.split('=');
                    if(extra.length || !keys.includes(key) || updates.has(key) || !usable(key,at(defaults.obj,key)) ||
                        (at(live.obj,key)!==undefined && !usable(key,at(live.obj,key))) ||
                        !(key===keys[2]?['-1','50','100','200']:['true','false']).includes(value)) fail(122);
                    updates.add(key);
                    const parts=key.split('.'), scalar=JSON.parse(value);
                    let parent=intended; for(const p of parts.slice(0,-1)) { if(!Object.hasOwn(parent,p)) parent[p]={}; parent=parent[p]; if(!parent || typeof parent!=='object' || Array.isArray(parent)) fail(122); }
                    parent[parts.at(-1)]=scalar;
                    live.doc.setIn(parts,scalar);
                }
                output=Buffer.from(String(live.doc));
                if(output.length>limit || !isDeepStrictEqual(parse(output).obj,intended)) fail(123);
            }
        }
        if(output) {
            cancel(); const backup=saveCopy(settingsDir(),original.bytes), tmp=path.join(root.at,'.config-server-'+crypto.randomUUID()+'.tmp');
            exclusive(tmp,output);
            try {
                cancel(); const now=bounded(livePath);
                if(now.stat.dev!==original.stat.dev || now.stat.ino!==original.stat.ino || now.stat.ctimeNs!==original.stat.ctimeNs || hash(now.bytes)!==rev) fail(121);
                fs.renameSync(tmp,livePath); syncDir(root);
            } finally {try {fs.unlinkSync(tmp);} catch(e){if(e.code!=='ENOENT') throw e;}}
            console.log('saved=1\nbackup='+backup);
        }
    }
} catch(e) {
    const code=e.status || (action==='read'?120:123);
    console.error('error_code='+errors[code]); process.exitCode=code;
} finally {for(const fd of handles.reverse()) fs.closeSync(fd);}
NODE
