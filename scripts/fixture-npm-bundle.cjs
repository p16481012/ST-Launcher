'use strict';
// Copy the host npm's real bundled parser into an isolated Termux-style
// prefix. No network, npm install, or mocked version comparison is involved.
const fs = require('node:fs');
const path = require('node:path');
const [prefix, executable] = process.argv.slice(2);
const exe = fs.realpathSync(executable);
const roots = [path.resolve(path.dirname(exe), '..'), path.join(path.dirname(exe), 'node_modules/npm'),
    path.join(path.dirname(process.execPath), 'node_modules/npm')];
const npm = roots.find(root => {
    try { return JSON.parse(fs.readFileSync(path.join(root, 'package.json'))).name === 'npm'; }
    catch { return false; }
});
if (!npm) throw Error('test host npm bundle not found');
const target = path.join(prefix, 'lib/node_modules/npm');
fs.mkdirSync(path.join(target, 'bin'), { recursive: true });
fs.copyFileSync(path.join(npm, 'package.json'), path.join(target, 'package.json'));
fs.cpSync(path.join(npm, 'node_modules/semver'), path.join(target, 'node_modules/semver'), { recursive: true });
fs.writeFileSync(path.join(target, 'bin/npm-cli.js'), '// never executed fixture');
