(async()=>{try{
  await DB.put('b_offline_legacy','제1화 오프라인 구형 서재\n\nQA old file-origin content.');
  if(!Meta.saveBooks({b_offline_legacy:{name:'QA offline legacy.txt',pos:{pIdx:1,ratio:0.25,offset:4},lastRead:5,bookmarks:[{pos:{pIdx:1,offset:1},pct:50,text:'offline bookmark',time:3}]}}))throw Error('File-origin metadata save failed');
  Meta.saveCfg({font:8,theme:'green'});Meta.setLast('b_offline_legacy');
  await new Promise(r=>setTimeout(r,1500));
  if(!Meta.books().b_offline_legacy)throw Error('File-origin metadata read-back failed');
  window.__qaResult=JSON.stringify({ok:true,phase:'seed-file',origin:location.origin,readBack:true});
}catch(e){window.__qaResult=JSON.stringify({ok:false,phase:'seed-file',error:e.message});}})();
