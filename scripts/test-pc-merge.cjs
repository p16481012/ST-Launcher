#!/usr/bin/env node
// Run against actual ZIP bytes, the production central-directory validator and
// extractor. Windows-only adapter supplies Linux directory fd/mv semantics; the
// separate Bash suite exercises actual procfs and GNU no-replace operations.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),zlib=require('node:zlib'),{spawnSync}=require('node:child_process');
const assets=path.resolve(__dirname,'../app/src/main/assets'),src=f=>fs.readFileSync(path.join(assets,f),'utf8').replace(/\r\n/g,'\n');
const body=s=>s.split("<<'NODE'\n")[1].split('\nNODE')[0];
const helper=body(src('pc-merge-data.sh')),validate=body(src('manager.sh').split('validate_restore_archive() {')[1]),extract=body(src('archive-progress.sh'));
const root=fs.mkdtempSync(path.join(os.tmpdir(),'st-pc-plan-')),st=path.join(root,'st'),arc=path.join(root,'SillyTavern-Import-test.zip'),ix=path.join(root,'index.json'),work=path.join(root,'pc-merge-work-112'),pending=path.join(root,'pc-merge-pending');
const write=(p,s)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,s);};
const scalar=(s,k)=>s.split('\n').find(x=>x.startsWith(k+'='))?.slice(k.length+1);
let steps=0;
function inline(code,args){
    const r=spawnSync(process.execPath,['--input-type=commonjs','-e',code,'--','-',...args],{encoding:'utf8',timeout:20000});
    assert.equal(r.status,0,r.stderr||r.error?.message);return r.stdout;
}
const crc=b=>{let c=~0;for(const x of b){c^=x;for(let j=0;j<8;j++)c=(c>>>1)^((c&1)?0xedb88320:0);}return (c^-1)>>>0;};
function fixture(rows){
    const chunks=[],entries=[];let off=0;
    for(const [key,value] of rows){
        const fn=Buffer.from(key),plain=Buffer.from(value),data=zlib.deflateRawSync(plain),lh=Buffer.alloc(30),ch=Buffer.alloc(46);
        lh.writeUInt32LE(0x04034b50);lh.writeUInt16LE(20,4);lh.writeUInt16LE(0x800,6);lh.writeUInt16LE(8,8);
        lh.writeUInt32LE(crc(plain),14);lh.writeUInt32LE(data.length,18);lh.writeUInt32LE(plain.length,22);lh.writeUInt16LE(fn.length,26);
        ch.writeUInt32LE(0x02014b50);ch.writeUInt16LE(20,4);lh.copy(ch,6,4,28);ch.writeUInt32LE(off,42);
        chunks.push(lh,fn,data);entries.push(ch,fn);off+=lh.length+fn.length+data.length;
    }
    const cd=Buffer.concat(entries),end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);end.writeUInt16LE(rows.length,8);end.writeUInt16LE(rows.length,10);end.writeUInt32LE(cd.length,12);end.writeUInt32LE(off,16);
    write(arc,Buffer.concat([...chunks,cd,end]));fs.rmSync(ix,{force:true});inline(validate,[arc,ix]);
}
const windows=process.platform==='win32',fds=new Map();let fdSeq=100000;
const translate=p=>{
    if(typeof p!=='string')return p;
    const m=/^[\\/]proc[\\/]self[\\/]fd[\\/](\d+)(?:[\\/](.*))?$/.exec(p);
    return m&&fds.has(+m[1])?path.join(fds.get(+m[1]),m[2]||''):p;
};
const shim=new Proxy(fs,{get(o,k){
    if(k==='openSync')return (p,...args)=>{p=translate(p);if(fs.existsSync(p)&&fs.lstatSync(p).isDirectory()){const fd=fdSeq++;fds.set(fd,p);return fd;}return fs.openSync(p,...args);};
    if(k==='fstatSync')return (fd,...args)=>fds.has(fd)?fs.lstatSync(fds.get(fd),...args):fs.fstatSync(fd,...args);
    if(k==='closeSync')return fd=>{if(!fds.delete(fd))fs.closeSync(fd);};
    if(k==='fsyncSync')return fd=>{if(!fds.has(fd))fs.fsyncSync(fd);};
    if(['lstatSync','linkSync','rmdirSync','unlinkSync','readdirSync','mkdirSync','renameSync'].includes(k))return (...args)=>fs[k](...args.map(translate));
    return o[k];
}});
const mvAdapter=(cmd,args,opts)=>{
    assert.equal(cmd,'mv');assert.deepEqual([...args].slice(0,2),['-Tn','--']);
    const p=path.join(fds.get(opts.stdio[3]),path.basename(args[2])),q=path.join(fds.get(opts.stdio[4]),path.basename(args[3]));
    if(!fs.existsSync(q))fs.renameSync(p,q);return {status:0};
};
function run(args,expected=0){
    const output=[],err=[],p={argv:['node','-',...args],env:{},exitCode:0};
    vm.runInNewContext(helper,{require:n=>windows&&n==='fs'?shim:windows&&n==='child_process'?{spawnSync:mvAdapter}:require(n),process:p,Buffer,TextDecoder,console:{log:s=>output.push(s),error:e=>err.push(e)}});
    assert.equal(p.exitCode,expected,err.join('\n'));steps++;return output.join('\n');
}
function preview(map=''){return run(['plan',st,arc,ix,map,run(['stamp',arc])]);}
const map=o=>Buffer.from(JSON.stringify(o)).toString('base64');
try{
    write(path.join(st,'data/default-user/characters/A.png'),'phone');fs.mkdirSync(path.join(st,'data/work'),{recursive:true});
    fixture([['Outer/data/default-user/characters/A.png','othr!'],['Outer/data/default-user/chats/A/x.jsonl','do not cross-link'],['Outer/data/default-user/characters/B.png','new'],['Outer/data/default-user/chats/B/y.jsonl','chat'],['Outer/data/default-user/OpenAI Settings/P.json','{}'],['Outer/data/default-user/secrets.json','secret'],['Outer/data/default-user/QuickReplies/Q.json','no'],['Outer/data/pc/worlds/W.json','{}']]);
    let report=preview();assert.equal(scalar(report,'keep_count'),'2');assert.equal(scalar(report,'relationship_skipped'),'1');assert.equal(scalar(report,'excluded_count'),'2');
    const both=map({'default-user':'default-user',pc:'work'});report=preview(both);
    const stamp=scalar(report,'archive_revision'),target=scalar(report,'installation_revision');assert.equal(scalar(report,'add_count'),'4');
    run(['plan',st,arc,ix,map({'default-user':'work',pc:'work'}),stamp],151);
    fs.mkdirSync(work);fs.mkdirSync(path.join(work,'files'));fs.mkdirSync(path.join(work,'guard'));
    run(['plan',st,arc,ix,both,stamp,target,work,'10'],29);
    const race=path.join(st,'data/default-user/characters/B.png');write(race,'concurrent');
    run(['plan',st,arc,ix,both,stamp,target,work,'9999999999'],73);assert.equal(fs.readFileSync(race,'utf8'),'concurrent');fs.unlinkSync(race);
    run(['plan',st,arc,ix,both,stamp,target,work,'9999999999']);
    inline(extract,['extract',arc,path.join(work,'files'),path.join(work,'selected.json')]);
    run(['prepare',st,work]);fs.renameSync(path.join(work,'guard'),pending);
    run(['apply',st,pending]);assert.equal(fs.readFileSync(path.join(st,'data/default-user/characters/A.png'),'utf8'),'phone');assert.equal(fs.readFileSync(path.join(st,'data/work/worlds/W.json'),'utf8'),'{}');assert.ok(!fs.existsSync(path.join(st,'data/default-user/chats/A/x.jsonl')));
    run(['recover',st,pending]);assert.ok(fs.existsSync(race),'durable commit must not be undone');
    // Simulate failure after every publish but before durable commit; keep unrelated edits.
    fs.unlinkSync(path.join(pending,'committed.json'));
    run(['recover',st,pending]);assert.ok(!fs.existsSync(race));assert.ok(!fs.existsSync(path.join(st,'data/default-user/chats')));assert.equal(fs.readFileSync(path.join(st,'data/default-user/characters/A.png'),'utf8'),'phone');
    for(const [input,expected] of [
        [[['worlds/L.json','{}']],'user_data'],[[['copy/default-user/characters/D.png','x']],'user_data'],
        [[['copy/config.yaml','{}']],'config_extensions'],[[['PC/public/chats/A/y.jsonl','{}']],'legacy_public'],
        [[['copy/.st-launcher-manifest','format=st-launcher-backup-v1\nitems=config\n'],['copy/data/default-user/worlds/unselected.json','{}']],'launcher_backup'],
        [[['other/.st-launcher-manifest','format=st-launcher-backup-v1\nitems=user_data\n'],['other/data/default-user/characters/Native.png','x']],'launcher_backup'],
        [[['something.bin','x']],'unknown'],
    ]){fixture(input);const p=preview();assert.equal(scalar(p,'layout_kind'),expected);}
    fixture([['characters/NoPhone.png','x']]);const fresh=run(['plan',path.join(root,'absent-install'),arc,ix,'',run(['stamp',arc])]);assert.equal(scalar(fresh,'add_count'),'0');assert.ok(!fresh.includes('target\t'));assert.equal(scalar(fresh,'layout_kind'),'user_data');
    run(['plan',st,arc,ix,'',stamp],72);
    fixture([['data/default-user/.st-launcher-manifest','items=config'],['data/default-user/worlds/A.json','{}']]);run(['plan',st,arc,ix,'',run(['stamp',arc])],23);
    console.log('PC ZIP: '+steps+' worker invocations, actual validator/extractor and transaction scenarios passed'+(windows?' (Windows procfs adapter)':''));
}finally{fs.rmSync(root,{force:true,recursive:true});}
