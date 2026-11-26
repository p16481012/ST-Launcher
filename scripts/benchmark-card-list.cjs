'use strict';
// Measures the production Termux-side listing, including one Node process per page.
// Excludes Android IPC, UI rendering and thumbnail loading; no user profile is read.
const fs=require('node:fs'), path=require('node:path'), os=require('node:os');
const {spawnSync}=require('node:child_process'), zlib=require('node:zlib'), assert=require('node:assert/strict');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'st-card-list-bench-'));
const st=path.join(root,'install'), owner=path.join(root,'launcher'), downloads=path.join(root,'downloads');
const user='bench-user', profile=path.join(st,'data',user), b64=s=>Buffer.from(s).toString('base64');
const asset=fs.readFileSync(path.resolve(__dirname,'../app/src/main/assets/library-data.sh'),'utf8');
const code=asset.split("<<'LIBRARY_JS'")[1].split(/\r?\nLIBRARY_JS/)[0];
function crc(b){let n=~0;for(const v of b){n^=v;for(let i=0;i<8;i++)n=n&1?0xedb88320^(n>>>1):n>>>1;}return (~n)>>>0;}
function chunk(k,b){const d=Buffer.concat([Buffer.from(k),Buffer.from(b)]),p=Buffer.alloc(d.length+8);
    p.writeUInt32BE(d.length-4);d.copy(p,4);p.writeUInt32BE(crc(d),d.length+4);return p;}
const image=[Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',Buffer.from('00000001000000010802000000','hex')),chunk('IDAT',zlib.deflateSync(Buffer.from([0,2,3,4])))];
const N=2048;
function measure(phase){
    const seen=new Set();let cursor='',pages=0,first=0;const t=performance.now();
    do{
        const r=spawnSync(process.execPath,['-','library-list',st,owner,downloads,b64(user),'cards',cursor],{input:code,encoding:'utf8',timeout:45000});
        assert.equal(r.status,0,r.stderr||r.error?.message);
        const lines=r.stdout.trimEnd().split(/\r?\n/);const next=lines.pop();
        assert.match(next,/^next_cursor=/);cursor=next.slice(12);
        for(const row of lines){const fields=row.split('\t');assert.equal(fields[0],'item');assert(!seen.has(fields[1]));seen.add(fields[1]);}
        if(++pages===1)first=Math.round(performance.now()-t);assert(pages<100);
    }while(cursor);
    assert.equal(seen.size,N);
    console.log(JSON.stringify({fixture:'card-list',phase,cards:N,soloChats:N/8,groups:32,pages,firstPageMs:first,allPagesMs:Math.round(performance.now()-t),platform:process.platform,node:process.version}));
}
try {
    for(const d of [owner,downloads,...['characters','chats','groups'].map(x=>path.join(profile,x))])fs.mkdirSync(d,{recursive:true});
    for(let i=0;i<N;i++){
        const name='Card-'+String(i).padStart(4,'0');
        const meta=chunk('tEXt','ccv3\0'+b64(JSON.stringify({spec:'chara_card_v3',data:{name}})));
        fs.writeFileSync(path.join(profile,'characters',name+'.png'),Buffer.concat([...image,meta,chunk('IEND','')]));
        if(!(i%8)){const d=path.join(profile,'chats',name);fs.mkdirSync(d);fs.writeFileSync(path.join(d,'a.jsonl'),'{"chat_metadata":{}}\n{"mes":"test"}\n');}
        if(i<32)fs.writeFileSync(path.join(profile,'groups',i+'.json'),JSON.stringify({members:[name+'.png'],disabled_members:[]}));
    }
    measure('empty-launcher-cache');measure('populated-launcher-cache');
}finally{
    assert.equal(path.dirname(root),os.tmpdir());assert(path.basename(root).startsWith('st-card-list-bench-'));fs.rmSync(root,{recursive:true,force:true});
}
