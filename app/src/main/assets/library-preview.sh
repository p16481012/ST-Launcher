#!/usr/bin/env bash
# A read-only detail request. Never load installation modules, render markup, or execute QR/CSS.
set -eu
[[ "$#" == 4 ]] || exit 64
exec node --max-old-space-size=72 - "$@" <<'PREVIEW_JS'
'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),C=fs.constants;
const [home,usr64,path64,rev]=process.argv.slice(2),fds=[],dirs=[];
const maxBytes=2*1024*1024,maxRows=12,b64=s=>Buffer.from(s).toString('base64');
function fail(code){throw Object.assign(new Error('preview'),{code});}
const obj=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
function decode(s,limit){
    if(typeof s!=='string'||s.length>4*Math.ceil(limit/3))fail(64);
    const b=Buffer.from(s,'base64');
    if(b.length>limit||b.toString('base64')!==s)fail(64);
    try{return new TextDecoder('utf-8',{fatal:true}).decode(b);}catch{fail(64);}
}
const safe=s=>s&&!s.startsWith('.')&&!/[\\/\x00-\x1f\x7f]/.test(s)&&Buffer.byteLength(s)<=255;
const stamp=s=>[s.dev,s.ino,s.mode,s.size,s.mtimeNs,s.ctimeNs].join(':');
const stat=p=>fs.lstatSync(p,{bigint:true});
const ident=(a,b)=>a.dev===b.dev&&a.ino===b.ino;
function pin(p){
    const s=stat(p);if(!s.isDirectory())fail(145);
    dirs.push([p,s]);
    if(process.platform==='win32')return p;
    const fd=fs.openSync(p,C.O_RDONLY|C.O_DIRECTORY|C.O_NOFOLLOW);fds.push(fd);
    if(!ident(fs.fstatSync(fd,{bigint:true}),s))fail(145);
    return '/proc/self/fd/'+fd;
}
function clean(value,limit){
    if(typeof value!=='string')return '';
    const input=value.slice(0,limit*2+2).replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g,' ').trim();
    const chars=Array.from(input);
    return chars.length>limit||value.length>limit*2+2?chars.slice(0,limit-1).join('')+'…':input;
}
function rgba(s){
    if(typeof s!=='string'||s.length>60)return null;
    s=s.trim();
    const hex=s.match(/^#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i);
    if(hex){
        let h=hex[1];if(h.length<5)h=[...h].map(x=>x+x).join('');
        return ((h.slice(6)||'FF')+h.slice(0,6)).toUpperCase();
    }
    const m=s.match(/^(rgb|rgba)\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*(0|1|0?\.\d{1,6}|1\.0{1,6}))?\s*\)$/i);
    if(!m||((m[1].length===4)!==(m[5]!==undefined)))return null;
    const channels=[m[2],m[3],m[4]].map(Number);if(channels.some(n=>n>255))return null;
    return [Math.round(Number(m[5]??1)*255),...channels].map(x=>x.toString(16).padStart(2,'0')).join('').toUpperCase();
}
function summarize(folder,v){
    if(!obj(v))fail(144);
    const rows=[];let omitted=0,notice='';
    const add=(title,summary='',excerpt='',color='-')=>{
        if(rows.length===maxRows){omitted++;return;}
        rows.push(['peek',b64(clean(title,72)),b64(clean(summary,120)),b64(clean(excerpt,256)),color].join('\t'));
    };
    const tail=(n,processed)=>{omitted+=n-processed;};
    if(folder==='OpenAI Settings'){
        for(const [key,fallback,label] of [['temperature','temp_openai','온도'],['openai_max_tokens','max_tokens','최대 응답 토큰'],
            ['openai_max_context','max_context','컨텍스트 크기'],['top_p','top_p_openai','Top P']]){
            const n=v[key]??v[fallback];if(Number.isFinite(n))add(label,String(n));
        }
        if(Array.isArray(v.prompts)){
            const n=Math.min(v.prompts.length,maxRows-rows.length);
            for(let i=0;i<n;i++){const p=v.prompts[i];add(clean(p?.name,72)||clean(p?.identifier,72)||'프롬프트 '+(i+1),'프롬프트');}
            tail(v.prompts.length,n);
        }
    }else if(folder==='worlds'){
        if(!obj(v.entries)&&!Array.isArray(v.entries))fail(144);
        const keys=Object.keys(v.entries),n=Math.min(keys.length,maxRows);
        for(let i=0;i<n;i++){
            const entry=v.entries[keys[i]];if(!obj(entry))fail(144);
            const keywords=Array.isArray(entry.key)?entry.key.slice(0,5).map(k=>clean(k,32)).filter(Boolean):[];
            add(clean(entry.comment,72)||keywords[0]||'항목 '+(i+1),keywords.join(' · '),entry.content);
        }
        tail(keys.length,n);
    }else if(folder==='QuickReplies'){
        const qr=Array.isArray(v.qrList)?v.qrList:v.quickReplySlots;if(!Array.isArray(qr))fail(144);
        const n=Math.min(qr.length,maxRows);
        for(let i=0;i<n;i++){
            const q=qr[i];if(!obj(q))fail(144);
            add(clean(q.label,72)||'버튼 '+(i+1),'',q.message??q.mes);
        }
        tail(qr.length,n);
    }else{
        for(const [label,keys] of [['본문 글자',['main_text_color']],['배경',['chat_tint_color','blur_tint_color']],
            ['강조',['quote_text_color','underline_text_color']],['내 말풍선',['user_mes_blur_tint_color']],['상대 말풍선',['bot_mes_blur_tint_color']]]){
            const h=keys.map(k=>rgba(v[k])).find(Boolean);
            if(h)add(label,'#'+h.slice(2)+(h.startsWith('FF')?'':h.slice(0,2)),'',h);
        }
    }
    if(!rows.length)notice='미리볼 항목이 없어요.';
    return [...rows,['preview',omitted,b64(notice)].join('\t')].join('\n');
}
function main(){
    const user=decode(usr64,255),r=decode(path64,1024),parts=r.split('/');
    if(!safe(user)||user.startsWith('_')||parts.length!==2||!safe(parts[1])||!parts[1].endsWith('.json')||
        !['worlds','QuickReplies','OpenAI Settings','themes'].includes(parts[0])||!/^[0-9a-f]{64}$/.test(rev))fail(64);
    const first=pin(home),root=fs.realpathSync(home),profile=pin(path.join(pin(path.join(first,'data')),user));
    const p=path.join(pin(path.join(profile,parts[0])),parts[1]);
    const s=stat(p);
    if(!s.isFile()||rev!==crypto.createHash('sha256').update(JSON.stringify([root,user,r,stamp(s),'',false])).digest('hex'))fail(145);
    const fd=fs.openSync(p,C.O_RDONLY|C.O_NONBLOCK|C.O_NOFOLLOW);fds.push(fd);
    if(stamp(fs.fstatSync(fd,{bigint:true}))!==stamp(s))fail(145);
    let out;
    if(s.size>BigInt(maxBytes)) out='preview\t0\t'+b64('2 MiB가 넘어서 미리보기를 생략했어요. 파일은 그대로예요.');
    else{
        const bytes=Buffer.alloc(Number(s.size));
        for(let i=0;i<bytes.length;){const n=fs.readSync(fd,bytes,i,bytes.length-i,i);if(!n)fail(145);i+=n;}
        try{out=summarize(parts[0],JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)));}
        catch(e){if(e.code===144)throw e;fail(144);}
    }
    if(stamp(fs.fstatSync(fd,{bigint:true}))!==stamp(s)||stamp(stat(p))!==stamp(s)||dirs.some(([name,old])=>!ident(old,stat(name))))fail(145);
    console.log(out);
}
try{main();}catch(e){
    const code=e.code===64?64:e.code===144?144:145;
    console.error(code===64?'지원하지 않는 미리보기예요.':code===144?'파일을 미리보기 형식으로 읽지 못했어요.':'자료가 바뀌었거나 읽을 수 없어요. 목록을 다시 열어 주세요.');
    process.exitCode=code;
}finally{while(fds.length)fs.closeSync(fds.pop());}
PREVIEW_JS
