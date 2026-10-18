(async()=>{try{
  const assert=(v,m)=>{if(!v)throw Error(m);};
  for(let i=0;i<100&&!Meta.books().b_offline_legacy;i++)await new Promise(r=>setTimeout(r,100));
  const books=Meta.books(),file=books.b_offline_legacy;
  assert(file&&books.b_구형_txt_55743&&books.b_legacy_pixels,'a legacy library was not merged');
  assert((await DB.get('b_offline_legacy')).includes('QA old file-origin content.'),'file-origin body missing');
  assert(file.pos.pIdx===1&&file.pos.ratio===0.25&&file.bookmarks.length===1,'file-origin reading records changed');
  assert(Meta.cfg().font===21&&Meta.cfg().theme==='sepia','existing HTTPS settings overwritten');
  window.__qaResult=JSON.stringify({ok:true,phase:'legacy-merge',books:Object.keys(books).length,legacyBookmarks:file.bookmarks.length,origin:location.origin});
}catch(e){const b=Meta.books();window.__qaResult=JSON.stringify({ok:false,phase:'legacy-merge',error:e.message,keys:Object.keys(b)});}})();
