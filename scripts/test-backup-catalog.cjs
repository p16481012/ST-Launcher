const assert=require('assert/strict'), fs=require('fs'), os=require('os'), path=require('path'), {spawnSync}=require('child_process');
const helper=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/backup-catalog.sh'),'utf8').replace(/\r\n/g,'\n');
const code=helper.split("<<'NODE'\n")[1].split('\nNODE')[0];
const root=fs.mkdtempSync(path.join(os.tmpdir(),'st-catalog-test-'));
const name='SillyTavern-Launcher-20261124-000000.zip', source=path.join(root,name);
function run(action,arg,inject='',env={}) {return spawnSync(process.execPath,['-e',inject+code,'--','-',action,root,arg],{encoding:'utf8',env:{...process.env,...env},timeout:10000});}
function create(){fs.writeFileSync(source,'fixture'); return run('revision',name).stdout.trim();}
function request(rev,n=name){return Buffer.from(JSON.stringify([{name:n,revision:rev}])).toString('base64');}
try {
 let rev=create(); assert.match(rev,/^[a-f0-9]{64}$/);
 assert.equal(run('delete',request(rev)).status,0); assert(!fs.existsSync(source)); console.log('PASS exact selected backup removal');
 rev=create(); fs.appendFileSync(source,'changed'); assert.notEqual(run('delete',request(rev)).status,0); assert(fs.existsSync(source)); console.log('PASS stale revision preserves backup');
 rev=create(); assert.notEqual(run('delete',request(rev,'../'+name)).status,0); assert(fs.existsSync(source)); console.log('PASS traversal and malformed request rejected');
 assert.notEqual(run('delete',Buffer.from(JSON.stringify(Array(201).fill({name,revision:rev}))).toString('base64')).status,0);
 const cancel=path.join(root,'cancel'); fs.writeFileSync(cancel,'1'); assert.equal(run('delete',request(rev),'',{ST_CANCEL_REQUEST_FILE:cancel}).status,130); assert(fs.existsSync(source)); fs.unlinkSync(cancel); console.log('PASS cancellation before removal preserves source');
 const inject=`const catalogFs=require('fs'), originalUnlink=catalogFs.unlinkSync;
catalogFs.unlinkSync=function(file){if(String(file).endsWith('/backup.zip')||String(file).endsWith('\\\\backup.zip'))throw Object.assign(Error('fixture disk error'),{code:'EIO'});return originalUnlink.call(this,file);};\n`;
 const failed=run('delete',request(rev),inject); assert.notEqual(failed.status,0);
 const held=fs.readdirSync(root).filter(x=>x.startsWith('.st-cleanup-')); assert.equal(held.length,1); assert.equal(fs.readFileSync(path.join(root,held[0],'backup.zip'),'utf8'),'fixture'); assert(fs.existsSync(path.join(root,held[0],'RECOVER.txt'))); console.log('PASS removal failure preserves quarantined backup and recovery note');
 if(process.platform!=='win32') {
   const outside=path.join(root,'outside');fs.mkdirSync(outside);fs.writeFileSync(path.join(outside,'backup.zip'),'outside');
   rev=create();
   const swap=`const catalogFs=require('fs'), originalOpen=catalogFs.openSync;let swapped=false;
catalogFs.openSync=function(file,...args){if(!swapped&&String(file).includes('.st-cleanup-')){swapped=true;catalogFs.rmdirSync(file);catalogFs.symlinkSync(${JSON.stringify(outside)},file);}return originalOpen.call(this,file,...args);};\n`;
   assert.notEqual(run('delete',request(rev),swap).status,0);assert.equal(fs.readFileSync(path.join(outside,'backup.zip'),'utf8'),'outside');assert(fs.existsSync(source));console.log('PASS replaced quarantine path cannot redirect rename');
 } else console.log('NOTE quarantine symlink race is verified in Linux CI');
} finally {assert(path.basename(root).startsWith('st-catalog-test-')&&path.dirname(root)===os.tmpdir());fs.rmSync(root,{recursive:true,force:true});}
