// Real editor helper + curl + loopback, only a newly-created synthetic installation.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const http = require('node:http'), os = require('node:os'), crypto = require('node:crypto');
const {spawn} = require('node:child_process');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/manager.sh'), 'utf8');
const script = source.match(/<<'ST_LAUNCHER_FILES_JS'\r?\n([\s\S]*?)\r?\nST_LAUNCHER_FILES_JS/)[1];
const digest = data => crypto.createHash('sha256').update(data).digest('hex');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'st-private-edit-'));
const target = path.join(root, 'config.yaml'), cancel = path.join(root, 'cancel-receipt');
const token = crypto.randomBytes(32).toString('hex'), old = Buffer.from('original\n');
let handle, hits = 0;
const server = http.createServer((req, res) => {
    hits++;
    assert.equal(req.url, '/' + token);
    handle(res);
});
async function run(body, {status = 0, size = body.length, revision = digest(old), respond, after = status === 0 ? body : old, noRequest = false} = {}) {
    fs.writeFileSync(target, old);
    fs.rmSync(cancel, {force:true});
    const start = hits;
    handle = respond || (res => { res.writeHead(200, {'Content-Length': body.length}); res.end(body); });
    const args = ['-', root, root, root, root, '', 'write', Buffer.from('config.yaml').toString('base64'),
        revision, String(server.address().port), token, String(size)];
    const fixtureSecret = 'test-private-INPUT';
    for (const secret of [fixtureSecret, Buffer.from(fixtureSecret).toString('base64')]) assert(!args.join(' ').includes(secret));
    const child = spawn(process.execPath, args, {windowsHide:true, env:{...process.env, ST_CANCEL_REQUEST_FILE:cancel}});
    let stdout = '', stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk; }); child.stderr.on('data', chunk => { stderr += chunk; });
    const done = new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
    child.stdin.end(script);
    assert.equal(await done, status, stderr);
    assert.equal(hits - start, noRequest ? 0 : 1);
    assert(!stdout.includes(fixtureSecret) && !stderr.includes(fixtureSecret));
    assert(!stdout.includes(token) && !stderr.includes(token));
    assert.deepEqual(fs.readFileSync(target), after);
    assert.deepEqual(fs.readdirSync(root).filter(s => s.startsWith('.st-launcher-edit-')), []);
    if (!status) assert.match(stdout, /saved=1/);
}
(async () => {
    await new Promise((resolve, reject) => server.once('error',reject).listen(0,'127.0.0.1',resolve));
    const text = Buffer.from('password: test-private-INPUT\n한글\n\n');
    await run(text);
    await run(Buffer.alloc(0));
    await run(Buffer.alloc(65536, 97));
    await run(Buffer.alloc(65537), {status:64, noRequest:true});
    await run(text, {status:42, revision:'1'.repeat(64), noRequest:true});
    await run(text, {status:45, size:text.length + 1});
    await run(Buffer.from([0, 255]), {status:41});
    await run(text, {status:45, respond:res => { res.writeHead(403); res.end(); }});
    await run(text, {status:45, respond:res => { res.writeHead(200, {'Content-Length': text.length + 9}); res.end(text); }});
    await run(text, {status:130, respond:res => { fs.writeFileSync(cancel, 'requested'); res.end(text); }});
    const newer = Buffer.from('external edit');
    await run(text, {status:42, respond:res => { fs.writeFileSync(target, newer); res.end(text); }, after:newer});
    console.log('PASS: private file transfer, empty and 64 KiB limits, failed/partial reads, cancellation and concurrent edits');
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => {
    server.close(); fs.rmSync(root, {recursive:true, force:true});
});
