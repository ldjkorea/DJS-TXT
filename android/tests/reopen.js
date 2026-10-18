(async()=>{try{
  const assert=(v,m)=>{if(!v)throw Error(m);},before=Meta.books(),id='b_구형_txt_55743';
  assert(before[id]&&before.b_legacy_pixels,'legacy IDs missing');
  const expected=before[id].pos,marks=JSON.stringify(before[id].bookmarks);
  await openBook(id);await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  const actual=readerLayout.capture();assert(actual.pIdx===expected.pIdx,'paragraph changed after reopen');
  if(Number.isFinite(expected.charOffset))assert(Math.abs(actual.charOffset-expected.charOffset)<40,'sentence changed after reopen');
  assert(JSON.stringify(Meta.books()[id].bookmarks)===marks,'bookmark modified by reopening');
  assert(before.b_legacy_pixels.pos===4500,'unopened legacy pixel position modified');
  assert((await DB.get(id)).includes('QA paragraph 999'),'stored body missing');
  window.__qaResult=JSON.stringify({ok:true,phase:'reopen',paragraph:actual.pIdx,charOffset:actual.charOffset,expectedCharOffset:expected.charOffset,bookmarks:before[id].bookmarks.length+before.b_legacy_pixels.bookmarks.length,nodes:document.querySelectorAll('#text-display p').length,origin:location.origin});
}catch(e){window.__qaResult=JSON.stringify({ok:false,phase:'reopen',error:e.message});}})();
