// The same embedded Termux helper runs unchanged. POSIX-only rename/fsync assertions run on Linux CI.
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), crypto = require('node:crypto'), zlib = require('node:zlib');
const assert = require('node:assert/strict');
const helper = fs.readFileSync(path.join(__dirname,'../app/src/main/assets/share-character.sh'),'utf8')
    .replace(/\r\n/g,'\n').split("<<'CARD_JS'\n")[1].replace(/\nCARD_JS\s*$/,'');
const root = fs.mkdtempSync(path.join(os.tmpdir(),'st-card-fixture-'));
const home = path.join(root,'SillyTavern'), downloads = path.join(root,'Download');
const account = path.join(home,'data','default-user'), dest = path.join(account,'characters');
const source = 'SillyTavern-File-'+crypto.randomUUID()+'.tmp';
if(process.platform==='win32'){
    const key=Object.keys(process.env).find(k=>k.toLowerCase()==='path')||'PATH';
    const gitBin=path.join(process.env.ProgramFiles||'C:/Program Files','Git','usr','bin');
    if(fs.existsSync(path.join(gitBin,'mv.exe')))process.env[key]=gitBin+path.delimiter+process.env[key];
}
const b64 = s => Buffer.from(s).toString('base64'), sha = b => crypto.createHash('sha256').update(b).digest('hex');
const hashName = b64('default-user');
const run = (action,...args) => cp.spawnSync(process.execPath,['-',action,home,downloads,...args],
    {input:helper,encoding:'utf8',windowsHide:true,timeout:10_000});
const crc = b => { let c=~0; for(const n of b) {c^=n;for(let i=0;i<8;i++)c=(c&1)?0xedb88320^(c>>>1):c>>>1;}return (~c)>>>0; };
function chunk(t,b){const v=Buffer.concat([Buffer.from(t,'ascii'),b]);const out=Buffer.alloc(v.length+8);out.writeUInt32BE(b.length);v.copy(out,4);out.writeUInt32BE(crc(v),v.length+4);return out;}
const signature=Buffer.from('89504e470d0a1a0a','hex');
const hdr=Buffer.from('00000001000000010802000000','hex');
const make = (tags=[], extra=[]) => Buffer.concat([signature,chunk('IHDR',hdr),
    ...tags.map(([name,raw])=>chunk('tEXt',Buffer.from(name+'\0'+raw))),...extra,
    chunk('IDAT',zlib.deflateSync(Buffer.alloc(4))),chunk('IEND',Buffer.alloc(0))]);
const legacy = ['chara',b64(JSON.stringify({name:'V1'}))];
const modern = ['ccv3',b64(JSON.stringify({data:{name:'V3'}}))];
const v2 = ['chara',b64(JSON.stringify({data:{name:'V2'}}))];
let checks=0;
function result(r,status){assert.equal(r.status,status,r.stderr+'\n'+r.stdout+'\n'+String(r.error||''));checks++;return r.stdout;}
function imported(buf,name='Alice.png',policy='skip',user=hashName,hash=sha(buf)){
    fs.writeFileSync(path.join(downloads,source),buf);
    return run('import',user,source,b64(name),hash,policy);
}
try {
    fs.mkdirSync(dest,{recursive:true});fs.mkdirSync(downloads);
    for(const name of ['guest','_system','.hidden']) fs.mkdirSync(path.join(home,'data',name));
    const users=result(run('list-users'),0);
    assert(users.includes('user\t'+hashName+'\t1')&&users.includes('user\t'+b64('guest')+'\t0'));
    assert(!users.includes(b64('_system'))&&!users.includes(b64('.hidden')));
    const card=make([legacy,modern]);
    result(imported(card,'Alice.png','skip',b64('../outside')),113);
    result(imported(card,'Alice.png','skip',b64('guest')),113);
    result(imported(Buffer.from('not a png')),110);
    result(imported(make([])),110);
    result(imported(make([['ccv3','abc!'],legacy])),110);
    result(imported(make([['chara',b64('{"data":{}}')]])),110);
    result(imported(make([legacy,legacy])),110);
    const broken=Buffer.from(card);broken[30]^=1;result(imported(broken),110);
    result(imported(card,'Alice.png','skip',hashName,'0'.repeat(64)),110);
    result(imported(Buffer.alloc(20*1024*1024+1)),111);
    fs.writeFileSync(path.join(dest,'Alice.png'),'existing');
    result(imported(card),112);
    assert.equal(fs.readFileSync(path.join(dest,'Alice.png'),'utf8'),'existing');
    const plan=result(run('plan',hashName,b64('Alice.png'),b64('Alice.png'),b64('ㅂ'.repeat(200)+'?.png')),0)
        .trim().split(/\r?\n/).map(l=>l.split('\t'));
    assert.equal(Buffer.from(plan[0][2],'base64').toString(),'Alice (2).png');
    assert.equal(Buffer.from(plan[1][2],'base64').toString(),'Alice (3).png');
    assert(Buffer.from(plan[2][2],'base64').length<=200);
    {
        for(const [bytes,parsed] of [[card,'V3'],[make([legacy]),'V1'],[make([v2]),'V2']]){
            const answer=result(imported(bytes,'Alice.png','rename'),0);
            assert(answer.includes('card_name='+b64(parsed)));
            const filename=Buffer.from(answer.match(/card_file=(.+)/)[1],'base64').toString();
            assert.notEqual(filename,'Alice.png');assert.deepEqual(fs.readFileSync(path.join(dest,filename)),bytes);
        }
        const padding=20*1024*1024-make([legacy]).length-12;
        result(imported(make([legacy],[chunk('tEXt',Buffer.alloc(padding,65))]),'limit.png'),0);
    }
    if(process.platform!=='win32'){
        fs.symlinkSync(dest,path.join(home,'data','guest','characters'));result(imported(card,'A.png','rename',b64('guest')),113);
        fs.unlinkSync(path.join(downloads,source));fs.symlinkSync(path.join(dest,'Alice.png'),path.join(downloads,source));
        assert.notEqual(run('import',hashName,source,b64('A.png'),sha(card),'rename').status,0);
    }
    assert(!fs.readdirSync(dest).some(n=>n.startsWith('.st-launcher-card-')));
    console.log('share-character: '+checks+' helper cases passed'+(process.platform==='win32'?' (POSIX symlink and directory-fsync checks require Linux)':''));
} finally { fs.rmSync(root,{recursive:true,force:true}); }
