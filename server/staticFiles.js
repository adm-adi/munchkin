/**
 * Static file serving for the web client (server/public).
 *
 * Lives in its own module for the same reason validation.js does: requiring
 * server.js binds a port and opens the database, so anything defined there is
 * untestable. The handler here is pure wiring around the filesystem and can be
 * exercised directly.
 *
 * Security posture:
 *  - Only files inside `rootDir` are ever served. The resolved path is checked
 *    to stay within the root, so `..` sequences (raw or URL-encoded) cannot
 *    escape into the directory that holds server.js and munchkin.db.
 *  - Dotfiles and dot-directories are never served.
 *  - Only extensions in CONTENT_TYPES are served; everything else falls through
 *    to the caller's 404. A request for an unknown type can therefore never
 *    leak a stray file dropped into public/.
 */

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const CONTENT_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    // ES modules refuse to execute unless served as a JavaScript MIME type, so
    // .mjs must be mapped explicitly — a default of text/plain breaks the app.
    '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.webmanifest': 'application/manifest+json; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.ico': 'image/x-icon',
    '.txt': 'text/plain; charset=utf-8',
    '.woff2': 'font/woff2'
};

// The HTML shell and the manifest must revalidate on every load so a deploy is
// picked up immediately; hashed-free assets get a short cache instead.
const REVALIDATE_EXTENSIONS = new Set(['.html', '.webmanifest']);
const COMPRESSIBLE_EXTENSIONS = new Set([
    '.html', '.css', '.js', '.mjs', '.json', '.webmanifest', '.svg', '.txt'
]);

/**
 * Checks whether an HTTP `If-None-Match` header matches the active ETag.
 * @param {string|string[]|undefined} headerValue
 * @param {string} etag
 * @returns {boolean}
 */
function matchesIfNoneMatch(headerValue, etag) {
    if (!headerValue) return false;
    const raw = Array.isArray(headerValue) ? headerValue.join(',') : headerValue;
    const normalizedTarget = etag.replace(/^W\//, '');
    return raw.split(',').some(token => {
        const candidate = token.trim();
        if (!candidate) return false;
        if (candidate === '*') return true;
        return candidate === etag || candidate.replace(/^W\//, '') === normalizedTarget;
    });
}

/**
 * Parses `Accept-Encoding` and selects the preferred precompressed encoding.
 * @param {string|string[]|undefined} acceptEncoding
 * @returns {'br'|'gzip'|null}
 */
function selectEncoding(acceptEncoding) {
    if (!acceptEncoding) return null;
    const header = Array.isArray(acceptEncoding)
        ? acceptEncoding.join(',').toLowerCase()
        : String(acceptEncoding).toLowerCase();
    if (/\bbr\b/.test(header)) return 'br';
    if (/\bgzip\b/.test(header)) return 'gzip';
    return null;
}

function createStaticFileServer({ rootDir, logger }) {
    const root = path.resolve(rootDir);
    /** @type {Map<string, { ext: string, contentType: string, isServiceWorker: boolean, identity: { buffer: Buffer, etag: string }, br: { buffer: Buffer, etag: string } | null, gzip: { buffer: Buffer, etag: string } | null }>} */
    const assets = new Map();

    function preloadDirectory(dirPath) {
        let entries;
        try {
            entries = fs.readdirSync(dirPath, { withFileTypes: true });
        } catch (err) {
            if (logger) {
                logger.warn(`Static directory preload failed for ${dirPath}: ${err.message}`);
            }
            return;
        }

        for (const entry of entries) {
            if (entry.name.startsWith('.') || entry.isSymbolicLink()) continue;
            const fullPath = path.join(dirPath, entry.name);
            if (entry.isDirectory()) {
                preloadDirectory(fullPath);
                continue;
            }
            if (!entry.isFile()) continue;

            const ext = path.extname(entry.name).toLowerCase();
            const contentType = CONTENT_TYPES[ext];
            if (!contentType) continue;

            try {
                const raw = fs.readFileSync(fullPath);
                const hash = crypto.createHash('sha256').update(raw).digest('base64url').slice(0, 24);
                const identity = { buffer: raw, etag: `"${hash}"` };
                let br = null;
                let gzip = null;

                if (COMPRESSIBLE_EXTENSIONS.has(ext) && raw.length >= 256) {
                    const brBuf = zlib.brotliCompressSync(raw);
                    if (brBuf.length < raw.length) {
                        br = { buffer: brBuf, etag: `"${hash}-br"` };
                    }
                    const gzBuf = zlib.gzipSync(raw);
                    if (gzBuf.length < raw.length) {
                        gzip = { buffer: gzBuf, etag: `"${hash}-gz"` };
                    }
                }

                const relPath = path.relative(root, fullPath).split(path.sep).join('/');
                assets.set(fullPath, {
                    ext,
                    contentType,
                    isServiceWorker: relPath === 'sw.js',
                    identity,
                    br,
                    gzip
                });
            } catch (err) {
                if (logger) {
                    logger.error(`Failed to preload static asset ${fullPath}:`, err);
                }
            }
        }
    }

    if (fs.existsSync(root)) {
        preloadDirectory(root);
    }

    /**
     * Maps a URL pathname to an absolute file path inside the root, or null
     * when the path is malformed, escapes the root, or names a dotfile.
     */
    function resolveSafe(pathname) {
        let decoded;
        try {
            decoded = decodeURIComponent(pathname);
        } catch {
            return null;
        }
        if (decoded.includes('\0')) return null;
        if (decoded === '/') decoded = '/index.html';

        const segments = decoded.split('/').filter(Boolean);
        if (segments.length === 0) return null;
        if (segments.some(segment => segment.startsWith('.'))) return null;

        const filePath = path.resolve(root, ...segments);
        if (filePath !== root && !filePath.startsWith(root + path.sep)) return null;
        return filePath;
    }

    /**
     * Serves `pathname` if it maps to a known static file. Returns true when
     * the request has been taken over (including the not-found reply for a
     * well-formed asset path); false to let the caller keep routing.
     */
    return function serveStatic(req, res, pathname) {
        if (req.method !== 'GET' && req.method !== 'HEAD') return false;

        const filePath = resolveSafe(pathname);
        if (!filePath) return false;

        const ext = path.extname(filePath).toLowerCase();
        const contentType = CONTENT_TYPES[ext];
        if (!contentType) return false;

        const asset = assets.get(filePath);
        if (!asset) {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Not found' }));
            return true;
        }

        const preferred = selectEncoding(req.headers['accept-encoding']);
        const encoding = preferred === 'br' && asset.br
            ? 'br'
            : (preferred === 'gzip' && asset.gzip ? 'gzip' : null);
        const variant = encoding === 'br'
            ? asset.br
            : (encoding === 'gzip' ? asset.gzip : asset.identity);

        const cacheControl = (REVALIDATE_EXTENSIONS.has(asset.ext) || asset.isServiceWorker)
            ? 'no-cache'
            : 'public, max-age=300';

        /** @type {Record<string, string | number>} */
        const baseHeaders = {
            'Content-Type': asset.contentType,
            'Cache-Control': cacheControl,
            'ETag': variant.etag,
            'Vary': 'Accept-Encoding'
        };
        if (asset.isServiceWorker) {
            baseHeaders['Service-Worker-Allowed'] = '/';
        }

        if (matchesIfNoneMatch(req.headers['if-none-match'], variant.etag)) {
            res.writeHead(304, baseHeaders);
            res.end();
            return true;
        }

        const responseHeaders = {
            ...baseHeaders,
            'Content-Length': variant.buffer.length
        };
        if (encoding) {
            responseHeaders['Content-Encoding'] = encoding;
        }

        res.writeHead(200, responseHeaders);
        if (req.method === 'HEAD') {
            res.end();
            return true;
        }

        res.end(variant.buffer);
        return true;
    };
}

module.exports = { createStaticFileServer };
