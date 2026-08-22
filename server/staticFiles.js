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

const fs = require('fs');
const path = require('path');

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

function createStaticFileServer({ rootDir, logger }) {
    const root = path.resolve(rootDir);

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

        fs.stat(filePath, (err, stat) => {
            if (err || !stat.isFile()) {
                res.writeHead(404, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: 'Not found' }));
                return;
            }

            res.writeHead(200, {
                'Content-Type': contentType,
                'Content-Length': stat.size,
                'Cache-Control': REVALIDATE_EXTENSIONS.has(ext)
                    ? 'no-cache'
                    : 'public, max-age=300'
            });

            if (req.method === 'HEAD') {
                res.end();
                return;
            }

            const stream = fs.createReadStream(filePath);
            stream.pipe(res);
            stream.on('error', streamErr => {
                logger.error(`Static file stream failed for ${filePath}:`, streamErr);
                res.destroy();
            });
        });

        return true;
    };
}

module.exports = { createStaticFileServer, CONTENT_TYPES };
