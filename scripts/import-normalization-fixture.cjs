// Deterministic Store-ZIP fixture writer; no dependencies or external commands.
// Used only by sandbox tests, including hosts without Info-ZIP's zip command.
const fs = require('fs');
const path = require('path');
const [archive, root, ...arguments_] = process.argv.slice(2);
const exclusionStart = arguments_.indexOf('-x');
const selection = exclusionStart < 0 ? arguments_ : arguments_.slice(0, exclusionStart);
const excludeSecrets = exclusionStart >= 0 && arguments_.slice(exclusionStart + 1).includes('data/*/secrets.json');
const entries = new Map();
if (fs.existsSync(archive)) {
    const previous = fs.readFileSync(archive);
    let offset = 0;
    while (previous.readUInt32LE(offset) === 0x04034b50) {
        if (previous.readUInt16LE(offset + 8) !== 0) throw Error('Fixture writer can only update its own Store ZIP');
        const size = previous.readUInt32LE(offset + 18), length = previous.readUInt16LE(offset + 26);
        const name = previous.subarray(offset + 30, offset + 30 + length).toString('utf8');
        const start = offset + 30 + length + previous.readUInt16LE(offset + 28);
        entries.set(name, Buffer.from(previous.subarray(start, start + size)));
        offset = start + size;
    }
}
function visit(relative) {
    const name = relative.split(path.sep).join('/').replace(/^\.\//, '');
    if (name.split('/').includes('node_modules') || (excludeSecrets && /^data\/[^/]+\/secrets\.json$/.test(name))) return;
    const target = path.join(root, relative), stat = fs.lstatSync(target);
    if (stat.isSymbolicLink()) throw Error('No symlinks in normalization fixtures');
    if (stat.isDirectory()) {
        if (name !== '.' && name !== '') entries.set(`${name}/`, Buffer.alloc(0));
        for (const child of fs.readdirSync(target).sort()) visit(path.join(relative === '.' ? '' : relative, child));
    } else if (stat.isFile()) entries.set(name, fs.readFileSync(target));
    else throw Error('No special files in normalization fixtures');
}
for (const relative of selection) visit(relative);
const table = Array.from({ length: 256 }, (_, value) => {
    for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
    return value >>> 0;
});
const crc = value => {
    let result = 0xffffffff;
    for (const byte of value) result = (result >>> 8) ^ table[(result ^ byte) & 255];
    return (result ^ 0xffffffff) >>> 0;
};
let offset = 0;
const blocks = [], central = [];
for (const [name, value] of entries) {
    const filename = Buffer.from(name), checksum = crc(value), local = Buffer.alloc(30), header = Buffer.alloc(46);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(checksum, 14); local.writeUInt32LE(value.length, 18); local.writeUInt32LE(value.length, 22);
    local.writeUInt16LE(filename.length, 26);
    header.writeUInt32LE(0x02014b50); header.writeUInt16LE(0x314, 4); header.writeUInt16LE(20, 6);
    header.writeUInt16LE(0x800, 8); header.writeUInt32LE(checksum, 16);
    header.writeUInt32LE(value.length, 20); header.writeUInt32LE(value.length, 24); header.writeUInt16LE(filename.length, 28);
    header.writeUInt32LE(((name.endsWith('/') ? 0x41ed : 0x81a4) * 65536) >>> 0, 38);
    header.writeUInt32LE(offset, 42);
    blocks.push(local, filename, value); central.push(header, filename);
    offset += local.length + filename.length + value.length;
}
const centralBytes = Buffer.concat(central), end = Buffer.alloc(22);
end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.size, 8); end.writeUInt16LE(entries.size, 10);
end.writeUInt32LE(centralBytes.length, 12); end.writeUInt32LE(offset, 16);
fs.writeFileSync(archive, Buffer.concat([...blocks, centralBytes, end]));
