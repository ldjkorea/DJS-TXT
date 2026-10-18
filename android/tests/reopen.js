(async()=>{try{
  const assert=(v,m)=>{if(!v)throw Error(m);},before=Meta.books(),id='b_구형_txt_55743';
  assert(before[id]&&before.b_legacy_pixels,'legacy IDs missing');
  const expected=before[id].pos,marks=JSON.stringify(before[id].bookmarks);
  await openBook(id);await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  const actual=readerLayout.capture();assert(actual.pIdx===expected.pIdx,'paragraph changed after reopen');
  if(Number.isFinite(expected.charOffset))assert(actual.charOffset===expected.charOffset,'sentence changed after reopen');
  for(let cycle=0;cycle<10;cycle++){
    await openBook(id);await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(r))));
    const repeated=readerLayout.capture();
    assert(repeated.pIdx===actual.pIdx&&repeated.charOffset===actual.charOffset,'cumulative anchor drift on reopen '+cycle);
  }
  assert(JSON.stringify(Meta.books()[id].bookmarks)===marks,'bookmark modified by reopening');
  assert(before.b_legacy_pixels.pos===4500,'unopened legacy pixel position modified');
  assert((await DB.get(id)).includes('QA paragraph 999'),'stored body missing');
  window.__qaResult=JSON.stringify({ok:true,phase:'reopen',repeatedReopens:10,paragraph:actual.pIdx,charOffset:actual.charOffset,expectedCharOffset:expected.charOffset,bookmarks:before[id].bookmarks.length+before.b_legacy_pixels.bookmarks.length,nodes:document.querySelectorAll('#text-display p').length,origin:location.origin});
}catch(e){window.__qaResult=JSON.stringify({ok:false,phase:'reopen',error:e.message});}})();
