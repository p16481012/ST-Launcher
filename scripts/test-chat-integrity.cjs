'use strict';
const assert = require('node:assert/strict'), fs=require('node:fs'), path=require('node:path'), os=require('node:os');
const {spawnSync}=require('node:child_process');
const shell=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/chat-integrity.sh'),'utf8').replace(/\r\n/g,'\n');
const program=shell.split("<<'NODE'\n")[1].split('\nNODE\n')[0].replace(/\nNODE$/,'');
const box=fs.mkdtempSync(path.join(os.tmpdir(),'st-chat-test-')), root=path.join(box,'SillyTavern'),owner=path.join(box,'owner');
const b=s=>Buffer.from(s).toString('base64'),u=s=>Buffer.from(s,'base64').toString(), field=(s,k)=>s.split('\n').find(x=>x.startsWith(k+'='))?.slice(k.length+1);
function put(rel,bytes){const f=path.join(root,rel);fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,bytes);return rel;}
function run(args,status=0,env={}){const v=spawnSync(process.execPath,['-',...args.slice(0,1),root,owner,...args.slice(1)],{input:program,encoding:'utf8',env:{...process.env,...env},timeout:35000});assert.equal(v.status,status,v.stderr+' '+v.error);return v.stdout.trimEnd();}
function scan(cursor=''){const s=run(['scan',cursor]);return {s, rows:new Map(s.split('\n').filter(x=>x.startsWith('chat\t')).map(x=>{const c=x.split('\t');return[u(c[1]),c];}))};}
function repair(row,mode,status=0){return run(['repair',row[1],row[6],mode,row[7],row[10]],status);}
const head=(id)=>JSON.stringify({chat_metadata:{integrity:id?'00000000-0000-4000-8000-'+Buffer.from(id).toString('hex').padEnd(12,'0').slice(0,12):''},user_name:'사용자',character_name:'인물'});
const m=(text,seq)=>JSON.stringify({name:'사용자',is_user:true,mes:text,send_date:seq}), a=m('one',1),z=m('two',2), tail='{"mes":"broken',base='data/alice/chats/A/', bak='data/alice/backups/';
try{
fs.mkdirSync(owner,{recursive:true});
const good=put(base+'good.jsonl',[head('g'),a,z].join('\n'));
const target=put(base+'tail.jsonl',[head('a'),a,tail].join('\n')), backup=put(bak+'chat_good.jsonl',[head('a'),a,z].join('\n'));
put(bak+'chat_short.jsonl',[head('a'),a].join('\n'));put(bak+'chat_broken.jsonl',[head('a'),a,z,tail].join('\n'));
put('data/bob/backups/chat_other.jsonl',[head('a'),a,z,m('secret',3)].join('\n'));
const mid=put(base+'middle.jsonl',[head('b'),m('keep',8),'null',m('also keep',9)].join('\n'));
const header=put(base+'header.jsonl',['broken',m('h',10)].join('\n'));
const legacy=put('data/alice/group chats/legacy.jsonl',[a,z].join('\n'));
const uncertain=put(base+'legacy.jsonl',[head(''),a,tail].join('\n'));
const headerless=put(base+'unknown.jsonl',[a,z].join('\n'));
const missing=put(base+'no-backup.jsonl',[head('z'),m('unique',90),tail].join('\n'));
put('data/_private/chats/A/x.jsonl',tail);
let link=false;try{fs.symlinkSync(path.join(root,target),path.join(root,base+'s.jsonl'));link=true;}catch(e){if(process.platform!=='win32'||e.code!=='EPERM')throw e;}
let q=scan(), r=q.rows.get(target);assert.equal(r[2],'bad_tail');assert.equal(r[11],'verified');assert.equal(u(r[7]),backup);
assert.ok(!q.rows.has(good)&&!q.rows.has(legacy));assert.equal(field(q.s,'ok_count'),'2');assert.equal(q.rows.get(headerless)[2],'unknown');if(link)assert.ok(!q.rows.has(base+'s.jsonl'));
assert.equal(q.rows.get(uncertain)[11],'candidate');assert.equal(q.rows.get(header)[2],'bad_header');assert.equal(q.rows.get(mid)[2],'bad_middle');assert.equal(q.rows.get(missing)[11],'none');
repair(q.rows.get(uncertain),'restore-backup',102);repair(q.rows.get(mid),'trim-tail',103);
const initial=fs.readFileSync(path.join(root,target));let result=repair(r,'restore-backup'),id=field(result,'trash_id');
assert.deepEqual(fs.readFileSync(path.join(root,target)),fs.readFileSync(path.join(root,backup)));assert.match(scan().s,new RegExp('chat_undo\\t'+id+'\\t'));
run(['undo',id]);assert.deepEqual(fs.readFileSync(path.join(root,target)),initial);
// Fault-inject only the fixture's publish rename; the actual cleanup and receipt path still execute.
r=scan().rows.get(target);
const interrupted=spawnSync(process.execPath,['-','repair',root,owner,r[1],r[6],'restore-backup',r[7],r[10]],{
 input:program.replace("fs.renameSync(tmp,out);committed=true;", "throw new Error('simulated disk write failure');"), encoding:'utf8'});
assert.equal(interrupted.status,103);assert.deepEqual(fs.readFileSync(path.join(root,target)),initial);
assert.ok(fs.readdirSync(path.join(owner,'file-trash')).length===0);
q=scan();const old=fs.readFileSync(path.join(root,header));result=repair(q.rows.get(header),'rescue-copy');
let out=path.join(root,u(field(result,'output')));assert.deepEqual(fs.readFileSync(path.join(root,header)),old);
assert.equal(JSON.parse(fs.readFileSync(out,'utf8').split('\n')[0]).user_name,'사용자');
run(['undo',field(result,'trash_id')]);assert.ok(!fs.existsSync(out));
result=repair(q.rows.get(mid),'rescue-copy');out=path.join(root,u(field(result,'output')));
assert.equal(fs.readFileSync(out,'utf8').split('\n').length,3);fs.appendFileSync(out,'\n'+z);run(['undo',field(result,'trash_id')],101);assert.ok(fs.existsSync(out));
result=repair(q.rows.get(missing),'trim-tail');assert.equal(fs.readFileSync(path.join(root,missing),'utf8'),[head('z'),m('unique',90)].join('\n'));
run(['undo',field(result,'trash_id')]);assert.ok(fs.readFileSync(path.join(root,missing),'utf8').endsWith(tail));
r=scan().rows.get(target);fs.appendFileSync(path.join(root,backup),'\n');repair(r,'restore-backup',102);
r=scan().rows.get(target);fs.appendFileSync(path.join(root,target),'z');repair(r,'restore-backup',101);
const wrong=Array.from(scan().rows.get(target));wrong[7]=b('data/bob/backups/chat_other.jsonl');repair(wrong,'restore-backup',102);
const refuse=path.join(box,'cancel');fs.writeFileSync(refuse,'');run(['scan',''],130,{ST_CANCEL_REQUEST_FILE:refuse});
const big=put(base+'big.jsonl','');fs.truncateSync(path.join(root,big),101*1024*1024);assert.equal(scan().rows.get(big)[2],'too_large');
const continued=scan(b(base+'middle.jsonl'));assert.ok(!continued.rows.has(mid)&&continued.rows.has(target));
if(process.platform!=='win32'){
 const many=path.join(root,'data/z/chats/all');fs.mkdirSync(many,{recursive:true});
 for(let i=0;i<5001;i++)fs.writeFileSync(path.join(many,String(i).padStart(5,'0')+'.jsonl'),head('x'));
 const page=scan();assert.equal(field(page.s,'scanned'),'5000');const rest=scan(field(page.s,'next_cursor'));assert.ok(+field(rest.s,'scanned')>0);assert.equal(field(rest.s,'next_cursor'),'');
}
console.log('chat recovery: scanning, safe matching, repairs, persistent revision-bound undo, size limits, cancel and pagination passed');
}finally{fs.rmSync(box,{recursive:true,force:true});}
