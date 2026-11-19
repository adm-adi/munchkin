/**
 * Lightweight Web Audio synthesizer and haptics helper for the Web PWA.
 * Zero external assets (strict CSP safe) and gracefully no-ops in Node/unsupported browsers.
 */

const SOUND_KEY = 'munchkin.sound';
const HAPTICS_KEY = 'munchkin.haptics';

let audioCtx = null;

function readBoolPref(key, fallback) {
    try {
        if (typeof localStorage === 'undefined') return fallback;
        const raw = localStorage.getItem(key);
        if (raw === null) return fallback;
        return raw === '1' || raw === 'true';
    } catch {
        return fallback;
    }
}

function writeBoolPref(key, value) {
    try {
        if (typeof localStorage === 'undefined') return;
        localStorage.setItem(key, value ? '1' : '0');
    } catch {
        // Ignore storage quota or private-mode failures.
    }
}

export function isSoundEnabled() {
    return readBoolPref(SOUND_KEY, true);
}

export function setSoundEnabled(enabled) {
    writeBoolPref(SOUND_KEY, Boolean(enabled));
}

export function isHapticsEnabled() {
    return readBoolPref(HAPTICS_KEY, true);
}

export function setHapticsEnabled(enabled) {
    writeBoolPref(HAPTICS_KEY, Boolean(enabled));
}

function getAudioContext() {
    if (typeof window === 'undefined') return null;
    const Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) return null;
    if (!audioCtx) {
        try {
            audioCtx = new Ctor();
        } catch {
            return null;
        }
    }
    if (audioCtx.state === 'suspended') {
        audioCtx.resume().catch(() => {});
    }
    return audioCtx;
}

function vibrate(pattern) {
    if (!isHapticsEnabled()) return;
    try {
        if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function') {
            navigator.vibrate(pattern);
        }
    } catch {
        // Ignore unsupported vibration.
    }
}

function scheduleTone(ctx, { freq, endFreq = freq, startOffset = 0, duration = 0.12, type = 'triangle', gain = 0.14 }) {
    const now = ctx.currentTime + startOffset;
    const osc = ctx.createOscillator();
    const amp = ctx.createGain();
    osc.type = type;
    osc.frequency.setValueAtTime(freq, now);
    if (endFreq !== freq) {
        osc.frequency.exponentialRampToValueAtTime(Math.max(40, endFreq), now + duration);
    }
    amp.gain.setValueAtTime(0.001, now);
    amp.gain.exponentialRampToValueAtTime(gain, now + 0.015);
    amp.gain.exponentialRampToValueAtTime(0.001, now + duration);
    osc.connect(amp);
    amp.connect(ctx.destination);
    osc.start(now);
    osc.stop(now + duration + 0.02);
}

export function playTap() {
    vibrate(12);
    if (!isSoundEnabled()) return;
    const ctx = getAudioContext();
    if (!ctx) return;
    scheduleTone(ctx, { freq: 520, endFreq: 380, duration: 0.05, type: 'sine', gain: 0.06 });
}

export function playDiceRoll() {
    vibrate([18, 30, 18, 30, 28]);
    if (!isSoundEnabled()) return;
    const ctx = getAudioContext();
    if (!ctx) return;
    const freqs = [280, 420, 340, 520, 660];
    freqs.forEach((f, idx) => {
        scheduleTone(ctx, { freq: f, startOffset: idx * 0.045, duration: 0.045, type: 'triangle', gain: 0.1 });
    });
}

export function playVictory() {
    vibrate([30, 40, 30, 40, 80]);
    if (!isSoundEnabled()) return;
    const ctx = getAudioContext();
    if (!ctx) return;
    const notes = [523.25, 659.25, 783.99, 1046.5];
    notes.forEach((f, idx) => {
        scheduleTone(ctx, { freq: f, startOffset: idx * 0.09, duration: idx === 3 ? 0.32 : 0.12, type: 'triangle', gain: 0.15 });
    });
}

export function playDefeat() {
    vibrate([60, 50, 90]);
    if (!isSoundEnabled()) return;
    const ctx = getAudioContext();
    if (!ctx) return;
    scheduleTone(ctx, { freq: 320, endFreq: 240, startOffset: 0, duration: 0.18, type: 'sawtooth', gain: 0.09 });
    scheduleTone(ctx, { freq: 230, endFreq: 145, startOffset: 0.18, duration: 0.28, type: 'sawtooth', gain: 0.1 });
}

export function playTimerWarning() {
    vibrate([25, 35, 25]);
    if (!isSoundEnabled()) return;
    const ctx = getAudioContext();
    if (!ctx) return;
    scheduleTone(ctx, { freq: 880, startOffset: 0, duration: 0.08, type: 'square', gain: 0.07 });
    scheduleTone(ctx, { freq: 880, startOffset: 0.13, duration: 0.08, type: 'square', gain: 0.07 });
}

export function playUndo() {
    vibrate(20);
    if (!isSoundEnabled()) return;
    const ctx = getAudioContext();
    if (!ctx) return;
    scheduleTone(ctx, { freq: 580, endFreq: 310, duration: 0.14, type: 'sine', gain: 0.1 });
}
