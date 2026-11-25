const assert = require('node:assert/strict'), fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const {spawnSync} = require('node:child_process'), {createHash} = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname,'../app/src/main/assets/server-config.sh'),'utf8').replaceAll('\r\n','\n');
const code = source.split("<<'NODE'\n")[1].split('\nNODE')[0];
const yamlRoot = path.dirname(require.resolve('yaml/package.json',{paths:[process.env.ST_TEST_YAML_ROOT || process.cwd()]}));
const root = fs.mkdtempSync(path.join(os.tmpdir(),'st-conf-test-')), install = path.join(root,'install'), owner = path.join(root,'owner');
const cfg = path.join(install,'config.yaml'), def = path.join(install,'default','config.yaml');
const original = '# retained comment\ncustom: preserve-me\nbasicAuthUser:\n  password: never-expose-me\nbackups:\n  chat:\n    maxTotalBackups: 721\n';
let checks = 0;
function run(action,args=[],status=0,env={}) {
    const r=spawnSync(process.execPath,['-',action,install,owner,...args],{input:code,encoding:'utf8',env:{...process.env,...env},timeout:12000});
    assert.ifError(r.error); assert.equal(r.status,status,r.stderr);
    assert(!r.stdout.includes('never-expose-me') && !r.stderr.includes('never-expose-me'));
    return r.stdout;
}
const hash=bytes=>createHash('sha256').update(bytes).digest('hex'), b64=s=>Buffer.from(s).toString('base64');
function revision() {return run('read').match(/^config_revision=([0-9a-f]{64})/m)[1];}
function test(name,fn){fn(); console.log('PASS '+name); checks++;}
try {
    fs.mkdirSync(path.dirname(def),{recursive:true}); fs.mkdirSync(owner);
    fs.writeFileSync(path.join(install,'package.json'),'{"version":"1.19.1"}');
    fs.cpSync(yamlRoot,path.join(install,'node_modules/yaml'),{recursive:true});
    fs.writeFileSync(def,'thumbnails:\n  enabled: true\nbackups:\n  chat:\n    maxTotalBackups: -1\nenableCorsProxy: false\n');
    fs.writeFileSync(cfg,original);
    test('defaults, supported keys, custom quota and redacted output',()=>{
        const read=run('read'); assert(read.includes('thumbnails.enabled\t1\ttrue\ttrue\t0'));
        assert(read.includes('enableServerPlugins\t0\tnull\tnull\t0'));
        assert(read.includes('backups.chat.maxTotalBackups\t1\t721\t-1\t1'));
    });
    const first=revision(); let snapshot;
    test('YAML comments/order, missing key, unchanged credentials & byte-identical backup',()=>{
        const out=run('write',[first,b64('thumbnails.enabled=false,enableCorsProxy=true')]);
        snapshot=out.match(/^backup=(.+)$/m)[1];
        assert.equal(fs.readFileSync(path.join(owner,'backups/network-settings',snapshot),'utf8'),original);
        assert(fs.readFileSync(cfg,'utf8').startsWith(original)); assert(run('read').includes('thumbnails.enabled\t1\tfalse\ttrue\t1'));
    });
    test('reject stale and unsupported writes',()=>{
        run('write',[first,b64('thumbnails.enabled=true')],121);
        for(const entry of ['backups.chat.maxTotalBackups=7','enableCorsProxy=1','custom=false','enableServerPlugins=true','enableCorsProxy=true,enableCorsProxy=false'])
            run('write',[revision(),b64(entry)],122);
    });
    test('cancel before change preserves original',()=>{
        const before=fs.readFileSync(cfg);
        run('write',[revision(),b64('thumbnails.enabled=true')],130,{ST_CANCEL_REQUEST_FILE:def});
        assert.deepEqual(fs.readFileSync(cfg),before);
    });
    test('invalid YAML: recover a chosen snapshot and protect current bytes',()=>{
        fs.writeFileSync(cfg,'broken: ['); run('read',[],120);
        run('restore',[snapshot,'0'.repeat(64)],121);
        const restore=run('restore',[snapshot,hash(original)]); assert.equal(fs.readFileSync(cfg,'utf8'),original);
        assert.equal(fs.readFileSync(path.join(owner,'backups/network-settings',restore.match(/^backup=(.+)$/m)[1]),'utf8'),'broken: [');
    });
    test('quota usage ignores unrelated files and counts per-user backups',()=>{
        for(const user of ['amy','jo','_internal']){
            const dir=path.join(install,'data',user,'backups'); fs.mkdirSync(dir,{recursive:true});
            fs.writeFileSync(path.join(dir,'chat_a.jsonl'),'123'); fs.writeFileSync(path.join(dir,'x.jsonl'),'other');
        } const usage=run('usage'); assert(usage.includes('count=2\nbytes=6\ncomplete=1'));
        for(const user of ['amy','jo']) assert(usage.includes('user\t'+b64(user)+'\t1\t3'));
    });
    if(process.platform!=='win32') test('do not follow config symlink',()=>{
        fs.unlinkSync(cfg); fs.symlinkSync(def,cfg);
        run('read',[],120); assert(fs.lstatSync(cfg).isSymbolicLink());
    });
    console.log(checks+' server config fixtures passed');
} finally {fs.rmSync(root,{recursive:true,force:true});}
