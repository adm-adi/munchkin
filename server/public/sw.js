/**
 * Offline App-Shell Service Worker for Munchkin Mesa Tracker.
 * Network-first with cache fallback for same-origin app-shell assets so deploys
 * apply immediately while remaining usable offline. Never intercepts /api/*,
 * /health, non-GET requests, or WebSocket upgrades.
 */

const CACHE_NAME = 'munchkin-shell-v1';

const SHELL_ASSETS = [
    '/',
    '/index.html',
    '/css/app.css',
    '/manifest.webmanifest',
    '/icon.svg',
    '/icon-180.png',
    '/icon-512.png',
    '/js/app.mjs',
    '/js/protocol.mjs',
    '/js/engine.mjs',
    '/js/combat.mjs',
    '/js/net.mjs',
    '/js/session.mjs',
    '/js/i18n.mjs',
    '/js/ui.mjs'
];

self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(cache => cache.addAll(SHELL_ASSETS))
            .then(() => self.skipWaiting())
    );
});

self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys()
            .then(keys => Promise.all(
                keys.filter(key => key !== CACHE_NAME).map(key => caches.delete(key))
            ))
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', event => {
    const { request } = event;
    if (request.method !== 'GET') return;
    if (request.headers.get('upgrade') === 'websocket') return;

    const url = new URL(request.url);
    if (url.origin !== self.location.origin) return;
    if (url.pathname.startsWith('/api/') || url.pathname === '/health') return;

    event.respondWith(
        fetch(request)
            .then(response => {
                if (response && response.ok && response.type === 'basic') {
                    const copy = response.clone();
                    caches.open(CACHE_NAME)
                        .then(cache => cache.put(request, copy))
                        .catch(() => {});
                }
                return response;
            })
            .catch(async () => {
                const cached = await caches.match(request);
                if (cached) return cached;
                if (request.mode === 'navigate') {
                    return (await caches.match('/index.html')) || (await caches.match('/'));
                }
                return new Response('Offline', { status: 503, statusText: 'Offline' });
            })
    );
});
