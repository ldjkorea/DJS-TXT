(async()=>{try{
  const assert=(value,message)=>{if(!value)throw Error(message);};
  const books=Meta.books(),legacy=books.b_구형_txt_55743,pixel=books.b_legacy_pixels;
  assert(legacy&&pixel,'legacy IDs missing');
  assert(legacy.pos.pIdx===137&&legacy.pos.ratio===0.35,'legacy position changed');
  assert(pixel.pos===4500,'numeric position changed');
  assert(legacy.bookmarks[0].pos.pIdx===22&&pixel.bookmarks[0].pos===1200,'bookmarks changed');
  assert((await DB.get('b_구형_txt_55743')).includes('QA paragraph 999'),'body missing');
  assert(Meta.cfg().font===21&&Meta.cfg().fontFamily==='sans','settings changed');
  await openBook('b_구형_txt_55743');
  await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  assert(Math.abs(readerLayout.capture().pIdx-137)<=1,'reading anchor not restored');
  const nodes=document.querySelectorAll('#text-display p').length;assert(nodes<=180,'unbounded DOM');
  const snapshot=await librarySnapshot();assert(snapshot.contents.b_legacy_pixels.includes('QA paragraph 999'),'backup body missing');
  const bookmarkCount=snapshot.books.b_구형_txt_55743.bookmarks.length+snapshot.books.b_legacy_pixels.bookmarks.length;
  window.__qaResult=JSON.stringify({ok:true,phase:'verify',books:Object.keys(books).length,bookmarkCount,paragraphNodes:nodes,anchor:readerLayout.capture().pIdx,origin:location.origin,version:AndroidBridge.getVersionName()});
}catch(e){window.__qaResult=JSON.stringify({ok:false,error:e.message});}})();
