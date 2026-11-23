// Small compressed ZIPs for bounded manifest-reader tests; no external packages.
const fs = require('fs');
const zlib = require('zlib');
const [output, kind = 'valid'] = process.argv.slice(2);
const prefix = 'format=st-launcher-backup-v1\nitems=user_data\nsecrets=0\ncustom=한글\n';
let payload = Buffer.from(prefix);
if (kind === 'limit' || kind === 'oversized') {
    payload = Buffer.concat([payload, Buffer.alloc((kind === 'limit' ? 65536 : 1048576) - payload.length, 0x20)]);
}
if (kind === 'nul') payload = Buffer.concat([payload, Buffer.from([0])]);
const table = Array.from({ length: 256 }, (_, value) => {
    for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
    return value >>> 0;
});
let checksum = 0xffffffff;
for (const byte of payload) checksum = (checksum >>> 8) ^ table[(checksum ^ byte) & 255];
checksum = (checksum ^ 0xffffffff) >>> 0;
const packed = zlib.deflateRawSync(payload), name = Buffer.from('.st-launcher-manifest');
const locals = [], centrals = [], count = kind === 'duplicate' ? 2 : 1;
let offset = 0;
for (let index = 0; index < count; index++) {
    const local = Buffer.alloc(30), central = Buffer.alloc(46);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(8, 8);
    if (kind === 'encrypted') local.writeUInt16LE(1, 6);
    local.writeUInt32LE(checksum, 14); local.writeUInt32LE(packed.length, 18); local.writeUInt32LE(payload.length, 22);
    local.writeUInt16LE(name.length, 26);
    central.writeUInt32LE(0x02014b50); central.writeUInt16LE(0x314, 4); central.writeUInt16LE(20, 6);
    if (kind === 'encrypted') central.writeUInt16LE(1, 8);
    central.writeUInt16LE(8, 10); central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(packed.length, 20); central.writeUInt32LE(payload.length, 24); central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE((0x81a4 * 65536) >>> 0, 38); central.writeUInt32LE(offset, 42);
    locals.push(local, name, packed); centrals.push(central, name); offset += 30 + name.length + packed.length;
}
const directory = Buffer.concat(centrals), end = Buffer.alloc(22);
end.writeUInt32LE(0x06054b50); end.writeUInt16LE(count, 8); end.writeUInt16LE(count, 10);
end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
const archive = Buffer.concat([...locals, directory, end]);
if (kind === 'corrupt') archive[30 + name.length + 5] ^= 1;
fs.writeFileSync(output, archive);
