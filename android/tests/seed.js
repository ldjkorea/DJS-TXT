(async()=>{try{
  const body=Array.from({length:1000},(_,i)=>i%100===0?'제'+(i/100+1)+'화 QA':'QA paragraph '+i+' : data preservation fixture. '.repeat(15)).join('\n\n');
  const books={
    b_구형_txt_55743:{name:'QA legacy book.txt',size:body.length,pos:{pIdx:137,offset:7,ratio:0.35},progress:13.7,lastRead:20,bookmarks:[{pos:{pIdx:22,offset:4,ratio:0.25},pct:2.2,text:'legacy bookmark',time:10}]},
    b_legacy_pixels:{name:'QA pixel book.txt',size:body.length,pos:4500,progress:1,lastRead:10,bookmarks:[{pos:1200,pct:1,text:'pixel bookmark',time:8}]}
  };
  for(const id of Object.keys(books))await DB.put(id,body);
  if(!Meta.saveBooks(books))throw Error('metadata save');
  Meta.saveCfg({font:21,line:2,pad:26,gap:0.9,fontFamily:'sans',theme:'sepia',viewMode:'scroll'});Meta.setLast('b_구형_txt_55743');
  window.__qaResult=JSON.stringify({ok:true,phase:'seed',books:2,paragraphs:1000,bookmarkCount:2});
}catch(e){window.__qaResult=JSON.stringify({ok:false,error:e.message});}})();
