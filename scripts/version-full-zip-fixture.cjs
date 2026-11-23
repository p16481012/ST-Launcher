// Test-only ZIP generator for isolated miniature Git installations; no dependency.
const fs = require('fs'), path = require('path'), zlib = require('zlib');
const [root, output] = process.argv.slice(2);
if (!root || !output || !path.resolve(root).includes('st-version-flow.')) throw Error('fixture scope required');
const table = Array.from({ length: 256 }, (_, n) => { for (let i = 0; i < 8; i++) n = (n >>> 1) ^ ((n & 1) ? 0xedb88320 : 0); return n >>> 0; });
const crc = b => { let n = 0xffffffff; for (const v of b) n = (n >>> 8) ^ table[(n ^ v) & 255]; return (n ^ 0xffffffff) >>> 0; };
const input = [];
function walk(relative = '') {
    for (const entry of fs.readdirSync(path.join(root, relative), { withFileTypes: true })) {
        if (entry.name === 'node_modules' || entry.name === '.st-launcher-manifest') continue;
        const name = relative ? `${relative}/${entry.name}` : entry.name;
        if (entry.isDirectory()) { input.push([name + '/', Buffer.alloc(0), true]); walk(name); }
        else if (entry.isFile()) input.push([name, fs.readFileSync(path.join(root, name)), false]);
        else throw Error('unsupported fixture type');
    }
}
walk(); input.push(['.st-launcher-manifest', Buffer.from('format=st-launcher-backup-v1\nitems=full\nsecrets=1\n'), false]);
if (input.length > 1000 || input.reduce((sum, row) => sum + row[1].length, 0) > 16 * 1024 * 1024) throw Error('fixture size limit');
let offset = 0; const blocks = [], central = [];
for (const [name, contents, directory] of input) {
    const filename = Buffer.from(name), packed = zlib.deflateRawSync(contents), checksum = crc(contents), local = Buffer.alloc(30), h = Buffer.alloc(46);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6); local.writeUInt16LE(8, 8); local.writeUInt32LE(checksum, 14); local.writeUInt32LE(packed.length, 18); local.writeUInt32LE(contents.length, 22); local.writeUInt16LE(filename.length, 26);
    h.writeUInt32LE(0x02014b50); h.writeUInt16LE(0x314, 4); h.writeUInt16LE(20, 6); h.writeUInt16LE(0x800, 8); h.writeUInt16LE(8, 10); h.writeUInt32LE(checksum, 16); h.writeUInt32LE(packed.length, 20); h.writeUInt32LE(contents.length, 24); h.writeUInt16LE(filename.length, 28); h.writeUInt32LE(((directory ? 0x41ed : 0x81a4) * 65536) >>> 0, 38); h.writeUInt32LE(offset, 42);
    blocks.push(local, filename, packed); central.push(h, filename); offset += local.length + filename.length + packed.length;
}
const tableBytes = Buffer.concat(central), end = Buffer.alloc(22);
end.writeUInt32LE(0x06054b50); end.writeUInt16LE(input.length, 8); end.writeUInt16LE(input.length, 10); end.writeUInt32LE(tableBytes.length, 12); end.writeUInt32LE(offset, 16);
fs.writeFileSync(output, Buffer.concat([...blocks, tableBytes, end]), { flag: 'wx' });
