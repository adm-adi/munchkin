/**
 * Munchkin Mesa Tracker — Web PWA Client (Dungeon Edition).
 *
 * Authoritative client-server SPA: no client mutates game state locally;
 * every action emits a WebSocket message/event and renders server state
 * incrementally via patchScreen() while preserving focus, inputs, and scroll.
 * Strict CSP compliant: zero inline styles or script evals.
 */

import {
    CLASSES, RACES, GENDERS, PHASES, OUTCOMES, BONUS_TARGETS, LIMITS,
    uuid, messages, events, playerMeta, monsterInstance, tempBonus
} from './protocol.mjs';
import {
    playerList, canStart, tiedPlayerIds, allPlayersRolled,
    combatPower, canBeConfirmedWinner, activeClasses, activeRaces
} from './engine.mjs';
import {
    calculateResult, getBreakdown, runAwayModifiers, runAwayBonus, RUN_AWAY_THRESHOLD
} from './combat.mjs';
import {
    GameSocket, ConnState, oneOffRequest, authenticatedRequest
} from './net.mjs';
import { session } from './session.mjs';
import {
    SUPPORTED_LANGS, LANG_LABELS, EXPANSIONS, AVATARS, avatarEmoji,
    getLang, setLang, t, classLabel, raceLabel, genderLabel,
    avatarName, expansionLabel, abilitiesFor, errorText
} from './i18n.mjs';
import {
    h, createQrSvg, patchScreen, fmtSigned, toast,
    openModal, closeAllModals, confirmDialog
} from './ui.mjs';

const WS_URL = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}`;

if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
}

const DICE_FACES = ['⚀', '⚁', '⚂', '⚃', '⚄', '⚅'];

function initJoinDeepLink() {
    const params = new URLSearchParams(location.search);
    const joinParam = params.get('join');
    if (!joinParam) return;
    const cleanCode = joinParam.trim().toUpperCase().slice(0, LIMITS.JOIN_CODE_LENGTH);
    if (!cleanCode) return;
    if (!location.hash || location.hash === '#' || location.hash === '#/') {
        const cleanUrl = `${location.pathname}#/join/${encodeURIComponent(cleanCode)}`;
        history.replaceState(null, '', cleanUrl);
    }
}
initJoinDeepLink();

const initialId = (() => {
    const profile = session.profile;
    const id = session.identity;
    return {
        name: id?.name || profile?.username || '',
        avatarId: id?.avatarId ?? profile?.avatarId ?? 0,
        gender: id?.gender || profile?.gender || 'NA'
    };
})();

const app = {
    socket: null,
    game: null,
    prevGame: null,
    myPlayerId: null,
    conn: ConnState.DISCONNECTED,
    connAttempt: 0,
    busy: false,
    profileBusy: false,
    combatBusy: false,
    viewBoardDuringCombat: false,
    combatWasActive: false,
    pendingOutcomeWasWin: false,
    pendingBadStuff: null,
    pendingRunAwayFinish: null,
    winnerPromptDismissed: new Set(),
    winnerPromptOpen: false,
    breakdownOpen: false,
    heroModal: null,
    heroModalPlayerId: null,
    badStuffModal: null,
    badStuffMonsters: [],
    logDrawer: null,
    log: [],
    routeGen: 0,
    routeAbort: new AbortController(),
    lastRenderedRouteKey: null,
    drafts: {
        authTab: 'login',
        authEmail: '',
        authPassword: '',
        authUsername: '',
        authAvatar: initialId.avatarId,
        authGender: initialId.gender,
        identityName: initialId.name,
        identityAvatar: initialId.avatarId,
        identityGender: initialId.gender,
        epicMode: false,
        turnTimerSeconds: 0,
        joinCode: '',
        prefillCodeApplied: null
    },
    lobbies: {
        items: [],
        loading: false,
        error: null,
        loadedGen: -1
    },
    ranking: {
        items: [],
        me: null,
        loading: false,
        error: null,
        loadedGen: -1
    },
    history: {
        items: [],
        stats: { wins: 0, gamesPlayed: 0 },
        loading: false,
        error: null,
        loadedGen: -1
    },
    catalog: {
        query: '',
        expansion: '',
        items: [],
        limit: 20,
        offset: 0,
        hasMore: false,
        loading: false,
        loadingMore: false,
        error: null,
        loadedGen: -1,
        debounceTimer: null
    }
};

/* ==========================================================================
   Routing & Async Route Cancellation
   ========================================================================== */

function route() {
    const hash = location.hash || '#/';
    const parts = hash.slice(2).split('/').filter(Boolean);
    let arg = parts[1] || null;
    if (arg) {
        try { arg = decodeURIComponent(arg); } catch { /* keep raw */ }
    }
    return { name: parts[0] || 'home', arg };
}

function bumpRouteToken() {
    app.routeGen += 1;
    app.routeAbort.abort();
    app.routeAbort = new AbortController();
    return { gen: app.routeGen, signal: app.routeAbort.signal };
}

function isCurrentRoute(gen, signal) {
    return app.routeGen === gen && !signal?.aborted;
}

function nav(hash) {
    if (location.hash === hash) {
        bumpRouteToken();
        render();
    } else {
        location.hash = hash;
    }
}

/* ==========================================================================
   Game Log (Diffed from Server State Changes)
   ========================================================================== */

function pushLog(text) {
    if (!text) return;
    const now = new Date();
    const hh = String(now.getHours()).padStart(2, '0');
    const mm = String(now.getMinutes()).padStart(2, '0');
    const ss = String(now.getSeconds()).padStart(2, '0');
    app.log.unshift({ id: uuid(), time: `${hh}:${mm}:${ss}`, text });
    if (app.log.length > 80) app.log.length = 80;
    if (app.logDrawer) refreshLogDrawerBody();
}

function diffGameLog(prev, next) {
    if (!next) return;
    if (!prev) {
        if (next.phase === PHASES.IN_GAME && next.turnPlayerId) {
            const tp = next.players?.[next.turnPlayerId];
            if (tp) pushLog(t('log.turnOf', { name: tp.name }));
        }
        return;
    }

    const prevPlayers = prev.players || {};
    const nextPlayers = next.players || {};

    for (const [pid, p] of Object.entries(nextPlayers)) {
        const old = prevPlayers[pid];
        if (!old) {
            pushLog(t('log.playerJoined', { name: p.name }));
            continue;
        }
        if ((old.level ?? 1) !== (p.level ?? 1)) {
            pushLog(t('log.levelChange', { name: p.name, level: p.level }));
        }
        if ((old.gearBonus ?? 0) !== (p.gearBonus ?? 0)) {
            pushLog(t('log.gearChange', { name: p.name, gear: fmtSigned(p.gearBonus) }));
        }
        if (next.phase === PHASES.LOBBY && p.lastRoll && p.lastRoll !== old.lastRoll) {
            pushLog(t('log.rolled', { name: p.name, roll: p.lastRoll }));
        }
    }

    for (const [pid, old] of Object.entries(prevPlayers)) {
        if (!nextPlayers[pid]) {
            pushLog(t('log.playerLeft', { name: old.name }));
        }
    }

    if (prev.hostId && next.hostId && prev.hostId !== next.hostId) {
        const newHost = nextPlayers[next.hostId];
        if (newHost) pushLog(t('log.hostTransferred', { name: newHost.name }));
    }

    if (prev.phase !== next.phase && next.phase === PHASES.IN_GAME) {
        pushLog(t('log.gameStarted'));
    }

    if (next.phase === PHASES.IN_GAME && next.turnPlayerId && prev.turnPlayerId !== next.turnPlayerId) {
        const tp = nextPlayers[next.turnPlayerId];
        if (tp) pushLog(t('log.turnOf', { name: tp.name }));
    }

    if (!prev.combat && next.combat) {
        const mainP = nextPlayers[next.combat.mainPlayerId];
        pushLog(t('log.combatStarted', { name: mainP?.name || '?' }));
    } else if (prev.combat && !next.combat) {
        pushLog(app.pendingOutcomeWasWin ? t('log.combatWon') : t('log.combatEnded'));
    }

    const prevRoll = prev.combat?.lastDiceRoll;
    const nextRoll = next.combat?.lastDiceRoll;
    if (nextRoll && (!prevRoll || prevRoll.timestamp !== nextRoll.timestamp || prevRoll.playerId !== nextRoll.playerId || prevRoll.result !== nextRoll.result)) {
        if (nextRoll.purpose === 'RUN_AWAY') {
            pushLog(t(nextRoll.success ? 'log.runAwayOk' : 'log.runAwayFail', {
                name: nextRoll.playerName,
                roll: nextRoll.result
            }));
        } else {
            pushLog(t('log.rolled', { name: nextRoll.playerName, roll: nextRoll.result }));
        }
    }
}

function logDrawerNodes() {
    return app.log.length === 0
        ? [h('p', { class: 'muted-text' }, t('board.logEmpty'))]
        : [h('div', { class: 'log-list', dataset: { scrollKey: 'game-log-list' } },
            app.log.map(entry => h('div', { class: 'log-row', dataset: { key: `log-${entry.id}` } },
                h('span', {}, entry.text),
                h('span', { class: 'log-time tabular' }, entry.time)
            ))
        )];
}

function refreshLogDrawerBody() {
    if (!app.logDrawer) return;
    app.logDrawer.setBody(logDrawerNodes());
}

function openLogDrawer() {
    if (app.logDrawer) return;
    const prevFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const bodyEl = h('div', { class: 'grow' });
    let closed = false;

    const close = () => {
        if (closed) return;
        closed = true;
        document.removeEventListener('keydown', onKey, true);
        if (backdrop.isConnected) backdrop.remove();
        app.logDrawer = null;
        if (prevFocus && prevFocus.isConnected) {
            try { prevFocus.focus({ preventScroll: true }); } catch { prevFocus.focus(); }
        }
    };

    const setBody = (nodes) => patchScreen(bodyEl, nodes, { preserveFocus: false });
    setBody(logDrawerNodes());

    const closeBtn = h('button', {
        class: 'icon-btn',
        'aria-label': t('common.close'),
        'data-autofocus': 'true',
        onclick: close
    }, '✕');

    const drawer = h('aside', {
        class: 'log-drawer',
        role: 'dialog',
        'aria-modal': 'true',
        'aria-labelledby': 'log-drawer-title',
        tabindex: '-1'
    },
        h('div', { class: 'row-between' },
            h('h2', { class: 'modal-title', id: 'log-drawer-title' }, t('board.logTitle')),
            closeBtn
        ),
        h('hr', { class: 'divider' }),
        bodyEl
    );

    const backdrop = h('div', {
        class: 'drawer-backdrop',
        onclick: (e) => {
            if (e.target === backdrop) close();
        }
    }, drawer);

    const onKey = (e) => {
        if (e.key === 'Escape') {
            e.preventDefault();
            e.stopPropagation();
            close();
            return;
        }
        if (e.key === 'Tab') {
            e.preventDefault();
            closeBtn.focus();
        }
    };

    app.logDrawer = { close, setBody };
    document.body.appendChild(backdrop);
    document.addEventListener('keydown', onKey, true);
    queueMicrotask(() => {
        if (!closed) closeBtn.focus();
    });
}

/* ==========================================================================
   WebSocket & Game State Lifecycle
   ========================================================================== */

function attachSocket(socket) {
    app.socket = socket;
    socket.on('state', (gameState) => {
        const prev = app.prevGame;
        app.game = gameState;
        app.prevGame = gameState;
        onGameStateChanged(prev, gameState);
        render();
    });
    socket.on('welcome', (welcome) => {
        app.myPlayerId = welcome.yourPlayerId;
        app.prevGame = null;
        app.game = welcome.gameState;
        app.log = [];
        persistSeat(welcome);
        onGameStateChanged(null, welcome.gameState);
    });
    socket.on('connection', ({ state, attempt }) => {
        app.conn = state;
        app.connAttempt = attempt;
        render();
    });
    socket.on('dice', (msg) => {
        if (msg?.playerName && msg?.result) {
            pushLog(t('log.rolled', { name: msg.playerName, roll: msg.result }));
            toast(t('board.rolledToast', { name: msg.playerName, roll: msg.result }), 'info');
        }
    });
    socket.on('error', (err) => {
        app.combatBusy = false;
        app.pendingOutcomeWasWin = false;
        app.pendingBadStuff = null;
        app.pendingRunAwayFinish = null;
        toast(errorText(err), 'err');
        render();
    });
    socket.on('deleted', (reason) => {
        toast(reason || t('conn.gameDeleted'), 'warn');
        cleanupGame();
        nav('#/home');
    });
}

function persistSeat(welcome) {
    const state = welcome.gameState || app.game;
    if (!state) return;
    const target = state.players?.[welcome.yourPlayerId];
    session.saveSeat({
        joinCode: state.joinCode,
        playerId: welcome.yourPlayerId,
        reconnectToken: welcome.reconnectToken || null,
        name: target?.name || currentIdentity().name,
        avatarId: target?.avatarId ?? 0,
        gender: target?.gender || 'NA'
    });
}

function cleanupGame() {
    app.socket?.leave();
    app.socket = null;
    app.game = null;
    app.prevGame = null;
    app.myPlayerId = null;
    app.conn = ConnState.DISCONNECTED;
    app.combatBusy = false;
    app.combatWasActive = false;
    app.viewBoardDuringCombat = false;
    app.pendingOutcomeWasWin = false;
    app.pendingBadStuff = null;
    app.pendingRunAwayFinish = null;
    app.winnerPromptDismissed = new Set();
    app.winnerPromptOpen = false;
    app.heroModal = null;
    app.heroModalPlayerId = null;
    app.badStuffModal = null;
    app.badStuffMonsters = [];
    if (app.logDrawer) app.logDrawer.close();
    app.log = [];
    session.clearSeat();
    closeAllModals();
}

function onGameStateChanged(prevState, nextState) {
    diffGameLog(prevState, nextState);

    const combatActive = Boolean(nextState?.combat);
    if (combatActive && !app.combatWasActive) {
        app.viewBoardDuringCombat = false;
    }

    // Complete pending Run Away after PLAYER_ROLL updates combat.lastDiceRoll and seq
    if (app.pendingRunAwayFinish && nextState) {
        const pending = app.pendingRunAwayFinish;
        const roll = nextState.combat?.lastDiceRoll;
        const rollMatched = roll && roll.purpose === 'RUN_AWAY' && roll.playerId === pending.actorId && roll.timestamp >= pending.startedAt - 2000;
        const seqAdvanced = (nextState.seq ?? 0) > (pending.baseSeq ?? -1);
        if (!nextState.combat) {
            app.pendingRunAwayFinish = null;
            app.combatBusy = false;
        } else if (rollMatched || seqAdvanced) {
            app.pendingRunAwayFinish = null;
            if (pending.isMainRunner) {
                app.pendingOutcomeWasWin = false;
                sendEvent(actor => events.combatEnd(actor, OUTCOMES.ESCAPE, {
                    expectedSeq: nextState.seq,
                    cancelled: false
                }));
            } else {
                app.combatBusy = false;
                if (!pending.escaped && app.pendingBadStuff?.monsters) {
                    const mList = app.pendingBadStuff.monsters;
                    app.pendingBadStuff = null;
                    showBadStuff(mList);
                } else {
                    app.pendingBadStuff = null;
                }
            }
        }
    }

    // Combat ended on server -> unlock and trigger non-optimistic Bad Stuff ONLY after snapshot confirms combat ended in loss
    if (prevState?.combat && !nextState?.combat) {
        app.combatBusy = false;
        const pending = app.pendingBadStuff;
        const wasWin = app.pendingOutcomeWasWin;
        app.pendingBadStuff = null;
        app.pendingOutcomeWasWin = false;
        const mainId = prevState.combat.mainPlayerId;
        const mainLeveledUp = (nextState?.players?.[mainId]?.level ?? 0) > (prevState.players?.[mainId]?.level ?? 0);
        if (pending && !wasWin && !mainLeveledUp && nextState?.phase === PHASES.IN_GAME) {
            showBadStuff(pending.monsters);
        }
    }

    app.combatWasActive = combatActive;

    if (app.heroModal && app.heroModalPlayerId) {
        const target = nextState?.players?.[app.heroModalPlayerId];
        if (!target) {
            app.heroModal.close();
        } else {
            app.heroModal.setBody(heroModalNodes(target));
        }
    }

    if (app.badStuffModal) {
        app.badStuffModal.setBody(badStuffNodes(app.badStuffMonsters));
    }

    maybePromptWinner();
}

function me() {
    return app.game?.players?.[app.myPlayerId] || null;
}

function isHost() {
    return Boolean(app.game && app.myPlayerId && app.game.hostId === app.myPlayerId);
}

function sendEvent(builder) {
    if (!app.socket?.isConnected()) {
        toast(t('conn.notConnected'), 'err');
        return false;
    }
    try {
        app.socket.sendEvent(builder(app.myPlayerId));
        return true;
    } catch (err) {
        toast(errorText(err), 'err');
        return false;
    }
}

function sendMessage(msg) {
    try {
        app.socket?.sendMessage(msg);
        return true;
    } catch (err) {
        toast(errorText(err), 'err');
        return false;
    }
}

function buildSetGearEvent(actorId, gear) {
    if (typeof events.setGear === 'function') return events.setGear(actorId, gear);
    return {
        type: 'SET_GEAR',
        eventId: uuid(),
        actorId,
        timestamp: Date.now(),
        targetPlayerId: actorId,
        gear
    };
}

function currentIdentity() {
    const profile = session.profile;
    return {
        name: app.drafts.identityName.trim() || profile?.username || '',
        avatarId: app.drafts.identityAvatar ?? profile?.avatarId ?? 0,
        gender: app.drafts.identityGender || profile?.gender || 'NA'
    };
}

async function startGameAsHost({ name, avatarId, gender, epicMode, turnTimerSeconds }) {
    if (app.busy) return;
    app.busy = true;
    render();
    try {
        session.saveIdentity({ name, avatarId, gender });
        const socket = new GameSocket({ url: WS_URL });
        attachSocket(socket);
        const meta = playerMeta({ name, avatarId, gender, userId: session.profile?.id || null });
        await socket.create({
            meta,
            superMunchkin: epicMode,
            turnTimerSeconds,
            authToken: session.authToken
        });
        nav('#/game');
    } catch (err) {
        cleanupGame();
        toast(errorText(err), 'err');
    } finally {
        app.busy = false;
        render();
    }
}

async function joinGame({ joinCode, name, avatarId, gender, playerId = null, reconnectToken = null }) {
    if (app.busy) return;
    app.busy = true;
    render();
    try {
        session.saveIdentity({ name, avatarId, gender });
        const socket = new GameSocket({ url: WS_URL });
        attachSocket(socket);
        const meta = playerMeta({ playerId, name, avatarId, gender, userId: session.profile?.id || null });
        await socket.join({
            joinCode,
            meta,
            reconnectToken,
            authToken: session.authToken
        });
        nav('#/game');
    } catch (err) {
        const wasResume = Boolean(playerId);
        const roomGone = err?.code === 'INVALID_JOIN_CODE' || err?.code === 'GAME_NOT_FOUND';
        cleanupGame();
        if (wasResume && !roomGone) {
            session.saveSeat({ joinCode, playerId, reconnectToken, name, avatarId, gender });
        }
        toast(errorText(err), 'err');
    } finally {
        app.busy = false;
        render();
    }
}

function resumeSeat() {
    const seat = session.seat;
    if (!seat) return;
    joinGame({
        joinCode: seat.joinCode,
        name: seat.name,
        avatarId: seat.avatarId,
        gender: seat.gender,
        playerId: seat.playerId,
        reconnectToken: seat.reconnectToken
    });
}

async function leaveCurrentGame() {
    if (!app.game) {
        cleanupGame();
        nav('#/home');
        return;
    }
    if (isHost() && app.game.phase !== PHASES.FINISHED) {
        const del = await confirmDialog({
            title: t('leave.closeTitle'),
            message: t('leave.closeText'),
            confirmText: t('leave.closeYes'),
            danger: true
        });
        if (!del) return;
        try { sendMessage(messages.deleteGame()); } catch { /* best effort */ }
        setTimeout(() => { cleanupGame(); nav('#/home'); }, 250);
        return;
    }
    const go = app.game.phase === PHASES.FINISHED || await confirmDialog({
        title: t('leave.leaveTitle'),
        message: t('leave.leaveText'),
        confirmText: t('leave.leaveYes')
    });
    if (!go) return;
    cleanupGame();
    nav('#/home');
}

/* ==========================================================================
   Winner Confirmation & Multi-Winner Chooser (PROD-05)
   ========================================================================== */

function maybePromptWinner() {
    const state = app.game;
    if (!state || !isHost() || app.winnerPromptOpen) return;
    if (state.phase !== PHASES.IN_GAME || state.winnerId) return;

    const candidates = playerList(state).filter(p =>
        canBeConfirmedWinner(p, state.settings) && !app.winnerPromptDismissed.has(p.playerId)
    );
    if (candidates.length === 0) return;

    app.winnerPromptOpen = true;
    const maxLevel = state.settings?.maxLevel ?? 10;

    if (candidates.length > 1) {
        const modal = openModal({
            title: t('winner.chooseTitle'),
            dismissible: false,
            onClose: () => { app.winnerPromptOpen = false; },
            body: [
                h('p', { class: 'muted-text' }, t('winner.chooseText')),
                h('div', { class: 'stack-sm' }, candidates.map(c =>
                    h('button', {
                        class: 'btn btn-secondary btn-block',
                        onclick: () => {
                            app.winnerPromptOpen = false;
                            modal.close();
                            sendMessage(messages.gameOver(state.gameId, c.playerId));
                        }
                    }, `👑 ${t('winner.pickPlayer', { name: c.name, level: c.level })}`)
                )),
                h('button', {
                    class: 'btn btn-ghost btn-block',
                    onclick: () => {
                        for (const c of candidates) app.winnerPromptDismissed.add(c.playerId);
                        app.winnerPromptOpen = false;
                        modal.close();
                    }
                }, t('winner.notYet'))
            ]
        });
        return;
    }

    const candidate = candidates[0];
    const modal = openModal({
        title: `👑 ${t('winner.title')}`,
        dismissible: false,
        onClose: () => { app.winnerPromptOpen = false; },
        body: [
            h('p', {}, t('winner.message', { name: candidate.name, maxLevel })),
            h('div', { class: 'row-wrap' },
                h('button', {
                    class: 'btn btn-ghost',
                    onclick: () => {
                        app.winnerPromptDismissed.add(candidate.playerId);
                        app.winnerPromptOpen = false;
                        modal.close();
                    }
                }, t('winner.notYet')),
                h('button', {
                    class: 'btn btn-secondary',
                    'data-autofocus': 'true',
                    onclick: () => {
                        app.winnerPromptOpen = false;
                        modal.close();
                        sendMessage(messages.gameOver(state.gameId, candidate.playerId));
                    }
                }, t('winner.confirm'))
            )
        ]
    });
}

/* ==========================================================================
   Shared UI Building Blocks
   ========================================================================== */

function langSwitcher() {
    const active = getLang();
    return h('div', { class: 'lang-switch', role: 'group', 'aria-label': t('lang.label') },
        SUPPORTED_LANGS.map(code => h('button', {
            class: 'lang-btn',
            dataset: { key: `lang-${code}` },
            'aria-pressed': active === code ? 'true' : 'false',
            onclick: () => {
                if (getLang() === code) return;
                setLang(code);
                document.title = t('app.title');
                if (app.logDrawer) refreshLogDrawerBody();
                if (app.heroModal && app.heroModalPlayerId) {
                    const target = app.game?.players?.[app.heroModalPlayerId];
                    if (target) app.heroModal.setBody(heroModalNodes(target));
                }
                if (app.badStuffModal) app.badStuffModal.setBody(badStuffNodes(app.badStuffMonsters));
                render();
            }
        }, LANG_LABELS[code]))
    );
}

function connectionPill() {
    if (!app.socket) return null;
    const dotClass = app.conn === ConnState.CONNECTED ? 'is-online'
        : (app.conn === ConnState.CONNECTING || app.conn === ConnState.RECONNECTING) ? 'is-warn'
        : 'is-offline';
    const label = app.conn === ConnState.CONNECTED ? t('conn.online')
        : (app.conn === ConnState.CONNECTING || app.conn === ConnState.RECONNECTING) ? t('conn.connecting')
        : t('conn.offlineLabel');
    return h('span', { class: 'conn-pill', dataset: { key: 'conn-pill' } },
        h('span', { class: `conn-dot ${dotClass}`, 'aria-hidden': 'true' }),
        h('span', {}, label)
    );
}

function topbar(routeName) {
    const profile = session.profile;
    const inGameScreen = routeName === 'game' && Boolean(app.game);

    return h('header', { class: 'topbar', dataset: { key: 'topbar' } },
        h('div', { class: 'topbar-inner' },
            h('button', {
                class: 'brand-btn',
                'aria-label': t('app.title'),
                onclick: () => {
                    if (inGameScreen) {
                        leaveCurrentGame();
                    } else {
                        nav('#/home');
                    }
                }
            },
                h('span', { class: 'brand-mark', 'aria-hidden': 'true' }, '⚔️'),
                h('span', { class: 'brand-title' }, 'Munchkin')
            ),
            h('div', { class: 'topbar-actions' },
                connectionPill(),
                inGameScreen && h('button', {
                    class: 'icon-btn',
                    'aria-label': t('board.toggleLog'),
                    title: t('board.toggleLog'),
                    onclick: openLogDrawer
                }, '📜'),
                langSwitcher(),
                profile
                    ? h('button', {
                        class: 'user-chip',
                        onclick: openProfileModal
                    },
                        h('span', { 'aria-hidden': 'true' }, avatarEmoji(profile.avatarId, profile.gender)),
                        h('span', {}, profile.username)
                    )
                    : h('button', {
                        class: 'btn btn-ghost',
                        onclick: () => nav('#/auth')
                    }, t('home.signIn'))
            )
        )
    );
}

function connectionBanner() {
    if (!app.socket) return null;
    if (app.conn === ConnState.RECONNECTING || app.conn === ConnState.CONNECTING) {
        return h('div', { class: 'banner banner-warn', dataset: { key: 'conn-banner' }, role: 'status' },
            h('span', {}, t('conn.reconnecting', { attempt: app.connAttempt || 1 }))
        );
    }
    if (app.conn === ConnState.DISCONNECTED && app.game) {
        return h('div', { class: 'banner banner-danger', dataset: { key: 'conn-banner' }, role: 'alert' },
            h('span', {}, t('conn.offline')),
            h('button', { class: 'btn btn-ghost', onclick: () => app.socket?.nudge() }, t('conn.retry'))
        );
    }
    return null;
}

function pageHeader(title, { subtitle = null, back = null, right = null } = {}) {
    return h('div', { class: 'page-header' },
        h('div', { class: 'row' },
            back && h('button', {
                class: 'icon-btn',
                'aria-label': t('common.back'),
                onclick: back
            }, '←'),
            h('div', {},
                h('h1', { class: 'page-title' }, title),
                subtitle && h('p', { class: 'page-subtitle' }, subtitle)
            )
        ),
        right && h('div', { class: 'row-wrap' }, right)
    );
}

function avatarBadge(player) {
    return h('div', { class: 'avatar-badge', 'aria-hidden': 'true' },
        avatarEmoji(player.avatarId, player.gender)
    );
}

function playerTraitsText(p) {
    const classes = activeClasses(p).map(c => classLabel(c)).join('/');
    const races = activeRaces(p).map(r => raceLabel(r)).join('/');
    const g = genderLabel(p.gender || 'NA');
    return `${races || raceLabel('HUMAN')} · ${classes || classLabel('NONE')} · ${g}`;
}

function avatarSelector({ selectedId, gender, onSelect }) {
    return h('div', { class: 'avatar-grid', role: 'group', 'aria-label': t('identity.avatar') },
        AVATARS.map(slot => h('button', {
            class: 'avatar-tile',
            dataset: { key: `av-${slot.id}` },
            'aria-pressed': selectedId === slot.id ? 'true' : 'false',
            onclick: () => onSelect(slot.id)
        },
            h('span', { class: 'avatar-glyph', 'aria-hidden': 'true' }, gender === 'F' ? slot.f : slot.m),
            h('span', { class: 'avatar-label' }, avatarName(slot.id))
        ))
    );
}

function genderSelector({ selected, onSelect }) {
    return h('div', { class: 'segment-group', role: 'group', 'aria-label': t('identity.gender') },
        GENDERS.map(g => h('button', {
            class: 'segment',
            dataset: { key: `gen-${g}` },
            'aria-pressed': selected === g ? 'true' : 'false',
            onclick: () => onSelect(g)
        }, genderLabel(g)))
    );
}

function identityFields() {
    return h('div', { class: 'stack' },
        h('label', { class: 'field' },
            h('span', { class: 'field-label' }, t('identity.yourName')),
            h('input', {
                class: 'input',
                id: 'f-name',
                dataset: { focusKey: 'f-name' },
                maxlength: String(LIMITS.MAX_NAME_LENGTH),
                value: app.drafts.identityName,
                placeholder: t('identity.namePlaceholder'),
                autocomplete: 'nickname',
                oninput: (e) => { app.drafts.identityName = e.target.value; }
            })
        ),
        h('div', { class: 'field' },
            h('span', { class: 'field-label' }, t('identity.avatar')),
            avatarSelector({
                selectedId: app.drafts.identityAvatar,
                gender: app.drafts.identityGender,
                onSelect: (id) => {
                    app.drafts.identityAvatar = id;
                    render();
                }
            })
        ),
        h('div', { class: 'field' },
            h('span', { class: 'field-label' }, t('identity.gender')),
            genderSelector({
                selected: app.drafts.identityGender,
                onSelect: (g) => {
                    app.drafts.identityGender = g;
                    render();
                }
            })
        )
    );
}

/* ==========================================================================
   Views: Home, Profile, Hosted Games, Auth
   ========================================================================== */

function homeView() {
    const profile = session.profile;
    const seat = session.seat;
    const inGame = Boolean(app.socket && app.game);

    return h('section', { class: 'screen', dataset: { key: 'view-home' } },
        h('div', { class: 'hero-banner' },
            h('span', { class: 'hero-eyebrow' }, 'Dungeon Edition'),
            h('h1', { class: 'hero-heading' }, t('app.title')),
            h('p', { class: 'hero-copy' }, t('home.installHint'))
        ),
        (inGame || seat) && h('button', {
            class: 'list-btn card-accent',
            disabled: app.busy,
            onclick: () => (inGame ? nav('#/game') : resumeSeat())
        },
            h('div', { class: 'stack-sm' },
                h('strong', {}, inGame ? t('home.returnToGame') : t('home.resumeGame')),
                h('span', { class: 'muted-text tabular' },
                    t('home.codeLabel', { code: inGame ? app.game.joinCode : seat.joinCode }))
            ),
            h('span', { class: 'badge badge-gold' }, t('common.enter'))
        ),
        h('div', { class: 'card' },
            h('div', { class: 'action-grid' },
                h('button', {
                    class: 'btn btn-primary btn-block',
                    onclick: () => nav('#/create')
                }, `⚔️ ${t('home.newGame')}`),
                h('button', {
                    class: 'btn btn-secondary btn-block',
                    onclick: () => nav('#/join')
                }, `🔑 ${t('home.joinGame')}`),
                h('button', {
                    class: 'btn btn-ghost btn-block',
                    onclick: () => nav('#/catalog')
                }, `📖 ${t('home.catalog')}`),
                h('button', {
                    class: 'btn btn-ghost btn-block',
                    onclick: () => nav('#/ranking')
                }, `🏆 ${t('home.ranking')}`),
                profile && h('button', {
                    class: 'btn btn-ghost btn-block',
                    onclick: () => nav('#/history')
                }, `📜 ${t('home.history')}`),
                profile && h('button', {
                    class: 'btn btn-ghost btn-block',
                    onclick: openHostedGamesModal
                }, `🗂️ ${t('home.myGames')}`)
            )
        )
    );
}

function openProfileModal() {
    const profile = session.profile;
    if (!profile) return;

    const draft = {
        username: profile.username || '',
        avatarId: profile.avatarId ?? 0,
        gender: profile.gender || 'NA',
        currentPassword: '',
        newPassword: '',
        busy: false
    };

    const modal = openModal({
        title: t('profile.title'),
        dismissible: true,
        body: renderProfileNodes()
    });

    function renderProfileNodes() {
        return [
            h('div', { class: 'row' },
                h('div', { class: 'avatar-badge', 'aria-hidden': 'true' },
                    avatarEmoji(draft.avatarId, draft.gender)),
                h('div', { class: 'stack-sm grow' },
                    h('strong', {}, draft.username || profile.username),
                    h('span', { class: 'muted-text' }, profile.email || '')
                )
            ),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('profile.username')),
                h('input', {
                    class: 'input',
                    id: 'prof-username',
                    dataset: { focusKey: 'prof-username' },
                    maxlength: String(LIMITS.MAX_NAME_LENGTH),
                    value: draft.username,
                    oninput: (e) => { draft.username = e.target.value; }
                })
            ),
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('profile.avatar')),
                avatarSelector({
                    selectedId: draft.avatarId,
                    gender: draft.gender,
                    onSelect: (id) => {
                        draft.avatarId = id;
                        modal.setBody(renderProfileNodes());
                    }
                })
            ),
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('profile.gender')),
                genderSelector({
                    selected: draft.gender,
                    onSelect: (g) => {
                        draft.gender = g;
                        modal.setBody(renderProfileNodes());
                    }
                })
            ),
            h('details', { class: 'inset-band', dataset: { key: 'pw-details', preserveOpen: 'true' } },
                h('summary', {}, t('profile.changePassword')),
                h('p', { class: 'hint-text' }, t('profile.passwordRule', { min: LIMITS.MIN_PASSWORD_LENGTH })),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('profile.currentPassword')),
                    h('input', {
                        class: 'input',
                        type: 'password',
                        id: 'prof-curpw',
                        dataset: { focusKey: 'prof-curpw' },
                        autocomplete: 'current-password',
                        value: draft.currentPassword,
                        oninput: (e) => { draft.currentPassword = e.target.value; }
                    })
                ),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('profile.newPassword', { min: LIMITS.MIN_PASSWORD_LENGTH })),
                    h('input', {
                        class: 'input',
                        type: 'password',
                        id: 'prof-newpw',
                        dataset: { focusKey: 'prof-newpw' },
                        autocomplete: 'new-password',
                        value: draft.newPassword,
                        oninput: (e) => { draft.newPassword = e.target.value; }
                    })
                )
            ),
            h('div', { class: 'row-wrap' },
                h('button', {
                    class: 'btn btn-primary grow',
                    disabled: draft.busy,
                    onclick: saveProfile
                }, draft.busy ? t('common.saving') : t('profile.saveChanges')),
                h('button', {
                    class: 'btn btn-ghost',
                    disabled: draft.busy,
                    onclick: () => {
                        session.clearAuth();
                        modal.close();
                        toast(t('profile.loggedOut'), 'info');
                        render();
                    }
                }, t('profile.logout'))
            ),
            h('hr', { class: 'divider' }),
            h('div', { class: 'stack-sm' },
                h('span', { class: 'section-heading' }, t('profile.dangerZone')),
                h('button', {
                    class: 'btn btn-outline-danger btn-block',
                    disabled: draft.busy,
                    onclick: () => openDeleteAccountModal(modal)
                }, t('profile.deleteAccount'))
            )
        ];
    }

    async function saveProfile() {
        if (draft.busy || app.profileBusy) return;
        const username = draft.username.trim();
        const wantsPw = Boolean(draft.newPassword || draft.currentPassword);

        const changed = username !== profile.username ||
            draft.avatarId !== (profile.avatarId ?? 0) ||
            draft.gender !== (profile.gender || 'NA') ||
            wantsPw;

        if (!changed) {
            toast(t('profile.noChanges'), 'info');
            return;
        }
        if (wantsPw) {
            if (!draft.currentPassword) {
                toast(t('profile.currentPasswordRequired'), 'err');
                return;
            }
            if (draft.newPassword.length < LIMITS.MIN_PASSWORD_LENGTH) {
                toast(t('profile.passwordTooShort', { min: LIMITS.MIN_PASSWORD_LENGTH }), 'err');
                return;
            }
            if (draft.newPassword.toLowerCase() === username.toLowerCase()) {
                toast(t('profile.passwordMatchesUsername'), 'err');
                return;
            }
        }

        draft.busy = true;
        app.profileBusy = true;
        modal.setBody(renderProfileNodes());

        try {
            const payload = messages.updateProfile({
                userId: profile.id,
                username: username !== profile.username ? username : null,
                avatarId: draft.avatarId,
                gender: draft.gender,
                currentPassword: wantsPw ? draft.currentPassword : null,
                password: wantsPw ? draft.newPassword : null
            });
            const { reply, auth } = await authenticatedRequest(WS_URL, session.authToken, payload);
            const nextToken = reply.token || auth?.token || session.authToken;
            const nextUser = reply.user || profile;
            session.saveAuth({ token: nextToken, user: nextUser });
            app.drafts.identityName = nextUser.username || app.drafts.identityName;
            app.drafts.identityAvatar = nextUser.avatarId ?? app.drafts.identityAvatar;
            app.drafts.identityGender = nextUser.gender || app.drafts.identityGender;
            toast(t('profile.updated'), 'ok');
            modal.close();
            render();
        } catch (err) {
            toast(errorText(err), 'err');
        } finally {
            draft.busy = false;
            app.profileBusy = false;
            if (modal.card.isConnected) modal.setBody(renderProfileNodes());
        }
    }
}

function openDeleteAccountModal(parentModal) {
    let pw = '';
    let busy = false;

    const modal = openModal({
        title: t('profile.deleteConfirmTitle'),
        dismissible: true,
        body: renderNodes()
    });

    function renderNodes() {
        return [
            h('p', { class: 'muted-text' }, t('profile.deleteConfirmText')),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('profile.deletePasswordLabel')),
                h('input', {
                    class: 'input',
                    type: 'password',
                    id: 'del-acc-pw',
                    dataset: { focusKey: 'del-acc-pw' },
                    autocomplete: 'current-password',
                    'data-autofocus': 'true',
                    value: pw,
                    oninput: (e) => { pw = e.target.value; }
                })
            ),
            h('div', { class: 'row-wrap' },
                h('button', {
                    class: 'btn btn-ghost',
                    disabled: busy,
                    onclick: () => modal.close()
                }, t('common.cancel')),
                h('button', {
                    class: 'btn btn-danger',
                    disabled: busy,
                    onclick: submitDelete
                }, busy ? t('common.saving') : t('profile.deleteAccount'))
            )
        ];
    }

    async function submitDelete() {
        if (busy) return;
        if (!pw) {
            toast(t('profile.currentPasswordRequired'), 'err');
            return;
        }
        busy = true;
        modal.setBody(renderNodes());
        try {
            await authenticatedRequest(WS_URL, session.authToken, messages.deleteAccount(pw));
            cleanupGame();
            session.clearAuth();
            modal.close();
            if (parentModal) parentModal.close();
            toast(t('profile.accountDeleted'), 'ok');
            nav('#/auth');
        } catch (err) {
            toast(errorText(err), 'err');
        } finally {
            busy = false;
            if (modal.card.isConnected) modal.setBody(renderNodes());
        }
    }
}

function openHostedGamesModal() {
    if (!session.authToken) {
        nav('#/auth');
        return;
    }

    let state = { loading: true, error: null, games: [], busyId: null };
    const modal = openModal({
        title: t('hosted.title'),
        dismissible: true,
        body: renderHostedNodes()
    });

    function renderHostedNodes() {
        if (state.loading) {
            return [h('p', { class: 'muted-text', role: 'status' }, t('hosted.loading'))];
        }
        if (state.error) {
            return [
                h('p', { class: 'danger-text' }, state.error),
                h('button', { class: 'btn btn-ghost', onclick: load }, t('common.retry'))
            ];
        }
        if (state.games.length === 0) {
            return [h('p', { class: 'muted-text' }, t('hosted.empty'))];
        }
        return [
            h('div', { class: 'list-divide' }, state.games.map(g =>
                h('div', { class: 'flat-row', dataset: { key: `hg-${g.gameId}` } },
                    h('div', { class: 'stack-sm' },
                        h('strong', { class: 'tabular' }, g.joinCode),
                        h('span', { class: 'muted-text tabular' },
                            t('hosted.playersCount', {
                                count: g.playerCount,
                                phase: t(`phase.${g.phase}`)
                            }))
                    ),
                    h('div', { class: 'row-wrap' },
                        h('button', {
                            class: 'btn btn-primary',
                            disabled: state.busyId === g.gameId,
                            onclick: () => {
                                modal.close();
                                nav(`#/join/${encodeURIComponent(g.joinCode)}`);
                            }
                        }, t('common.enter')),
                        h('button', {
                            class: 'btn btn-danger',
                            disabled: state.busyId === g.gameId,
                            onclick: async () => {
                                const ok = await confirmDialog({
                                    title: t('hosted.deleteTitle', { code: g.joinCode }),
                                    message: t('hosted.deleteText'),
                                    confirmText: t('common.delete'),
                                    danger: true
                                });
                                if (!ok) return;
                                state.busyId = g.gameId;
                                modal.setBody(renderHostedNodes());
                                try {
                                    await authenticatedRequest(
                                        WS_URL, session.authToken, messages.deleteHostedGame(g.gameId));
                                    toast(t('hosted.deleted'), 'ok');
                                    load();
                                } catch (err) {
                                    state.busyId = null;
                                    toast(errorText(err), 'err');
                                    if (modal.card.isConnected) modal.setBody(renderHostedNodes());
                                }
                            }
                        }, t('common.delete'))
                    )
                )
            ))
        ];
    }

    async function load() {
        state = { ...state, loading: true, error: null, busyId: null };
        modal.setBody(renderHostedNodes());
        try {
            const { reply } = await authenticatedRequest(WS_URL, session.authToken, messages.getHostedGames());
            if (!modal.card.isConnected) return;
            state.games = reply.games || [];
            state.loading = false;
            modal.setBody(renderHostedNodes());
        } catch (err) {
            if (!modal.card.isConnected) return;
            state.error = errorText(err);
            state.loading = false;
            modal.setBody(renderHostedNodes());
        }
    }

    load();
}

function authView() {
    const mode = app.drafts.authTab;

    async function doLogin(e) {
        e.preventDefault();
        if (app.busy) return;
        const email = app.drafts.authEmail.trim();
        const password = app.drafts.authPassword;
        if (!email || !password) {
            toast(t('auth.fillCredentials'), 'err');
            return;
        }
        app.busy = true;
        render();
        try {
            const reply = await oneOffRequest(WS_URL, messages.login(email, password));
            session.saveAuth({ token: reply.token, user: reply.user });
            app.drafts.identityName = reply.user.username || app.drafts.identityName;
            app.drafts.identityAvatar = reply.user.avatarId ?? app.drafts.identityAvatar;
            app.drafts.identityGender = reply.user.gender || app.drafts.identityGender;
            app.drafts.authPassword = '';
            toast(t('auth.welcomeBack', { name: reply.user.username }), 'ok');
            nav('#/home');
        } catch (err) {
            toast(errorText(err), 'err');
        } finally {
            app.busy = false;
            render();
        }
    }

    async function doRegister(e) {
        e.preventDefault();
        if (app.busy) return;
        const username = app.drafts.authUsername.trim();
        const email = app.drafts.authEmail.trim();
        const password = app.drafts.authPassword;
        if (!username || !email || password.length < LIMITS.MIN_PASSWORD_LENGTH) {
            toast(t('auth.registerInvalid', { min: LIMITS.MIN_PASSWORD_LENGTH }), 'err');
            return;
        }
        if (password.toLowerCase() === username.toLowerCase()) {
            toast(t('profile.passwordMatchesUsername'), 'err');
            return;
        }
        app.busy = true;
        render();
        try {
            const reply = await oneOffRequest(WS_URL, messages.register({
                username,
                email,
                password,
                avatarId: app.drafts.authAvatar,
                gender: app.drafts.authGender
            }));
            session.saveAuth({ token: reply.token, user: reply.user });
            app.drafts.identityName = reply.user.username || username;
            app.drafts.identityAvatar = reply.user.avatarId ?? app.drafts.authAvatar;
            app.drafts.identityGender = reply.user.gender || app.drafts.authGender;
            app.drafts.authPassword = '';
            toast(t('auth.accountCreated', { name: reply.user.username }), 'ok');
            nav('#/home');
        } catch (err) {
            toast(errorText(err), 'err');
        } finally {
            app.busy = false;
            render();
        }
    }

    return h('section', { class: 'screen', dataset: { key: 'view-auth' } },
        pageHeader(t('auth.title'), { back: () => nav('#/home') }),
        h('div', { class: 'card' },
            h('div', { class: 'segment-group', role: 'tablist' },
                h('button', {
                    class: 'segment',
                    'aria-pressed': mode === 'login' ? 'true' : 'false',
                    onclick: () => { app.drafts.authTab = 'login'; render(); }
                }, t('auth.loginTab')),
                h('button', {
                    class: 'segment',
                    'aria-pressed': mode === 'register' ? 'true' : 'false',
                    onclick: () => { app.drafts.authTab = 'register'; render(); }
                }, t('auth.registerTab'))
            ),
            mode === 'login'
                ? h('form', { class: 'stack', onsubmit: doLogin },
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, t('auth.email')),
                        h('input', {
                            class: 'input',
                            id: 'l-email',
                            dataset: { focusKey: 'l-email' },
                            type: 'email',
                            autocomplete: 'email',
                            required: true,
                            value: app.drafts.authEmail,
                            oninput: (e) => { app.drafts.authEmail = e.target.value; }
                        })
                    ),
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, t('auth.password')),
                        h('input', {
                            class: 'input',
                            id: 'l-pass',
                            dataset: { focusKey: 'l-pass' },
                            type: 'password',
                            autocomplete: 'current-password',
                            required: true,
                            value: app.drafts.authPassword,
                            oninput: (e) => { app.drafts.authPassword = e.target.value; }
                        })
                    ),
                    h('button', {
                        class: 'btn btn-primary btn-block',
                        type: 'submit',
                        disabled: app.busy
                    }, app.busy ? t('auth.loggingIn') : t('auth.loginBtn'))
                )
                : h('form', { class: 'stack', onsubmit: doRegister },
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, t('auth.username')),
                        h('input', {
                            class: 'input',
                            id: 'r-user',
                            dataset: { focusKey: 'r-user' },
                            maxlength: String(LIMITS.MAX_NAME_LENGTH),
                            autocomplete: 'username',
                            required: true,
                            value: app.drafts.authUsername,
                            oninput: (e) => { app.drafts.authUsername = e.target.value; }
                        })
                    ),
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, t('auth.email')),
                        h('input', {
                            class: 'input',
                            id: 'r-email',
                            dataset: { focusKey: 'r-email' },
                            type: 'email',
                            autocomplete: 'email',
                            required: true,
                            value: app.drafts.authEmail,
                            oninput: (e) => { app.drafts.authEmail = e.target.value; }
                        })
                    ),
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, t('auth.passwordMin', { min: LIMITS.MIN_PASSWORD_LENGTH })),
                        h('input', {
                            class: 'input',
                            id: 'r-pass',
                            dataset: { focusKey: 'r-pass' },
                            type: 'password',
                            minlength: String(LIMITS.MIN_PASSWORD_LENGTH),
                            autocomplete: 'new-password',
                            required: true,
                            value: app.drafts.authPassword,
                            oninput: (e) => { app.drafts.authPassword = e.target.value; }
                        })
                    ),
                    h('div', { class: 'field' },
                        h('span', { class: 'field-label' }, t('identity.avatar')),
                        avatarSelector({
                            selectedId: app.drafts.authAvatar,
                            gender: app.drafts.authGender,
                            onSelect: (id) => { app.drafts.authAvatar = id; render(); }
                        })
                    ),
                    h('div', { class: 'field' },
                        h('span', { class: 'field-label' }, t('identity.gender')),
                        genderSelector({
                            selected: app.drafts.authGender,
                            onSelect: (g) => { app.drafts.authGender = g; render(); }
                        })
                    ),
                    h('button', {
                        class: 'btn btn-primary btn-block',
                        type: 'submit',
                        disabled: app.busy
                    }, app.busy ? t('auth.registering') : t('auth.registerBtn'))
                )
        )
    );
}

/* ==========================================================================
   Views: Create & Join (+ Active Lobby Discovery)
   ========================================================================== */

function createView() {
    return h('section', { class: 'screen', dataset: { key: 'view-create' } },
        pageHeader(t('create.title'), { back: () => nav('#/home') }),
        h('form', {
            class: 'card',
            onsubmit: (e) => {
                e.preventDefault();
                const { name, avatarId, gender } = currentIdentity();
                if (!name) {
                    toast(t('create.enterName'), 'err');
                    document.getElementById('f-name')?.focus();
                    return;
                }
                startGameAsHost({
                    name,
                    avatarId,
                    gender,
                    epicMode: app.drafts.epicMode,
                    turnTimerSeconds: Number(app.drafts.turnTimerSeconds) || 0
                });
            }
        },
            identityFields(),
            h('label', { class: 'checkbox-row' },
                h('input', {
                    type: 'checkbox',
                    checked: app.drafts.epicMode,
                    onchange: (e) => { app.drafts.epicMode = e.target.checked; }
                }),
                h('div', { class: 'stack-sm' },
                    h('strong', {}, t('create.epicMode')),
                    h('span', { class: 'hint-text' }, t('create.epicDesc'))
                )
            ),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('create.turnTimer')),
                h('select', {
                    class: 'select',
                    value: String(app.drafts.turnTimerSeconds),
                    onchange: (e) => { app.drafts.turnTimerSeconds = Number(e.target.value) || 0; }
                },
                    h('option', { value: '0' }, t('create.timerNone')),
                    h('option', { value: '60' }, t('create.timer1m')),
                    h('option', { value: '120' }, t('create.timer2m')),
                    h('option', { value: '300' }, t('create.timer5m'))
                )
            ),
            h('button', {
                class: 'btn btn-primary btn-block',
                type: 'submit',
                disabled: app.busy
            }, app.busy ? t('create.creating') : t('create.submit'))
        )
    );
}

async function fetchActiveLobbies(gen, signal) {
    app.lobbies.loading = true;
    app.lobbies.error = null;
    app.lobbies.loadedGen = gen;
    render();
    try {
        const msg = typeof messages.listGames === 'function' ? messages.listGames() : { type: 'LIST_GAMES' };
        const reply = await oneOffRequest(WS_URL, msg);
        if (!isCurrentRoute(gen, signal)) return;
        app.lobbies.items = reply.games || [];
        app.lobbies.loading = false;
        render();
    } catch (err) {
        if (!isCurrentRoute(gen, signal)) return;
        app.lobbies.error = errorText(err);
        app.lobbies.loading = false;
        render();
    }
}

function joinView(codeArg) {
    if (codeArg && app.drafts.prefillCodeApplied !== codeArg) {
        app.drafts.prefillCodeApplied = codeArg;
        app.drafts.joinCode = String(codeArg).trim().toUpperCase().slice(0, LIMITS.JOIN_CODE_LENGTH);
    }

    if (app.lobbies.loadedGen !== app.routeGen && !app.lobbies.loading) {
        const { routeGen, routeAbort } = app;
        queueMicrotask(() => {
            if (isCurrentRoute(routeGen, routeAbort.signal)) {
                fetchActiveLobbies(routeGen, routeAbort.signal);
            }
        });
    }

    const attemptJoin = (code) => {
        const joinCode = String(code || '').trim().toUpperCase();
        if (joinCode.length !== LIMITS.JOIN_CODE_LENGTH) {
            toast(t('join.codeLengthError', { len: LIMITS.JOIN_CODE_LENGTH }), 'err');
            return;
        }
        const { name, avatarId, gender } = currentIdentity();
        if (!name) {
            toast(t('join.enterName'), 'err');
            document.getElementById('f-name')?.focus();
            return;
        }
        joinGame({ joinCode, name, avatarId, gender });
    };

    return h('section', { class: 'screen', dataset: { key: 'view-join' } },
        pageHeader(t('join.title'), { back: () => nav('#/home') }),
        h('form', {
            class: 'card',
            onsubmit: (e) => {
                e.preventDefault();
                attemptJoin(app.drafts.joinCode);
            }
        },
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('join.codeLabel')),
                h('input', {
                    class: 'input input-code',
                    id: 'f-code',
                    dataset: { focusKey: 'f-code' },
                    maxlength: String(LIMITS.JOIN_CODE_LENGTH),
                    value: app.drafts.joinCode,
                    placeholder: 'ABCD2345',
                    autocapitalize: 'characters',
                    autocomplete: 'off',
                    spellcheck: 'false',
                    oninput: (e) => {
                        const up = e.target.value.toUpperCase();
                        e.target.value = up;
                        app.drafts.joinCode = up;
                    }
                })
            ),
            identityFields(),
            h('button', {
                class: 'btn btn-primary btn-block',
                type: 'submit',
                disabled: app.busy
            }, app.busy ? t('join.joining') : `🔑 ${t('join.submit')}`)
        ),
        h('div', { class: 'card' },
            h('div', { class: 'section-bar' },
                h('h2', { class: 'section-heading' }, t('join.activeLobbies')),
                h('button', {
                    class: 'btn btn-ghost',
                    disabled: app.lobbies.loading,
                    onclick: () => fetchActiveLobbies(app.routeGen, app.routeAbort.signal)
                }, app.lobbies.loading ? t('common.loading') : t('common.refresh'))
            ),
            app.lobbies.loading && app.lobbies.items.length === 0
                ? h('p', { class: 'muted-text' }, t('common.loading'))
                : app.lobbies.error
                    ? h('p', { class: 'danger-text' }, app.lobbies.error)
                    : app.lobbies.items.length === 0
                        ? h('p', { class: 'muted-text' }, t('join.noLobbies'))
                        : h('div', { class: 'list-divide' }, app.lobbies.items.map(g =>
                            h('div', { class: 'flat-row', dataset: { key: `lob-${g.joinCode}` } },
                                h('div', { class: 'stack-sm' },
                                    h('strong', { class: 'tabular' }, g.joinCode),
                                    h('span', { class: 'muted-text tabular' },
                                        `${t('join.hostLabel', { name: g.hostName })} · ${t('join.playersRatio', { count: g.playerCount, max: g.maxPlayers || LIMITS.MAX_PLAYERS })}`)
                                ),
                                h('button', {
                                    class: 'btn btn-secondary',
                                    disabled: app.busy,
                                    onclick: () => attemptJoin(g.joinCode)
                                }, t('join.joinOneTap'))
                            )
                        ))
        )
    );
}

/* ==========================================================================
   Views: Game Lobby (with Scannable SVG QR & Host Transfer)
   ========================================================================== */

function shareInvite() {
    const code = app.game?.joinCode;
    if (!code) return;
    const url = `${location.origin}/#/join/${code}`;
    const text = t('lobby.inviteText', { code, url });
    if (navigator.share) {
        navigator.share({ title: t('app.title'), text, url }).catch(() => {});
    } else if (navigator.clipboard) {
        navigator.clipboard.writeText(url)
            .then(() => toast(t('lobby.inviteCopied'), 'ok'))
            .catch(() => toast(url, 'info'));
    } else {
        toast(url, 'info');
    }
}

function startHint(state) {
    if (Object.keys(state.players).length < 2) return t('lobby.needMorePlayers');
    if (!allPlayersRolled(state)) return t('lobby.needRolls');
    if (tiedPlayerIds(state).length > 0) return t('lobby.resolveTie');
    return t('lobby.startGame');
}

function lobbyView() {
    const state = app.game;
    const list = playerList(state);
    const tied = tiedPlayerIds(state);
    const my = me();
    const host = isHost();
    const inviteUrl = `${location.origin}/#/join/${state.joinCode}`;

    return h('section', { class: 'screen', dataset: { key: 'view-lobby' } },
        pageHeader(t('lobby.title'), {
            back: leaveCurrentGame,
            right: h('button', {
                class: 'icon-btn',
                'aria-label': t('common.share'),
                onclick: shareInvite
            }, '📤')
        }),
        h('div', { class: 'card code-panel' },
            h('span', { class: 'muted-text' }, t('lobby.codeTapToShare')),
            h('div', { class: 'code-chip tabular' }, state.joinCode),
            h('div', { class: 'qr-frame' },
                createQrSvg(inviteUrl, { label: t('lobby.qrAlt', { code: state.joinCode }) })
            ),
            h('span', { class: 'hint-text' }, t('lobby.qrHint')),
            h('button', {
                class: 'btn btn-secondary',
                onclick: shareInvite
            }, `📋 ${t('lobby.copyLink')}`)
        ),
        h('div', { class: 'card' },
            h('h2', { class: 'section-heading' },
                t('lobby.players', { count: list.length, max: LIMITS.MAX_PLAYERS })),
            h('div', { class: 'list-divide' }, list.map((p, index) => {
                const isTied = tied.includes(p.playerId);
                return h('div', { class: 'flat-row', dataset: { key: `lp-${p.playerId}` } },
                    h('div', { class: 'row grow' },
                        avatarBadge(p),
                        h('div', { class: 'stack-sm grow' },
                            h('div', { class: 'row-wrap' },
                                h('strong', {}, p.name),
                                p.playerId === state.hostId && h('span', { class: 'badge badge-gold' }, '👑'),
                                p.playerId === app.myPlayerId && h('span', { class: 'badge' }, t('common.you'))
                            ),
                            h('span', { class: 'muted-text' },
                                p.isConnected ? t('lobby.connected') : t('lobby.disconnected'),
                                isTied ? ` · ${t('lobby.tiedHint')}` : ''
                            )
                        )
                    ),
                    h('div', { class: 'row-wrap' },
                        h('span', { class: `roll-badge ${isTied ? 'is-tied' : ''}` },
                            p.lastRoll ? DICE_FACES[p.lastRoll - 1] : '·'),
                        host && p.playerId !== app.myPlayerId && [
                            index > 0 && h('button', {
                                class: 'icon-btn',
                                'aria-label': t('lobby.moveUp'),
                                title: t('lobby.moveUp'),
                                onclick: () => sendMessage(messages.swapPlayers(list[index - 1].playerId, p.playerId))
                            }, '↑'),
                            h('button', {
                                class: 'icon-btn',
                                'aria-label': t('lobby.transferHost'),
                                title: t('lobby.transferHost'),
                                onclick: async () => {
                                    const ok = await confirmDialog({
                                        title: t('lobby.transferTitle'),
                                        message: t('lobby.transferText', { name: p.name }),
                                        confirmText: t('common.confirm')
                                    });
                                    if (ok) sendMessage(messages.transferHost(p.playerId));
                                }
                            }, '👑'),
                            h('button', {
                                class: 'icon-btn icon-btn-danger',
                                'aria-label': t('lobby.kickTitle', { name: p.name }),
                                title: t('lobby.kick'),
                                onclick: async () => {
                                    const ok = await confirmDialog({
                                        title: t('lobby.kickTitle', { name: p.name }),
                                        message: t('lobby.kickTitle', { name: p.name }),
                                        confirmText: t('lobby.kick'),
                                        danger: true
                                    });
                                    if (ok) sendMessage(messages.kickPlayer(p.playerId));
                                }
                            }, '✕')
                        ]
                    )
                );
            }))
        ),
        h('div', { class: 'bottom-bar' },
            my && (my.lastRoll === null || my.lastRoll === undefined || my.lastRoll === 0 || tied.includes(my.playerId)) && h('button', {
                class: 'btn btn-secondary btn-block',
                onclick: () => sendEvent(actor => events.playerRoll(actor, 1 + Math.floor(Math.random() * 6), 'GAME_START', false))
            }, `🎲 ${tied.includes(my.playerId) ? t('lobby.rollTie') : t('lobby.rollStart')}`),
            host
                ? h('button', {
                    class: 'btn btn-primary btn-block',
                    disabled: !canStart(state),
                    onclick: () => sendEvent(actor => events.gameStart(actor))
                }, canStart(state) ? `▶️ ${t('lobby.startGame')}` : startHint(state))
                : h('p', { class: 'muted-text center-text' }, t('lobby.waitingHost'))
        )
    );
}

/* ==========================================================================
   Views: Board & Hero Sheet Modal
   ========================================================================== */

function turnCountdownInfo() {
    const state = app.game;
    if (!state?.turnEndsAt || !app.socket) return { text: '', urgent: false };
    const remaining = Math.max(0, Math.round((state.turnEndsAt - app.socket.serverNow()) / 1000));
    const minutes = Math.floor(remaining / 60);
    const seconds = String(remaining % 60).padStart(2, '0');
    return { text: `⏱ ${minutes}:${seconds}`, urgent: remaining <= 15 };
}

function statStepper(label, value, onMinus, onPlus, { minusDisabled = false, plusDisabled = false, labelId } = {}) {
    return h('div', { class: 'stepper' },
        h('button', {
            disabled: minusDisabled,
            'aria-label': `${label} −1`,
            onclick: onMinus
        }, '−'),
        h('div', { class: 'stepper-mid' },
            h('span', { class: 'stat-num tabular', id: labelId }, String(value)),
            h('span', { class: 'stat-caption' }, label)
        ),
        h('button', {
            disabled: plusDisabled,
            'aria-label': `${label} +1`,
            onclick: onPlus
        }, '+')
    );
}

function boardView() {
    const state = app.game;
    const list = playerList(state);
    const my = me();
    const host = isHost();
    const turnPlayer = state.turnPlayerId ? state.players[state.turnPlayerId] : null;
    const myTurn = state.turnPlayerId === app.myPlayerId;
    const combat = state.combat;
    const minLevel = state.settings?.minLevel ?? 1;
    const maxLevel = state.settings?.maxLevel ?? 10;
    const countdown = turnCountdownInfo();
    const canEndTurn = myTurn || (host && turnPlayer && !turnPlayer.isConnected);

    const renderPlayerCard = (p) => {
        const isMe = p.playerId === app.myPlayerId;
        const isTurn = p.playerId === state.turnPlayerId;
        const cardCls = [
            'hero-card',
            isTurn ? 'is-turn' : '',
            isMe ? 'is-me' : '',
            p.isConnected ? '' : 'is-offline'
        ].filter(Boolean).join(' ');

        return h('article', { class: cardCls, dataset: { key: `hero-${p.playerId}` } },
            h('div', { class: 'hero-head' },
                h('div', { class: 'hero-identity' },
                    avatarBadge(p),
                    h('div', { class: 'hero-name-wrap stack-sm' },
                        h('div', { class: 'row-wrap' },
                            h('h3', { class: 'hero-name' }, p.name),
                            p.playerId === state.hostId && h('span', { class: 'badge badge-gold' }, '👑'),
                            isMe && h('span', { class: 'badge badge-crimson' }, t('common.you'))
                        ),
                        h('span', { class: 'hero-meta' }, playerTraitsText(p))
                    )
                ),
                h('div', { class: 'hero-power-box' },
                    h('div', { class: 'hero-power tabular' }, String(combatPower(p))),
                    h('div', { class: 'hero-power-label' }, t('board.power'))
                )
            ),
            isMe
                ? h('div', { class: 'stat-controls' },
                    statStepper(t('board.level'), p.level,
                        () => sendEvent(actor => events.decLevel(actor)),
                        () => sendEvent(actor => events.incLevel(actor)),
                        { minusDisabled: p.level <= minLevel, plusDisabled: p.level >= maxLevel }),
                    statStepper(t('board.gear'), p.gearBonus || 0,
                        () => sendEvent(actor => events.decGear(actor)),
                        () => sendEvent(actor => events.incGear(actor)))
                )
                : h('div', { class: 'row-wrap' },
                    h('span', { class: 'badge tabular' }, t('board.lvlShort', { n: p.level })),
                    h('span', { class: 'badge tabular' }, t('board.gearShort', { n: p.gearBonus || 0 })),
                    !p.isConnected && h('span', { class: 'badge badge-danger' }, t('lobby.disconnected'))
                ),
            h('div', { class: 'hero-actions' },
                isMe && h('div', { class: 'row-wrap' },
                    h('button', {
                        class: 'btn btn-ghost',
                        onclick: () => openHeroModal(p)
                    }, t('board.editHero')),
                    h('button', {
                        class: 'btn btn-ghost',
                        onclick: () => {
                            const roll = 1 + Math.floor(Math.random() * 6);
                            sendEvent(actor => events.playerRoll(actor, roll, 'RANDOM', true));
                            toast(t('board.rolledToast', { name: p.name, roll }), 'info');
                        }
                    }, `🎲 ${t('board.rollDie')}`),
                    h('button', {
                        class: 'btn btn-outline-danger',
                        onclick: async () => {
                            const ok = await confirmDialog({
                                title: t('board.dieConfirmTitle'),
                                message: t('board.dieConfirmText'),
                                confirmText: t('board.dieResetGear'),
                                danger: true
                            });
                            if (ok) sendEvent(actor => buildSetGearEvent(actor, 0));
                        }
                    }, `💀 ${t('board.dieResetGear')}`)
                ),
                !isMe && h('div', { class: 'row-wrap' },
                    h('button', {
                        class: 'btn btn-ghost',
                        onclick: () => openHeroModal(p)
                    }, t('hero.abilities')),
                    host && canBeConfirmedWinner(p, state.settings) && h('button', {
                        class: 'btn btn-secondary',
                        onclick: async () => {
                            const ok = await confirmDialog({
                                title: t('winner.crownTitle'),
                                message: t('winner.crownConfirm', { name: p.name }),
                                confirmText: t('results.crownAction')
                            });
                            if (ok) sendMessage(messages.gameOver(state.gameId, p.playerId));
                        }
                    }, `👑 ${t('results.crownAction')}`),
                    host && h('button', {
                        class: 'icon-btn',
                        'aria-label': t('lobby.transferHost'),
                        title: t('lobby.transferHost'),
                        onclick: async () => {
                            const ok = await confirmDialog({
                                title: t('lobby.transferTitle'),
                                message: t('lobby.transferText', { name: p.name }),
                                confirmText: t('common.confirm')
                            });
                            if (ok) sendMessage(messages.transferHost(p.playerId));
                        }
                    }, '👑'),
                    host && h('button', {
                        class: 'icon-btn icon-btn-danger',
                        'aria-label': t('lobby.kickTitle', { name: p.name }),
                        title: t('lobby.kick'),
                        onclick: async () => {
                            const ok = await confirmDialog({
                                title: t('lobby.kickTitle', { name: p.name }),
                                message: t('lobby.kickTitle', { name: p.name }),
                                confirmText: t('lobby.kick'),
                                danger: true
                            });
                            if (ok) sendMessage(messages.kickPlayer(p.playerId));
                        }
                    }, '✕')
                )
            )
        );
    };

    return h('section', { class: 'screen', dataset: { key: 'view-board' } },
        pageHeader(t('board.title', { code: state.joinCode }), {
            back: leaveCurrentGame,
            right: [
                host && my && canBeConfirmedWinner(my, state.settings) && h('button', {
                    class: 'btn btn-secondary',
                    onclick: () => sendMessage(messages.gameOver(state.gameId, my.playerId))
                }, `👑 ${t('results.crownAction')}`),
                h('button', {
                    class: 'icon-btn',
                    'aria-label': t('common.share'),
                    onclick: shareInvite
                }, '📤')
            ]
        }),
        turnPlayer && h('div', { class: `turn-banner ${myTurn ? 'is-my-turn' : ''}` },
            h('strong', {}, myTurn ? t('board.yourTurn') : t('board.turnOf', { name: turnPlayer.name })),
            state.turnEndsAt && h('span', {
                id: 'turn-countdown',
                class: `timer-pill tabular ${countdown.urgent ? 'is-urgent' : ''}`
            }, countdown.text)
        ),
        combat && h('button', {
            class: 'banner banner-warn btn-block',
            onclick: () => { app.viewBoardDuringCombat = false; render(); }
        }, h('strong', {}, `⚔️ ${t('board.combatActiveBanner')}`)),
        h('div', { class: 'board-grid' },
            list.map(renderPlayerCard)
        ),
        h('div', { class: 'bottom-bar' },
            h('div', { class: 'action-grid' },
                !combat && h('button', {
                    class: 'btn btn-secondary btn-block',
                    onclick: () => sendEvent(actor => events.combatStart(actor, host ? (state.turnPlayerId || actor) : actor))
                }, `⚔️ ${t('board.startCombat')}`),
                canEndTurn && h('button', {
                    class: 'btn btn-primary btn-block',
                    onclick: () => sendEvent(actor => events.endTurn(actor))
                }, `⏭️ ${t('board.endTurn')}`)
            )
        )
    );
}

function heroModalNodes(player) {
    const editable = player.playerId === app.myPlayerId;
    const classes = activeClasses(player);
    const races = activeRaces(player);
    const items = [];
    for (const c of classes) {
        if (c === 'NONE') continue;
        for (const ab of abilitiesFor(c)) items.push({ title: classLabel(c), ...ab });
    }
    for (const r of races) {
        if (r === 'HUMAN') continue;
        for (const ab of abilitiesFor(r)) items.push({ title: raceLabel(r), ...ab });
    }

    return [
        h('div', { class: 'row' },
            avatarBadge(player),
            h('div', { class: 'stack-sm' },
                h('strong', {}, player.name),
                h('span', { class: 'muted-text' }, playerTraitsText(player))
            )
        ),
        editable && h('div', { class: 'stack' },
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('hero.class')),
                h('div', { class: 'chip-grid' }, CLASSES.map(c => h('button', {
                    class: 'chip',
                    'aria-pressed': player.characterClass === c ? 'true' : 'false',
                    onclick: () => sendEvent(actor => events.setClass(actor, c, false))
                }, classLabel(c))))
            ),
            h('label', { class: 'checkbox-row' },
                h('input', {
                    type: 'checkbox',
                    checked: Boolean(player.hasSuperMunchkin),
                    onchange: (e) => sendEvent(actor => events.setSuperMunchkin(actor, e.target.checked))
                }),
                h('div', { class: 'stack-sm' },
                    h('strong', {}, t('hero.superMunchkin')),
                    h('span', { class: 'hint-text' }, t('hero.superMunchkinDesc'))
                )
            ),
            player.hasSuperMunchkin && h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('hero.secondClass')),
                h('div', { class: 'chip-grid' }, CLASSES.map(c => h('button', {
                    class: 'chip',
                    'aria-pressed': player.secondaryClass === c ? 'true' : 'false',
                    onclick: () => sendEvent(actor => events.setClass(actor, c, true))
                }, classLabel(c))))
            ),
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('hero.race')),
                h('div', { class: 'chip-grid' }, RACES.map(r => h('button', {
                    class: 'chip',
                    'aria-pressed': player.characterRace === r ? 'true' : 'false',
                    onclick: () => sendEvent(actor => events.setRace(actor, r, false))
                }, raceLabel(r))))
            ),
            h('label', { class: 'checkbox-row' },
                h('input', {
                    type: 'checkbox',
                    checked: Boolean(player.hasHalfBreed),
                    onchange: (e) => sendEvent(actor => events.setHalfBreed(actor, e.target.checked))
                }),
                h('div', { class: 'stack-sm' },
                    h('strong', {}, t('hero.halfBreed')),
                    h('span', { class: 'hint-text' }, t('hero.halfBreedDesc'))
                )
            ),
            player.hasHalfBreed && h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('hero.secondRace')),
                h('div', { class: 'chip-grid' }, RACES.map(r => h('button', {
                    class: 'chip',
                    'aria-pressed': player.secondaryRace === r ? 'true' : 'false',
                    onclick: () => sendEvent(actor => events.setRace(actor, r, true))
                }, raceLabel(r))))
            ),
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('hero.gender')),
                genderSelector({
                    selected: player.gender || 'NA',
                    onSelect: (g) => sendEvent(actor => events.setGender(actor, g))
                })
            )
        ),
        h('hr', { class: 'divider' }),
        h('div', { class: 'stack-sm' },
            h('h3', { class: 'section-heading' }, t('hero.abilities')),
            items.length > 0
                ? h('div', { class: 'list-divide' }, items.map(ab =>
                    h('div', { class: 'flat-row' },
                        h('div', { class: 'stack-sm' },
                            h('strong', {}, `${ab.auto ? '⚙️' : '✋'} ${ab.title}`),
                            h('span', { class: 'muted-text' }, ab.text)
                        )
                    )
                ))
                : h('p', { class: 'muted-text' }, t('common.none')),
            h('p', { class: 'hint-text' }, t('hero.abilitiesLegend'))
        )
    ];
}

function openHeroModal(targetPlayer) {
    const player = targetPlayer || me();
    if (!player) return;
    app.heroModalPlayerId = player.playerId;
    app.heroModal = openModal({
        title: player.playerId === app.myPlayerId ? t('hero.title') : player.name,
        dismissible: true,
        onClose: () => {
            app.heroModal = null;
            app.heroModalPlayerId = null;
        },
        body: heroModalNodes(player)
    });
}

/* ==========================================================================
   Views: Combat & Modals (Victory, Bad Stuff, Run Away, Monsters, Bonuses)
   ========================================================================== */

function combatBreakdownLabels() {
    return {
        base: (name) => t('breakdown.base', { name }),
        helper: (name) => t('breakdown.helper', { name }),
        monster: (name, level) => t('breakdown.monster', { name, level }),
        manual: t('breakdown.manual'),
        clericVsUndead: t('breakdown.clericVsUndead'),
        helperClericVsUndead: t('breakdown.helperClericVsUndead'),
        cardModifiers: t('breakdown.cardModifiers')
    };
}

function combatView() {
    const state = app.game;
    const combat = state.combat;
    const mainP = state.players[combat.mainPlayerId];
    const helperP = combat.helperPlayerId ? state.players[combat.helperPlayerId] : null;
    const monsters = combat.monsters || [];
    const bonuses = combat.tempBonuses || [];
    const calc = calculateResult(combat, state);
    const breakdown = getBreakdown(combat, state, combatBreakdownLabels());
    const otherPlayers = playerList(state).filter(p => p.playerId !== combat.mainPlayerId);
    const isMain = app.myPlayerId === combat.mainPlayerId;
    const isHelper = Boolean(combat.helperPlayerId && app.myPlayerId === combat.helperPlayerId);
    const canModify = isMain || isHelper || isHost() || app.myPlayerId === state.turnPlayerId;
    const canResolve = isMain || isHost();
    const canRunAway = (isMain || isHelper || isHost()) && monsters.length > 0;
    const monsterLimitReached = monsters.length >= LIMITS.MAX_MONSTERS_PER_COMBAT;
    const bonusLimitReached = bonuses.length >= LIMITS.MAX_BONUSES_PER_COMBAT;

    const outcomeText = monsters.length === 0
        ? t('combat.needMonsters')
        : calc.outcome === OUTCOMES.WIN
            ? (calc.warriorTieBreak ? t('combat.winWarriorTie') : t('combat.winning'))
            : t('combat.losingGap', { gap: calc.marginToWin });

    return h('section', { class: 'screen', dataset: { key: 'view-combat' } },
        pageHeader(t('combat.title'), {
            back: () => { app.viewBoardDuringCombat = true; render(); },
            right: h('button', {
                class: 'btn btn-ghost',
                onclick: () => { app.viewBoardDuringCombat = true; render(); }
            }, t('combat.viewBoard'))
        }),
        h('div', { class: 'versus-strip' },
            h('div', { class: 'versus-side' },
                h('span', { class: 'section-heading' }, t('combat.heroes')),
                h('span', { class: 'versus-score is-heroes tabular' }, String(calc.heroesPower)),
                h('span', { class: 'muted-text' }, mainP ? mainP.name : '?'),
                helperP && h('span', { class: 'badge' }, `+ ${helperP.name}`)
            ),
            h('div', { class: 'versus-vs' }, 'VS'),
            h('div', { class: 'versus-side' },
                h('span', { class: 'section-heading' }, t('combat.monsters')),
                h('span', { class: 'versus-score is-monsters tabular' }, String(calc.monstersPower)),
                h('span', { class: 'muted-text' }, t('combat.monstersCount', { count: monsters.length }))
            )
        ),
        h('div', {
            class: `combat-outcome-pill ${calc.outcome === OUTCOMES.WIN && monsters.length > 0 ? 'is-win' : 'is-lose'}`
        }, outcomeText),
        h('div', { class: 'card' },
            h('div', { class: 'section-bar' },
                h('h2', { class: 'section-heading' }, t('combat.monsters')),
                canModify && h('button', {
                    class: 'btn btn-primary',
                    disabled: monsterLimitReached,
                    onclick: openAddMonsterModal
                }, t('combat.addMonsterBtn'))
            ),
            monsters.length === 0
                ? h('p', { class: 'muted-text' }, t('combat.noMonstersHint'))
                : h('div', { class: 'list-divide' }, monsters.map(m => {
                    const modText = m.flatModifier ? ` (${fmtSigned(m.flatModifier)})` : '';
                    const undead = m.isUndead ? ` · ${t('combat.undeadTag')}` : '';
                    return h('div', { class: 'flat-row', dataset: { key: `cm-${m.id}` } },
                        h('div', { class: 'stack-sm grow' },
                            h('strong', {}, m.name),
                            h('span', { class: 'muted-text tabular' },
                                t('combat.monsterMeta', {
                                    level: m.baseLevel,
                                    mod: modText,
                                    treasures: m.treasures ?? 1,
                                    levels: m.levels ?? 1,
                                    undead
                                })),
                            m.badStuff && h('span', { class: 'hint-text' }, m.badStuff)
                        ),
                        canModify && h('div', { class: 'row-wrap' },
                            h('button', {
                                class: 'icon-btn',
                                'aria-label': `${m.name} −1`,
                                onclick: () => sendEvent(actor => events.combatUpdateMonster(actor, {
                                    ...m,
                                    flatModifier: (m.flatModifier || 0) - 1
                                }))
                            }, '−'),
                            h('button', {
                                class: 'icon-btn',
                                'aria-label': `${m.name} +1`,
                                onclick: () => sendEvent(actor => events.combatUpdateMonster(actor, {
                                    ...m,
                                    flatModifier: (m.flatModifier || 0) + 1
                                }))
                            }, '+'),
                            h('button', {
                                class: 'icon-btn icon-btn-danger',
                                'aria-label': `${t('common.remove')} ${m.name}`,
                                onclick: () => sendEvent(actor => events.combatRemoveMonster(actor, m.id))
                            }, '✕')
                        )
                    );
                }))
        ),
        canModify && h('div', { class: 'card' },
            h('h2', { class: 'section-heading' }, t('combat.quickMods')),
            h('div', { class: 'grid-2' },
                h('div', { class: 'inset-band' },
                    h('div', { class: 'row-between' },
                        h('strong', {}, t('combat.sideHeroes')),
                        h('span', { class: 'badge tabular' }, fmtSigned(combat.heroModifier || 0))
                    ),
                    h('div', { class: 'row-wrap' },
                        [-5, -1, 1, 5].map(delta => h('button', {
                            class: 'btn btn-ghost grow',
                            onclick: () => sendEvent(actor =>
                                events.combatSetModifier(actor, BONUS_TARGETS.HEROES, (combat.heroModifier || 0) + delta))
                        }, fmtSigned(delta)))
                    )
                ),
                h('div', { class: 'inset-band' },
                    h('div', { class: 'row-between' },
                        h('strong', {}, t('combat.sideMonsters')),
                        h('span', { class: 'badge tabular' }, fmtSigned(combat.monsterModifier || 0))
                    ),
                    h('div', { class: 'row-wrap' },
                        [-5, -1, 1, 5].map(delta => h('button', {
                            class: 'btn btn-ghost grow',
                            onclick: () => sendEvent(actor =>
                                events.combatSetModifier(actor, BONUS_TARGETS.MONSTER, (combat.monsterModifier || 0) + delta))
                        }, fmtSigned(delta)))
                    )
                )
            )
        ),
        h('div', { class: 'card' },
            h('h2', { class: 'section-heading' }, t('combat.helper')),
            otherPlayers.length === 0
                ? h('p', { class: 'muted-text' }, t('combat.noHelpers'))
                : h('div', { class: 'chip-grid' },
                    h('button', {
                        class: 'chip',
                        disabled: !canModify,
                        'aria-pressed': !combat.helperPlayerId ? 'true' : 'false',
                        onclick: () => sendEvent(actor => events.combatRemoveHelper(actor))
                    }, t('combat.noHelper')),
                    otherPlayers.map(p => h('button', {
                        class: 'chip',
                        disabled: !canModify,
                        'aria-pressed': combat.helperPlayerId === p.playerId ? 'true' : 'false',
                        onclick: () => sendEvent(actor => events.combatAddHelper(actor, p.playerId))
                    }, `${p.name} (${combatPower(p)})`))
                )
        ),
        h('div', { class: 'card' },
            h('div', { class: 'section-bar' },
                h('div', {},
                    h('h2', { class: 'section-heading' }, t('combat.tempBonuses')),
                    h('span', { class: 'hint-text' }, t('combat.bonusesHint'))
                ),
                canModify && h('button', {
                    class: 'btn btn-ghost',
                    disabled: bonusLimitReached,
                    onclick: openAddBonusModal
                }, t('combat.addBonusBtn'))
            ),
            bonuses.length === 0
                ? h('p', { class: 'muted-text' }, t('combat.noBonuses'))
                : h('div', { class: 'list-divide' }, bonuses.map(b =>
                    h('div', { class: 'flat-row', dataset: { key: `cb-${b.id}` } },
                        h('div', { class: 'stack-sm' },
                            h('strong', {}, b.label),
                            h('span', { class: 'muted-text' },
                                t('combat.bonusMeta', {
                                    amount: fmtSigned(b.amount),
                                    side: b.appliesTo === BONUS_TARGETS.HEROES ? t('combat.sideHeroes') : t('combat.sideMonsters')
                                }))
                        ),
                        canModify && h('button', {
                            class: 'icon-btn icon-btn-danger',
                            'aria-label': `${t('common.remove')} ${b.label}`,
                            onclick: () => sendEvent(actor => events.combatRemoveBonus(actor, b.id))
                        }, '✕')
                    )
                ))
        ),
        h('details', {
            class: 'card',
            open: app.breakdownOpen,
            dataset: { key: 'combat-breakdown', preserveOpen: 'true' },
            ontoggle: (e) => { app.breakdownOpen = e.target.open; }
        },
            h('summary', {}, t('combat.breakdown')),
            h('div', { class: 'grid-2' },
                h('div', { class: 'stack-sm' },
                    h('h3', { class: 'section-heading' }, t('combat.sideHeroes')),
                    h('div', { class: 'list-divide' }, breakdown.heroSources.map(s =>
                        h('div', { class: 'flat-row' },
                            h('span', {}, s.label),
                            h('strong', { class: 'tabular' }, fmtSigned(s.amount))
                        )
                    ))
                ),
                h('div', { class: 'stack-sm' },
                    h('h3', { class: 'section-heading' }, t('combat.sideMonsters')),
                    h('div', { class: 'list-divide' }, breakdown.monsterSources.map(s =>
                        h('div', { class: 'flat-row' },
                            h('span', {}, s.label),
                            h('strong', { class: 'tabular' }, fmtSigned(s.amount))
                        )
                    ))
                )
            )
        ),
        h('div', { class: 'bottom-bar' },
            canResolve
                ? [
                    h('div', { class: 'action-grid' },
                        h('button', {
                            class: `btn ${calc.outcome === OUTCOMES.WIN ? 'btn-ok' : 'btn-danger'} btn-block`,
                            disabled: app.combatBusy || monsters.length === 0,
                            onclick: () => resolveCombat(calc)
                        }, app.combatBusy
                            ? t('combat.resolving')
                            : (calc.outcome === OUTCOMES.WIN
                                ? t('combat.victoryBtn', { levels: calc.totalLevels, treasures: calc.totalTreasures })
                                : t('combat.defeatBtn'))),
                        h('button', {
                            class: 'btn btn-secondary btn-block',
                            disabled: app.combatBusy || !canRunAway,
                            onclick: openRunAwayModal
                        }, `🏃 ${t('combat.runAwayBtn')}`)
                    ),
                    h('button', {
                        class: 'btn btn-ghost btn-block',
                        disabled: app.combatBusy,
                        onclick: cancelCombat
                    }, t('combat.cancelCombat'))
                ]
                : isHelper
                    ? h('button', {
                        class: 'btn btn-secondary btn-block',
                        disabled: app.combatBusy || !canRunAway,
                        onclick: openRunAwayModal
                    }, `🏃 ${t('combat.runAwayBtn')}`)
                    : h('p', { class: 'muted-text center-text' },
                        t('combat.waitingMain', { name: mainP?.name || '?' }))
        )
    );
}

async function cancelCombat() {
    const state = app.game;
    if (!state?.combat || app.combatBusy) return;
    const ok = await confirmDialog({
        title: t('combat.cancelConfirmTitle'),
        message: t('combat.cancelConfirmText'),
        confirmText: t('combat.cancelCombat')
    });
    if (!ok || !app.game?.combat) return;
    app.combatBusy = true;
    app.pendingOutcomeWasWin = false;
    app.pendingBadStuff = null;
    render();
    sendEvent(actor => events.combatEnd(actor, OUTCOMES.ESCAPE, {
        expectedSeq: app.game.seq,
        cancelled: true
    }));
}

async function resolveCombat(result) {
    const state = app.game;
    const combat = state?.combat;
    if (!combat || app.combatBusy || (combat.monsters || []).length === 0) return;

    const isWin = result.outcome === OUTCOMES.WIN;
    const message = isWin
        ? (result.helperLevelsGained
            ? t('combatEnd.winTextElf', {
                levels: result.totalLevels,
                treasures: result.totalTreasures,
                helperLevels: result.helperLevelsGained
            })
            : t('combatEnd.winText', { levels: result.totalLevels, treasures: result.totalTreasures }))
        : t('combatEnd.loseText');

    const ok = await confirmDialog({
        title: isWin ? t('combatEnd.winTitle') : t('combatEnd.loseTitle'),
        message,
        confirmText: isWin ? t('combatEnd.winYes') : t('combatEnd.loseYes'),
        danger: !isWin
    });
    if (!ok || !app.game?.combat) return;

    app.combatBusy = true;
    app.pendingOutcomeWasWin = isWin;
    app.pendingBadStuff = isWin ? null : { monsters: [...(app.game.combat.monsters || [])] };
    render();

    const sent = sendEvent(actor => events.combatEnd(actor, result.outcome, {
        levelsGained: result.totalLevels,
        treasuresGained: result.totalTreasures,
        helperLevelsGained: result.helperLevelsGained,
        expectedSeq: app.game.seq
    }));
    if (!sent) {
        app.combatBusy = false;
        app.pendingBadStuff = null;
        app.pendingOutcomeWasWin = false;
        render();
    }
}

function badStuffNodes(monsters) {
    const my = me();
    const minLevel = app.game?.settings?.minLevel ?? 1;
    const withText = (monsters || []).filter(m => (m.badStuff || '').trim());

    return [
        h('p', { class: 'muted-text' }, t('badstuff.intro')),
        withText.length > 0
            ? h('div', { class: 'list-divide' }, withText.map(m =>
                h('div', { class: 'flat-row' },
                    h('div', { class: 'stack-sm' },
                        h('strong', {}, m.name),
                        h('span', { class: 'muted-text' }, m.badStuff)
                    )
                )
            ))
            : h('p', {}, t('badstuff.fallback')),
        my && h('div', { class: 'inset-band' },
            h('strong', { class: 'tabular' }, t('badstuff.currentStats', {
                level: my.level,
                gear: my.gearBonus || 0
            })),
            h('div', { class: 'row-wrap' },
                h('button', {
                    class: 'btn btn-ghost grow',
                    disabled: my.level <= minLevel,
                    onclick: () => sendEvent(actor => events.decLevel(actor))
                }, t('badstuff.loseLevel')),
                h('button', {
                    class: 'btn btn-danger grow',
                    onclick: () => sendEvent(actor => buildSetGearEvent(actor, 0))
                }, t('badstuff.dieResetGear'))
            )
        ),
        h('button', {
            class: 'btn btn-primary btn-block',
            'data-autofocus': 'true',
            onclick: () => app.badStuffModal?.close()
        }, t('common.done'))
    ];
}

function showBadStuff(monsters) {
    app.badStuffMonsters = [...(monsters || [])];
    if (app.badStuffModal) {
        app.badStuffModal.setBody(badStuffNodes(app.badStuffMonsters));
        return;
    }
    app.badStuffModal = openModal({
        title: `💀 ${t('badstuff.title')}`,
        dismissible: true,
        onClose: () => {
            app.badStuffModal = null;
            app.badStuffMonsters = [];
        },
        body: badStuffNodes(app.badStuffMonsters)
    });
}

function openRunAwayModal() {
    const state = app.game;
    const combat = state?.combat;
    if (!combat || (combat.monsters || []).length === 0 || app.combatBusy) return;

    const mainP = state.players[combat.mainPlayerId];
    const helperP = combat.helperPlayerId ? state.players[combat.helperPlayerId] : null;
    const isHelper = Boolean(helperP && app.myPlayerId === helperP.playerId);
    const runner = isHelper ? helperP : (me() || mainP);
    if (!runner) return;

    const mods = runAwayModifiers(runner);
    const bonus = runAwayBonus(runner);
    const monsters = [...(combat.monsters || [])];
    let selectedDie = null;

    const modal = openModal({
        title: `🏃 ${t('runaway.title')}`,
        dismissible: true,
        body: renderStep()
    });

    function renderStep() {
        const modText = mods.map(([race, amt]) => `${fmtSigned(amt)} ${raceLabel(race)}`).join(' · ');
        const total = selectedDie !== null ? selectedDie + bonus : null;
        const escaped = total !== null ? total >= RUN_AWAY_THRESHOLD : false;

        return [
            h('div', { class: 'inset-band' },
                h('strong', {}, t('runaway.runningAs', { name: runner.name })),
                h('span', { class: 'muted-text' },
                    t('runaway.thresholdHint', { threshold: RUN_AWAY_THRESHOLD })),
                modText && h('span', { class: 'badge' }, modText)
            ),
            h('button', {
                class: 'btn btn-secondary btn-block',
                disabled: app.combatBusy,
                onclick: () => {
                    selectedDie = 1 + Math.floor(Math.random() * 6);
                    modal.setBody(renderStep());
                }
            }, `🎲 ${t('runaway.rollDigital')}`),
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('runaway.orPickPhysical')),
                h('div', { class: 'dice-grid', role: 'group', 'aria-label': t('runaway.orPickPhysical') },
                    [1, 2, 3, 4, 5, 6].map(val => h('button', {
                        class: 'dice-pick',
                        'aria-pressed': selectedDie === val ? 'true' : 'false',
                        disabled: app.combatBusy,
                        onclick: () => {
                            selectedDie = val;
                            modal.setBody(renderStep());
                        }
                    }, String(val)))
                )
            ),
            selectedDie !== null && h('div', { class: 'inset-band' },
                h('strong', { class: 'tabular' },
                    `${DICE_FACES[selectedDie - 1]} ${t('runaway.total', { total })}`),
                mods.length > 0 && h('span', { class: 'muted-text tabular' },
                    t('runaway.dieMod', { die: selectedDie, mods: modText })),
                h('span', { class: escaped ? 'ok-text' : 'danger-text' },
                    escaped
                        ? t('runaway.escaped', { total })
                        : t('runaway.caught', { total, threshold: RUN_AWAY_THRESHOLD }))
            ),
            selectedDie !== null && h('button', {
                class: `btn ${escaped ? 'btn-ok' : 'btn-danger'} btn-block`,
                disabled: app.combatBusy,
                onclick: () => confirmRunAway(selectedDie, escaped)
            }, escaped ? `🏃 ${t('runaway.confirmEscape')}` : `💀 ${t('runaway.confirmCaught')}`)
        ];
    }

    function confirmRunAway(die, escaped) {
        const curState = app.game;
        if (!curState?.combat || app.combatBusy) return;

        app.combatBusy = true;
        app.pendingOutcomeWasWin = false;
        app.pendingBadStuff = escaped ? null : { monsters };
        app.pendingRunAwayFinish = {
            actorId: app.myPlayerId,
            isMainRunner: !isHelper,
            escaped,
            baseSeq: curState.seq ?? 0,
            startedAt: Date.now()
        };

        modal.close();
        render();

        const sent = sendEvent(actor => events.playerRoll(actor, die, 'RUN_AWAY', escaped));
        if (!sent) {
            app.combatBusy = false;
            app.pendingRunAwayFinish = null;
            app.pendingBadStuff = null;
            render();
        }
    }
}

function openAddMonsterModal() {
    let query = '';
    let loading = true;
    let error = null;
    let results = [];
    let debounce = null;

    const modal = openModal({
        title: t('monsterModal.title'),
        dismissible: true,
        body: renderNodes()
    });

    function renderNodes() {
        return [
            h('input', {
                class: 'input',
                id: 'm-search',
                dataset: { focusKey: 'm-search' },
                type: 'search',
                placeholder: t('monsterModal.searchPlaceholder'),
                'data-autofocus': 'true',
                value: query,
                oninput: (e) => {
                    query = e.target.value;
                    clearTimeout(debounce);
                    debounce = setTimeout(runSearch, 240);
                }
            }),
            loading
                ? h('p', { class: 'muted-text', role: 'status' }, t('common.loading'))
                : error
                    ? h('div', { class: 'row-between' },
                        h('span', { class: 'danger-text' }, error),
                        h('button', { class: 'btn btn-ghost', onclick: runSearch }, t('common.retry'))
                    )
                    : results.length === 0
                        ? h('p', { class: 'muted-text' }, t('monsterModal.noResults'))
                        : h('div', { class: 'scroll-box list-divide' }, results.map(m =>
                            h('div', { class: 'flat-row', dataset: { key: `cm-res-${m.id || m.name}` } },
                                h('div', { class: 'stack-sm grow' },
                                    h('strong', {}, m.name),
                                    h('span', { class: 'muted-text tabular' },
                                        t('combat.monsterMeta', {
                                            level: m.level,
                                            mod: m.modifier ? ` (${fmtSigned(m.modifier)})` : '',
                                            treasures: m.treasures ?? 1,
                                            levels: m.levels ?? 1,
                                            undead: m.isUndead ? ` · ${t('combat.undeadTag')}` : ''
                                        }))
                                ),
                                h('button', {
                                    class: 'btn btn-primary',
                                    onclick: () => {
                                        addMonsterToCombat({
                                            name: m.name,
                                            baseLevel: m.level,
                                            flatModifier: m.modifier || 0,
                                            treasures: m.treasures ?? 1,
                                            levels: m.levels ?? 1,
                                            isUndead: Boolean(m.isUndead),
                                            badStuff: m.badStuff || ''
                                        });
                                        modal.close();
                                    }
                                }, t('common.add'))
                            )
                        )),
            h('details', { class: 'inset-band', dataset: { key: 'nm-details', preserveOpen: 'true' } },
                h('summary', {}, t('monsterModal.manualSummary')),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('monsterModal.name')),
                    h('input', { class: 'input', id: 'nm-name', dataset: { focusKey: 'nm-name' }, maxlength: '80' })
                ),
                h('div', { class: 'grid-2' },
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, t('monsterModal.levelRange')),
                        h('input', { class: 'input', id: 'nm-level', type: 'number', min: '1', max: '20', value: '1' })
                    ),
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, t('monsterModal.modRange')),
                        h('input', { class: 'input', id: 'nm-mod', type: 'number', min: '-10', max: '10', value: '0' })
                    ),
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, t('monsterModal.treasures')),
                        h('input', { class: 'input', id: 'nm-treasures', type: 'number', min: '0', max: '10', value: '1' })
                    ),
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, t('monsterModal.levelsReward')),
                        h('input', { class: 'input', id: 'nm-levels', type: 'number', min: '1', max: '5', value: '1' })
                    )
                ),
                h('label', { class: 'checkbox-row' },
                    h('input', { type: 'checkbox', id: 'nm-undead' }),
                    h('span', {}, `🧟 ${t('monsterModal.undead')}`)
                ),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('monsterModal.badStuff')),
                    h('input', {
                        class: 'input',
                        id: 'nm-badstuff',
                        maxlength: '200',
                        placeholder: t('monsterModal.badStuffPlaceholder')
                    })
                ),
                h('button', {
                    class: 'btn btn-primary btn-block',
                    onclick: () => {
                        const name = document.getElementById('nm-name')?.value.trim() || '';
                        if (!name) {
                            toast(t('monsterModal.nameRequired'), 'err');
                            return;
                        }
                        addMonsterToCombat({
                            name,
                            baseLevel: Number(document.getElementById('nm-level')?.value) || 1,
                            flatModifier: Number(document.getElementById('nm-mod')?.value) || 0,
                            treasures: Number(document.getElementById('nm-treasures')?.value ?? 1),
                            levels: Number(document.getElementById('nm-levels')?.value) || 1,
                            isUndead: Boolean(document.getElementById('nm-undead')?.checked),
                            badStuff: document.getElementById('nm-badstuff')?.value.trim() || ''
                        }, { contribute: true });
                        modal.close();
                    }
                }, t('monsterModal.addToCombat'))
            )
        ];
    }

    async function runSearch() {
        const trimmed = query.trim();
        loading = true;
        error = null;
        modal.setBody(renderNodes());
        try {
            const qParam = trimmed.length === 0 ? '__ALL__' : (trimmed.length === 1 ? '__ALL__' : trimmed);
            const reply = await oneOffRequest(WS_URL, messages.catalogSearch(qParam, { limit: trimmed.length === 1 ? 50 : 20 }));
            if (!modal.card.isConnected) return;
            const rows = reply.results || [];
            results = trimmed.length === 1
                ? rows.filter(r => r.name.toLowerCase().includes(trimmed.toLowerCase()))
                : rows;
            loading = false;
            modal.setBody(renderNodes());
        } catch (err) {
            if (!modal.card.isConnected) return;
            error = errorText(err) || t('monsterModal.searchError');
            loading = false;
            modal.setBody(renderNodes());
        }
    }

    runSearch();
}

function addMonsterToCombat(data, { contribute = false } = {}) {
    sendEvent(actor => events.combatAddMonster(actor, monsterInstance(data)));
    if (contribute && session.authToken) {
        authenticatedRequest(WS_URL, session.authToken, messages.catalogAdd({
            id: '',
            name: data.name,
            level: data.baseLevel,
            modifier: data.flatModifier || 0,
            treasures: data.treasures ?? 1,
            levels: data.levels ?? 1,
            isUndead: Boolean(data.isUndead),
            badStuff: data.badStuff || '',
            expansion: 'base',
            createdBy: session.profile?.username || null
        })).catch(() => {});
    }
}

function openAddBonusModal() {
    let label = '';
    let amount = '1';
    let side = BONUS_TARGETS.HEROES;

    const modal = openModal({
        title: t('bonusModal.title'),
        dismissible: true,
        body: renderNodes()
    });

    function renderNodes() {
        return [
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('bonusModal.cardName')),
                h('input', {
                    class: 'input',
                    id: 'bn-label',
                    dataset: { focusKey: 'bn-label' },
                    maxlength: '40',
                    'data-autofocus': 'true',
                    placeholder: t('bonusModal.cardPlaceholder'),
                    value: label,
                    oninput: (e) => { label = e.target.value; }
                })
            ),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('bonusModal.amount')),
                h('input', {
                    class: 'input',
                    id: 'bn-amount',
                    dataset: { focusKey: 'bn-amount' },
                    type: 'number',
                    value: amount,
                    oninput: (e) => { amount = e.target.value; }
                })
            ),
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('bonusModal.appliesTo')),
                h('div', { class: 'segment-group' },
                    h('button', {
                        class: 'segment',
                        'aria-pressed': side === BONUS_TARGETS.HEROES ? 'true' : 'false',
                        onclick: () => { side = BONUS_TARGETS.HEROES; modal.setBody(renderNodes()); }
                    }, t('combat.sideHeroes')),
                    h('button', {
                        class: 'segment',
                        'aria-pressed': side === BONUS_TARGETS.MONSTER ? 'true' : 'false',
                        onclick: () => { side = BONUS_TARGETS.MONSTER; modal.setBody(renderNodes()); }
                    }, t('combat.sideMonsters'))
                )
            ),
            h('div', { class: 'row-wrap' },
                h('button', {
                    class: 'btn btn-ghost grow',
                    onclick: () => modal.close()
                }, t('common.cancel')),
                h('button', {
                    class: 'btn btn-primary grow',
                    onclick: () => {
                        sendEvent(actor => events.combatAddBonus(actor, tempBonus({
                            label: label.trim() || t('bonusModal.defaultLabel'),
                            amount: Number(amount) || 0,
                            appliesTo: side
                        })));
                        modal.close();
                    }
                }, t('common.add'))
            )
        ];
    }
}

/* ==========================================================================
   Views: Monster Catalog (#/catalog) (PROD-06)
   ========================================================================== */

async function loadCatalogPage({ append = false } = {}) {
    const gen = app.routeGen;
    const signal = app.routeAbort.signal;
    const cat = app.catalog;
    const nextOffset = append ? cat.items.length : 0;

    if (append) cat.loadingMore = true;
    else cat.loading = true;
    cat.error = null;
    cat.loadedGen = gen;
    render();

    const trimmed = cat.query.trim();
    const effectiveQ = trimmed.length >= 2 ? trimmed : '__ALL__';
    const reqLimit = trimmed.length === 1 ? 50 : cat.limit;

    try {
        const reply = await oneOffRequest(WS_URL, messages.catalogSearch(effectiveQ, {
            expansion: cat.expansion || null,
            limit: reqLimit,
            offset: nextOffset
        }));
        if (!isCurrentRoute(gen, signal)) return;

        const raw = reply.results || [];
        const filtered = trimmed.length === 1
            ? raw.filter(m => m.name.toLowerCase().includes(trimmed.toLowerCase()))
            : raw;

        cat.items = append ? [...cat.items, ...filtered] : filtered;
        cat.offset = nextOffset;
        cat.hasMore = raw.length >= reqLimit;
        cat.loading = false;
        cat.loadingMore = false;
        render();
    } catch (err) {
        if (!isCurrentRoute(gen, signal)) return;
        cat.error = errorText(err) || t('catalog.error');
        cat.loading = false;
        cat.loadingMore = false;
        render();
    }
}

function openContributeCatalogModal() {
    if (!session.authToken) {
        toast(t('catalog.signInToAdd'), 'info');
        nav('#/auth');
        return;
    }

    const form = {
        name: '',
        level: '1',
        modifier: '0',
        treasures: '1',
        levels: '1',
        isUndead: false,
        expansion: 'base',
        badStuff: '',
        busy: false
    };

    const modal = openModal({
        title: t('catalog.addTitle'),
        dismissible: true,
        body: renderNodes()
    });

    function renderNodes() {
        return [
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('monsterModal.name')),
                h('input', {
                    class: 'input',
                    id: 'cat-name',
                    dataset: { focusKey: 'cat-name' },
                    maxlength: '80',
                    'data-autofocus': 'true',
                    value: form.name,
                    oninput: (e) => { form.name = e.target.value; }
                })
            ),
            h('div', { class: 'grid-2' },
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('monsterModal.levelRange')),
                    h('input', {
                        class: 'input', type: 'number', min: '1', max: '20',
                        value: form.level,
                        oninput: (e) => { form.level = e.target.value; }
                    })
                ),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('monsterModal.modRange')),
                    h('input', {
                        class: 'input', type: 'number', min: '-10', max: '10',
                        value: form.modifier,
                        oninput: (e) => { form.modifier = e.target.value; }
                    })
                ),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('monsterModal.treasures')),
                    h('input', {
                        class: 'input', type: 'number', min: '0', max: '10',
                        value: form.treasures,
                        oninput: (e) => { form.treasures = e.target.value; }
                    })
                ),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('monsterModal.levelsReward')),
                    h('input', {
                        class: 'input', type: 'number', min: '1', max: '5',
                        value: form.levels,
                        oninput: (e) => { form.levels = e.target.value; }
                    })
                )
            ),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('catalog.expansion')),
                h('select', {
                    class: 'select',
                    value: form.expansion,
                    onchange: (e) => { form.expansion = e.target.value; }
                }, EXPANSIONS.map(exp => h('option', { value: exp }, expansionLabel(exp))))
            ),
            h('label', { class: 'checkbox-row' },
                h('input', {
                    type: 'checkbox',
                    checked: form.isUndead,
                    onchange: (e) => { form.isUndead = e.target.checked; }
                }),
                h('span', {}, `🧟 ${t('monsterModal.undead')}`)
            ),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('monsterModal.badStuff')),
                h('textarea', {
                    class: 'textarea',
                    maxlength: '200',
                    placeholder: t('monsterModal.badStuffPlaceholder'),
                    value: form.badStuff,
                    oninput: (e) => { form.badStuff = e.target.value; }
                })
            ),
            h('div', { class: 'row-wrap' },
                h('button', {
                    class: 'btn btn-ghost grow',
                    disabled: form.busy,
                    onclick: () => modal.close()
                }, t('common.cancel')),
                h('button', {
                    class: 'btn btn-primary grow',
                    disabled: form.busy,
                    onclick: submitMonster
                }, form.busy ? t('common.saving') : t('common.add'))
            )
        ];
    }

    async function submitMonster() {
        if (form.busy) return;
        const name = form.name.trim();
        if (!name) {
            toast(t('monsterModal.nameRequired'), 'err');
            return;
        }
        form.busy = true;
        modal.setBody(renderNodes());
        try {
            await authenticatedRequest(WS_URL, session.authToken, messages.catalogAdd({
                id: '',
                name,
                level: Number(form.level) || 1,
                modifier: Number(form.modifier) || 0,
                treasures: Number(form.treasures ?? 1),
                levels: Number(form.levels) || 1,
                isUndead: Boolean(form.isUndead),
                badStuff: form.badStuff.trim(),
                expansion: form.expansion || 'base',
                createdBy: session.profile?.username || null
            }, session.profile?.id));
            toast(t('catalog.added', { name }), 'ok');
            modal.close();
            loadCatalogPage({ append: false });
        } catch (err) {
            toast(errorText(err), 'err');
        } finally {
            form.busy = false;
            if (modal.card.isConnected) modal.setBody(renderNodes());
        }
    }
}

function catalogView() {
    const cat = app.catalog;
    if (cat.loadedGen !== app.routeGen && !cat.loading) {
        const gen = app.routeGen;
        const sig = app.routeAbort.signal;
        queueMicrotask(() => {
            if (isCurrentRoute(gen, sig)) loadCatalogPage({ append: false });
        });
    }

    return h('section', { class: 'screen', dataset: { key: 'view-catalog' } },
        pageHeader(t('catalog.title'), { back: () => nav('#/home') }),
        h('div', { class: 'card' },
            h('div', { class: 'catalog-toolbar' },
                h('input', {
                    class: 'input',
                    id: 'cat-q',
                    dataset: { focusKey: 'cat-q' },
                    type: 'search',
                    placeholder: t('catalog.searchPlaceholder'),
                    value: cat.query,
                    oninput: (e) => {
                        cat.query = e.target.value;
                        clearTimeout(cat.debounceTimer);
                        cat.debounceTimer = setTimeout(() => loadCatalogPage({ append: false }), 260);
                    }
                }),
                h('select', {
                    class: 'select',
                    'aria-label': t('catalog.expansion'),
                    value: cat.expansion,
                    onchange: (e) => {
                        cat.expansion = e.target.value;
                        loadCatalogPage({ append: false });
                    }
                },
                    h('option', { value: '' }, t('catalog.allExpansions')),
                    EXPANSIONS.map(exp => h('option', { value: exp }, expansionLabel(exp)))
                ),
                h('button', {
                    class: 'btn btn-primary',
                    onclick: openContributeCatalogModal
                }, `+ ${t('catalog.addBtn')}`)
            ),
            cat.query.trim().length === 1 && h('p', { class: 'hint-text' }, t('catalog.searchHintShort'))
        ),
        h('div', { class: 'card' },
            cat.loading
                ? h('p', { class: 'muted-text', role: 'status' }, t('common.loading'))
                : cat.error
                    ? h('div', { class: 'row-between' },
                        h('span', { class: 'danger-text' }, cat.error),
                        h('button', {
                            class: 'btn btn-ghost',
                            onclick: () => loadCatalogPage({ append: false })
                        }, t('common.retry'))
                    )
                    : cat.items.length === 0
                        ? h('p', { class: 'muted-text' }, t('catalog.empty'))
                        : h('div', { class: 'stack' },
                            h('div', { class: 'list-divide' }, cat.items.map(m =>
                                h('div', { class: 'flat-row', dataset: { key: `cat-${m.id || m.name}` } },
                                    h('div', { class: 'stack-sm grow' },
                                        h('div', { class: 'row-wrap' },
                                            h('strong', {}, m.name),
                                            m.isUndead && h('span', { class: 'badge badge-ember' }, t('combat.undeadTag')),
                                            m.expansion && h('span', { class: 'badge' }, expansionLabel(m.expansion))
                                        ),
                                        h('span', { class: 'muted-text tabular' },
                                            t('combat.monsterMeta', {
                                                level: m.level,
                                                mod: m.modifier ? ` (${fmtSigned(m.modifier)})` : '',
                                                treasures: m.treasures ?? 1,
                                                levels: m.levels ?? 1,
                                                undead: ''
                                            })),
                                        m.badStuff && h('span', { class: 'hint-text' },
                                            `${t('catalog.badStuffLabel')}: ${m.badStuff}`)
                                    )
                                )
                            )),
                            cat.hasMore && h('button', {
                                class: 'btn btn-ghost btn-block',
                                disabled: cat.loadingMore,
                                onclick: () => loadCatalogPage({ append: true })
                            }, cat.loadingMore ? t('common.loading') : t('common.loadMore'))
                        )
        )
    );
}

/* ==========================================================================
   Views: Results, Ranking, History
   ========================================================================== */

function resultsView() {
    const state = app.game;
    const winner = state.winnerId ? state.players[state.winnerId] : null;
    const standings = playerList(state).slice().sort((a, b) =>
        (b.level - a.level) || (combatPower(b) - combatPower(a))
    );

    return h('section', { class: 'screen', dataset: { key: 'view-results' } },
        h('div', { class: 'hero-banner center-text' },
            h('span', { class: 'hero-eyebrow' }, '🏆'),
            h('h1', { class: 'hero-heading' },
                winner ? t('results.winnerTitle', { name: winner.name }) : t('results.finishedTitle')),
            winner && h('p', { class: 'hero-copy' }, avatarEmoji(winner.avatarId, winner.gender))
        ),
        h('div', { class: 'card' },
            h('h2', { class: 'section-heading' }, t('results.standings')),
            h('div', { class: 'list-divide' }, standings.map((p, idx) =>
                h('div', { class: 'flat-row', dataset: { key: `res-${p.playerId}` } },
                    h('div', { class: 'row grow' },
                        h('strong', { class: 'rank-num tabular' }, `${idx + 1}.`),
                        avatarBadge(p),
                        h('strong', {}, p.name)
                    ),
                    h('div', { class: 'row-wrap' },
                        h('span', { class: 'badge tabular' }, t('board.lvlShort', { n: p.level })),
                        h('span', { class: 'muted-text tabular' }, `💰 ${p.treasures || 0}`)
                    )
                )
            ))
        ),
        h('button', {
            class: 'btn btn-primary btn-block',
            onclick: () => { cleanupGame(); nav('#/home'); }
        }, t('results.backHome'))
    );
}

function winRate(entry) {
    return entry.gamesPlayed > 0 ? Math.floor((entry.wins * 100) / entry.gamesPlayed) : 0;
}

async function fetchRanking(gen, signal) {
    app.ranking.loading = true;
    app.ranking.error = null;
    app.ranking.loadedGen = gen;
    render();
    try {
        const token = session.authToken;
        const reply = token
            ? (await authenticatedRequest(WS_URL, token, messages.getLeaderboard())).reply
            : await oneOffRequest(WS_URL, messages.getLeaderboard());
        if (!isCurrentRoute(gen, signal)) return;
        app.ranking.items = reply.leaderboard || [];
        app.ranking.me = reply.me || null;
        app.ranking.loading = false;
        render();
    } catch (err) {
        if (!isCurrentRoute(gen, signal)) return;
        app.ranking.error = errorText(err);
        app.ranking.loading = false;
        render();
    }
}

function rankingView() {
    const rk = app.ranking;
    if (rk.loadedGen !== app.routeGen && !rk.loading) {
        const gen = app.routeGen;
        const sig = app.routeAbort.signal;
        queueMicrotask(() => {
            if (isCurrentRoute(gen, sig)) fetchRanking(gen, sig);
        });
    }

    return h('section', { class: 'screen', dataset: { key: 'view-ranking' } },
        pageHeader(t('ranking.title'), { back: () => nav('#/home') }),
        h('div', { class: 'card' },
            rk.loading
                ? h('p', { class: 'muted-text', role: 'status' }, t('common.loading'))
                : rk.error
                    ? h('div', { class: 'row-between' },
                        h('span', { class: 'danger-text' }, rk.error),
                        h('button', {
                            class: 'btn btn-ghost',
                            onclick: () => fetchRanking(app.routeGen, app.routeAbort.signal)
                        }, t('common.retry'))
                    )
                    : [
                        rk.me && h('div', { class: 'inset-band' },
                            h('div', { class: 'row-wrap' },
                                h('strong', {}, rk.me.rank
                                    ? t('ranking.yourRank', { rank: rk.me.rank })
                                    : t('ranking.outsideTop')),
                                h('span', { class: 'muted-text tabular' },
                                    t('ranking.stats', {
                                        wins: rk.me.wins,
                                        games: rk.me.gamesPlayed,
                                        rate: winRate(rk.me)
                                    }))
                            )
                        ),
                        rk.items.length === 0
                            ? h('p', { class: 'muted-text' }, t('ranking.empty'))
                            : h('div', { class: 'list-divide' }, rk.items.map((entry, idx) =>
                                h('div', { class: 'flat-row', dataset: { key: `rk-${entry.username}-${idx}` } },
                                    h('div', { class: 'row grow' },
                                        h('strong', { class: 'rank-num tabular' }, `${idx + 1}.`),
                                        h('span', { class: 'avatar-badge', 'aria-hidden': 'true' },
                                            avatarEmoji(entry.avatarId, entry.gender)),
                                        h('strong', {}, entry.username)
                                    ),
                                    h('div', { class: 'row-wrap tabular' },
                                        h('strong', {}, `🏆 ${entry.wins}`),
                                        h('span', { class: 'muted-text' }, `${winRate(entry)}%`)
                                    )
                                )
                            ))
                    ]
        )
    );
}

async function fetchHistory(gen, signal) {
    if (!session.authToken || !session.profile) return;
    app.history.loading = true;
    app.history.error = null;
    app.history.loadedGen = gen;
    render();
    try {
        const { reply } = await authenticatedRequest(
            WS_URL, session.authToken, messages.getHistory(session.profile.id)
        );
        if (!isCurrentRoute(gen, signal)) return;
        app.history.stats = reply.stats || { wins: 0, gamesPlayed: 0 };
        app.history.items = reply.games || [];
        app.history.loading = false;
        render();
    } catch (err) {
        if (!isCurrentRoute(gen, signal)) return;
        app.history.error = errorText(err);
        app.history.loading = false;
        render();
    }
}

function historyView() {
    if (!session.authToken) {
        queueMicrotask(() => nav('#/auth'));
        return h('section', { class: 'screen', dataset: { key: 'view-history-redirect' } });
    }

    const hs = app.history;
    if (hs.loadedGen !== app.routeGen && !hs.loading) {
        const gen = app.routeGen;
        const sig = app.routeAbort.signal;
        queueMicrotask(() => {
            if (isCurrentRoute(gen, sig)) fetchHistory(gen, sig);
        });
    }

    return h('section', { class: 'screen', dataset: { key: 'view-history' } },
        pageHeader(t('history.title'), { back: () => nav('#/home') }),
        h('div', { class: 'card' },
            hs.loading
                ? h('p', { class: 'muted-text', role: 'status' }, t('common.loading'))
                : hs.error
                    ? h('div', { class: 'row-between' },
                        h('span', { class: 'danger-text' }, hs.error),
                        h('button', {
                            class: 'btn btn-ghost',
                            onclick: () => fetchHistory(app.routeGen, app.routeAbort.signal)
                        }, t('common.retry'))
                    )
                    : [
                        h('div', { class: 'inset-band' },
                            h('div', { class: 'row-wrap' },
                                h('strong', { class: 'tabular' }, t('history.winsOf', { wins: hs.stats.wins })),
                                h('span', { class: 'muted-text tabular' },
                                    t('history.statsTail', {
                                        games: hs.stats.gamesPlayed,
                                        rate: winRate(hs.stats)
                                    }))
                            )
                        ),
                        hs.items.length === 0
                            ? h('p', { class: 'muted-text' }, t('history.empty'))
                            : h('div', { class: 'list-divide' }, hs.items.map((game, idx) =>
                                h('div', { class: 'flat-row', dataset: { key: `hist-${game.gameId || idx}` } },
                                    h('div', { class: 'row grow' },
                                        h('span', { 'aria-hidden': 'true' }, game.didIWin ? '🏆' : '⚔️'),
                                        h('div', { class: 'stack-sm' },
                                            h('strong', {}, game.didIWin
                                                ? t('history.victory')
                                                : (game.winnerName
                                                    ? t('history.wonBy', { name: game.winnerName })
                                                    : t('history.noWinner'))),
                                            h('span', { class: 'muted-text tabular' },
                                                `${new Date(game.endedAt).toLocaleDateString(getLang())} · ${t('history.playersCount', { count: game.playerCount })}`)
                                        )
                                    )
                                )
                            ))
                    ]
        )
    );
}

/* ==========================================================================
   Game View Dispatcher & Root Render
   ========================================================================== */

function gameView() {
    const state = app.game;
    if (!state) {
        const seat = session.seat;
        return h('section', { class: 'screen', dataset: { key: 'view-game-resume' } },
            pageHeader(t('gameResume.title'), { back: () => nav('#/home') }),
            h('div', { class: 'card' },
                seat
                    ? h('div', { class: 'stack' },
                        h('p', {}, t('gameResume.found', { code: seat.joinCode })),
                        h('button', {
                            class: 'btn btn-primary btn-block',
                            disabled: app.busy,
                            onclick: resumeSeat
                        }, app.busy ? t('gameResume.connecting') : t('gameResume.resumeBtn'))
                    )
                    : h('p', { class: 'muted-text' }, t('gameResume.none'))
            )
        );
    }
    if (state.phase === PHASES.FINISHED) return resultsView();
    if (state.phase === PHASES.LOBBY) return lobbyView();
    if (state.combat && !app.viewBoardDuringCombat) return combatView();
    return boardView();
}

function render() {
    const root = document.getElementById('app');
    if (!root) return;
    document.title = t('app.title');

    const { name, arg } = route();
    let view;
    switch (name) {
        case 'home': view = homeView(); break;
        case 'auth': view = authView(); break;
        case 'create': view = createView(); break;
        case 'join': view = joinView(arg); break;
        case 'catalog': view = catalogView(); break;
        case 'game': view = gameView(); break;
        case 'ranking': view = rankingView(); break;
        case 'history': view = historyView(); break;
        default: view = homeView();
    }

    const isWide = name === 'catalog' || (name === 'game' && app.game?.phase === PHASES.IN_GAME);
    const routeKey = [
        name,
        arg || '',
        app.game?.phase || '-',
        app.game?.combat && !app.viewBoardDuringCombat ? 'combat' : '-'
    ].join('|');

    const switchedScreen = routeKey !== app.lastRenderedRouteKey;
    app.lastRenderedRouteKey = routeKey;

    const mainNode = h('main', {
        class: `main-shell ${isWide ? 'is-wide' : ''}`,
        dataset: { key: `shell-${name}` }
    },
        connectionBanner(),
        view
    );

    patchScreen(root, [topbar(name), mainNode], { preserveFocus: !switchedScreen });
    if (switchedScreen) {
        window.scrollTo(0, 0);
    }
}

setInterval(() => {
    const el = document.getElementById('turn-countdown');
    if (!el) return;
    const { text, urgent } = turnCountdownInfo();
    el.textContent = text;
    el.classList.toggle('is-urgent', urgent);
}, 500);

document.addEventListener('visibilitychange', () => {
    if (!document.hidden) app.socket?.nudge();
});
window.addEventListener('pageshow', () => app.socket?.nudge());
window.addEventListener('online', () => app.socket?.nudge());

window.addEventListener('hashchange', () => {
    bumpRouteToken();
    closeAllModals();
    app.winnerPromptOpen = false;
    app.heroModal = null;
    app.heroModalPlayerId = null;
    app.badStuffModal = null;
    if (app.logDrawer) app.logDrawer.close();
    render();
});

render();
