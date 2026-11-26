'use strict';
// Kill the actual rename worker at its filesystem publication boundaries.
// Nothing here relies on product-only fault-injection flags.
module.exports=function pairedRenameRecovery({base,home,owner,as,p,put,read,card,usage,invoke,b64,change,inode,bytes}){
    const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
    const child=(code,expected)=>assert.equal(code.status,expected,[code.error,code.stdout,code.stderr].join('\n'));
    const exists=r=>fs.existsSync(p(r)),journal=path.join(owner,'card-rename-pending.json');
    const repair=()=>invoke([],as,'library-card-recover');
    const source=(stem)=>'characters/'+stem+'.png',dir=(stem)=>'chats/'+stem,chat=(stem)=>dir(stem)+'/log.jsonl';
    const create=stem=>{put(source(stem),bytes);put(chat(stem),'{"chat_metadata":{"integrity":"'+stem+'"}}\n{"mes":"preserve me"}\n');return card(stem);};
    function fault(stem,target,at,after,act){
        const injected=path.join(base,'card-fault.cjs');
        const payload=act||'process.exit(85);';
        fs.writeFileSync(injected,`const fs=require('fs'),cp=require('child_process'),move=cp.execFileSync;
        let calls=0;cp.execFileSync=function(exe,...a){const n=exe==='mv'?++calls:0;
        if(n===${at}&&${!after}){${payload}}
        const r=move.call(this,exe,...a);if(n===${at}&&${after}){${payload}}return r;};`);
        const row=card(stem);
        return invoke(['-r',injected],as,'library-card-change',b64(row.path),row.revision,'rename',b64(target));
    }
    for(const [i,at,after] of [[1,1,false],[2,1,true],[3,2,true],[4,3,true]]){
        const old='CrashOld'+i,next='CrashNew'+i;create(old);
        const content=read(chat(old)),ids=inode(dir(old)),fileId=inode(chat(old));
        child(fault(old,next,at,after),85);
        assert(fs.existsSync(journal),'Power loss must leave a durable, recoverable pending marker.');
        child(repair(),0);child(repair(),0);
        assert(!fs.existsSync(journal),'Idempotent recovery must retire a resolved marker.');
        const restored=exists(source(old));const live=restored?old:next,other=restored?next:old;
        assert(exists(source(live))&&exists(chat(live)),'No half-published card/chat pair after recovery.');
        assert(!exists(source(other))&&!exists(dir(other)),'Resolved journal must leave no ambiguous card identity.');
        assert.deepEqual(inode(dir(live)),ids);assert.deepEqual(inode(chat(live)),fileId);assert.deepEqual(read(chat(live)),content);
        if(restored){assert.deepEqual(read(source(old)),bytes);change(0,card(old),'rename',next);}
        assert.equal(card(next).label,next);assert.deepEqual(read(chat(next)),content);assert.deepEqual(inode(dir(next)),ids);
    }
    const old='LiveConflict',next='NewConflict';create(old);const priorID=inode(dir(old)),original=read(chat(old));
    child(fault(old,next,2,true),85);
    // Another actor has recreated the old directory: do not merge, overwrite or discard either copy.
    put(chat(old),'unrelated recreated folder');const collision=repair();
    assert(collision.status!==0);assert(fs.existsSync(journal));assert.equal(read(chat(old)).toString(),'unrelated recreated folder');
    assert.deepEqual(inode(dir(next)),priorID);assert.deepEqual(read(chat(next)),original);
    fs.renameSync(p(dir(old)),p('chats/conflict-saved-by-user'));child(repair(),0);
    assert.deepEqual(read(chat(exists(source(old))?old:next)),original);assert(!fs.existsSync(journal));
    const late=create('LateDest'),plan=late.revision;
    put(chat('TakenAtConfirm'),'must not merge');const targetChanged=invoke([],as,'library-card-change',b64(late.path),plan,'rename',b64('TakenAtConfirm'));
    assert.notEqual(targetChanged.status,0);assert.equal(read(chat('TakenAtConfirm')).toString(),'must not merge');assert(exists(source('LateDest')));
    create('IOError');const expected=read(chat('IOError'));
    const broken=fault('IOError','IOErrorNew',2,false,"throw Object.assign(new Error('fixture write error'),{code:'EIO'});");
    assert.notEqual(broken.status,0);assert.deepEqual(read(source('IOError')),bytes);assert.deepEqual(read(chat('IOError')),expected);
    assert(!exists(source('IOErrorNew'))&&!exists(dir('IOErrorNew'))&&!fs.existsSync(journal));
    create('CancelPair');const cancelFile=path.join(base,'cancel-pair-request'),cancelledChat=read(chat('CancelPair'));
    const cancelled=fault('CancelPair','CancelledDestination',1,true,
        'process.env.ST_CANCEL_REQUEST_FILE='+JSON.stringify(cancelFile)+';fs.writeFileSync(process.env.ST_CANCEL_REQUEST_FILE,"1");');
    child(cancelled,130);assert.deepEqual(read(source('CancelPair')),bytes);assert.deepEqual(read(chat('CancelPair')),cancelledChat);
    assert(!exists(source('CancelledDestination'))&&!exists(dir('CancelledDestination'))&&!fs.existsSync(journal));
    // Another profile with identically named cards/chats must stay untouched.
    const guest=path.join(home,'data','other-profile');fs.mkdirSync(path.join(guest,dir('LateDest')),{recursive:true});
    fs.mkdirSync(path.join(guest,'characters'),{recursive:true});fs.writeFileSync(path.join(guest,source('LateDest')),'foreign');
    fs.writeFileSync(path.join(guest,chat('LateDest')),'foreign chat');
    change(0,card('LateDest'),'rename','LateNew');
    assert.equal(fs.readFileSync(path.join(guest,source('LateDest')),'utf8'),'foreign');
    assert.equal(fs.readFileSync(path.join(guest,chat('LateDest')),'utf8'),'foreign chat');assert(!fs.existsSync(path.join(guest,source('LateNew'))));
    if(process.platform!=='win32'){
        const outside=path.join(base,'outside-card-chat');fs.mkdirSync(outside);fs.writeFileSync(path.join(outside,'private.jsonl'),'outside');
        put(source('UnsafeLink'),bytes);fs.symlinkSync(outside,p(dir('UnsafeLink')));
        const unsafe=card('UnsafeLink');assert.notEqual(usage(unsafe),'none');const result=invoke([],as,'library-card-change',b64(unsafe.path),unsafe.revision,'rename',b64('UnsafeMoved'));
        assert.notEqual(result.status,0);assert(!exists(source('UnsafeMoved')));assert(fs.lstatSync(p(dir('UnsafeLink'))).isSymbolicLink());
        fs.symlinkSync(outside,p(dir('UnsafeDest')));const safe=create('SafeSource');change(145,safe,'rename','UnsafeDest');
        assert(!exists(source('UnsafeDest')));assert.equal(fs.readFileSync(path.join(outside,'private.jsonl'),'utf8'),'outside');
    }
    console.log('Coupled card/chat rename: inode and byte preservation, interrupted publication, retry, I/O failure and conflict-safe recovery passed.');
};
