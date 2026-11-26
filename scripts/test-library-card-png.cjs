'use strict';
// Unit exercise of the bundled codec. No ST server, image codec or user profile.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),assert=require('node:assert/strict'),zlib=require('node:zlib');
const asset=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/library-data.sh'),'utf8').replace(/\r\n/g,'\n');
const payload=asset.split("<<'LIBRARY_JS'\n")[1].split('\nLIBRARY_JS')[0].split('\n// Manager command entry point.\n');
assert.equal(payload.length,2,'load declarations without invoking the manager');
const {writeNamedCard,cardInfo}=new Function('require',payload[0]+'\nreturn {writeNamedCard,cardInfo};')(require);
const scratch=fs.mkdtempSync(path.join(os.tmpdir(),'st-card-png-')),source=path.join(scratch,'input.png'),tmp=path.join(scratch,'output.tmp');
const crc=b=>{let a=-1;for(const n of b){a^=n;for(let i=0;i<8;i++)a=(a&1)?0xedb88320^(a>>>1):a>>>1;}return (~a)>>>0;};
const chunk=(tag,data)=>{const buf=Buffer.concat([Buffer.from(tag),Buffer.from(data)]),out=Buffer.alloc(buf.length+8);
    out.writeUInt32BE(buf.length-4);buf.copy(out,4);out.writeUInt32BE(crc(buf),buf.length+4);return out;};
const sig=Buffer.from('89504e470d0a1a0a','hex'),ih=chunk('IHDR',Buffer.from('00000001000000010802000000','hex')),
    idat=chunk('IDAT',zlib.deflateSync(Buffer.from([0,15,72,198]))),end=chunk('IEND',[]),note=chunk('tEXt','Comment\0direct attachment');
const png=(...parts)=>Buffer.concat([sig,ih,note,idat,...parts,end]);
const tag=(k,raw)=>chunk('tEXt',k+'\0'+Buffer.from(raw).toString('base64'));
const run=(buf,name)=>{fs.writeFileSync(source,buf);const fd=fs.openSync(tmp,'w');try{writeNamedCard(source,fd,name);}finally{fs.closeSync(fd);}return fs.readFileSync(tmp);};
const bad=(b,code=144)=>{assert.throws(()=>run(b,'New'),e=>e.exit===code);assert.deepEqual(fs.readFileSync(source),b);};
const named='새 이름 {글자} ✨',quoted=JSON.stringify(named);
try{
    // Independent V1, V2+V3 cards. The only allowed changed bytes are the
    // name tokens (or an inserted missing name), and their chunk lengths/CRC.
    const v1='{"name" : "Old", "extensions":{"name":"Old"}, "id":9223372036854775807}';
    assert.deepEqual(run(png(tag('chara',v1)),named),png(tag('chara',v1.replace('"Old"',quoted))));
    const v2=' \n{ "sp\\u0065c":"chara_card_v2","na\\u006de": "Old", "data":{"name":"Old","note":"x}]\\\"{", "number":1.000e+04 } }\n';
    const v3='\uFEFF\n{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Old","x":[{"name":"Old"}]} }';
    const privateChunk=chunk('stAg',Buffer.from('00000b1300000b1301','hex'));
    const mixed=png(tag('CHARA',v2),privateChunk,tag('cCv3',v3));
    const wanted=png(tag('CHARA',v2.replaceAll('"Old"',quoted)),privateChunk,
        tag('cCv3',v3.replace('{"spec"','{"name":'+quoted+',"spec"').replace('"Old"',quoted)));
    assert.deepEqual(run(mixed,named),wanted);assert.equal(cardInfo(tmp).name,named);assert.deepEqual(fs.readFileSync(source),mixed);
    // Do not add a fictitious V2 "data" object to V1. Populate a missing nested name
    // in an otherwise readable existing V2 object.
    const missing='{"name":"Top","data": { \t }}';
    assert.deepEqual(run(png(tag('chara',missing)),named),png(tag('chara','{"name":'+quoted+',"data": {"name":'+quoted+' \t }}')));
    const good=png(tag('chara',v1)),malCRC=Buffer.from(good);malCRC[sig.length+ih.length+note.length+idat.length-1]^=1;
    bad(malCRC);const badMetaCrc=Buffer.from(good);badMetaCrc[good.length-end.length-1]^=1;bad(badMetaCrc);
    bad(good.subarray(0,-1));bad(Buffer.concat([good,Buffer.from([1])]));bad(png());bad(png(tag('chara',v1),tag('CHARA',v1)));
    bad(png(tag('ccv3','{"name":"V3 without data"}')));bad(png(tag('chara','{"spec":"future","name":"No"}')));
    bad(png(tag('chara','{"name":"one","na\\u006de":"two"}')));bad(png(tag('ccv3','{"data":{"name":"A","name":"B"}}')));
    bad(png(tag('chara','{"data":{"name":"A"},"data":{"name":"B"}}')));bad(png(tag('chara','{"name":"A","data":false}')));
    bad(png(tag('chara','{')));bad(png(chunk('tEXt','chara\0!!!=')));bad(png(tag('chara',Buffer.from([255]))));
    bad(png(tag('chara',v1),chunk('zTXt','CCV3\0x')));bad(png(chunk('tEXt','chara\0'+'A'.repeat(8*1024*1024))));
    bad(png(tag('chara',v1),idat));bad(png(tag('chara',v1),chunk('UNKN',[]))); // interrupted or unknown image encoding
    console.log('Card PNG: V1/V2/V3 names, both chunks, unchanged image/opaque JSON, bounded metadata and corrupt/ambiguous inputs passed.');
}finally{fs.rmSync(scratch,{recursive:true,force:true});}
