// Bounded read-only preview and selected-file revision tests; isolated fixture only.
const fs = require('fs'), path = require('path'), os = require('os'), assert = require('assert');
const zlib = require('zlib'), { spawnSync } = require('child_process');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/manager.sh'), 'utf8');
const code = source.match(/<<'ST_LAUNCHER_FILES_JS'\r?\n([\s\S]*?)\r?\nST_LAUNCHER_FILES_JS/)[1];
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'st-file-preview-'));
const install = path.join(root, 'install'), launcher = path.join(root, 'launcher'), downloads = path.join(root, 'downloads');
for (const folder of [install, launcher, downloads]) fs.mkdirSync(folder);
const b64 = value => Buffer.from(value).toString('base64');
function run(action, relative, value = '', expected = 0) {
    const result = spawnSync(process.execPath, ['-', install, launcher, downloads, root, '', action, b64(relative), value], { input: code, encoding: 'utf8' });
    assert.strictEqual(result.status, expected, result.stderr || result.stdout);
    assert(Buffer.byteLength(result.stdout) < 40 * 1024, 'callback exceeded budget');
    return result.stdout;
}
const fields = value => Object.fromEntries(value.trimEnd().split(/\r?\n/).map(line => [line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1)]));
function chunk(type, bytes) {
    const name = Buffer.from(type), header = Buffer.alloc(8), tail = Buffer.alloc(4);
    header.writeUInt32BE(bytes.length); name.copy(header, 4);
    let crc = 0xffffffff;
    for (const byte of Buffer.concat([name, bytes])) { crc ^= byte; for (let i = 0; i < 8; i++) crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1; }
    tail.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
    return Buffer.concat([header, bytes, tail]);
}
function png(width, height, metadata = []) {
    const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6;
    return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', header), ...metadata,
        chunk('IDAT', zlib.deflateSync(Buffer.from([0,255,0,0,255]))), chunk('IEND', Buffer.alloc(0))]);
}
try {
    fs.writeFileSync(path.join(install, 'small.txt'), '작은 파일\n');
    let result = fields(run('preview', 'small.txt'));
    assert.strictEqual(Buffer.from(result.content_b64, 'base64').toString(), '작은 파일\n');
    assert.strictEqual(result.truncated, '0');
    const large = 'a'.repeat(24575) + '한'.repeat(1000);
    fs.writeFileSync(path.join(install, 'large.txt'), large);
    result = fields(run('preview', 'large.txt'));
    assert.strictEqual(result.truncated, '1');
    assert.strictEqual(Buffer.from(result.content_b64, 'base64').toString(), 'a'.repeat(24575));
    assert.strictEqual(fs.readFileSync(path.join(install, 'large.txt'), 'utf8'), large);
    fs.writeFileSync(path.join(install, 'binary'), Buffer.from([0,1,2]));
    run('preview', 'binary', '', 41);
    fs.writeFileSync(path.join(install, 'badutf8.txt'), Buffer.from([0xff]));
    run('preview', 'badutf8.txt', '', 41);
    run('preview', '../outside', '', 64);
    fs.mkdirSync(path.join(install, 'folder'));
    run('preview', 'folder', '', 41);
    console.log('PASS text previews are bounded, UTF-8 complete, read-only and path-safe');

    fs.writeFileSync(path.join(install, 'tiny.png'), png(1, 1));
    result = fields(run('preview', 'tiny.png'));
    assert.strictEqual(result.preview_kind, 'png');
    assert.deepStrictEqual(Buffer.from(result.content_b64, 'base64'), fs.readFileSync(path.join(install, 'tiny.png')));
    const card = b64(JSON.stringify({ spec: 'chara_card_v2', data: { name: '카드\u0001\u202e', description: '설명'.repeat(10000), system_prompt: 'must not appear' } }));
    fs.writeFileSync(path.join(install, 'card.png'), png(1024, 1024, [chunk('tEXt', Buffer.from('chara\0' + card))]));
    result = fields(run('preview', 'card.png'));
    assert.strictEqual(result.preview_kind, 'card');
    assert.strictEqual(Buffer.from(result.name_b64, 'base64').toString(), '카드');
    assert(Buffer.from(result.description_b64, 'base64').length <= 8192);
    assert(!JSON.stringify(result).includes('must not appear'));
    const compressed = Buffer.concat([Buffer.from('chara\0'), Buffer.from([1,0,0,0]), zlib.deflateSync(Buffer.from(b64(JSON.stringify({ name: 'compressed', description: 'okay' }))))]);
    fs.writeFileSync(path.join(install, 'itxt.png'), png(1000, 1000, [chunk('iTXt', compressed)]));
    result = fields(run('preview', 'itxt.png'));
    assert.strictEqual(Buffer.from(result.name_b64, 'base64').toString(), 'compressed');
    const bomb = Buffer.concat([Buffer.from('chara\0'), Buffer.from([0]), zlib.deflateSync(Buffer.alloc(2 * 1024 * 1024, 65))]);
    fs.writeFileSync(path.join(install, 'bomb.png'), png(1000, 1000, [chunk('zTXt', bomb)]));
    result = fields(run('preview', 'bomb.png'));
    assert.strictEqual(result.preview_kind, 'card'); assert.strictEqual(result.name_b64, '');
    fs.writeFileSync(path.join(install, 'huge.png'), Buffer.alloc(0)); fs.truncateSync(path.join(install, 'huge.png'), 8 * 1024 * 1024 + 1);
    run('preview', 'huge.png', '', 40);
    fs.writeFileSync(path.join(install, 'many.png'), png(1024, 1024, Array.from({length:2049}, () => chunk('tEXt', Buffer.from('x\0y')))));
    run('preview', 'many.png', '', 40);
    console.log('PASS PNG byte/pixel/chunk/metadata limits and compressed metadata bomb rejection');

    const listing = run('list', '', '0');
    const row = listing.split(/\r?\n/).map(line => line.split('\t')).find(parts => parts[0] === 'entry' && Buffer.from(parts[3], 'base64').toString() === 'small.txt');
    assert.strictEqual(row.length, 8); assert(/^[a-f0-9]{64}$/.test(row[7]));
    fs.appendFileSync(path.join(install, 'small.txt'), 'changed');
    run('trash', 'small.txt', row[7], 42);
    assert(fs.existsSync(path.join(install, 'small.txt')));
    run('trash', 'small.txt', 'invalid', 42);
    console.log('PASS selected-file revisions reject stale and invalid trash requests');
} finally { fs.rmSync(root, { recursive: true, force: true }); }
