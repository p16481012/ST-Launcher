'use strict';
// Fixtures run against the exact Node payload shipped by manager.sh. Only the
// sandbox created here is written; there is no dependency on a real ST install.
const assert=require('node:assert/strict'), fs=require('node:fs'), path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),crypto=require('node:crypto');
// Native Windows Node has no mv.exe on PATH. Exercise the shipped helper
// with Git Bash's real GNU mv, just as the Termux/Linux fixture does.
const bash=process.platform==='win32'?(process.env.GIT_BASH||'C:/Program Files/Git/bin/bash.exe'):'/bin/bash';
if(process.platform==='win32'){
    const bin=path.resolve(path.dirname(bash),'../usr/bin');
    if(fs.existsSync(path.join(bin,'mv.exe')))process.env.Path=bin+path.delimiter+process.env.Path;
}
const base=fs.mkdtempSync(path.join(os.tmpdir(),'st-library-')),home=path.join(base,'st'),owner=path.join(base,'launcher'),dl=path.join(base,'downloads'),user='default-user',profile=path.join(home,'data',user);
const script=path.join(__dirname,'../app/src/main/assets/library-data.sh'),helper=fs.readFileSync(script,'utf8').split("<<'LIBRARY_JS'")[1].split(/\r?\nLIBRARY_JS/)[0];
const b64=s=>Buffer.from(s).toString('base64'),decode=s=>Buffer.from(s,'base64').toString(),hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const file=(r,data)=>{const p=path.join(profile,r);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,data);};
const invoke=(prefix,as,action,...args)=>cp.spawnSync(process.execPath,[...prefix,'-',action,home,owner,dl,b64(as),...args],{input:helper,encoding:'utf8',timeout:30000,windowsHide:true});
const run=(action,...args)=>invoke([],user,action,...args);
const answer=(status,cmd,...args)=>{const p=run(cmd,...args);assert.equal(p.status,status,[cmd,p.stdout,p.stderr,p.error].join('\n'));return p.stdout;};
const readValue=(out,k)=>out.split(/\r?\n/).find(s=>s.startsWith(k+'='))?.slice(k.length+1);
const rows=out=>out.split(/\r?\n/).filter(s=>s.startsWith('item\t')).map(r=>{const [,name,label,bytes,chats,mtime,revision,a,fileBytes,artRev]=r.split('\t');return {path:decode(name),label:decode(label),bytes:Number(bytes),chats:Number(chats),mtime:Number(mtime),revision,protected:a==='1',fileBytes:Number(fileBytes),artRev};});
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
    const cardA=cards.find(c=>c.path==='characters/A.png');assert.equal(cardA.fileBytes,bg.length);assert(cardA.bytes>cardA.fileBytes);assert.match(cardA.artRev,/^[a-f0-9]{64}$/);
    assert(!orphan[0].artRev);
    const guest=path.join(home,'data','guest');fs.mkdirSync(path.join(guest,'characters'),{recursive:true});fs.writeFileSync(path.join(guest,'characters/A.png'),png('Private guest card',9));
    const guestView=invoke([],'guest','library-list','cards','');assert.equal(guestView.status,0,guestView.stderr);assert.equal(rows(guestView.stdout)[0].label,'Private guest card');
    assert.equal(inspect('cards').find(c=>c.path==='characters/A.png').label,'Example');assert.notEqual(invoke([],'../default-user','library-list','cards').status,0);
    file('characters/NewLater.png',bg);
    assert.equal(readValue(answer(0,'library-trash',list([orphan.find(c=>c.path.endsWith('NewLater'))])),'moved'),'0');
    file('chats/A/test.jsonl','{"chat_metadata":{}}\n{"mes":"appended"}');
    assert.equal(readValue(answer(0,'library-trash',list([cards.find(c=>c.path==='characters/A.png')]),'1'),'moved'),'0');
    cards=inspect('cards');assert.equal(cards.find(c=>c.path==='characters/A.png').artRev,cardA.artRev);
    let record=answer(0,'library-trash',list([cards.find(c=>c.path==='characters/A.png'),cards.find(c=>c.path.endsWith('Fav.png')),orphan.find(c=>c.path.endsWith('Lost'))]),'1');
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
    const preset={prompts:[]}, theme={name:'Night',main_text_color:'white'}, qr={name:'Night',qrList:[]};
    for(const [kind,folder,format] of [['presets','OpenAI Settings',preset],['themes','themes',theme],['quickreplies','QuickReplies',qr]]){
        for(const other of ['presets','themes','quickreplies'].filter(k=>k!==kind))answer(144,'library-import',...imp(other,'Cross.json',format));
        const named=kind!=='presets';
        if(named)file(folder+'/Existing-file.json',JSON.stringify(format));
        const result=answer(0,'library-import',...imp(kind,'Case.json',format));
        const target=named?'Night (2).json':'Case.json';
        assert.equal(decode(readValue(result,'imported_b64')),target);
        const stored=JSON.parse(fs.readFileSync(path.join(profile,folder,target),'utf8'));
        if(named)assert.equal(stored.name,'Night (2)');else assert.deepEqual(stored,preset);
        assert(inspect(kind).every(row=>row.path.startsWith(folder+'/')));
    }
    for(const [folder,format] of [['NovelAI Settings',{temperature:1,repetition_penalty:1.2}],['instruct',{name:'ChatML',input_sequence:'a',output_sequence:'b'}],['TextGen Settings',{temp:1,epsilon_cutoff:0}]]){
        file(folder+'/Keep.json',JSON.stringify(format));answer(144,'library-import',...imp('presets','No.json',format));
        assert.deepEqual(JSON.parse(fs.readFileSync(path.join(profile,folder,'Keep.json'),'utf8')),format);
    }
    assert.deepEqual(inspect('presets').map(r=>r.path),['OpenAI Settings/Case.json']);
    answer(144,'library-import',...imp('quickreplies','Ambiguous.json',{prompts:[],qrList:[]}));
    answer(144,'library-import',...imp('presets','Combined.json',{instruct:{},context:{}}));
    file('settings.json',JSON.stringify({background:{name:'elsewhere.png'},power_user:{theme:'Night'},oai_settings:{preset_settings_openai:'Case'}}));
    assert.equal(readValue(answer(0,'library-usage',b64('OpenAI Settings/Case.json')),'state'),'used');
    assert.equal(readValue(answer(0,'library-usage',b64('themes/Existing-file.json')),'state'),'used');
    assert.equal(readValue(answer(0,'library-usage',b64('QuickReplies/Existing-file.json')),'state'),'unconfirmed');
    assert.equal(readValue(answer(0,'library-usage',b64('backgrounds/Card.png')),'state'),'none');
    file('chats/A/broken.jsonl','{broken');assert.equal(readValue(answer(0,'library-usage',b64('backgrounds/Card.png')),'state'),'unconfirmed');
    file('group chats/g.jsonl',JSON.stringify({chat_metadata:{custom_background:'url("backgrounds/Card.png")'}})+'\n');assert.equal(readValue(answer(0,'library-usage',b64('backgrounds/Card.png')),'state'),'used');
    const asset=(kind,r)=>inspect(kind).find(x=>x.path===r);
    const argv=(x,mode,stem='',allow='0')=>[b64(x.path),x.revision,mode,b64(stem),allow];
    const change=(code,x,...opts)=>answer(code,'library-asset-change',...argv(x,...opts));
    const settings={background:{name:'Card.png'},power_user:{theme:'Night'},oai_settings:{preset_settings_openai:'Case'},
        world_info_settings:{world_info:{globalSelect:['City']}},extension_settings:{quickReplyV2:{config:{setList:[{set:'Night'}]}}}};
    file('settings.json',JSON.stringify(settings));
    for(const [k,r] of [['backgrounds','backgrounds/Card.png'],['worlds','worlds/City.json'],['presets','OpenAI Settings/Case.json'],
        ['themes','themes/Existing-file.json'],['quickreplies','QuickReplies/Existing-file.json']]){
        const x=asset(k,r);change(144,x,'trash','','1');change(144,x,'rename','Blocked','1');
        const result=change(0,x,'copy','Independent');assert.equal(decode(readValue(result,'name_b64')),'Independent'+path.extname(r));
        const target=path.join(profile,path.dirname(r),'Independent'+path.extname(r));
        if(['themes','quickreplies'].includes(k))assert.equal(JSON.parse(fs.readFileSync(target)).name,'Independent');
        else assert.deepEqual(fs.readFileSync(target),fs.readFileSync(path.join(profile,r)));
        change(145,x,'copy','Independent');fs.unlinkSync(target);
    }
    const firstQr=asset('quickreplies','QuickReplies/Existing-file.json');
    delete settings.extension_settings.quickReplyV2.config;
    settings.extension_settings.quickReplyV2.characterConfigs={A:{setList:[{set:'Night'}]}};file('settings.json',JSON.stringify(settings));
    change(144,firstQr,'rename','Blocked','1');
    settings.extension_settings.quickReplyV2.characterConfigs={};file('settings.json',JSON.stringify(settings));
    file('chats/A/qr.jsonl',JSON.stringify({chat_metadata:{quickReply:{setList:[{set:'Night'}]}}}));
    change(144,firstQr,'trash','','1');fs.unlinkSync(path.join(profile,'chats/A/qr.jsonl'));
    file('QuickReplies/Menu.json',JSON.stringify({name:'Menu',qrList:[{contextList:[{set:'Night'}]}]}));
    change(144,firstQr,'trash','','1');fs.unlinkSync(path.join(profile,'QuickReplies/Menu.json'));
    change(144,firstQr,'trash');change(145,firstQr,'copy','Night (2)');
    for(const [k,d,suffix,bytes] of [['backgrounds','backgrounds','.PNG',bg],['worlds','worlds','.json','{"entries":{}}'],
        ['presets','OpenAI Settings','.json','{"prompts":[]}'],['themes','themes','.json','\ufeff{ "nested":{"name":"nested"}, "name" : "Original", "id":9007199254740993 }'],
        ['quickreplies','QuickReplies','.json','{ "qrList":[], "name" : "Original", "id":9007199254740993 }']]){
        const r=d+'/Fixture'+suffix,s=d+'/Changed'+suffix,src=path.join(profile,r),dst=path.join(profile,s);
        file(r,bytes);const x=asset(k,r);
        change(64,x,'rename','../nested','1');change(64,x,'rename','Fixture','1');change(64,x,'copy','bad:name','1');
        file(s,'do not replace');change(145,x,'rename','Changed','1');assert.equal(fs.readFileSync(dst,'utf8'),'do not replace');fs.unlinkSync(dst);
        const renamed=change(0,x,'rename','Changed','1');assert(!fs.existsSync(src));assert.equal(decode(readValue(renamed,'name_b64')),path.basename(s));
        const structured=k==='themes'||k==='quickreplies';
        assert.deepEqual(fs.readFileSync(dst),Buffer.from(structured?bytes.replace('"Original"','"Changed"'):bytes));
        if(structured){assert.equal(readValue(answer(0,'library-undo',readValue(renamed,'group_id')),'restored'),'1');assert.deepEqual(fs.readFileSync(src),Buffer.from(bytes));fs.unlinkSync(src);}
        const y=asset(k,s),origBytes=fs.readFileSync(dst),trashed=change(0,y,'trash','','1'),groupId=readValue(trashed,'group_id');
        assert(!fs.existsSync(dst));assert(answer(0,'library-history').includes(groupId));
        file(s,'replacement');assert.equal(readValue(answer(0,'library-undo',groupId),'restored'),'0');assert.equal(fs.readFileSync(dst,'utf8'),'replacement');fs.unlinkSync(dst);
        if(structured){
            const alias=d+'/Unrelated.json';file(alias,'{"name":"Changed"}');
            assert.equal(readValue(answer(0,'library-undo',groupId),'restored'),'0');assert(!fs.existsSync(dst));fs.unlinkSync(path.join(profile,alias));
        }
        assert.equal(readValue(answer(0,'library-undo',groupId),'restored'),'1');assert.deepEqual(fs.readFileSync(dst),origBytes);
        file(s,'changed since list');change(145,y,'trash','','1');answer(145,'library-usage',b64(y.path),y.revision);
        fs.unlinkSync(dst);
    }
    // Termination after output publication and before original -> trash: neither version is lost.
    file('themes/Recoverable.json','{"name":"Recoverable","main_text_color":"blue"}');
    const before=asset('themes','themes/Recoverable.json');
    const interrupted=invoke(['-r',fault],user,'library-asset-change',...argv(before,'rename','Published','1'));
    assert.equal(interrupted.status,85,interrupted.stderr);
    assert.equal(JSON.parse(fs.readFileSync(path.join(profile,'themes/Recoverable.json'))).name,'Recoverable');
    assert.equal(JSON.parse(fs.readFileSync(path.join(profile,'themes/Published.json'))).name,'Published');
    if(process.platform!=='win32'){
        fs.symlinkSync(path.join(profile,'characters/A.png'),path.join(profile,'characters/linked.png'));assert(!inspect('cards').some(x=>x.path==='characters/linked.png'));
        fs.renameSync(path.join(profile,'OpenAI Settings'),path.join(profile,'openai-original'));
        fs.symlinkSync(path.join(profile,'worlds'),path.join(profile,'OpenAI Settings'));assert.notEqual(run('library-import',...imp('presets','No.json',preset)).status,0);
        assert(!fs.existsSync(path.join(profile,'worlds/No.json')));fs.unlinkSync(path.join(profile,'OpenAI Settings'));
        fs.unlinkSync(path.join(dl,stage));fs.symlinkSync(path.join(profile,'worlds/City.json'),path.join(dl,stage));assert.notEqual(run('library-import','worlds',stage,b64('Link.json'),hash(Buffer.from(JSON.stringify(orig)))).status,0);assert(!fs.existsSync(path.join(profile,'worlds/Link.json')));
        fs.symlinkSync(guest,path.join(home,'data','guest-link'));assert.notEqual(invoke([],'guest-link','library-list','cards').status,0);
    }
    for(let i=0;i<480;i++)file('worlds/More-'+i+'-'.repeat(48)+'.json','{"entries":{}}');
    let cursor='',seen=new Set(),pages=0;
    do{const page=answer(0,'library-list','worlds',cursor);assert(Buffer.byteLength(page)<40*1024);for(const x of rows(page)){assert(!seen.has(x.path));seen.add(x.path);}cursor=readValue(page,'next_cursor');assert(++pages<20);}while(cursor);
    assert(pages>1);assert.equal(seen.size,482);
    require('./library-card-fixtures.cjs')({base,home,owner,invoke,rows,b64,decode,readValue,chunk});
    console.log('Library data: stable paging, separated imports, safe five-category rename/copy/trash, crash recovery, grouped undo, revisions and tri-state usage passed.');
}finally{fs.rmSync(base,{recursive:true,force:true});}
