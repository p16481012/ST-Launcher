// Tiny intentionally invalid ZIPs for the sandbox regression suite only.
const fs = require('fs');
const [kind, output] = process.argv.slice(2);
const entries = kind === 'duplicate' ? ['same.txt', 'same.txt'] : [kind === 'traversal' ? '../escape.txt' : 'safe.txt'];
const locals = [], centrals = [];
let offset = 0;
for (const path of entries) {
    const name = Buffer.from(path), payload = Buffer.from('fixture');
    const flags = kind === 'encrypted' ? 1 : 0;
    const extra = kind === 'unicode' ? Buffer.concat([Buffer.from([0x75, 0x70, 13, 0, 1, 0, 0, 0, 0]), Buffer.from('../x.txt')]) : Buffer.alloc(0);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4);
    local.writeUInt16LE(flags, 6); local.writeUInt16LE(kind === 'expanded-limit' ? 8 : 0, 8);
    local.writeUInt32LE(payload.length, 18); local.writeUInt32LE(payload.length, 22);
    local.writeUInt16LE(name.length, 26); local.writeUInt16LE(extra.length, 28);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(0x0314, 4); central.writeUInt16LE(20, 6);
    central.writeUInt16LE(flags, 8); central.writeUInt16LE(kind === 'expanded-limit' ? 8 : 0, 10);
    central.writeUInt32LE(payload.length, 20); central.writeUInt32LE(kind === 'size-mismatch' ? payload.length + 1 : payload.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(((kind === 'symlink' ? 0xa1ff : 0x81a4) << 16) >>> 0, 38);
    central.writeUInt32LE(offset, 42);
    locals.push(local, name, extra, payload); centrals.push(central, name);
    offset += local.length + name.length + extra.length + payload.length;
}
const centralBytes = Buffer.concat(centrals), end = Buffer.alloc(22);
end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
end.writeUInt32LE(centralBytes.length, 12); end.writeUInt32LE(offset, 16);
// CRC deliberately remains zero: the CRC fixture must pass structural checks
// and fail during its one extraction, while other fixtures fail beforehand.
fs.writeFileSync(output, Buffer.concat([...locals, centralBytes, end]));
