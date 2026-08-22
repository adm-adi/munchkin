/**
 * Small DOM helpers: hyperscript, toasts, and a modal layer.
 *
 * Views re-render from scratch on every state change; modals live in their own
 * layer outside the re-rendered screen so an incoming snapshot does not close
 * the dialog the player is typing into.
 */

const BOOLEAN_PROPS = new Set(['value', 'checked', 'disabled', 'selected', 'readOnly', 'autofocus']);

export function h(tag, props = {}, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
        if (value === null || value === undefined || value === false) continue;
        if (key === 'class') {
            el.className = value;
        } else if (key.startsWith('on') && typeof value === 'function') {
            el.addEventListener(key.slice(2).toLowerCase(), value);
        } else if (key === 'dataset') {
            Object.assign(el.dataset, value);
        } else if (key === 'style' && typeof value === 'object') {
            Object.assign(el.style, value);
        } else if (BOOLEAN_PROPS.has(key)) {
            el[key] = value;
        } else {
            el.setAttribute(key, value === true ? '' : value);
        }
    }
    appendChildren(el, children);
    return el;
}

function appendChildren(el, children) {
    for (const child of children) {
        if (child === null || child === undefined || child === false || child === true) continue;
        if (Array.isArray(child)) {
            appendChildren(el, child);
        } else {
            el.append(child instanceof Node ? child : document.createTextNode(String(child)));
        }
    }
}

export function clear(el) {
    while (el.firstChild) el.removeChild(el.firstChild);
}

export function fmtSigned(n) {
    return n >= 0 ? `+${n}` : String(n);
}

// ============== Toasts ==============

let toastContainer = null;

export function toast(message, kind = 'info') {
    if (!toastContainer) {
        toastContainer = h('div', { class: 'toasts' });
        document.body.append(toastContainer);
    }
    const el = h('div', { class: `toast toast-${kind}` }, message);
    toastContainer.append(el);
    setTimeout(() => el.classList.add('toast-out'), 3200);
    setTimeout(() => el.remove(), 3600);
}

// ============== Modals ==============

let modalLayer = null;
const openModals = new Set();

function layer() {
    if (!modalLayer) {
        modalLayer = h('div', { class: 'modal-layer' });
        document.body.append(modalLayer);
    }
    return modalLayer;
}

/**
 * Opens a modal. Returns close(). `dismissible` closes on backdrop tap.
 */
export function openModal({ title = null, content, actions = [], dismissible = true, class: extraClass = '' }) {
    const card = h('div', { class: `modal-card ${extraClass}` },
        title ? h('h2', { class: 'modal-title' }, title) : null,
        h('div', { class: 'modal-content' }, content),
        actions.length
            ? h('div', { class: 'modal-actions' }, actions)
            : null
    );
    const backdrop = h('div', {
        class: 'modal-backdrop',
        onClick: e => {
            if (dismissible && e.target === backdrop) close();
        }
    }, card);

    layer().append(backdrop);
    openModals.add(backdrop);

    function close() {
        openModals.delete(backdrop);
        backdrop.remove();
    }
    return close;
}

export function closeAllModals() {
    for (const backdrop of openModals) backdrop.remove();
    openModals.clear();
}

/** Yes/no confirmation as a promise. */
export function confirmDialog({ title, text, yes = 'Confirmar', no = 'Cancelar', danger = false }) {
    return new Promise(resolve => {
        const close = openModal({
            title,
            content: text ? h('p', {}, text) : null,
            dismissible: false,
            actions: [
                h('button', {
                    class: 'btn btn-ghost',
                    onClick: () => { close(); resolve(false); }
                }, no),
                h('button', {
                    class: `btn ${danger ? 'btn-danger' : 'btn-primary'}`,
                    onClick: () => { close(); resolve(true); }
                }, yes)
            ]
        });
    });
}
