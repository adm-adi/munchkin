/**
 * Generates the web client's PNG icons (public/icon-180.png, icon-512.png)
 * from the same design as public/icon.svg — a gold five-pip die on a dark
 * rounded square. Pure Node (zlib is built in), no image dependencies, and
 * deterministic output, so the PNGs are committed and this script only needs
 * re-running if the design changes:
 *
 *   node scripts/generate_web_icons.js
 *
 * PNGs exist because iOS ignores SVG for apple-touch-icon: without them,
 * "Add to Home Screen" falls back to a screenshot of the page.
 */

const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

// ---- Minimal PNG writer ----

const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) {
            c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        }
        table[n] = c;
    }
    return table;
})();

function crc32(buf) {
    let crc = -1;
    for (const byte of buf) {
        crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    }
    return (crc ^ -1) >>> 0;
}

function chunk(type, data) {
    const typeAndData = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const out = Buffer.alloc(typeAndData.length + 8);
    out.writeUInt32BE(data.length, 0);
    typeAndData.copy(out, 4);
    out.writeUInt32BE(crc32(typeAndData), typeAndData.length + 4);
    return out;
}

function encodePng(width, height, rgba) {
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0);
    ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8;   // bit depth
    ihdr[9] = 6;   // color type: RGBA
    // compression 0, filter 0, interlace 0

    const stride = width * 4;
    const raw = Buffer.alloc((stride + 1) * height);
    for (let y = 0; y < height; y++) {
        raw[y * (stride + 1)] = 0; // filter: none
        rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
    }

    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', ihdr),
        chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
        chunk('IEND', Buffer.alloc(0))
    ]);
}

// ---- Icon design (coordinates in 512-space, like icon.svg) ----

const BG = [0x13, 0x10, 0x20];
const GOLD = [0xf0, 0xb4, 0x29];

function insideRoundedRect(x, y, left, top, width, height, radius) {
    const right = left + width;
    const bottom = top + height;
    if (x < left || x > right || y < top || y > bottom) return false;
    const cx = Math.max(left + radius, Math.min(right - radius, x));
    const cy = Math.max(top + radius, Math.min(bottom - radius, y));
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2 ||
        (x >= left + radius && x <= right - radius) ||
        (y >= top + radius && y <= bottom - radius);
}

const PIPS = [[176, 176], [336, 176], [256, 256], [176, 336], [336, 336]];

function colorAt(x, y) {
    // Returns [r, g, b, a] for a point in 512-space.
    if (!insideRoundedRect(x, y, 0, 0, 512, 512, 112)) return [0, 0, 0, 0];
    for (const [px, py] of PIPS) {
        if ((x - px) ** 2 + (y - py) ** 2 <= 34 ** 2) return [...BG, 255];
    }
    if (insideRoundedRect(x, y, 96, 96, 320, 320, 64)) return [...GOLD, 255];
    return [...BG, 255];
}

function renderIcon(size) {
    const rgba = Buffer.alloc(size * size * 4);
    const scale = 512 / size;
    // 2x2 subsampling for soft edges.
    const offsets = [0.25, 0.75];
    for (let y = 0; y < size; y++) {
        for (let x = 0; x < size; x++) {
            let r = 0, g = 0, b = 0, a = 0;
            for (const dy of offsets) {
                for (const dx of offsets) {
                    const [cr, cg, cb, ca] = colorAt((x + dx) * scale, (y + dy) * scale);
                    r += cr * ca; g += cg * ca; b += cb * ca; a += ca;
                }
            }
            const i = (y * size + x) * 4;
            if (a > 0) {
                rgba[i] = Math.round(r / a);
                rgba[i + 1] = Math.round(g / a);
                rgba[i + 2] = Math.round(b / a);
            }
            rgba[i + 3] = Math.round(a / 4);
        }
    }
    return encodePng(size, size, rgba);
}

for (const size of [180, 512]) {
    const outPath = path.join(__dirname, '..', 'public', `icon-${size}.png`);
    fs.writeFileSync(outPath, renderIcon(size));
    console.log(`✅ ${outPath} (${fs.statSync(outPath).size} bytes)`);
}
