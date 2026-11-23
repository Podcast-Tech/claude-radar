// GoGo for iPhone: notifications + offline shell.
const CACHE = 'gogo-v1';
const SHELL = ['./', 'index.html', 'app.js', 'manifest.webmanifest', 'vendor/supabase-2.118.0.js',
  'gogo/index.html', 'gogo/fonts.css', 'gogo/fredoka.woff2', 'gogo/nunito.woff2', 'icon-180.png'];

self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
// Network first (so updates arrive right away), cache when offline.
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== location.origin) return;
  e.respondWith(fetch(e.request).then(r => { const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return r; })
    .catch(() => caches.match(e.request, { ignoreSearch: true })));
});

self.addEventListener('push', e => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (_) { d = { title: 'Claude Radar', body: e.data && e.data.text() }; }
  e.waitUntil(Promise.all([
    self.registration.showNotification(d.title || 'Claude Radar', {
      body: d.body || '', tag: d.session_id || 'radar', renotify: true,
      icon: 'icon-192.png', badge: 'icon-192.png', data: { session_id: d.session_id, url: d.url },
    }),
    self.clients.matchAll({ type: 'window' }).then(cs => cs.forEach(c => c.postMessage({ type: 'push', data: d }))),
  ]));
});

self.addEventListener('notificationclick', e => {
  e.notification.close();
  const d = e.notification.data || {};
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(cs => {
    const msg = { type: 'opened', session_id: d.session_id, url: d.url };
    if (cs.length) { cs[0].postMessage(msg); return cs[0].focus(); }
    const q = new URLSearchParams(); if (d.session_id) q.set('seen', d.session_id); if (d.url) q.set('url', d.url);
    return self.clients.openWindow('./?' + q.toString());
  }));
});
