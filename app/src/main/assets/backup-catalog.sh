#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
node - "$@" <<'NODE'
'use strict';
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const [action, directory, argument, keepArgument] = process.argv.slice(2);
const validName = n => typeof n === 'string' && n.length <= 255 && /^SillyTavern-Launcher-[0-9]{8}-[0-9]{6}(-[0-9]+)?( \([0-9]+\))*\.zip$/.test(n);
const identity = s => `${s.dev}:${s.ino}:${s.size}:${s.mtimeNs}`;
const revision = s => crypto.createHash('sha256').update(`${identity(s)}:${s.ctimeNs}`).digest('hex');
let directoryFd, root, pinned;
function stat(file) { const s = fs.lstatSync(file, {bigint:true}); if (!s.isFile() || s.isSymbolicLink()) throw Error('일반 ZIP 파일만 선택할 수 있습니다.'); return s; }
function cancelled() { if ([process.env.ST_CANCEL_REQUEST_FILE,process.env.ST_CANCEL_REQUEST_ID_FILE].some(p=>p && fs.existsSync(p))) { const e=Error('작업을 중단했습니다.'); e.code='CANCELLED'; throw e; } }
try {
  root = fs.realpathSync(directory);
  directoryFd = fs.openSync(root, fs.constants.O_RDONLY | (fs.constants.O_DIRECTORY || 0) | (fs.constants.O_NOFOLLOW || 0));
  pinned = process.platform === 'win32' ? root : `/proc/self/fd/${directoryFd}`;
  if (action === 'revision') {
    if (!validName(argument)) throw Error('백업 이름이 올바르지 않습니다.');
    process.stdout.write(revision(stat(path.join(pinned, argument))) + '\n');
  } else if (action === 'delete') {
    if (!argument || argument.length > 120000) throw Error('삭제 요청이 너무 큽니다.');
    const selected = JSON.parse(Buffer.from(argument, 'base64').toString('utf8'));
    if (!Array.isArray(selected) || !selected.length || selected.length > 200 || new Set(selected.map(x=>x.name)).size !== selected.length) throw Error('백업은 한 번에 200개 이하로 선택해 주세요.');
    // Automatic retention is only allowed while the replacement archive still exists, unchanged.
    let keep;
    if (keepArgument) {
      if (keepArgument.length > 1024) throw Error('보관할 백업을 확인하지 못했어요.');
      keep=JSON.parse(Buffer.from(keepArgument,'base64').toString('utf8'));
      if (!validName(keep.name) || !/^[a-f0-9]{64}$/.test(keep.revision) || selected.some(i=>i.name===keep.name)) throw Error('새 백업은 정리하지 않아요.');
    }
    function confirmReplacement() { if (keep && revision(stat(path.join(pinned,keep.name)))!==keep.revision) throw Error('새 백업이 바뀌어 정리를 중단했어요.'); }
    confirmReplacement();
    for (const item of selected) {
      if (!validName(item.name) || !/^[a-f0-9]{64}$/.test(item.revision)) throw Error('백업 목록을 다시 확인해 주세요.');
      if (revision(stat(path.join(pinned,item.name))) !== item.revision) throw Error('백업이 변경되었습니다. 삭제하지 않았습니다. 목록을 새로고침해 주세요.');
    }
    let count = 0;
    for (const item of selected) {
      cancelled();
      confirmReplacement();
      const source=path.join(pinned,item.name), before=stat(source);
      if (revision(before)!==item.revision) throw Error(`백업이 변경되었습니다. 확인된 삭제: ${count}/${selected.length}개.`);
      const name='.st-cleanup-'+crypto.randomUUID(), work=path.join(pinned,name);
      fs.mkdirSync(work,{mode:0o700});
      const workFd=fs.openSync(work,fs.constants.O_RDONLY|(fs.constants.O_DIRECTORY||0)|(fs.constants.O_NOFOLLOW||0));
      const workStat=fs.fstatSync(workFd,{bigint:true});
      const workAnchor=process.platform==='win32'?work:`/proc/self/fd/${workFd}`, held=path.join(workAnchor,'backup.zip');
      function removeEmptyWork() {
        fs.unlinkSync(path.join(workAnchor,'RECOVER.txt'));
        const named=fs.lstatSync(work,{bigint:true});
        if(named.dev===workStat.dev && named.ino===workStat.ino && named.isDirectory() && !named.isSymbolicLink()) fs.rmdirSync(work);
      }
      let moved=false;
      try {
        fs.writeFileSync(path.join(workAnchor,'RECOVER.txt'),`완료되지 않은 백업 삭제입니다. backup.zip을 ${item.name} 이름으로 되돌릴 수 있습니다.\n`,{flag:'wx',mode:0o600});
        cancelled();
        fs.renameSync(source,held); moved=true;
        if (identity(stat(held))!==identity(before)) throw Error('삭제 직전에 백업이 변경되어 원본을 보존했습니다.');
        cancelled();
        fs.unlinkSync(held); moved=false; count++;
        removeEmptyWork();
        process.stdout.write(`deleted_count=${count}\n`);
      } catch(error) {
        if (moved) {
          // Rename-back has no portable NOREPLACE primitive. Preserve the held copy instead.
          error.message += ` 보존 위치: ${path.join(root,name,'backup.zip')} (원래 이름 ${item.name})`;
        }
        if (!moved) { try { removeEmptyWork(); } catch (_) {} }
        error.message += ` 확인된 삭제: ${count}/${selected.length}개.`;
        throw error;
      } finally { fs.closeSync(workFd); }
    }
  } else throw Error('지원하지 않는 백업 관리 요청입니다.');
} catch (error) {
  process.stderr.write((error.message || '백업 관리에 실패했습니다.')+'\n');
  process.exitCode=error.code==='CANCELLED'?130:64;
} finally { if(directoryFd!==undefined) fs.closeSync(directoryFd); }
NODE
