(async()=>{try{
  const deadline=Date.now()+15000;
  while(window.hasLegacyRecords&&!await window.hasLegacyRecords(window.__qaExpectedLegacy||{})){
    if(Date.now()>deadline)throw Error('Legacy records are not durable after restart');
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  const books=Meta.books(),contents={},rawStorage={};
  for(const id of Object.keys(books)){const body=await DB.get(id);if(body===null)throw Error('A stored book body is missing; upgrade stopped');contents[id]=body;}
  for(const key of ['nr-books','nr-cfg','nr-last'])rawStorage[key]=localStorage.getItem(key);
  const snapshot={version:3,exportedAt:Date.now(),books,contents,cfg:Meta.cfg(),lastBook:Meta.lastBook(),rawStorage};
  window.__qaResult=QaAudit.save(JSON.stringify(snapshot));
}catch(e){window.__qaResult=JSON.stringify({ok:false,phase:'audit',error:e.message});}})();
