'use strict';
// Real central-directory validation, preview, recheck, extraction and apply.
module.exports=({root,st,arc,ix,fixture,write,preview,run,scalar,inline,extract,map})=>{
    const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
    const profile=path.join(st,'data','default-user'),put=(r,v)=>write(path.join(profile,r),v);
    const G=(id,members,chats,disabled=[])=>JSON.stringify({id,members,disabled_members:disabled,chat_id:chats[0],chats});
    const rows=[['characters/A.png','fake!'],['chats/A/a.jsonl','oldPC'],
        ['characters/Orphan.png','no'],['chats/Orphan/other.jsonl','do not join'],
        ['characters/Empty.png','new'],['chats/Empty/pc.jsonl','empty folder ok'],
        ['characters/Same.png','same'],['chats/Same/pc.jsonl','same card chat'],['characters/Free.png','free'],
        ['User Avatars/me.png','avatar'],['settings.json','{"personas":{"me.png":"Rin"}}'],
        ['characters/Free/expression.png','expression'],['user/images/chat.png','image'],['extensions/pkg/x.js','extension'],
        ['groups/Affected.json',G('Affected',['A.png'],['bad','bad-2'])],
        ['groups/Disabled.json',G('Disabled',['Free.png'],['disabled'],['A.png'])],
        ['groups/Orphan.json',G('Orphan',['Orphan.png'],['orphan-g'])],
        ['groups/Good.json',G('Good',['Free.png','Same.png','Empty.png'],['ok1','ok2'])],
        ['groups/Keep.json',G('Keep',['Same.png'],['keep'])],
        ['groups/Changed.json',G('Changed',['Same.png'],['changed'])],
        ['groups/Broken.json','not-json'],['groups/NoOwner.json',G('NoOwner',['missing.png'],['no-owner'])],
        ['groups/One.json',G('One',[],['shared'])],['groups/Two.json',G('Two',[],['shared'])],
        ['groups/OtherClaim.json',G('OtherClaim',[],['phone-claims'])],['groups/Lone.json',G('Lone',[],['lone'])],
    ];
    const groupChats=['bad','bad-2','disabled','orphan-g','ok1','ok2','keep','changed','Broken','no-owner','shared','phone-claims','lone','unknown-owner'];
    for(const s of groupChats)rows.push(['group chats/'+s+'.jsonl','PC '+s]);
    const wrapped=rows.map(([r,v])=>['data/default-user/'+r,v]);
    // Same names in another profile must not borrow the blocked relationships.
    wrapped.push(['data/pc/characters/A.png','other'],['data/pc/groups/Affected.json',G('Affected',['A.png'],['bad'])],
        ['data/pc/group chats/bad.jsonl','other PC']);
    put('characters/A.png','phone');put('characters/Same.png','same');put('chats/Orphan/prior.jsonl','old phone');
    fs.mkdirSync(path.join(profile,'chats/Empty'),{recursive:true});
    put('groups/Keep.json',G('Keep',['Same.png'],['keep']));put('groups/Changed.json',G('Changed',[],['old-id']));
    put('groups/Phone.json',G('Phone',[],['phone-claims']));put('group chats/lone.jsonl','old lone chat');
    fixture(wrapped);
    const mapping=map({'default-user':'default-user','pc':'work'}),res=preview(mapping);
    assert.ok(res.includes('relation\tcharacters\t1'));assert.ok(res.includes('relation\tchats\t2'));
    assert.ok(res.includes('relation\tgroups\t9'));assert.ok(res.includes('relation\tgroup_chats\t10'));
    assert.equal(scalar(res,'relationship_skipped'),'22');assert.ok(res.includes('kind\tavatars\t1\t0'));
    for(const k of ['settings','expressions','chat_images','extensions'])assert.ok(res.includes('excluded\t'+k+'\t1'),k);
    assert.equal(scalar(res,'excluded_count'),'4');
    const stamp=scalar(res,'archive_revision'),target=scalar(res,'installation_revision');
    const omit={ST_PC_INCLUDE_AVATARS:'0'},without=run(['plan',st,arc,ix,mapping,stamp],0,omit);
    assert.equal(scalar(res,'avatars_included'),'1');assert.equal(scalar(without,'avatars_included'),'0');
    assert.equal(scalar(without,'candidate_avatars'),'1');assert.equal(scalar(without,'excluded_count'),'5');
    assert.ok(without.includes('excluded\tavatars\t1'));assert.equal(+scalar(without,'add_count'),+scalar(res,'add_count')-1);
    const withoutTarget=scalar(without,'installation_revision');assert.notEqual(withoutTarget,target);
    run(['recheck',st,arc,ix,mapping,stamp,target],73,omit);run(['recheck',st,arc,ix,mapping,stamp,withoutTarget],73);
    const stage=path.join(root,'without-persona');fs.mkdirSync(stage);
    run(['plan',st,arc,ix,mapping,stamp,withoutTarget,stage,'9999999999'],0,omit);
    assert.ok(JSON.parse(fs.readFileSync(path.join(stage,'selected.json'))).entries.every(e=>!e.path.includes('User Avatars/')));
    // Both reverse ownership checks must be repeated after the user confirms.
    put('chats/Empty/late.jsonl','created later');run(['recheck',st,arc,ix,mapping,stamp,target],73);
    fs.unlinkSync(path.join(profile,'chats/Empty/late.jsonl'));
    put('groups/Late.json',G('Late',[],['ok1']));run(['recheck',st,arc,ix,mapping,stamp,target],73);
    fs.unlinkSync(path.join(profile,'groups/Late.json'));
    const box=path.join(root,'relationships'),work=path.join(box,'pc-merge-work-224'),pending=path.join(box,'pc-merge-pending');
    fs.mkdirSync(path.join(work,'files'),{recursive:true});fs.mkdirSync(path.join(work,'guard'));
    run(['plan',st,arc,ix,mapping,stamp,target,work,'9999999999']);
    inline(extract,['extract',arc,path.join(work,'files'),path.join(work,'selected.json')]);
    run(['prepare',st,work]);run(['recheck',st,arc,ix,mapping,stamp,target]);fs.renameSync(path.join(work,'guard'),pending);run(['apply',st,pending]);
    for(const r of ['characters/Free.png','characters/Empty.png','groups/Good.json','chats/Same/pc.jsonl',
        'group chats/ok1.jsonl','group chats/ok2.jsonl','group chats/keep.jsonl','chats/Empty/pc.jsonl','User Avatars/me.png']){
        assert.ok(fs.existsSync(path.join(profile,r)),r);
    }
    for(const r of ['characters/Orphan.png','chats/A/a.jsonl','chats/Orphan/other.jsonl','groups/Affected.json','groups/Disabled.json',
        'group chats/bad.jsonl','group chats/disabled.jsonl','group chats/shared.jsonl','group chats/changed.jsonl','group chats/no-owner.jsonl',
        'groups/Orphan.json','groups/Lone.json','group chats/unknown-owner.jsonl','settings.json'])assert.ok(!fs.existsSync(path.join(profile,r)),r);
    assert.equal(fs.readFileSync(path.join(profile,'characters/A.png'),'utf8'),'phone');
    assert.equal(fs.readFileSync(path.join(profile,'chats/Orphan/prior.jsonl'),'utf8'),'old phone');
    assert.equal(fs.readFileSync(path.join(profile,'group chats/lone.jsonl'),'utf8'),'old lone chat');
    assert.equal(fs.readFileSync(path.join(st,'data/work/group chats/bad.jsonl'),'utf8'),'other PC');
    console.log('PC ZIP relationships: independent groups/profiles, disabled members, malformed or ambiguous owners, verified matches, orphan protection, persona-only scope and commit-time rechecks passed.');
};
