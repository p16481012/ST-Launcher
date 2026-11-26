// Run the production helper against isolated profiles, without needing Android.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {spawnSync}=require('node:child_process');
const helper=path.join(__dirname,'../app/src/main/assets/library-preview.sh');
const script=fs.readFileSync(helper,'utf8').split("<<'PREVIEW_JS'")[1].split(/\r?\nPREVIEW_JS/)[0];
const lib=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/library-data.sh'),'utf8').split("<<'LIBRARY_JS'")[1].split(/\r?\nLIBRARY_JS/)[0];
const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'st-peek-'))),user='default-user',profile=path.join(root,'data',user);
const b64=s=>Buffer.from(s).toString('base64'),dec=s=>Buffer.from(s,'base64').toString('utf8');
function put(r,body){const p=path.join(profile,r);fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,typeof body==='string'?body:JSON.stringify(body));return p;}
function call(r,rev,u=user){
    const args=[root,b64(u),b64(r),rev],opts={encoding:'utf8',timeout:6000};
    const a=process.platform==='win32'?spawnSync(process.execPath,['-',...args],{...opts,input:script}):spawnSync('bash',[helper,...args],opts);
    assert(!a.error);return a;
}
function revision(kind,r){
    const a=spawnSync(process.execPath,['-','library-list',root,root,root,b64(user),kind,''],{input:lib,encoding:'utf8',timeout:6000});
    assert.equal(a.status,0,a.stderr);
    return a.stdout.split(/\r?\n/).map(s=>s.split('\t')).find(c=>c[0]==='item'&&dec(c[1])===r)[6];
}
function peek(r,kind){
    const result=call(r,revision(kind,r));assert.equal(result.status,0,result.stderr);assert(result.stdout.length<32*1024);
    const lines=result.stdout.trimEnd().split(/\r?\n/).map(l=>l.split('\t')),footer=lines.pop();assert.equal(footer[0],'preview');
    return {rows:lines.map(c=>({title:dec(c[1]),summary:dec(c[2]),excerpt:dec(c[3]),argb:c[4]})),omitted:+footer[1],notice:dec(footer[2]||'')};
}
try {
    const p='OpenAI Settings/A.json';put(p,{temperature:0.7,openai_max_tokens:500,prompts:[{name:'First',content:'PRIVATE-LONG-BODY'}],proxy_password:'PRIVATE-TOKEN'});
    const preset=peek(p,'presets');assert(preset.rows.some(x=>x.title==='온도'&&x.summary==='0.7'));
    assert(preset.rows.some(x=>x.title==='First'));assert(!JSON.stringify(preset).includes('PRIVATE'));
    const w='worlds/W.json',para='한글📚'.repeat(200);put(w,{entries:Object.fromEntries(Array.from({length:18},(_,i)=>[i,{comment:'W'+i,key:['태그'],content:para}]))});
    const world=peek(w,'worlds');assert.equal(world.rows.length,12);assert.equal(world.omitted,6);
    assert.equal(world.rows[0].summary,'태그');assert.equal(Array.from(world.rows[0].excerpt).length,256);assert(world.rows[0].excerpt.endsWith('…'));
    const q='QuickReplies/X.json';put(q,{qrList:[{label:'Say',message:'/echo hello\n/another',other:'PRIVATE'}]});
    assert.equal(peek(q,'quickreplies').rows[0].excerpt,'/echo hello /another');
    put(q,{quickReplySlots:[{label:'Legacy',mes:'hello'}]});assert.equal(peek(q,'quickreplies').rows[0].excerpt,'hello');
    const t='themes/T.json';put(t,{main_text_color:'#1238',chat_tint_color:'rgba(20, 30, 40, .5)',quote_text_color:'var(--INJECT)',custom_css:'PRIVATE-CSS'});
    const theme=peek(t,'themes');assert.deepEqual(theme.rows.map(x=>x.argb),['88112233','80141E28']);assert(!JSON.stringify(theme).includes('PRIVATE'));
    const old=revision('themes',t);put(t,'{');assert.equal(call(t,old).status,145);
    const malformed=call(t,revision('themes',t));assert.equal(malformed.status,144);assert.equal(malformed.stdout,'');
    put(w,' '.repeat(2*1024*1024+1));const heavy=peek(w,'worlds');assert(!heavy.rows.length&&heavy.notice.includes('2 MiB'));
    const zeros='0'.repeat(64);
    assert.equal(call('../settings.json',zeros).status,64);assert.equal(call(t,zeros,'../default-user').status,64);
    assert.equal(call('other/T.json',zeros).status,64);
    if(process.platform!=='win32'){
        fs.symlinkSync(path.join(profile,'themes','T.json'),path.join(profile,'themes','Link.json'));
        assert.equal(call('themes/Link.json',zeros).status,145);
        fs.symlinkSync(profile,path.join(root,'data','guest'));assert.equal(call(t,zeros,'guest').status,145);
    }
    console.log('Asset preview: live list revision, summaries, strict scope, bounded rows/bytes, inert text/colors, stale and malformed input passed.');
} finally {fs.rmSync(root,{recursive:true,force:true});}
