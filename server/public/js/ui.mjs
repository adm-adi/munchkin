/**
 * Accessible DOM helpers, incremental DOM reconciler (patchScreen),
 * keyboard-trapped dialog modals, live-region toasts, and pure-JS QR SVG encoder.
 * Strict CSP: zero inline `style` attributes or `element.style.*` mutations.
 */

import { t } from './i18n.mjs';

const SVG_NS = 'http://www.w3.org/2000/svg';

function bindEvent(el, eventName, fn) {
    if (!el.__events) el.__events = Object.create(null);
    if (!(eventName in el.__events)) {
        el.addEventListener(eventName, (ev) => {
            const handler = el.__events?.[eventName];
            if (typeof handler === 'function') handler(ev);
        });
    }
    el.__events[eventName] = typeof fn === 'function' ? fn : null;
}

function appendChildren(parent, children) {
    for (const child of children) {
        if (child === null || child === undefined || child === false || child === true) continue;
        if (Array.isArray(child)) {
            appendChildren(parent, child);
        } else if (child instanceof Node) {
            parent.appendChild(child);
        } else {
            parent.appendChild(document.createTextNode(String(child)));
        }
    }
}

export function h(tag, props = null, ...children) {
    const el = document.createElement(tag);
    const p = props && typeof props === 'object' ? props : {};

    if (tag === 'button' && p.type === undefined) {
        el.type = 'button';
    }

    for (const [k, v] of Object.entries(p)) {
        if (v === undefined || v === null || v === false) continue;
        if (k === 'style') {
            // Strict CSP: ignore any style object/string.
            continue;
        }
        if (k === 'class' || k === 'className') {
            el.className = String(v);
        } else if (k === 'dataset' && typeof v === 'object') {
            for (const [dk, dv] of Object.entries(v)) {
                if (dv !== undefined && dv !== null) el.dataset[dk] = String(dv);
            }
        } else if (k.startsWith('on') && typeof v === 'function') {
            bindEvent(el, k.slice(2).toLowerCase(), v);
        } else if (k === 'value') {
            el.value = String(v);
            el.__hasValueProp = true;
        } else if (k === 'checked') {
            el.checked = Boolean(v);
        } else if (k === 'disabled') {
            el.disabled = Boolean(v);
        } else if (k === 'readOnly' || k === 'readonly') {
            el.readOnly = Boolean(v);
        } else if (k === 'required') {
            el.required = Boolean(v);
        } else if (k === 'selected') {
            el.selected = Boolean(v);
        } else if (k === 'open') {
            el.open = Boolean(v);
        } else if (v === true) {
            el.setAttribute(k, '');
        } else {
            el.setAttribute(k, String(v));
        }
    }

    appendChildren(el, children);
    return el;
}

export function svgEl(tag, attrs = null, ...children) {
    const el = document.createElementNS(SVG_NS, tag);
    if (attrs && typeof attrs === 'object') {
        for (const [k, v] of Object.entries(attrs)) {
            if (v === undefined || v === null || v === false || k === 'style') continue;
            el.setAttribute(k, v === true ? '' : String(v));
        }
    }
    for (const child of children) {
        if (child instanceof Node) el.appendChild(child);
    }
    return el;
}

export function clear(el) {
    if (!el) return;
    while (el.firstChild) el.removeChild(el.firstChild);
}

export function fmtSigned(n) {
    const num = Math.trunc(Number(n) || 0);
    return num > 0 ? `+${num}` : String(num);
}

/* ==========================================================================
   Incremental DOM Reconciler (preserves active focus, selection & scroll)
   ========================================================================== */

function flattenNodes(input) {
    const out = [];
    const push = (item) => {
        if (item === null || item === undefined || item === false || item === true) return;
        if (Array.isArray(item)) {
            for (const sub of item) push(sub);
        } else if (item instanceof Node) {
            out.push(item);
        } else {
            out.push(document.createTextNode(String(item)));
        }
    };
    push(input);
    return out;
}

function nodeKey(node) {
    if (!(node instanceof Element)) return null;
    if (node.dataset?.key) return `k:${node.dataset.key}`;
    if (node.id) return `id:${node.id}`;
    return null;
}

function canPatchInPlace(cur, nxt) {
    if (cur.nodeType !== nxt.nodeType) return false;
    if (cur.nodeType === Node.TEXT_NODE) return true;
    if (!(cur instanceof Element) || !(nxt instanceof Element)) return false;
    if (cur.namespaceURI !== nxt.namespaceURI || cur.tagName !== nxt.tagName) return false;
    const ck = nodeKey(cur);
    const nk = nodeKey(nxt);
    return ck === nk;
}

function syncElementPropsAndAttrs(cur, nxt, activeEl) {
    if (cur.namespaceURI === SVG_NS) {
        for (const attr of Array.from(cur.attributes)) {
            if (!nxt.hasAttribute(attr.name)) cur.removeAttribute(attr.name);
        }
        for (const attr of Array.from(nxt.attributes)) {
            if (attr.name === 'style') continue;
            if (cur.getAttribute(attr.name) !== attr.value) {
                cur.setAttribute(attr.name, attr.value);
            }
        }
        return;
    }

    const isFocused = cur === activeEl;

    // Remove attributes absent in next (except value handled on live control state)
    for (const attr of Array.from(cur.attributes)) {
        if (attr.name === 'value' && isFocused) continue;
        if (attr.name === 'open' && cur.tagName === 'DETAILS' && cur.dataset?.preserveOpen === 'true') continue;
        if (!nxt.hasAttribute(attr.name)) {
            cur.removeAttribute(attr.name);
        }
    }

    for (const attr of Array.from(nxt.attributes)) {
        if (attr.name === 'style') continue;
        if (attr.name === 'value' && isFocused) continue;
        if (attr.name === 'open' && cur.tagName === 'DETAILS' && cur.dataset?.preserveOpen === 'true') continue;
        if (cur.getAttribute(attr.name) !== attr.value) {
            cur.setAttribute(attr.name, attr.value);
        }
    }

    // Sync interactive properties
    if ('disabled' in cur && 'disabled' in nxt && cur.disabled !== nxt.disabled) {
        cur.disabled = nxt.disabled;
    }
    if ('checked' in cur && 'checked' in nxt && cur.checked !== nxt.checked) {
        cur.checked = nxt.checked;
    }
    if (!isFocused && (cur instanceof HTMLInputElement || cur instanceof HTMLTextAreaElement || cur instanceof HTMLSelectElement)) {
        if ((nxt.__hasValueProp || cur instanceof HTMLSelectElement) && cur.value !== nxt.value) {
            cur.value = nxt.value;
        }
    }

    // Rebind live event handlers via stable __events slots
    const prevEvents = cur.__events || Object.create(null);
    const nextEvents = nxt.__events || Object.create(null);
    for (const evName of Object.keys(prevEvents)) {
        if (!(evName in nextEvents)) prevEvents[evName] = null;
    }
    for (const [evName, fn] of Object.entries(nextEvents)) {
        bindEvent(cur, evName, fn);
    }
}

function patchNode(cur, nxt, activeEl) {
    if (cur.nodeType === Node.TEXT_NODE) {
        if (cur.nodeValue !== nxt.nodeValue) cur.nodeValue = nxt.nodeValue;
        return cur;
    }
    syncElementPropsAndAttrs(cur, nxt, activeEl);
    patchChildren(cur, Array.from(nxt.childNodes), activeEl);
    return cur;
}

function patchChildren(parent, nextList, activeEl) {
    const oldChildren = Array.from(parent.childNodes);
    const keyedOld = new Map();
    for (const oldNode of oldChildren) {
        const k = nodeKey(oldNode);
        if (k) keyedOld.set(k, oldNode);
    }

    let cursor = parent.firstChild;
    const consumed = new Set();

    for (const nextNode of nextList) {
        const nk = nodeKey(nextNode);
        if (nk && keyedOld.has(nk)) {
            const match = keyedOld.get(nk);
            consumed.add(match);
            if (match !== cursor) {
                parent.insertBefore(match, cursor);
            } else {
                cursor = cursor.nextSibling;
            }
            patchNode(match, nextNode, activeEl);
            continue;
        }

        while (cursor && (consumed.has(cursor) || nodeKey(cursor) !== null)) {
            const toRemove = cursor;
            cursor = cursor.nextSibling;
            if (!consumed.has(toRemove)) parent.removeChild(toRemove);
        }

        if (cursor && canPatchInPlace(cursor, nextNode)) {
            const current = cursor;
            cursor = cursor.nextSibling;
            consumed.add(current);
            patchNode(current, nextNode, activeEl);
        } else {
            parent.insertBefore(nextNode, cursor);
        }
    }

    while (cursor) {
        const nextSibling = cursor.nextSibling;
        if (!consumed.has(cursor)) parent.removeChild(cursor);
        cursor = nextSibling;
    }
}

function captureFocusSnapshot(container) {
    const active = document.activeElement;
    if (!(active instanceof HTMLElement) || !container.contains(active)) return null;
    const focusKey = active.dataset?.focusKey || null;
    const id = active.id || null;
    const isText = active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement;
    let selStart = null;
    let selEnd = null;
    if (isText) {
        try {
            selStart = active.selectionStart;
            selEnd = active.selectionEnd;
        } catch { /* inputs like type=checkbox throw */ }
    }
    return { active, focusKey, id, selStart, selEnd };
}

function restoreFocusSnapshot(container, snap) {
    if (!snap) return;
    let target = null;
    if (snap.active.isConnected && container.contains(snap.active)) {
        target = snap.active;
    } else if (snap.focusKey) {
        for (const el of container.querySelectorAll('[data-focus-key]')) {
            if (el.dataset.focusKey === snap.focusKey) {
                target = el;
                break;
            }
        }
    } else if (snap.id) {
        const candidate = document.getElementById(snap.id);
        if (candidate && container.contains(candidate)) target = candidate;
    }

    if (target instanceof HTMLElement && document.activeElement !== target) {
        try {
            target.focus({ preventScroll: true });
        } catch {
            target.focus();
        }
        if (snap.selStart !== null && (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) {
            try {
                target.setSelectionRange(snap.selStart, snap.selEnd ?? snap.selStart);
            } catch { /* ignore */ }
        }
    }
}

export function patchScreen(container, nextNodes, { preserveFocus = true } = {}) {
    if (!container) return;
    const nextList = flattenNodes(nextNodes);
    const activeEl = document.activeElement;
    const focusSnap = preserveFocus ? captureFocusSnapshot(container) : null;
    const scrollX = window.scrollX;
    const scrollY = window.scrollY;
    const scrollMap = new Map();
    for (const el of container.querySelectorAll('[data-scroll-key]')) {
        scrollMap.set(el.dataset.scrollKey, { top: el.scrollTop, left: el.scrollLeft });
    }

    patchChildren(container, nextList, activeEl);

    for (const el of container.querySelectorAll('[data-scroll-key]')) {
        const saved = scrollMap.get(el.dataset.scrollKey);
        if (saved) {
            el.scrollTop = saved.top;
            el.scrollLeft = saved.left;
        }
    }

    if (preserveFocus && focusSnap) {
        restoreFocusSnapshot(container, focusSnap);
        if (window.scrollX !== scrollX || window.scrollY !== scrollY) {
            window.scrollTo(scrollX, scrollY);
        }
    }
}

/* ==========================================================================
   Live-Region Toasts
   ========================================================================== */

let toastBox = null;

function ensureToastBox() {
    if (!toastBox || !toastBox.isConnected) {
        toastBox = h('div', {
            class: 'toasts',
            id: 'toasts',
            role: 'status',
            'aria-live': 'polite',
            'aria-atomic': 'true'
        });
        document.body.appendChild(toastBox);
    }
    return toastBox;
}

export function toast(message, kind = 'info', ms = 3400) {
    if (!message) return;
    const box = ensureToastBox();
    const cls = kind === 'err' ? 'toast toast-err'
        : kind === 'warn' ? 'toast toast-warn'
        : kind === 'ok' ? 'toast toast-ok'
        : 'toast';
    const el = h('div', { class: cls }, message);
    box.appendChild(el);
    setTimeout(() => {
        if (el.isConnected) el.remove();
    }, ms);
}

/* ==========================================================================
   Accessible Dialog Modal Stack (role=dialog, aria-modal, Focus Trap, Esc)
   ========================================================================== */

let modalSeq = 0;
const modalStack = [];

function getFocusable(root) {
    const selector = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])';
    const out = [];
    for (const el of root.querySelectorAll(selector)) {
        if (!(el instanceof HTMLElement)) continue;
        if (el.closest('[hidden], [aria-hidden="true"]')) continue;
        if (el.getClientRects().length === 0) continue;
        out.push(el);
    }
    return out;
}

export function openModal({ title, body, dismissible = true, onClose, onCancel }) {
    const prevFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const titleId = `modal-title-${++modalSeq}`;

    let closed = false;
    const bodyEl = h('div', { class: 'modal-body' });

    const close = () => {
        if (closed) return;
        closed = true;
        document.removeEventListener('keydown', onKeyDown, true);
        const idx = modalStack.indexOf(entry);
        if (idx >= 0) modalStack.splice(idx, 1);
        if (wrap.isConnected) wrap.remove();
        if (typeof onClose === 'function') onClose();
        const top = modalStack[modalStack.length - 1];
        if (prevFocus && prevFocus.isConnected && (!top || top.card.contains(prevFocus))) {
            try {
                prevFocus.focus({ preventScroll: true });
            } catch {
                prevFocus.focus();
            }
        } else if (top) {
            const topFocus = getFocusable(top.card)[0] || top.card;
            topFocus.focus();
        }
    };

    const handleCancel = () => {
        if (typeof onCancel === 'function') {
            onCancel();
            return;
        }
        if (dismissible) close();
    };

    const headerChildren = [];
    if (title) {
        headerChildren.push(h('h2', { class: 'modal-title', id: titleId }, title));
    }
    if (dismissible) {
        headerChildren.push(h('button', {
            class: 'icon-btn',
            type: 'button',
            'aria-label': t('common.close'),
            onclick: handleCancel
        }, '✕'));
    }

    const card = h('div', {
        class: 'modal-card',
        role: 'dialog',
        'aria-modal': 'true',
        tabindex: '-1',
        ...(title ? { 'aria-labelledby': titleId } : { 'aria-label': t('app.title') })
    },
        headerChildren.length ? h('div', { class: 'modal-header' }, ...headerChildren) : null,
        bodyEl
    );

    const setBody = (nodes) => {
        patchScreen(bodyEl, nodes, { preserveFocus: true });
    };
    setBody(body);

    const wrap = h('div', {
        class: 'modal-backdrop',
        onclick: (e) => {
            if (e.target === wrap) handleCancel();
        }
    }, card);

    const entry = { wrap, card, close, handleCancel };

    const onKeyDown = (e) => {
        if (modalStack[modalStack.length - 1] !== entry) return;
        if (e.key === 'Escape') {
            e.preventDefault();
            e.stopPropagation();
            handleCancel();
            return;
        }
        if (e.key === 'Tab') {
            const focusables = getFocusable(card);
            if (!focusables.length) {
                e.preventDefault();
                card.focus();
                return;
            }
            const first = focusables[0];
            const last = focusables[focusables.length - 1];
            const active = document.activeElement;
            if (e.shiftKey) {
                if (active === first || !card.contains(active)) {
                    e.preventDefault();
                    last.focus();
                }
            } else if (active === last || !card.contains(active)) {
                e.preventDefault();
                first.focus();
            }
        }
    };

    modalStack.push(entry);
    document.body.appendChild(wrap);
    document.addEventListener('keydown', onKeyDown, true);

    queueMicrotask(() => {
        if (closed) return;
        const auto = card.querySelector('[data-autofocus]');
        const firstFocusable = getFocusable(card)[0];
        const target = auto instanceof HTMLElement ? auto : (firstFocusable || card);
        try {
            target.focus({ preventScroll: true });
        } catch {
            target.focus();
        }
    });

    return { close, card, bodyEl, setBody };
}

export function closeAllModals() {
    while (modalStack.length > 0) {
        const top = modalStack[modalStack.length - 1];
        top.close();
    }
}

export function confirmDialog({ title, message, confirmText, cancelText, danger = false }) {
    return new Promise((resolve) => {
        let done = false;
        const finish = (val) => {
            if (done) return;
            done = true;
            modal.close();
            resolve(val);
        };
        const modal = openModal({
            title,
            dismissible: true,
            onCancel: () => finish(false),
            onClose: () => {
                if (!done) {
                    done = true;
                    resolve(false);
                }
            },
            body: [
                h('p', { class: 'muted-text' }, message),
                h('div', { class: 'row-wrap' },
                    h('button', {
                        class: 'btn btn-ghost',
                        onclick: () => finish(false)
                    }, cancelText || t('common.cancel')),
                    h('button', {
                        class: `btn ${danger ? 'btn-danger' : 'btn-primary'}`,
                        'data-autofocus': 'true',
                        onclick: () => finish(true)
                    }, confirmText || t('common.confirm'))
                )
            ]
        });
    });
}

/* ==========================================================================
   Pure-JS ISO/IEC 18004 QR Code SVG Encoder (Versions 1..10, Level M)
   ========================================================================== */

const QR_VERSIONS = [
    null,
    { dataBytes: 16, ecPerBlock: 10, b1: 1, d1: 16, b2: 0, d2: 0, align: [] },
    { dataBytes: 28, ecPerBlock: 16, b1: 1, d1: 28, b2: 0, d2: 0, align: [6, 18] },
    { dataBytes: 44, ecPerBlock: 26, b1: 1, d1: 44, b2: 0, d2: 0, align: [6, 22] },
    { dataBytes: 64, ecPerBlock: 18, b1: 2, d1: 32, b2: 0, d2: 0, align: [6, 26] },
    { dataBytes: 86, ecPerBlock: 24, b1: 2, d1: 43, b2: 0, d2: 0, align: [6, 30] },
    { dataBytes: 108, ecPerBlock: 16, b1: 4, d1: 27, b2: 0, d2: 0, align: [6, 34] },
    { dataBytes: 124, ecPerBlock: 18, b1: 4, d1: 31, b2: 0, d2: 0, align: [6, 22, 38] },
    { dataBytes: 154, ecPerBlock: 22, b1: 2, d1: 38, b2: 2, d2: 39, align: [6, 24, 42] },
    { dataBytes: 182, ecPerBlock: 22, b1: 3, d1: 36, b2: 2, d2: 37, align: [6, 26, 46] },
    { dataBytes: 216, ecPerBlock: 26, b1: 4, d1: 43, b2: 1, d2: 44, align: [6, 28, 50] }
];

const GF_EXP = new Uint8Array(256);
const GF_LOG = new Uint8Array(256);
(() => {
    let x = 1;
    for (let i = 0; i < 255; i++) {
        GF_EXP[i] = x;
        GF_LOG[x] = i;
        x = (x << 1) ^ (x & 0x80 ? 0x11d : 0);
    }
})();

function gfMul(a, b) {
    if (a === 0 || b === 0) return 0;
    return GF_EXP[(GF_LOG[a] + GF_LOG[b]) % 255];
}

function rsGenerator(ecLen) {
    let poly = [1];
    for (let i = 0; i < ecLen; i++) {
        const next = new Array(poly.length + 1).fill(0);
        const factor = GF_EXP[i];
        for (let j = 0; j < poly.length; j++) {
            next[j] ^= poly[j];
            next[j + 1] ^= gfMul(poly[j], factor);
        }
        poly = next;
    }
    return poly;
}

function rsRemainder(data, ecLen) {
    const gen = rsGenerator(ecLen);
    const rem = new Uint8Array(ecLen);
    for (let i = 0; i < data.length; i++) {
        const factor = data[i] ^ rem[0];
        rem.copyWithin(0, 1);
        rem[ecLen - 1] = 0;
        if (factor !== 0) {
            for (let j = 0; j < ecLen; j++) {
                rem[j] ^= gfMul(gen[j + 1], factor);
            }
        }
    }
    return rem;
}

function bchFormatBits(mask) {
    // Level M indicator is 0b00 (0). 5 data bits = (0 << 3) | mask.
    const data = mask & 7;
    let rem = data << 10;
    const poly = 0x537;
    for (let i = 14; i >= 10; i--) {
        if ((rem >> i) & 1) rem ^= poly << (i - 10);
    }
    return ((data << 10) | rem) ^ 0x5412;
}

function bchVersionBits(version) {
    let rem = version << 12;
    const poly = 0x1f25;
    for (let i = 17; i >= 12; i--) {
        if ((rem >> i) & 1) rem ^= poly << (i - 12);
    }
    return (version << 12) | rem;
}

function maskBit(mask, r, c) {
    switch (mask) {
        case 0: return (r + c) % 2 === 0;
        case 1: return r % 2 === 0;
        case 2: return c % 3 === 0;
        case 3: return (r + c) % 3 === 0;
        case 4: return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0;
        case 5: return ((r * c) % 2 + (r * c) % 3) === 0;
        case 6: return (((r * c) % 2 + (r * c) % 3) % 2) === 0;
        case 7: return (((r + c) % 2 + (r * c) % 3) % 2) === 0;
        default: return false;
    }
}

function penaltyScore(grid, size) {
    let score = 0;
    // Rule 1: runs of 5+ same color in rows and columns
    for (let i = 0; i < size; i++) {
        let rowRun = 1;
        let colRun = 1;
        for (let j = 1; j < size; j++) {
            if (grid[i * size + j] === grid[i * size + j - 1]) {
                rowRun++;
                if (rowRun === 5) score += 3;
                else if (rowRun > 5) score += 1;
            } else {
                rowRun = 1;
            }
            if (grid[j * size + i] === grid[(j - 1) * size + i]) {
                colRun++;
                if (colRun === 5) score += 3;
                else if (colRun > 5) score += 1;
            } else {
                colRun = 1;
            }
        }
    }
    // Rule 2: 2x2 blocks
    for (let r = 0; r < size - 1; r++) {
        for (let c = 0; c < size - 1; c++) {
            const v = grid[r * size + c];
            if (v === grid[r * size + c + 1] && v === grid[(r + 1) * size + c] && v === grid[(r + 1) * size + c + 1]) {
                score += 3;
            }
        }
    }
    // Rule 3: 1:1:3:1:1 finder-like patterns
    const patA = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0];
    const patB = [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1];
    for (let r = 0; r < size; r++) {
        for (let c = 0; c <= size - 11; c++) {
            let mRA = true;
            let mRB = true;
            let mCA = true;
            let mCB = true;
            for (let k = 0; k < 11; k++) {
                const rv = grid[r * size + c + k];
                const cv = grid[(c + k) * size + r];
                if (rv !== patA[k]) mRA = false;
                if (rv !== patB[k]) mRB = false;
                if (cv !== patA[k]) mCA = false;
                if (cv !== patB[k]) mCB = false;
            }
            if (mRA || mRB) score += 40;
            if (mCA || mCB) score += 40;
        }
    }
    // Rule 4: dark proportion
    let dark = 0;
    for (let i = 0; i < grid.length; i++) if (grid[i]) dark++;
    const pct = (dark * 100) / grid.length;
    const prev5 = Math.floor(Math.abs(pct - 50) / 5);
    score += prev5 * 10;
    return score;
}

export function encodeQrMatrix(text) {
    const bytes = new TextEncoder().encode(String(text));
    let ver = 1;
    while (ver < QR_VERSIONS.length) {
        const spec = QR_VERSIONS[ver];
        const lenBits = ver >= 10 ? 16 : 8;
        const neededBytes = Math.ceil((4 + lenBits + bytes.length * 8) / 8);
        if (neededBytes <= spec.dataBytes) break;
        ver++;
    }
    if (ver >= QR_VERSIONS.length) {
        throw new Error('QR payload exceeds version 10 capacity');
    }

    const spec = QR_VERSIONS[ver];
    const bitBuf = [];
    const pushBits = (val, count) => {
        for (let i = count - 1; i >= 0; i--) bitBuf.push((val >> i) & 1);
    };

    pushBits(0b0100, 4); // Byte mode
    pushBits(bytes.length, ver >= 10 ? 16 : 8);
    for (const b of bytes) pushBits(b, 8);

    const maxBits = spec.dataBytes * 8;
    const termBits = Math.min(4, maxBits - bitBuf.length);
    for (let i = 0; i < termBits; i++) bitBuf.push(0);
    while (bitBuf.length % 8 !== 0) bitBuf.push(0);

    const dataCodewords = new Uint8Array(spec.dataBytes);
    for (let i = 0; i < bitBuf.length / 8; i++) {
        let byte = 0;
        for (let b = 0; b < 8; b++) byte = (byte << 1) | bitBuf[i * 8 + b];
        dataCodewords[i] = byte;
    }
    const padBytes = [0xec, 0x11];
    for (let i = bitBuf.length / 8, p = 0; i < spec.dataBytes; i++, p++) {
        dataCodewords[i] = padBytes[p % 2];
    }

    // Split into RS blocks & interleave
    const dataBlocks = [];
    const ecBlocks = [];
    let offset = 0;
    for (let i = 0; i < spec.b1; i++) {
        const slice = dataCodewords.slice(offset, offset + spec.d1);
        offset += spec.d1;
        dataBlocks.push(slice);
        ecBlocks.push(rsRemainder(slice, spec.ecPerBlock));
    }
    for (let i = 0; i < spec.b2; i++) {
        const slice = dataCodewords.slice(offset, offset + spec.d2);
        offset += spec.d2;
        dataBlocks.push(slice);
        ecBlocks.push(rsRemainder(slice, spec.ecPerBlock));
    }

    const allCodewords = [];
    const maxDataLen = Math.max(spec.d1, spec.d2);
    for (let i = 0; i < maxDataLen; i++) {
        for (const blk of dataBlocks) if (i < blk.length) allCodewords.push(blk[i]);
    }
    for (let i = 0; i < spec.ecPerBlock; i++) {
        for (const blk of ecBlocks) allCodewords.push(blk[i]);
    }

    const size = ver * 4 + 17;
    const modules = new Uint8Array(size * size);
    const reserved = new Uint8Array(size * size);

    const setFixed = (r, c, val) => {
        if (r < 0 || r >= size || c < 0 || c >= size) return;
        modules[r * size + c] = val ? 1 : 0;
        reserved[r * size + c] = 1;
    };

    const drawFinder = (r0, c0) => {
        for (let dr = -1; dr <= 7; dr++) {
            for (let dc = -1; dc <= 7; dc++) {
                const r = r0 + dr;
                const c = c0 + dc;
                if (r < 0 || r >= size || c < 0 || c >= size) continue;
                const on = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6 &&
                    (dr === 0 || dr === 6 || dc === 0 || dc === 6 || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4));
                setFixed(r, c, on);
            }
        }
    };

    drawFinder(0, 0);
    drawFinder(0, size - 7);
    drawFinder(size - 7, 0);

    for (let i = 8; i < size - 8; i++) {
        setFixed(6, i, i % 2 === 0);
        setFixed(i, 6, i % 2 === 0);
    }

    for (const r0 of spec.align) {
        for (const c0 of spec.align) {
            if (reserved[r0 * size + c0]) continue;
            for (let dr = -2; dr <= 2; dr++) {
                for (let dc = -2; dc <= 2; dc++) {
                    const dist = Math.max(Math.abs(dr), Math.abs(dc));
                    setFixed(r0 + dr, c0 + dc, dist !== 1);
                }
            }
        }
    }

    // Reserve format areas + dark module
    for (let i = 0; i < 9; i++) {
        if (i !== 6) {
            reserved[8 * size + i] = 1;
            reserved[i * size + 8] = 1;
        }
    }
    for (let i = 0; i < 8; i++) {
        reserved[8 * size + (size - 1 - i)] = 1;
        if (i < 7) reserved[(size - 1 - i) * size + 8] = 1;
    }
    setFixed(size - 8, 8, 1);

    if (ver >= 7) {
        const vBits = bchVersionBits(ver);
        for (let i = 0; i < 18; i++) {
            const bit = (vBits >> i) & 1;
            const a = Math.floor(i / 3);
            const b = size - 11 + (i % 3);
            setFixed(a, b, bit);
            setFixed(b, a, bit);
        }
    }

    // Place data bits in upward/downward 2-column zig-zag
    let bitIdx = 0;
    const totalBits = allCodewords.length * 8;
    let upward = true;
    for (let right = size - 1; right >= 1; right -= 2) {
        if (right === 6) right = 5;
        for (let step = 0; step < size; step++) {
            const r = upward ? size - 1 - step : step;
            for (const c of [right, right - 1]) {
                const idx = r * size + c;
                if (reserved[idx]) continue;
                let bit = 0;
                if (bitIdx < totalBits) {
                    const byte = allCodewords[bitIdx >> 3];
                    bit = (byte >> (7 - (bitIdx & 7))) & 1;
                    bitIdx++;
                }
                modules[idx] = bit;
            }
        }
        upward = !upward;
    }

    // Exact ISO/IEC 18004 bit mapping (bit 14 MSB -> bit 0 LSB):
    const writeFormat = (grid, mask) => {
        const fmt = bchFormatBits(mask);
        // Horizontal row 8 left + vertical col 8 top (bit 14 down to bit 0)
        const seqA = [
            [8, 0], [8, 1], [8, 2], [8, 3], [8, 4], [8, 5], [8, 7], [8, 8],
            [7, 8], [5, 8], [4, 8], [3, 8], [2, 8], [1, 8], [0, 8]
        ];
        // Vertical col 8 bottom + horizontal row 8 right (bit 14 down to bit 0)
        const seqB = [
            [size - 1, 8], [size - 2, 8], [size - 3, 8], [size - 4, 8], [size - 5, 8], [size - 6, 8], [size - 7, 8],
            [8, size - 8], [8, size - 7], [8, size - 6], [8, size - 5], [8, size - 4], [8, size - 3], [8, size - 2], [8, size - 1]
        ];
        for (let k = 0; k < 15; k++) {
            const bit = (fmt >> (14 - k)) & 1;
            const [ra, ca] = seqA[k];
            const [rb, cb] = seqB[k];
            grid[ra * size + ca] = bit;
            grid[rb * size + cb] = bit;
        }
    };

    let bestMask = 0;
    let bestScore = Infinity;
    let bestGrid = null;

    for (let mask = 0; mask < 8; mask++) {
        const candidate = new Uint8Array(modules);
        for (let r = 0; r < size; r++) {
            for (let c = 0; c < size; c++) {
                const idx = r * size + c;
                if (!reserved[idx] && maskBit(mask, r, c)) {
                    candidate[idx] ^= 1;
                }
            }
        }
        writeFormat(candidate, mask);
        const s = penaltyScore(candidate, size);
        if (s < bestScore) {
            bestScore = s;
            bestMask = mask;
            bestGrid = candidate;
        }
    }

    void bestMask;
    return { size, grid: bestGrid };
}

export function createQrSvg(text, { label = '', margin = 2 } = {}) {
    const { size, grid } = encodeQrMatrix(text);
    const total = size + margin * 2;
    const cmds = [];
    for (let r = 0; r < size; r++) {
        for (let c = 0; c < size; c++) {
            if (grid[r * size + c]) {
                cmds.push(`M${c + margin},${r + margin}h1v1h-1z`);
            }
        }
    }
    return svgEl('svg', {
        class: 'qr-svg',
        viewBox: `0 0 ${total} ${total}`,
        width: '176',
        height: '176',
        role: 'img',
        'aria-label': label || text
    },
        svgEl('rect', { x: '0', y: '0', width: String(total), height: String(total), fill: '#FAF7F0' }),
        svgEl('path', { d: cmds.join(''), fill: '#0F0A08' })
    );
}
