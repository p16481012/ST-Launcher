'use strict';
// Fresh profile; exercises the same read / write / undo protocol used by Compose.
module.exports=function verifyCardActions({base,home,invoke,rows,b64,decode,readValue,chunk}){
    const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
    const as='card-fixture',profile=path.join(home,'data',as),p=r=>path.join(profile,r);
    const put=(r,bytes)=>{fs.mkdirSync(path.dirname(p(r)),{recursive:true});fs.writeFileSync(p(r),bytes);};
    const read=r=>fs.readFileSync(p(r));
    const call=(code,cmd,...params)=>{const result=invoke([],as,cmd,...params);assert.equal(result.status,code,[cmd,result.stderr,result.stdout].join('\n'));return result.stdout;};
    const card=name=>rows(call(0,'library-list','cards','')).find(x=>x.path==='characters/'+name+'.png');
    const usage=row=>readValue(call(0,'library-card-usage',b64(row.path),row.revision),'state');
    const change=(code,row,mode,stem)=>call(code,'library-card-change',b64(row.path),row.revision,mode,b64(stem));
    const bytes=Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),
        chunk('IHDR',Buffer.from('00000001000000010802000000','hex')),
        chunk('IDAT',require('node:zlib').deflateSync(Buffer.from([0,10,50,130]))),
        chunk('tEXt','ccv3\0'+b64(JSON.stringify({spec:'chara_card_v3',spec_version:'3.0',
            data:{name:'표시 이름',description:'카드 소개',extensions:{}}}))),chunk('IEND','')]);
    for(const dir of ['characters','groups','chats'])fs.mkdirSync(p(dir),{recursive:true});
    put('settings.json','{}');put('characters/원본.png',bytes);
    const start=card('원본');assert.equal(start.label,'표시 이름');assert.equal(usage(start),'none');
    // Interruption after the new card becomes visible must never lose the source.
    const interrupt=path.join(base,'stop-before-shelving.cjs');
    fs.writeFileSync(interrupt,"const cp=require('node:child_process'),move=cp.execFileSync;let i=0;cp.execFileSync=function(exe,...args){if(exe==='mv'&&++i===2)process.exit(85);return move.call(this,exe,...args)};");
    const interrupted=invoke(['-r',interrupt],as,'library-card-change',b64(start.path),start.revision,'rename',b64('작업 중단 사본'));
    assert.equal(interrupted.status,85,interrupted.stderr);assert.deepEqual(read(start.path),bytes);
    assert.equal(card('작업 중단 사본').label,'작업 중단 사본');
    change(64,start,'rename','../탈출');
    put('characters/겹침.png',bytes);change(145,start,'rename','겹침');assert.deepEqual(read('characters/겹침.png'),bytes);
    put('chats/남은 대화/keep.jsonl','{"chat_metadata":{}}\n');change(145,start,'copy','남은 대화');
    assert(!fs.existsSync(p('characters/남은 대화.png')));
    const renamed=change(0,start,'rename','새 캐릭터');
    assert.equal(decode(readValue(renamed,'name_b64')),'새 캐릭터.png');assert(!fs.existsSync(p(start.path)));
    const renamedRow=card('새 캐릭터');assert.equal(renamedRow.label,'새 캐릭터');
    const renamedBytes=read(renamedRow.path);
    // A fresh link after the sheet opened must block the original mutation as well.
    const chat='chats/새 캐릭터/first.jsonl';put(chat,'{"chat_metadata":{}}\n{"mes":"보존"}\n');
    const stale=invoke([],as,'library-card-change',b64(renamedRow.path),renamedRow.revision,'rename',b64('오래된 선택'));
    assert(stale.status!==0);assert(!fs.existsSync(p('characters/오래된 선택.png')));
    const linked=card('새 캐릭터');assert.equal(usage(linked),'used');change(144,linked,'rename','연결 해제');
    const origChat=read(chat);change(0,linked,'copy','사본');assert.equal(card('사본').label,'사본');
    assert.deepEqual(read(chat),origChat);assert.deepEqual(read(linked.path),renamedBytes);assert(!fs.existsSync(p('chats/사본')));
    const info=call(0,'library-trash',b64(JSON.stringify([{path:linked.path,revision:linked.revision}])),'0');
    assert.equal(readValue(info,'moved'),'1');assert(!fs.existsSync(p(linked.path)));assert.deepEqual(read(chat),origChat);
    assert.equal(readValue(call(0,'library-undo',readValue(info,'group_id')),'restored'),'1');
    assert.deepEqual(read(linked.path),renamedBytes);assert.deepEqual(read(chat),origChat);
    const group='groups/그룹.json',groupBytes=Buffer.from(JSON.stringify({members:['겹침.png'],disabled_members:[]}));
    put(group,groupBytes);const member=card('겹침');assert.equal(usage(member),'used');
    change(144,member,'rename','다른 이름');change(0,member,'copy','그룹 사본');
    assert.deepEqual(read(group),groupBytes);assert.deepEqual(read(member.path),bytes);assert.equal(card('그룹 사본').label,'그룹 사본');
    put(group,'{');const unverified=card('사본');assert.equal(usage(unverified),'unconfirmed');
    change(144,unverified,'rename','강제 변경');assert(!fs.existsSync(p('characters/강제 변경.png')));
    console.log('Character card actions: display and file rename, link guards, copy, collisions, stale checks, card-only trash and undo passed.');
};
