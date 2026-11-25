'use strict';
// Fixtures run against the exact Node payload shipped by manager.sh. Only the
// sandbox created here is written; there is no dependency on a real ST install.
const assert=require('node:assert/strict'), fs=require('node:fs'), path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),crypto=require('node:crypto');
const base=fs.mkdtempSync(path.join(os.tmpdir(),'st-library-')),home=path.join(base,'st'),owner=path.join(base,'launcher'),dl=path.join(base,'downloads'),user='default-user',profile=path.join(home,'data',user);
const script=path.join(__dirname,'../app/src/main/assets/library-data.sh'),helper=fs.readFileSync(script,'utf8').split("<<'LIBRARY_JS'")[1].split(/\r?\nLIBRARY_JS/)[0];
const b64=s=>Buffer.from(s).toString('base64'),decode=s=>Buffer.from(s,'base64').toString(),hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const file=(r,data)=>{const p=path.join(profile,r);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,data);};
const invoke=(prefix,as,action,...args)=>cp.spawnSync(process.execPath,[...prefix,'-',action,home,owner,dl,b64(as),...args],{input:helper,encoding:'utf8',timeout:30000,windowsHide:true});
const run=(action,...args)=>invoke([],user,action,...args);
const answer=(status,cmd,...args)=>{const p=run(cmd,...args);assert.equal(p.status,status,[cmd,p.stdout,p.stderr,p.error].join('\n'));return p.stdout;};
const readValue=(out,k)=>out.split(/\r?\n/).find(s=>s.startsWith(k+'='))?.slice(k.length+1);
const rows=out=>out.split(/\r?\n/).filter(s=>s.startsWith('item\t')).map(r=>{const [,name,label,bytes,chats,mtime,revision,a,sha]=r.split('\t');return {path:decode(name),label:decode(label),bytes:Number(bytes),chats:Number(chats),mtime:Number(mtime),revision,protected:a==='1',sha};});
const inspect=k=>rows(answer(0,'library-list',k,''));
const list=items=>b64(JSON.stringify(items.map(i=>({path:i.path,revision:i.revision}))));
const crc=b=>{let a=~0;for(const n of b){a^=n;for(let i=0;i<8;i++)a=(a&1)?0xedb88320^(a>>>1):a>>>1;}return (~a)>>>0;};
const chunk=(s,b)=>{const d=Buffer.concat([Buffer.from(s),Buffer.from(b)]),out=Buffer.alloc(d.length+8);out.writeUInt32BE(d.length-4);d.copy(out,4);out.writeUInt32BE(crc(d),d.length+4);return out;};
const png=(name,color,fav=false)=>Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',Buffer.from('00000001000000010802000000','hex')),
    chunk('IDAT',require('node:zlib').deflateSync(Buffer.from([0,color,0,0]))),chunk('tEXt','ccv3\0'+b64(JSON.stringify({data:{name,extensions:{fav}}}))),chunk('IEND','')]);
const bg=png('Example',1),stage='SillyTavern-File-'+crypto.randomUUID()+'.tmp';
function imp(kind,name,data,choice){const b=Buffer.isBuffer(data)?data:Buffer.from(JSON.stringify(data));fs.writeFileSync(path.join(dl,stage),b);return [kind,stage,b64(name),hash(b),...(choice?[choice]:[])];}
try{
    fs.mkdirSync(dl);fs.mkdirSync(owner);
    file('characters/A.png',bg);file('characters/B.png',bg);file('characters/C.png',png('Example',2));
    file('characters/Fav.png',png('Fav',7,true));file('characters/Member.png',png('Member',8));file('groups/g.json',JSON.stringify({members:['Member.png']}));
    file('chats/A/test.jsonl','{"chat_metadata":{}}\n');file('chats/Lost/log.jsonl','{"chat_metadata":{}}\n');file('chats/NewLater/log.jsonl','{"chat_metadata":{}}\n');
    let cards=inspect('cards'),orphan=inspect('orphans');assert.equal(cards.length,5);assert.equal(cards.find(c=>c.path==='characters/A.png').chats,1);
    assert(cards.find(c=>c.path.endsWith('Fav.png')).protected);assert(cards.find(c=>c.path.endsWith('Member.png')).protected);assert.equal(orphan.length,2);
    const dup=rows(answer(0,'library-duplicates',''));assert.equal(dup.find(c=>c.path==='characters/A.png').sha,dup.find(c=>c.path==='characters/B.png').sha);
    assert(!dup.some(c=>c.path==='characters/C.png'&&c.sha===hash(bg)));
    const guest=path.join(home,'data','guest');fs.mkdirSync(path.join(guest,'characters'),{recursive:true});fs.writeFileSync(path.join(guest,'characters/A.png'),png('Private guest card',9));
    const guestView=invoke([],'guest','library-list','cards','');assert.equal(guestView.status,0,guestView.stderr);assert.equal(rows(guestView.stdout)[0].label,'Private guest card');
    assert.equal(inspect('cards').find(c=>c.path==='characters/A.png').label,'Example');assert.notEqual(invoke([],'../default-user','library-list','cards').status,0);
    file('characters/NewLater.png',bg);
    assert.equal(readValue(answer(0,'library-trash',list([orphan.find(c=>c.path.endsWith('NewLater'))])),'moved'),'0');
    file('chats/A/test.jsonl','{"chat_metadata":{}}\n{"mes":"appended"}');
    assert.equal(readValue(answer(0,'library-trash',list([cards.find(c=>c.path==='characters/A.png')]),'1'),'moved'),'0');
    cards=inspect('cards');let record=answer(0,'library-trash',list([cards.find(c=>c.path==='characters/A.png'),cards.find(c=>c.path.endsWith('Fav.png')),orphan.find(c=>c.path.endsWith('Lost'))]),'1');
    const gid=readValue(record,'group_id');assert.equal(readValue(record,'moved'),'2');assert.equal(readValue(record,'skipped'),'1');
    assert(!fs.existsSync(path.join(profile,'characters/A.png')));assert(!fs.existsSync(path.join(profile,'chats/A')));assert(!fs.existsSync(path.join(profile,'chats/Lost')));
    assert(answer(0,'library-history').includes(gid));file('characters/A.png','new different card');
    const first=answer(0,'library-undo',gid);assert.equal(readValue(first,'restored'),'1');assert.equal(readValue(first,'skipped'),'2');
    assert(!fs.existsSync(path.join(profile,'chats/A')));assert.equal(fs.readFileSync(path.join(profile,'characters/A.png'),'utf8'),'new different card');
    fs.unlinkSync(path.join(profile,'characters/A.png'));assert.equal(readValue(answer(0,'library-undo',gid),'restored'),'2');
    assert(fs.existsSync(path.join(profile,'chats/A/test.jsonl')));assert(!answer(0,'library-history').includes(gid));
    // Stop the isolated helper after moving one file and preparing metadata for
    // the second move. A new helper must recover the first without touching
    // the second (the GUI never got the command's receipts).
    const fault=path.join(base,'fault.cjs');fs.writeFileSync(fault,"const cp=require('node:child_process'),orig=cp.execFileSync;let moves=0;cp.execFileSync=(exe,...a)=>{if(exe==='mv'&&++moves===2)process.exit(85);return orig(exe,...a);};");
    const killed=invoke(['-r',fault],user,'library-trash',list([inspect('cards').find(c=>c.path==='characters/A.png')]),'1');
    assert.equal(killed.status,85,killed.stderr);assert(!fs.existsSync(path.join(profile,'characters/A.png')));assert(fs.existsSync(path.join(profile,'chats/A/test.jsonl')));
    const groups=answer(0,'library-history').trim().split(/\r?\n/).map(row=>row.split('\t'));const last=groups[0][1];assert.equal(groups[0][3],'1');
    assert(!invoke([],'guest','library-history').stdout.includes(last));assert.notEqual(invoke([],'guest','library-undo',last).status,0);
    assert.equal(readValue(answer(0,'library-undo',last),'restored'),'1');assert(fs.existsSync(path.join(profile,'chats/A/test.jsonl')));
    assert.equal(fs.readdirSync(path.join(owner,'file-trash')).length,0);
    const orig={entries:{1:{content:'Keep'}}},next={entries:{1:{content:'Other'}}};
    // Exercise the actual shell dispatch to the existing shared PNG reader,
    // not a second parser that happens to accept our test card.
    const bash=process.platform==='win32'?(process.env.GIT_BASH||'C:/Program Files/Git/bin/bash.exe'):'/bin/bash';
    const importPng=data=>{
        const argv=imp('cards','A.png',data);
        return cp.spawnSync(bash,[script,'library-import',home,owner,dl,b64(user),...argv],{encoding:'utf8',windowsHide:true,timeout:15000});
    };
    const direct=importPng(bg);
    assert.equal(direct.status,0,direct.stderr||String(direct.error));assert.equal(decode(readValue(direct.stdout,'imported_b64')),'A (2).png');
    assert.deepEqual(fs.readFileSync(path.join(profile,'characters/A (2).png')),bg);
    assert.equal(importPng(Buffer.from('not a character')).status,110);
    file('worlds/City.json',JSON.stringify(orig));
    assert.equal(decode(readValue(answer(0,'library-import',...imp('worlds','City.json',next)),'imported_b64')),'City (2).json');
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(profile,'worlds/City.json'),'utf8')),orig);
    answer(144,'library-import',...imp('worlds','Foreign.json',{entries:{},lorebookVersion:5}));
    assert.equal(decode(readValue(answer(0,'library-import',...imp('backgrounds','Card.png',bg)),'imported_b64')),'Card.png');
    answer(144,'library-import',...imp('backgrounds','NotPng.jpg',bg));
    const amb={temperature:1,repetition_penalty:1.2};answer(146,'library-import',...imp('presets','Sample.json',amb));
    answer(0,'library-import',...imp('presets','Sample.json',amb,'novel'));assert(fs.existsSync(path.join(profile,'NovelAI Settings/Sample.json')));
    const instruct={name:'ChatML',input_sequence:'a',output_sequence:'b'};file('instruct/ChatML.json',JSON.stringify(instruct));
    answer(0,'library-import',...imp('presets','DiffFile.json',instruct));
    assert.equal(JSON.parse(fs.readFileSync(path.join(profile,'instruct/ChatML (2).json'),'utf8')).name,'ChatML (2)');
    for(const [folder,format,pick] of [
        ['OpenAI Settings',{prompts:[]}], ['context',{story_string:'{{story}}'}],
        ['sysprompt',{name:'Case',content:'system'}], ['reasoning',{name:'Case',prefix:'<',suffix:'>',separator:' '}],
        ['QuickReplies',{quickReplySlots:[]}], ['themes',{main_text_color:'white'}],
        ['TextGen Settings',{temp:1,epsilon_cutoff:0},'textgen'],['KoboldAI Settings',{temp:1,rep_pen:1},'kobold']
    ]){answer(0,'library-import',...imp('presets','Case.json',format,pick));assert(fs.existsSync(path.join(profile,folder,'Case.json')));}
    answer(144,'library-import',...imp('presets','Combined.json',{instruct:{},context:{}}));
    file('settings.json',JSON.stringify({background:{name:'elsewhere.png'},power_user:{instruct:{preset:'ChatML'}}}));
    assert.equal(readValue(answer(0,'library-usage',b64('instruct/ChatML.json')),'state'),'used');
    assert.equal(readValue(answer(0,'library-usage',b64('backgrounds/Card.png')),'state'),'none');
    file('chats/A/broken.jsonl','{broken');assert.equal(readValue(answer(0,'library-usage',b64('backgrounds/Card.png')),'state'),'unconfirmed');
    file('group chats/g.jsonl',JSON.stringify({chat_metadata:{custom_background:'url("backgrounds/Card.png")'}})+'\n');assert.equal(readValue(answer(0,'library-usage',b64('backgrounds/Card.png')),'state'),'used');
    if(process.platform!=='win32'){
        fs.symlinkSync(path.join(profile,'characters/A.png'),path.join(profile,'characters/linked.png'));assert(!inspect('cards').some(x=>x.path==='characters/linked.png'));
        fs.renameSync(path.join(profile,'sysprompt'),path.join(profile,'sysprompt-original'));
        fs.symlinkSync(path.join(profile,'worlds'),path.join(profile,'sysprompt'));assert.notEqual(run('library-import',...imp('presets','No.json',{name:'No',content:'Ignore'})).status,0);
        assert(!fs.existsSync(path.join(profile,'worlds/No.json')));fs.unlinkSync(path.join(profile,'sysprompt'));
        fs.unlinkSync(path.join(dl,stage));fs.symlinkSync(path.join(profile,'worlds/City.json'),path.join(dl,stage));assert.notEqual(run('library-import','worlds',stage,b64('Link.json'),hash(Buffer.from(JSON.stringify(orig)))).status,0);assert(!fs.existsSync(path.join(profile,'worlds/Link.json')));
        fs.symlinkSync(guest,path.join(home,'data','guest-link'));assert.notEqual(invoke([],'guest-link','library-list','cards').status,0);
    }
    for(let i=0;i<480;i++)file('worlds/More-'+i+'-'.repeat(48)+'.json','{"entries":{}}');
    let cursor='',seen=new Set(),pages=0;
    do{const page=answer(0,'library-list','worlds',cursor);assert(Buffer.byteLength(page)<40*1024);for(const x of rows(page)){assert(!seen.has(x.path));seen.add(x.path);}cursor=readValue(page,'next_cursor');assert(++pages<20);}while(cursor);
    assert(pages>1);assert.equal(seen.size,482);
    console.log('Library data: stable paging, checked imports, grouped undo, stale revisions, manual duplicates and tri-state usage passed.');
}finally{fs.rmSync(base,{recursive:true,force:true});}
