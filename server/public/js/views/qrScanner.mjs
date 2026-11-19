/**
 * Browser QR scanner modal for joining or watching rooms directly from the Web PWA.
 * Uses native BarcodeDetector + getUserMedia with clean fallback when unavailable.
 */

import { h, openModal } from '../ui.mjs';
import { t } from '../i18n.mjs';
import { LIMITS } from '../protocol.mjs';

/**
 * Extracts a room join code from a universal QR URL, query string, legacy JSON, or raw code.
 * Deliberately ignores any wsUrl field so clients never connect to untrusted hosts.
 */
export function extractJoinCode(raw) {
    const text = String(raw || '').trim();
    if (!text) return null;

    // 1. Legacy JSON payload: { "joinCode": "ABCD2345", ... }
    if (text.startsWith('{') && text.endsWith('}')) {
        try {
            const parsed = JSON.parse(text);
            const candidate = String(parsed.joinCode || parsed.code || '').trim().toUpperCase();
            if (/^[A-Z0-9]{6,12}$/.test(candidate)) {
                return candidate.slice(0, LIMITS.JOIN_CODE_LENGTH);
            }
        } catch {
            // Fall through to URL/plain matching
        }
    }

    // 2. Universal join / TV URL: #/join/CODE, /join/CODE, #/tv/CODE, ?join=CODE
    const urlMatch = text.match(/(?:[#/?&](?:join|tv)[/=])([A-Za-z0-9]{6,12})/i);
    if (urlMatch && urlMatch[1]) {
        return urlMatch[1].toUpperCase().slice(0, LIMITS.JOIN_CODE_LENGTH);
    }

    // 3. Bare alphanumeric join code
    const bare = text.toUpperCase();
    if (/^[A-Z0-9]{6,12}$/.test(bare)) {
        return bare.slice(0, LIMITS.JOIN_CODE_LENGTH);
    }
    return null;
}

export function isQrScannerSupported() {
    return typeof navigator !== 'undefined' &&
        Boolean(navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function') &&
        typeof window !== 'undefined' &&
        'BarcodeDetector' in window;
}

export function openQrScannerModal({ onCodeDetected }) {
    let stream = null;
    let rafId = null;
    let stopped = false;

    const cleanup = () => {
        stopped = true;
        if (rafId !== null) {
            cancelAnimationFrame(rafId);
            rafId = null;
        }
        if (stream) {
            for (const track of stream.getTracks()) {
                try { track.stop(); } catch { /* ignore */ }
            }
            stream = null;
        }
    };

    const statusEl = h('p', { class: 'muted-text' }, t('qr.starting'));
    const videoEl = h('video', {
        class: 'qr-video',
        autoplay: 'true',
        muted: 'true',
        playsinline: 'true'
    });

    const modal = openModal(
        t('qr.scanTitle'),
        [
            h('div', { class: 'qr-scanner-frame' }, videoEl),
            statusEl
        ],
        [
            h('button', {
                class: 'btn btn-secondary',
                type: 'button',
                onclick: () => modal.close()
            }, t('common.cancel'))
        ],
        { onClose: cleanup }
    );

    if (!isQrScannerSupported()) {
        statusEl.textContent = t('qr.unsupported');
        statusEl.className = 'danger-text';
        return;
    }

    (async () => {
        try {
            const Detector = window.BarcodeDetector;
            const detector = new Detector({ formats: ['qr_code'] });
            stream = await navigator.mediaDevices.getUserMedia({
                video: { facingMode: { ideal: 'environment' } },
                audio: false
            });
            if (stopped) {
                cleanup();
                return;
            }
            videoEl.srcObject = stream;
            await videoEl.play();
            statusEl.textContent = t('qr.pointingHint');

            const tick = async () => {
                if (stopped) return;
                try {
                    if (videoEl.readyState >= 2) {
                        const codes = await detector.detect(videoEl);
                        for (const item of codes) {
                            const extracted = extractJoinCode(item.rawValue);
                            if (extracted) {
                                modal.close();
                                onCodeDetected(extracted);
                                return;
                            }
                        }
                    }
                } catch {
                    // Ignore transient frame decode errors
                }
                if (!stopped) {
                    rafId = requestAnimationFrame(tick);
                }
            };
            rafId = requestAnimationFrame(tick);
        } catch {
            if (!stopped) {
                statusEl.textContent = t('qr.permissionError');
                statusEl.className = 'danger-text';
            }
        }
    })();
}
