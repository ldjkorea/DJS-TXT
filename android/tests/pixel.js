(async()=>{try{
  const assert=(v,m)=>{if(!v)throw Error(m);};
  const book=Meta.books().b_legacy_pixels;assert(book&&book.pos===4500,'pixel fixture changed before opening');
  await openBook('b_legacy_pixels');await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  const originalY=scrollY;assert(Math.abs(originalY-4500)<45,'numeric scroll position not restored');
  restorePosition(book.bookmarks[0].pos);await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  assert(Math.abs(scrollY-1200)<45,'numeric bookmark not restored');
  assert(Meta.books().b_legacy_pixels.bookmarks[0].pos===1200,'legacy bookmark rewritten');
  window.__qaResult=JSON.stringify({ok:true,phase:'pixel',positionY:originalY,bookmarkY:scrollY,nodes:document.querySelectorAll('#text-display p').length});
}catch(e){window.__qaResult=JSON.stringify({ok:false,phase:'pixel',error:e.message});}})();
