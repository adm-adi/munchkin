/**
 * Offline App-Shell Service Worker for Munchkin Mesa Tracker.
 * Network-first with short timeout ("lie-fi" protection) and cache fallback for
 * same-origin app-shell assets so deploys apply immediately while remaining
 * usable offline. Never intercepts /api/*, /health, non-GET requests, or WS.
 */

const CACHE_NAME = 'munchkin-shell-v6';
const NETWORK_TIMEOUT_MS = 4000;

const SHELL_ASSETS = [
    '/',
    '/index.html',
    '/css/app.css',
    '/manifest.webmanifest',
    '/icon.svg',
    '/icon-180.png',
    '/icon-192.png',
    '/icon-512.png',
    '/js/app.mjs',
    '/js/protocol.mjs',
    '/js/engine.mjs',
    '/js/combat.mjs',
    '/js/net.mjs',
    '/js/session.mjs',
    '/js/i18n.mjs',
    '/js/ui.mjs',
    '/js/sound.mjs',
    '/js/views/combatHelpers.mjs',
    '/js/views/tvView.mjs',
    '/js/views/offlineMode.mjs',
    '/js/views/qrScanner.mjs'
];

self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE_NAME)
            .then(cache => cache.addAll(
                SHELL_ASSETS.map(url => new Request(url, { cache: 'reload' }))
            ))
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

function fetchWithTimeout(request, timeoutMs) {
    if (typeof AbortController === 'undefined') {
        return fetch(request);
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    return fetch(request, { signal: controller.signal })
        .finally(() => clearTimeout(timer));
}

self.addEventListener('fetch', event => {
    const { request } = event;
    if (request.method !== 'GET') return;
    if (request.headers.get('upgrade') === 'websocket') return;

    const url = new URL(request.url);
    if (url.origin !== self.location.origin) return;
    if (url.pathname.startsWith('/api/') || url.pathname === '/health') return;

    const cacheKey = url.pathname === '/' ? '/' : url.pathname;

    event.respondWith(
        fetchWithTimeout(request, NETWORK_TIMEOUT_MS)
            .then(response => {
                if (response && response.ok && response.type === 'basic') {
                    const copy = response.clone();
                    caches.open(CACHE_NAME)
                        .then(cache => cache.put(cacheKey, copy))
                        .catch(() => {});
                }
                return response;
            })
            .catch(async () => {
                const cached = await caches.match(cacheKey);
                if (cached) return cached;
                if (request.mode === 'navigate') {
                    return (await caches.match('/index.html')) || (await caches.match('/'));
                }
                return new Response('Offline', { status: 503, statusText: 'Offline' });
            })
    );
});
