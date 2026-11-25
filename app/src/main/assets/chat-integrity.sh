#!/data/data/com.termux/files/usr/bin/bash
# Bounded read-only inspection or consented mutation under manager's shared lock.
exec node - "$@" <<'NODE'
'use strict';
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const [action,rootArg,ownerArg,...args] = process.argv.slice(2);
const C=fs.constants, MAX=100*1024*1024, MAX_LINE=8*1024*1024, MAX_RECORDS=300000;
const hash = v=>crypto.createHash('sha256').update(v).digest('hex');
const b64=s=>Buffer.from(s).toString('base64');
function fail(code,msg){throw Object.assign(new Error(msg),{status:code});}
function decode(s){const b=Buffer.from(s||'','base64'); if(b.length>4096||b.toString('base64')!==s)fail(100,'올바르지 않은 파일 경로예요.');return new TextDecoder('utf-8',{fatal:true}).decode(b);}
function cancel(){if([process.env.ST_CANCEL_REQUEST_FILE,process.env.ST_CANCEL_REQUEST_ID_FILE].some(p=>p&&fs.existsSync(p)))fail(130,'검사를 중단했어요.');}
const root=fs.realpathSync(rootArg);
const idRE=/^\d{1,16}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
function dir(p){return fs.lstatSync(p).isDirectory();}
function file(p){return fs.lstatSync(p).isFile();}
function parts(s){const a=s.split('/');if(a.some(v=>!v||v==='.'||v==='..'||/[\\\0-\x1f]/.test(v)))fail(100,'올바르지 않은 파일 경로예요.');return a;}
function chatPath(r,isBackup=false){
 const a=parts(r);
 if(a[0]!=='data'||a[1].startsWith('_')||!a.at(-1).endsWith('.jsonl')||!(isBackup?
   a.length===4&&a[2]==='backups'&&a[3].startsWith('chat_'):
   (a.length===5&&a[2]==='chats')||(a.length===4&&a[2]==='group chats')))fail(100,'채팅 파일 경로가 아니에요.');
 let p=root; for(const e of a.slice(0,-1)){p=path.join(p,e);if(!dir(p))fail(101,'폴더가 바뀌었어요. 다시 검사해 주세요.');}
 return path.join(p,a.at(-1));
}
function rev(s,r){return hash([s.dev,s.ino,s.size,s.mtimeNs,r].join(':'));}
function revision(p,r){return rev(fs.lstatSync(p,{bigint:true}),r);}
function parseFile(r,backup=false,limit=Infinity,onRecord=null){
 const p=chatPath(r,backup);
 const fd=fs.openSync(p,C.O_RDONLY|C.O_NOFOLLOW|C.O_NONBLOCK);
 try{
 const st=fs.fstatSync(fd,{bigint:true}); if(!st.isFile())fail(101,'파일이 바뀌었어요.');
 const revision=rev(st,r), base={r,revision,mtime:Number(st.mtimeMs),messages:0,lines:0,bad:0,digests:[],headerDigest:'',slug:'',first:'',firstUser:'',kind:'ok'};
 if(st.size>MAX)return {...base,kind:'too_large'};
 let badHeader=false,unsupported=false,badIndices=[],lineCount=0,lastIncomplete=false;
 const utf8=new TextDecoder('utf-8',{fatal:true}), buf=Buffer.alloc(65536); let pieces=[],length=0;
 function line(bytes,isFinal){
  if(bytes.every(v=>v===32||v===9||v===13))return;
  const n=lineCount++; if(lineCount>MAX_RECORDS)fail(104,'파일의 줄 수가 검사 한도를 넘었어요.');
  let obj,raw,parseError;
  try{raw=utf8.decode(bytes);obj=JSON.parse(raw);}catch(e){parseError=e;}
  const object=obj&&typeof obj==='object'&&!Array.isArray(obj), message=object&&typeof obj.mes==='string';
  if(n===0){
   if(object&&!('mes' in obj)&&(obj.chat_metadata||'user_name' in obj||'character_name' in obj||'create_date' in obj)){
    const uuid=obj.chat_metadata?.integrity;
    base.headerDigest=hash(JSON.stringify(obj));
    base.slug=typeof uuid==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(uuid)?uuid.toLowerCase():''; if(onRecord)onRecord(raw,false);return;
   }
   if(message&&r.includes('/group chats/')){ /* Legacy headerless group. */ }
   else if(message)unsupported=true;
   else badHeader=true;
  }
  if(message){base.messages++;base.digests.push(hash(JSON.stringify(obj)));
   if(!base.firstUser&&obj.is_user===true&&typeof obj.name==='string')base.firstUser=obj.name;
   if(base.messages===1&&obj.send_date!=null)base.first=hash(JSON.stringify([obj.send_date,obj.mes.slice(0,200)]));
   if(onRecord)onRecord(raw,true);
  }else {
   badIndices.push(n);
   // Only demonstrable JSON EOF damage: an invalid token, primitive or complete malformed line is NOT a truncated tail.
   lastIncomplete=!!(isFinal&&parseError&&typeof raw==='string'&&raw.trimStart().startsWith('{')&&
       (/unexpected end|unterminated string/i.test(parseError.message)||/position (\d+)/i.exec(parseError.message)?.[1]===String(raw.length)));
  }
 }
 function part(v){if(!v.length)return;length+=v.length;if(length>MAX_LINE)fail(104,'한 줄의 크기가 검사 한도를 넘었어요.');pieces.push(Buffer.from(v));}
 function flush(final){const b=Buffer.concat(pieces,length);pieces=[];length=0;line(b,final);}
 try{for(;;){cancel();if(Date.now()>limit)fail(105,'일부만 검사했어요.');const n=fs.readSync(fd,buf,0,buf.length,null);if(!n)break;
   let start=0;for(let i=0;i<n;i++)if(buf[i]===10){part(buf.subarray(start,i));flush(false);start=i+1;}part(buf.subarray(start,n));}if(length)flush(true);}
 catch(e){if(e.status===104)return {...base,kind:'too_large'};throw e;}
 if(rev(fs.fstatSync(fd,{bigint:true}),r)!==revision||revision!==rev(fs.lstatSync(p,{bigint:true}),r))fail(101,'검사하는 동안 파일이 바뀌었어요.');
 base.bad=badIndices.length;base.lines=lineCount;
 base.kind=badHeader||!lineCount?'bad_header':unsupported?'unknown':!base.bad?'ok':
  base.bad===1&&badIndices[0]===lineCount-1&&lastIncomplete?'bad_tail':'bad_middle';
 return base;
 }finally{fs.closeSync(fd);}
}
function entries(p){const key=e=>Buffer.from(e.name+(e.isDirectory()?'/':''));return fs.readdirSync(p,{withFileTypes:true}).filter(e=>!/[\0-\x1f\\]/.test(e.name)).sort((a,b)=>Buffer.compare(key(a),key(b)));}
function* files(cursor){
 const data=path.join(root,'data'); if(!dir(data))fail(100,'사용자 데이터 폴더를 확인해 주세요.');
 function before(prefix){return cursor&&Buffer.compare(Buffer.from(prefix+'/'),Buffer.from(cursor))<0&&!cursor.startsWith(prefix+'/');}
 for(const u of entries(data)){if(!u.isDirectory()||u.name.startsWith('_'))continue;
 const d='data/'+u.name;
 if(before(d))continue;
 for(const k of ['chats','group chats']){const sub=d+'/'+k; if(before(sub)||!fs.existsSync(path.join(root,sub))||!dir(path.join(root,sub)))continue;
 const dirs=k==='chats'?entries(path.join(root,sub)).filter(x=>x.isDirectory()).map(x=>sub+'/'+x.name):[sub];
 for(const cd of dirs){if(before(cd))continue;for(const f of entries(path.join(root,cd))){const r=cd+'/'+f.name;
 if(f.isFile()&&r.endsWith('.jsonl')&&(!cursor||Buffer.compare(Buffer.from(r),Buffer.from(cursor))>0))yield r;}}
 }}
}
function isMatch(a,b){return a.r.split('/')[1]===b.r.split('/')[1]&&b.kind==='ok'&&b.messages>a.messages;}
function strength(a,b){
 if(!isMatch(a,b))return '';
 if(a.slug&&b.slug&&a.slug!==b.slug)return '';
 // Refuse to roll back any surviving edit. Prefix for tail, ordered subsequence for middle.
 // Restoring an older header can also roll back the user's chat notes, metadata or extension state.
 if(a.slug&&a.slug===b.slug&&a.headerDigest===b.headerDigest&&a.digests.length){
 let i=0;for(const h of b.digests){if(h===a.digests[i])i++;else if(a.kind==='bad_tail'&&i<a.digests.length)break;}
 if(i===a.digests.length)return 'verified';
 }
 return a.first&&a.first===b.first?'candidate':'';
}
function candidate(a,deadline){
 const d='data/'+a.r.split('/')[1]+'/backups';if(!fs.existsSync(path.join(root,d))||!dir(path.join(root,d)))return {};
 let best=null,typ='',limited=false;
 for(const e of entries(path.join(root,d))){if(!e.isFile()||!/^chat_.*\.jsonl$/.test(e.name))continue;
 if(Date.now()>deadline){limited=true;break;}
 try{const b=parseFile(d+'/'+e.name,true,deadline);const t=strength(a,b);
 if(t&&(!best||(t==='verified'&&typ!=='verified')||(t===typ&&b.mtime>best.mtime))){best={r:b.r,revision:b.revision,mtime:b.mtime,messages:b.messages};typ=t;}}
 catch(e){if(e.status===130)throw e;limited=true;if(e.status===105)break;}
 }
 return {best,typ,limited};
}
function scan(){
 const cursor=args[0]?decode(args[0]):'';if(cursor)chatPath(cursor);
 const deadline=Date.now()+20000;let n=0,ok=0,unread=0,prev=cursor,next='',rows=[],bytes=0;
 for(const r of files(cursor)){
 cancel();if(n>=5000||(n&&Date.now()>deadline)||bytes>14000){next=prev;break;}
 let a;try{a=parseFile(r,false,deadline);}catch(e){if(e.status===130)throw e;if(e.status===105&&n){next=prev;break;}unread++;prev=r;n++;continue;}
 prev=r;n++;if(a.kind==='ok'){ok++;continue;}
 const fit=['bad_tail','bad_header','bad_middle'].includes(a.kind)?candidate(a,Math.min(Date.now()+3000,deadline)):{};
 const b=fit.best;
 if(fit.limited)unread++;
 const row=['chat',b64(r),a.kind,a.messages,a.bad,a.mtime,a.revision,b?b64(b.r):'',b?.messages||0,b?.mtime||0,b?.revision||'',fit.typ||'none'].join('\t');
 if(row.length>12000){unread++;continue;}rows.push(row);bytes+=row.length+1;
 }
 if(!cursor)rows.push(...undoReceipts());
 console.log([...rows,'scanned='+n,'ok_count='+ok,'unread='+unread,'next_cursor='+b64(next)].join('\n'));
}
function undoReceipts(){
 const trash=path.join(fs.realpathSync(ownerArg),'file-trash'),v=[];
 if(!fs.existsSync(trash)||!dir(trash))return v;
 for(const d of entries(trash).reverse().filter(x=>x.isDirectory()&&idRE.test(x.name)).slice(0,100)){
  cancel();try{const p=path.join(trash,d.name,'metadata.json');if(!file(p)||fs.statSync(p).size>8192)continue;
  const m=JSON.parse(fs.readFileSync(p,'utf8'));if(m.root!==root||!m.chat||!m.chat.originalRevision)continue;
  const out=chatPath(m.chat.outRel);
  if(file(out)&&revision(out,m.chat.outRel)===m.chat.newRevision){
   const label=m.relative.split('/').at(-1);v.push(['chat_undo',d.name,b64(label)].join('\t'));
  }if(v.length>=8)break;
  }catch{/* Unrelated trash, missing or edited output; never override it. */}
 }return v;
}
function safeSub(parent,name){const p=path.join(parent,name);try{fs.mkdirSync(p,{mode:0o700});}catch(e){if(e.code!=='EEXIST')throw e;}if(!dir(p))fail(103,'안전한 보관 폴더를 열 수 없어요.');return p;}
function syncFile(p){
 // Windows does not expose directory handles through openSync. Termux/Linux always fsync the directory.
 if(process.platform==='win32'&&dir(p))return;
 const fd=fs.openSync(p,(process.platform==='win32'?C.O_RDWR:C.O_RDONLY)|C.O_NOFOLLOW);try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}
}
function copyStable(src,dest,rel,expected){
 const input=fs.openSync(src,C.O_RDONLY|C.O_NONBLOCK|C.O_NOFOLLOW);try{
 const st=fs.fstatSync(input,{bigint:true});if(!st.isFile()||st.size>MAX||rev(st,rel)!==expected)fail(101,'파일이 바뀌었어요. 다시 검사해 주세요.');
 const out=fs.openSync(dest,'wx',0o600);try{const buf=Buffer.alloc(65536);for(;;){cancel();const n=fs.readSync(input,buf,0,buf.length,null);if(!n)break;fs.writeFileSync(out,buf.subarray(0,n));}fs.fsyncSync(out);}
 finally{fs.closeSync(out);}if(rev(fs.fstatSync(input,{bigint:true}),rel)!==expected||revision(src,rel)!==expected)fail(101,'파일이 바뀌었어요. 다시 검사해 주세요.');
 }finally{fs.closeSync(input);}
}
function repair(){
 const [rs,expected,mode,bs,br]=args,r=decode(rs),p=chatPath(r);cancel();const a=parseFile(r);if(a.revision!==expected)fail(101,'검사한 뒤 채팅이 바뀌었어요.');
 if(!['bad_tail','bad_header','bad_middle'].includes(a.kind))fail(103,'이 파일은 변경할 수 없어요.');
 const useBackup=['restore-backup','candidate-copy'].includes(mode),rescue=['rescue-copy','candidate-copy'].includes(mode);
 let b;if(useBackup){const s=decode(bs);if(parts(s)[1]!==parts(r)[1])fail(102,'같은 사용자의 사본이 아니에요.');
 try{b=parseFile(s,true);}catch(e){if(e.status===130)throw e;fail(102,'사본을 다시 확인해 주세요.');}
 if(b.revision!==br||!(mode==='restore-backup'?strength(a,b)==='verified':strength(a,b)))fail(102,'같은 대화와 내용인지 확인할 수 없어요.');}
 else if(!(mode==='trim-tail'&&a.kind==='bad_tail'||mode==='rescue-copy'&&a.messages>0))fail(103,'지원하지 않는 복구 방식이에요.');
 const dirPath=path.dirname(p),tmp=path.join(dirPath,'.st-launcher-chat-'+crypto.randomUUID()+'.tmp');
 const owner=fs.realpathSync(ownerArg),base=safeSub(owner,'file-trash'),id=Date.now()+'-'+crypto.randomUUID(),container=path.join(base,id);
 let committed=false,made=false,reserved=false;
 const outRel=rescue?r.slice(0,r.lastIndexOf('/')+1)+'되찾은 대화-'+crypto.randomUUID()+'.jsonl':r;
 const out=chatPath(outRel);
 try{
 if(b)copyStable(chatPath(b.r,true),tmp,b.r,b.revision);
 else{
 const fd=fs.openSync(tmp,'wx',0o600);try{let wrote=false;
 const emit=s=>{fs.writeFileSync(fd,(wrote?'\n':'')+s);wrote=true;};
 if(a.kind==='bad_header'&&rescue)emit(JSON.stringify(r.includes('/group chats/')?{chat_metadata:{}}:
   {user_name:a.firstUser||'User',character_name:parts(r)[3],create_date:humanizedDate(new Date()),chat_metadata:{}}));
 const check=parseFile(r,false,Infinity,(raw,isMessage)=>{if(a.kind!=='bad_header'||isMessage)emit(raw);});if(check.revision!==expected)fail(101,'채팅이 바뀌었어요.');
 fs.fsyncSync(fd);}finally{fs.closeSync(fd);}}
 const newRevision=revision(tmp,outRel);
 fs.mkdirSync(container,{mode:0o700});made=true;
 copyStable(p,path.join(container,'item'),r,expected);
 const meta=path.join(container,'metadata.json');
 fs.writeFileSync(meta,JSON.stringify({root,relative:r,deleted_at:Date.now(),chat:{outRel,newRevision,rescue,originalRevision:revision(path.join(container,'item'),r)}}),{flag:'wx',mode:0o600});
 syncFile(meta);syncFile(container);syncFile(base);cancel();
 if(revision(p,r)!==expected)fail(101,'복구하기 직전에 채팅이 바뀌었어요.');
 if(b&&revision(chatPath(b.r,true),b.r)!==br)fail(102,'사본이 바뀌었어요.');
 if(rescue){fs.closeSync(fs.openSync(out,'wx',0o600));reserved=true;}
 fs.renameSync(tmp,out);committed=true;syncFile(dirPath);
 console.log('repaired='+mode+'\nmessages='+(b?.messages??a.messages)+'\ntrash_id='+id+'\noutput='+b64(outRel));
 }finally{if(fs.existsSync(tmp))fs.unlinkSync(tmp);if(!committed){if(reserved)fs.unlinkSync(out);if(made){for(const n of ['item','metadata.json']){const f=path.join(container,n);if(fs.existsSync(f))fs.unlinkSync(f);}fs.rmdirSync(container);}}}
}
function humanizedDate(d){const pad=n=>String(n).padStart(2,'0');return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+'@'+pad(d.getHours())+'h '+pad(d.getMinutes())+'m '+pad(d.getSeconds())+'s '+String(d.getMilliseconds()).padStart(3,'0')+'ms';}
function undo(){
 const id=args[0];if(!idRE.test(id))fail(103,'되돌리기 정보가 없어요.');
 const trash=path.join(fs.realpathSync(ownerArg),'file-trash'),container=path.join(trash,id);
 if(!dir(trash)||!dir(container))fail(103,'원본 보관함이 없어요.');
 const meta=path.join(container,'metadata.json'),item=path.join(container,'item');
 if(!file(meta)||fs.statSync(meta).size>8192||!file(item))fail(103,'원본 보관 정보를 확인할 수 없어요.');
 const m=JSON.parse(fs.readFileSync(meta)),c=m.chat;if(m.root!==root||!c)fail(103,'다른 작업의 원본이에요.');
 const out=chatPath(c.outRel);if(!file(out)||revision(out,c.outRel)!==c.newRevision)fail(101,'그 뒤에 채팅이 바뀌어 되돌리지 않았어요.');
 if(revision(item,m.relative)!==c.originalRevision)fail(101,'보관한 원본이 바뀌어 되돌리지 않았어요.');
 cancel();if(c.rescue){fs.unlinkSync(out);}else{
 if(c.outRel!==m.relative)fail(103,'되돌리기 경로가 맞지 않아요.');
 const tmp=path.join(path.dirname(out),'.st-launcher-chat-'+crypto.randomUUID()+'.tmp');
 try{copyStable(item,tmp,m.relative,c.originalRevision);cancel();if(revision(out,c.outRel)!==c.newRevision)fail(101,'그 뒤에 채팅이 바뀌어 되돌리지 않았어요.');
 fs.renameSync(tmp,out);}finally{if(fs.existsSync(tmp))fs.unlinkSync(tmp);}
 } syncFile(path.dirname(out)); fs.unlinkSync(meta);fs.unlinkSync(item);fs.rmdirSync(container);console.log('undone=1');
}
try{if(action==='scan')scan();else if(action==='repair')repair();else if(action==='undo')undo();else fail(100,'지원하지 않는 요청이에요.');}
catch(e){process.exitCode=e.status||(action==='scan'?100:103);console.error(e.status?e.message:'채팅 파일을 안전하게 처리하지 못했어요.');}
NODE
