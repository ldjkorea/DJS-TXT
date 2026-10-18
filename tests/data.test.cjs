const {test}=require('node:test');
const assert=require('node:assert/strict');
const data=require('../js/data.js');
const record={name:'구형 책.txt',lastRead:20,pos:{pIdx:137,offset:7,ratio:0.35},bookmarks:[{pos:{pIdx:22,offset:4},text:'책갈피',pct:2,time:10}]};
test('legacy ID and paragraph position are preserved',()=>{
  const restored=data.backup({version:2,books:{'b_구형_txt_55743':record},lastBook:'b_구형_txt_55743'});
  assert.deepEqual(restored.books['b_구형_txt_55743'].pos,record.pos);
  assert.equal(restored.lastBook,'b_구형_txt_55743');
});
test('numeric pixel positions and legacy bookmarks remain readable',()=>{
  const restored=data.book({...record,pos:4500});assert.equal(restored.pos,4500);assert.deepEqual(restored.bookmarks[0].pos,{pIdx:22,offset:4});
});
test('a portable backup must contain every book body',()=>{
  assert.throws(()=>data.backup({version:3,books:{book:record},contents:{}}),/본문/);
  assert.equal(data.backup({version:3,books:{book:record},contents:{book:'본문'}}).contents.book,'본문');
});
test('invalid setting types cannot break startup',()=>{
  const cfg=data.config({line:'bad',font:null,gap:Infinity,viewMode:'invalid',volumeKey:'false'});
  assert.equal(cfg.line.toFixed(1),'1.9');assert.equal(cfg.font,19);assert.equal(cfg.viewMode,'scroll');assert.equal(cfg.volumeKey,true);
});
test('setting ranges are bounded',()=>{const cfg=data.config({font:900,line:-2,pad:1000});assert.equal(cfg.font,40);assert.equal(cfg.line,1.2);assert.equal(cfg.pad,80);});
test('every pre-existing theme value survives validation',()=>{
  for(const theme of ['graphite','sepia','light','dark','night','green'])assert.equal(data.config({theme}).theme,theme);
});
test('a merge retains newer existing progress and all unique bookmarks',()=>{
  const incoming={...record,lastRead:10,pos:{pIdx:1},bookmarks:[...record.bookmarks,{pos:{pIdx:9},text:'추가',time:11}]};
  const merged=data.mergeBook(record,incoming);assert.deepEqual(merged.pos,record.pos);assert.equal(merged.bookmarks.length,2);
});
test('a newer backup progress may be restored without losing existing bookmarks',()=>{
  const incoming={...record,lastRead:30,pos:{pIdx:300},bookmarks:[]};
  const merged=data.mergeBook(record,incoming);assert.equal(merged.pos.pIdx,300);assert.equal(merged.bookmarks.length,1);
});
test('restoring the same bookmark with reordered JSON fields does not duplicate it',()=>{
  const current={...record,bookmarks:[{time:10,text:'same',pos:{pIdx:8,charOffset:5,windowStart:0,offset:2,ratio:0.2,progress:1}}]};
  const incoming=data.book(current);
  assert.equal(data.mergeBook(current,incoming).bookmarks.length,1);
});
test('untrusted IDs and unsupported backup formats are rejected before writes',()=>{
  assert.throws(()=>data.backup(JSON.parse('{"version":3,"books":{"__proto__":{"name":"bad"}},"contents":{}}')),/식별자/);
  assert.throws(()=>data.backup({version:3,books:{'x" onclick="bad':record},contents:{}}),/식별자/);
  assert.throws(()=>data.backup({version:99,books:{}}),/형식/);
});
test('legacy fallback fingerprints remain verifiable on HTTPS',async()=>{
  const fs=require('node:fs'),vm=require('node:vm'),crypto=require('node:crypto').webcrypto;
  const source=fs.readFileSync(require('node:path').join(__dirname,'../js/app.js'),'utf8');
  const normalize=source.slice(source.indexOf('function normalizedText('),source.indexOf('function splitIntoParagraphs('));
  const fingerprint=source.slice(source.indexOf('async function fingerprintContent('),source.indexOf('function formattedTime('));
  const modern=vm.runInNewContext(normalize+fingerprint+';fingerprintContent',{crypto,TextEncoder});
  const legacy=vm.runInNewContext(normalize+fingerprint+';fingerprintContent',{TextEncoder});
  const original=await legacy('구형 본문\r\n둘째 줄');
  assert.match(original,/^fallback-/);assert.equal(await modern('구형 본문\n둘째 줄',{legacyFallback:true}),original);
  assert.match(await modern('구형 본문\n둘째 줄'),/^[0-9a-f]{64}$/);
});
