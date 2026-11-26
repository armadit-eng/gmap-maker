// GMAP Maker service worker: cangkang aplikasi dicache agar bisa dibuka offline; permintaan ke Overpass/OSM TIDAK dicache.
const C='gmap-maker-v5';
const SHELL=['./','./index.html','./manifest.json','./icon-192.png','./icon-512.png','./icon-maskable-512.png','./apple-touch-icon.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const r=e.request,u=new URL(r.url);
  if(r.method!=='GET'||u.origin!==location.origin)return;               // Overpass, OSM, ESP32: langsung ke jaringan
  e.respondWith(fetch(r).then(res=>{const cp=res.clone();caches.open(C).then(c=>c.put(r,cp));return res})
    .catch(()=>caches.match(r).then(m=>m||caches.match('./index.html'))));  // jaringan dulu, cache bila offline
});
