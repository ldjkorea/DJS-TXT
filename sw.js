const CACHE = 'novel-reader-v221';
const ASSETS = [
  './index.html',
  './manifest.json',
  './css/app.css?v=221',
  './js/app.js?v=221',
  './js/data.js?v=221',
  './js/reader.js?v=221',
  './fonts/RIDIBatang.otf',
  './fonts/PretendardVariable.woff2',
  './icon-192.png',
  './icon-512.png'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(ASSETS.map(url=>new Request(url,{cache:'reload'}))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(key => key.startsWith('novel-reader-') && key !== CACHE)
          .map(key => caches.delete(key))
      ))
      .then(() => self.clients.claim())
  );
});

async function networkFirst(request) {
  const cache = await caches.open(CACHE);
  try {
    const response = await fetch(request);
    if(response&&response.ok){await cache.put(request,response.clone());return response;}
    const cached=await cache.match(request);if(cached)return cached;return response;
  } catch (error) {
    const cached = await cache.match(request);
    if (cached) return cached;
    if (request.mode === 'navigate') return cache.match('./index.html');
    throw error;
  }
}

self.addEventListener('fetch', event => {
  const request = event.request;
  if(new URL(request.url).origin!==self.location.origin)return;
  if(request.method==='POST'&&new URL(request.url).pathname.endsWith('/index.html')) {
    event.respondWith((async()=>{
      const data=await request.formData(),files=data.getAll('txtfiles').filter(f=>typeof f.arrayBuffer==='function');
      if(!files.length)return new Response('TXT 파일이 없습니다',{status:400});
      if(files.reduce((n,f)=>n+f.size,0)>50*1024*1024)return new Response('공유 파일은 합계 50MB까지 지원합니다',{status:413});
      const entries=[];
      for(const file of files){const bytes=new Uint8Array(await file.arrayBuffer());let binary='';for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));entries.push({name:file.name,bytes:btoa(binary)});}
      const id=crypto.randomUUID(),cache=await caches.open('djs-txt-shared');
      const pending=await cache.keys();if(pending.length>=10)return new Response('먼저 대기 중인 공유 파일을 가져와 주세요',{status:429});
      await cache.put('./shared/'+id,new Response(JSON.stringify(entries),{headers:{'Content-Type':'application/json'}}));
      return Response.redirect(new URL('./index.html?shared='+id,self.location.href),303);
    })());return;
  }
  if(request.method!=='GET')return;

  const destination = request.destination;
  if (request.mode === 'navigate' || destination === 'script' || destination === 'style' || destination === 'manifest') {
    event.respondWith(networkFirst(request));
    return;
  }

  event.respondWith(
    caches.match(request).then(cached => cached || networkFirst(request))
  );
});
