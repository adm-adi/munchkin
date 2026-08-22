/**
 * Munchkin Mesa Tracker — web client.
 *
 * Single-page app over the same authoritative WebSocket server the Android app
 * talks to. Views are plain functions re-rendered from scratch on every state
 * change; the golden rule holds here too: nothing mutates gameplay state
 * locally — every action is a message to the server, and the UI renders what
 * comes back.
 */

import {
    messages, events, playerMeta, monsterInstance, tempBonus, LIMITS,
    CLASSES, RACES, GENDERS, PHASES, OUTCOMES
} from './protocol.mjs';
import {
    playerList, canStart, tiedPlayerIds, allPlayersRolled,
    combatPower, canBeConfirmedWinner, activeClasses, activeRaces
} from './engine.mjs';
import {
    calculateResult, getBreakdown, runAwayModifiers, RUN_AWAY_THRESHOLD
} from './combat.mjs';
import {
    GameSocket, ConnState, oneOffRequest, authenticatedRequest
} from './net.mjs';
import { session } from './session.mjs';
import {
    CLASS_LABELS, RACE_LABELS, GENDER_LABELS, AVATARS, ABILITIES,
    avatarEmoji, errorText
} from './i18n.mjs';
import { h, clear, fmtSigned, toast, openModal, closeAllModals, confirmDialog } from './ui.mjs';

const WS_URL = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}`;

const BREAKDOWN_LABELS = {
    base: name => `${name} (base)`,
    helper: name => `${name} (ayudante)`,
    monster: (name, level) => `${name} (nivel ${level})`,
    manual: 'Modificador manual',
    clericVsUndead: 'Clérigo vs No-Muerto',
    helperClericVsUndead: 'Ayudante Clérigo vs No-Muerto',
    cardModifiers: 'Modificadores de carta'
};

const app = {
    socket: null,
    game: null,
    myPlayerId: null,
    conn: ConnState.DISCONNECTED,
    connAttempt: 0,
    busy: false,
    viewBoardDuringCombat: false,
    combatWasActive: false,
    winnerPromptDismissed: new Set(),
    winnerPromptOpen: false
};

// ============== Routing ==============

function route() {
    const hash = location.hash || '#/';
    const parts = hash.slice(2).split('/').filter(Boolean);
    let arg = parts[1] || null;
    if (arg) {
        try { arg = decodeURIComponent(arg); } catch { /* keep it raw */ }
    }
    return { name: parts[0] || 'home', arg };
}

function nav(hash) {
    if (location.hash === hash) render();
    else location.hash = hash;
}

// ============== Game session wiring ==============

function attachSocket(socket) {
    app.socket = socket;
    socket.on('state', gameState => {
        app.game = gameState;
        onGameStateChanged();
        render();
    });
    socket.on('welcome', welcome => {
        app.myPlayerId = welcome.yourPlayerId;
        app.game = welcome.gameState;
        persistSeat(welcome);
    });
    socket.on('connection', ({ state, attempt }) => {
        app.conn = state;
        app.connAttempt = attempt;
        render();
    });
    socket.on('error', err => toast(errorText(err), 'error'));
    socket.on('deleted', reason => {
        toast(reason || 'La partida ha sido borrada', 'info');
        cleanupGame();
        nav('#/home');
    });
}

function persistSeat(welcome) {
    const state = welcome.gameState || app.game;
    if (!state) return;
    const me = state.players?.[welcome.yourPlayerId];
    session.saveSeat({
        joinCode: state.joinCode,
        playerId: welcome.yourPlayerId,
        reconnectToken: welcome.reconnectToken || null,
        name: me?.name || myIdentity().name,
        avatarId: me?.avatarId ?? 0,
        gender: me?.gender || 'NA'
    });
}

function cleanupGame() {
    app.socket?.leave();
    app.socket = null;
    app.game = null;
    app.myPlayerId = null;
    app.conn = ConnState.DISCONNECTED;
    app.combatWasActive = false;
    app.viewBoardDuringCombat = false;
    app.winnerPromptDismissed = new Set();
    app.winnerPromptOpen = false;
    session.clearSeat();
    closeAllModals();
}

function onGameStateChanged() {
    const combatActive = Boolean(app.game?.combat);
    if (combatActive && !app.combatWasActive) {
        // A fresh combat pulls everyone to the combat screen, like the app.
        app.viewBoardDuringCombat = false;
    }
    app.combatWasActive = combatActive;
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
        toast('No conectado', 'error');
        return;
    }
    try {
        app.socket.sendEvent(builder(app.myPlayerId));
    } catch (err) {
        toast(errorText(err), 'error');
    }
}

function sendMessage(msg) {
    try {
        app.socket?.sendMessage(msg);
    } catch (err) {
        toast(errorText(err), 'error');
    }
}

function myIdentity() {
    const profile = session.profile;
    const identity = session.identity;
    return {
        name: identity?.name || profile?.username || '',
        avatarId: identity?.avatarId ?? profile?.avatarId ?? 0,
        gender: identity?.gender || profile?.gender || 'NA'
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
        toast(errorText(err), 'error');
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
            // cleanupGame drops the stored seat, which is right when the room
            // no longer exists — but a transient failure (timeout, network)
            // must keep "Reanudar" available for a retry.
            session.saveSeat({ joinCode, playerId, reconnectToken, name, avatarId, gender });
        }
        toast(errorText(err), 'error');
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
            title: 'Cerrar la partida',
            text: 'Eres el anfitrión. Si sales, la partida se cierra para todos. ¿Seguro?',
            yes: 'Cerrar partida',
            danger: true
        });
        if (!del) return;
        try { sendMessage(messages.deleteGame()); } catch { /* best effort */ }
        setTimeout(() => { cleanupGame(); nav('#/home'); }, 250);
        return;
    }
    const go = app.game.phase === PHASES.FINISHED || await confirmDialog({
        title: 'Salir de la partida',
        text: 'Podrás volver con el mismo código mientras la partida siga activa.',
        yes: 'Salir'
    });
    if (!go) return;
    cleanupGame();
    nav('#/home');
}

// ============== Winner confirmation (host) ==============

function maybePromptWinner() {
    const state = app.game;
    if (!state || !isHost() || app.winnerPromptOpen) return;
    if (state.phase !== PHASES.IN_GAME || state.winnerId) return;
    const candidate = playerList(state).find(p =>
        canBeConfirmedWinner(p, state.settings) && !app.winnerPromptDismissed.has(p.playerId));
    if (!candidate) return;

    app.winnerPromptOpen = true;
    const close = openModal({
        title: '👑 ¡Tenemos ganador!',
        dismissible: false,
        content: h('p', {},
            `${candidate.name} ha llegado al nivel ${state.settings?.maxLevel ?? 10}. `,
            '¿Confirmas la victoria y terminas la partida?'),
        actions: [
            h('button', {
                class: 'btn btn-ghost',
                onClick: () => {
                    app.winnerPromptDismissed.add(candidate.playerId);
                    app.winnerPromptOpen = false;
                    close();
                }
            }, 'Aún no'),
            h('button', {
                class: 'btn btn-primary',
                onClick: () => {
                    app.winnerPromptOpen = false;
                    close();
                    sendMessage(messages.gameOver(app.game.gameId, candidate.playerId));
                }
            }, 'Confirmar victoria')
        ]
    });
}

// ============== Shared UI pieces ==============

function avatarBadge(player, size = '') {
    return h('span', { class: `avatar ${size}` }, avatarEmoji(player.avatarId, player.gender));
}

function connectionBanner() {
    if (app.conn === ConnState.RECONNECTING) {
        return h('div', { class: 'conn-banner conn-warn' },
            `Reconectando… (intento ${app.connAttempt}/15)`);
    }
    if (app.conn === ConnState.FAILED_PERMANENTLY) {
        return h('div', { class: 'conn-banner conn-bad' },
            'Sin conexión. ',
            h('button', { class: 'btn btn-mini', onClick: () => app.socket?.nudge() }, 'Reintentar'));
    }
    return null;
}

function header(title, { back = null, right = null } = {}) {
    return h('header', { class: 'topbar' },
        back
            ? h('button', { class: 'btn-icon', onClick: back, 'aria-label': 'Volver' }, '←')
            : h('span', { class: 'topbar-spacer' }),
        h('h1', { class: 'topbar-title' }, title),
        right || h('span', { class: 'topbar-spacer' })
    );
}

function identityFields(prefill, { withName = true } = {}) {
    return h('div', {},
        withName && h('label', { class: 'field' },
            h('span', { class: 'field-label' }, 'Tu nombre'),
            h('input', {
                class: 'input', id: 'f-name', maxlength: String(LIMITS.MAX_NAME_LENGTH),
                placeholder: 'Nombre de munchkin', value: prefill.name || '', autocomplete: 'nickname'
            })
        ),
        h('div', { class: 'field' },
            h('span', { class: 'field-label' }, 'Avatar'),
            h('div', { class: 'avatar-grid', id: 'f-avatars' },
                AVATARS.map(slot => h('button', {
                    type: 'button',
                    class: `avatar-choice ${slot.id === (prefill.avatarId ?? 0) ? 'selected' : ''}`,
                    dataset: { avatarId: String(slot.id) },
                    title: slot.name,
                    onClick: e => {
                        const grid = e.currentTarget.parentElement;
                        grid.querySelectorAll('.avatar-choice').forEach(b => b.classList.remove('selected'));
                        e.currentTarget.classList.add('selected');
                        grid.querySelectorAll('.avatar-choice').forEach(b => {
                            const id = Number(b.dataset.avatarId);
                            const gender = selectedGender();
                            b.textContent = gender === 'F' ? AVATARS[id].f : AVATARS[id].m;
                        });
                    }
                }, (prefill.gender === 'F' ? slot.f : slot.m)))
            )
        ),
        h('div', { class: 'field' },
            h('span', { class: 'field-label' }, 'Género'),
            h('div', { class: 'segmented', id: 'f-gender' },
                GENDERS.map(g => h('button', {
                    type: 'button',
                    class: `segment ${g === (prefill.gender || 'NA') ? 'selected' : ''}`,
                    dataset: { gender: g },
                    onClick: e => {
                        const seg = e.currentTarget.parentElement;
                        seg.querySelectorAll('.segment').forEach(b => b.classList.remove('selected'));
                        e.currentTarget.classList.add('selected');
                        document.querySelectorAll('#f-avatars .avatar-choice').forEach(b => {
                            const id = Number(b.dataset.avatarId);
                            b.textContent = g === 'F' ? AVATARS[id].f : AVATARS[id].m;
                        });
                    }
                }, GENDER_LABELS[g]))
            )
        )
    );
}

function selectedGender() {
    return document.querySelector('#f-gender .segment.selected')?.dataset.gender || 'NA';
}

function readIdentityFields() {
    const name = document.getElementById('f-name')?.value.trim() || '';
    const avatarId = Number(document.querySelector('#f-avatars .avatar-choice.selected')?.dataset.avatarId || 0);
    const gender = selectedGender();
    return { name, avatarId, gender };
}

// ============== Views ==============

function homeView() {
    const profile = session.profile;
    const seat = session.seat;
    const inGame = Boolean(app.socket && app.game);

    return h('div', { class: 'screen home' },
        h('div', { class: 'hero' },
            h('div', { class: 'hero-die' }, '🎲'),
            h('h1', { class: 'hero-title' }, 'Munchkin'),
            h('p', { class: 'hero-sub' }, 'Mesa Tracker')
        ),
        profile
            ? h('button', { class: 'user-chip', onClick: () => openProfileModal() },
                h('span', { class: 'avatar sm' }, avatarEmoji(profile.avatarId, profile.gender)),
                h('span', {}, profile.username))
            : h('button', { class: 'user-chip', onClick: () => nav('#/auth') },
                h('span', { class: 'avatar sm' }, '👤'),
                h('span', {}, 'Iniciar sesión')),

        (inGame || seat) && h('button', {
            class: 'card resume-card',
            onClick: () => (inGame ? nav('#/game') : resumeSeat())
        },
            h('span', { class: 'resume-icon' }, '▶️'),
            h('span', {},
                h('strong', {}, inGame ? 'Volver a la partida' : 'Reanudar partida'),
                h('small', {}, `Código ${inGame ? app.game.joinCode : seat.joinCode}`))
        ),

        h('div', { class: 'home-actions' },
            h('button', { class: 'btn btn-primary btn-big', onClick: () => nav('#/create') },
                '⚔️ Nueva partida'),
            h('button', { class: 'btn btn-secondary btn-big', onClick: () => nav('#/join') },
                '🔑 Unirse con código'),
            h('button', { class: 'btn btn-ghost', onClick: () => nav('#/ranking') }, '🏆 Ranking'),
            profile && h('button', { class: 'btn btn-ghost', onClick: () => nav('#/history') }, '📜 Mi historial'),
            profile && h('button', { class: 'btn btn-ghost', onClick: () => openHostedGamesModal() }, '🗂️ Mis partidas')
        ),
        h('p', { class: 'fine-print' },
            'Añade esta página a tu pantalla de inicio para usarla como una app.')
    );
}

function openProfileModal() {
    const profile = session.profile;
    if (!profile) return;
    const close = openModal({
        title: profile.username,
        content: h('div', { class: 'profile-box' },
            h('div', { class: 'avatar lg' }, avatarEmoji(profile.avatarId, profile.gender)),
            h('p', { class: 'muted' }, profile.email)
        ),
        actions: [
            h('button', {
                class: 'btn btn-danger',
                onClick: () => { session.clearAuth(); close(); toast('Sesión cerrada'); render(); }
            }, 'Cerrar sesión'),
            h('button', { class: 'btn btn-ghost', onClick: () => close() }, 'Cerrar')
        ]
    });
}

async function openHostedGamesModal() {
    try {
        const { reply } = await authenticatedRequest(WS_URL, session.authToken, messages.getHostedGames());
        const games = reply.games || [];
        const close = openModal({
            title: 'Mis partidas',
            content: games.length === 0
                ? h('p', { class: 'muted' }, 'No tienes partidas activas como anfitrión.')
                : h('div', { class: 'list' }, games.map(g =>
                    h('div', { class: 'list-row' },
                        h('div', {},
                            h('strong', {}, g.joinCode),
                            h('small', { class: 'muted' }, ` · ${g.playerCount} jug. · ${g.phase}`)),
                        h('div', { class: 'row-actions' },
                            h('button', {
                                class: 'btn btn-mini btn-primary',
                                onClick: () => { close(); nav(`#/join/${encodeURIComponent(g.joinCode)}`); }
                            }, 'Entrar'),
                            h('button', {
                                class: 'btn btn-mini btn-danger',
                                onClick: async () => {
                                    const ok = await confirmDialog({
                                        title: `Borrar ${g.joinCode}`,
                                        text: 'La partida se cerrará para todos.',
                                        yes: 'Borrar', danger: true
                                    });
                                    if (!ok) return;
                                    try {
                                        await authenticatedRequest(
                                            WS_URL, session.authToken, messages.deleteHostedGame(g.gameId));
                                        toast('Partida borrada');
                                        close();
                                    } catch (err) {
                                        toast(errorText(err), 'error');
                                    }
                                }
                            }, 'Borrar'))
                    )))
        });
    } catch (err) {
        toast(errorText(err), 'error');
    }
}

function authView() {
    const identity = myIdentity();
    return h('div', { class: 'screen' },
        header('Tu cuenta', { back: () => nav('#/home') }),
        h('div', { class: 'card form-card' },
            h('div', { class: 'segmented', id: 'auth-tabs' },
                h('button', {
                    class: 'segment selected', dataset: { tab: 'login' },
                    onClick: e => switchAuthTab(e, 'login')
                }, 'Entrar'),
                h('button', {
                    class: 'segment', dataset: { tab: 'register' },
                    onClick: e => switchAuthTab(e, 'register')
                }, 'Crear cuenta')
            ),
            h('form', {
                id: 'auth-form-login',
                onSubmit: e => { e.preventDefault(); doLogin(); }
            },
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, 'Email'),
                    h('input', { class: 'input', id: 'login-email', type: 'email', autocomplete: 'email' })),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, 'Contraseña'),
                    h('input', { class: 'input', id: 'login-password', type: 'password', autocomplete: 'current-password' })),
                h('button', { class: 'btn btn-primary btn-big', type: 'submit', disabled: app.busy }, 'Entrar')
            ),
            h('form', {
                id: 'auth-form-register', class: 'hidden',
                onSubmit: e => { e.preventDefault(); doRegister(); }
            },
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, 'Nombre de usuario'),
                    h('input', { class: 'input', id: 'reg-username', maxlength: '20', autocomplete: 'username' })),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, 'Email'),
                    h('input', { class: 'input', id: 'reg-email', type: 'email', autocomplete: 'email' })),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, `Contraseña (mín. ${LIMITS.MIN_PASSWORD_LENGTH})`),
                    h('input', { class: 'input', id: 'reg-password', type: 'password', autocomplete: 'new-password' })),
                identityFields(identity, { withName: false }),
                h('button', { class: 'btn btn-primary btn-big', type: 'submit', disabled: app.busy }, 'Crear cuenta')
            )
        )
    );
}

function switchAuthTab(e, tab) {
    document.querySelectorAll('#auth-tabs .segment').forEach(b => b.classList.remove('selected'));
    e.currentTarget.classList.add('selected');
    document.getElementById('auth-form-login').classList.toggle('hidden', tab !== 'login');
    document.getElementById('auth-form-register').classList.toggle('hidden', tab !== 'register');
}

async function doLogin() {
    const email = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;
    if (!email || !password) return toast('Rellena email y contraseña', 'error');
    app.busy = true;
    try {
        const reply = await oneOffRequest(WS_URL, messages.login(email, password));
        session.saveAuth(reply.token, reply.user);
        toast(`¡Hola, ${reply.user.username}!`, 'success');
        nav('#/home');
    } catch (err) {
        toast(errorText(err), 'error');
    } finally {
        app.busy = false;
    }
}

async function doRegister() {
    const username = document.getElementById('reg-username').value.trim();
    const email = document.getElementById('reg-email').value.trim();
    const password = document.getElementById('reg-password').value;
    const { avatarId, gender } = readIdentityFields();
    if (!username || password.length < LIMITS.MIN_PASSWORD_LENGTH) {
        return toast(`Usuario y contraseña de al menos ${LIMITS.MIN_PASSWORD_LENGTH} caracteres`, 'error');
    }
    app.busy = true;
    try {
        const reply = await oneOffRequest(WS_URL, messages.register({ username, email, password, avatarId, gender }));
        session.saveAuth(reply.token, reply.user);
        toast(`Cuenta creada. ¡Bienvenido, ${reply.user.username}!`, 'success');
        nav('#/home');
    } catch (err) {
        toast(errorText(err), 'error');
    } finally {
        app.busy = false;
    }
}

function createView() {
    const identity = myIdentity();
    return h('div', { class: 'screen' },
        header('Nueva partida', { back: () => nav('#/home') }),
        h('form', {
            class: 'card form-card',
            onSubmit: e => {
                e.preventDefault();
                const { name, avatarId, gender } = readIdentityFields();
                if (!name) return toast('Escribe tu nombre', 'error');
                const epicMode = document.getElementById('f-epic').checked;
                const turnTimerSeconds = Number(document.getElementById('f-timer').value || 0);
                startGameAsHost({ name, avatarId, gender, epicMode, turnTimerSeconds });
            }
        },
            identityFields(identity),
            h('label', { class: 'field field-row' },
                h('span', {},
                    h('span', { class: 'field-label' }, 'Modo épico'),
                    h('small', { class: 'muted' }, 'La partida se juega hasta nivel 20')),
                h('input', { class: 'switch', id: 'f-epic', type: 'checkbox' })
            ),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, 'Temporizador de turno'),
                h('select', { class: 'input', id: 'f-timer' },
                    h('option', { value: '0' }, 'Sin límite'),
                    h('option', { value: '60' }, '1 minuto'),
                    h('option', { value: '120' }, '2 minutos'),
                    h('option', { value: '300' }, '5 minutos')
                )
            ),
            h('button', { class: 'btn btn-primary btn-big', type: 'submit', disabled: app.busy },
                app.busy ? 'Creando…' : '⚔️ Crear partida')
        )
    );
}

function joinView(codeArg) {
    const identity = myIdentity();
    return h('div', { class: 'screen' },
        header('Unirse a partida', { back: () => nav('#/home') }),
        h('form', {
            class: 'card form-card',
            onSubmit: e => {
                e.preventDefault();
                const joinCode = document.getElementById('f-code').value.trim().toUpperCase();
                const { name, avatarId, gender } = readIdentityFields();
                if (joinCode.length !== LIMITS.JOIN_CODE_LENGTH) return toast('El código tiene 8 caracteres', 'error');
                if (!name) return toast('Escribe tu nombre', 'error');
                joinGame({ joinCode, name, avatarId, gender });
            }
        },
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, 'Código de partida'),
                h('input', {
                    class: 'input input-code', id: 'f-code',
                    maxlength: String(LIMITS.JOIN_CODE_LENGTH),
                    value: codeArg || '', placeholder: 'ABCD2345',
                    autocapitalize: 'characters', autocomplete: 'off', spellcheck: 'false',
                    onInput: e => { e.target.value = e.target.value.toUpperCase(); }
                })
            ),
            identityFields(identity),
            h('button', { class: 'btn btn-primary btn-big', type: 'submit', disabled: app.busy },
                app.busy ? 'Uniéndose…' : '🔑 Unirse')
        )
    );
}

// ============== Game: lobby ==============

function shareInvite() {
    const code = app.game?.joinCode;
    if (!code) return;
    const url = `${location.origin}/#/join/${code}`;
    const text = `Únete a mi partida de Munchkin con el código ${code}: ${url}`;
    if (navigator.share) {
        navigator.share({ title: 'Munchkin', text, url }).catch(() => { /* cancelled */ });
    } else if (navigator.clipboard) {
        navigator.clipboard.writeText(text)
            .then(() => toast('Invitación copiada', 'success'))
            .catch(() => toast(url));
    } else {
        toast(url);
    }
}

function lobbyView() {
    const state = app.game;
    const list = playerList(state);
    const tied = tiedPlayerIds(state);
    const my = me();
    const host = isHost();

    return h('div', { class: 'screen' },
        header('Sala de espera', {
            back: () => leaveCurrentGame(),
            right: h('button', { class: 'btn-icon', onClick: shareInvite, 'aria-label': 'Compartir' }, '📤')
        }),
        h('button', { class: 'card code-card', onClick: shareInvite },
            h('small', { class: 'muted' }, 'Código de partida — toca para compartir'),
            h('div', { class: 'join-code' }, state.joinCode)
        ),
        h('div', { class: 'card' },
            h('h3', { class: 'card-title' }, `Jugadores (${list.length}/${LIMITS.MAX_PLAYERS})`),
            h('div', { class: 'list' }, list.map((p, index) =>
                h('div', { class: `list-row ${p.isConnected ? '' : 'row-off'}` },
                    avatarBadge(p),
                    h('div', { class: 'grow' },
                        h('strong', {}, p.name, p.playerId === state.hostId ? ' 👑' : '',
                            p.playerId === app.myPlayerId ? ' (tú)' : ''),
                        h('small', { class: 'muted' },
                            p.isConnected ? 'Conectado' : 'Desconectado',
                            tied.includes(p.playerId) ? ' · ¡empate, vuelve a tirar!' : '')),
                    h('span', { class: `die ${tied.includes(p.playerId) ? 'die-tied' : ''}` },
                        p.lastRoll ? `⚀⚁⚂⚃⚄⚅`[p.lastRoll - 1] : '·'),
                    host && p.playerId !== app.myPlayerId && h('span', { class: 'row-actions' },
                        index > 0 && h('button', {
                            class: 'btn-icon sm', title: 'Subir',
                            onClick: () => sendMessage(messages.swapPlayers(list[index - 1].playerId, p.playerId))
                        }, '↑'),
                        h('button', {
                            class: 'btn-icon sm', title: 'Expulsar',
                            onClick: async () => {
                                const ok = await confirmDialog({
                                    title: `Expulsar a ${p.name}`, yes: 'Expulsar', danger: true
                                });
                                if (ok) sendMessage(messages.kickPlayer(p.playerId));
                            }
                        }, '✕'))
                )))
        ),
        h('div', { class: 'sticky-actions' },
            // Ties persist server-side (lastRoll is not reset), so the tied
            // players must be offered a re-roll — same as LobbyScreen.kt.
            my && (my.lastRoll === null || my.lastRoll === undefined || tied.includes(my.playerId)) && h('button', {
                class: 'btn btn-secondary btn-big',
                onClick: () => sendEvent(actor => events.playerRoll(actor, 1 + Math.floor(Math.random() * 6)))
            }, tied.includes(my.playerId) ? '🎲 ¡Empate! Vuelve a tirar' : '🎲 Tirar el dado de inicio'),
            host
                ? h('button', {
                    class: 'btn btn-primary btn-big',
                    disabled: !canStart(state),
                    onClick: () => sendEvent(actor => events.gameStart(actor))
                }, canStart(state) ? '▶️ ¡Empezar partida!' : startHint(state))
                : h('p', { class: 'muted center' }, 'Esperando a que el anfitrión empiece…')
        )
    );
}

function startHint(state) {
    if (Object.keys(state.players).length < 2) return 'Faltan jugadores (mínimo 2)';
    if (!allPlayersRolled(state)) return 'Faltan tiradas de dado';
    if (tiedPlayerIds(state).length > 0) return 'Hay empate en las tiradas';
    return 'Empezar partida';
}

// ============== Game: board ==============

function turnCountdownText() {
    const state = app.game;
    if (!state?.turnEndsAt || !app.socket) return '';
    const remaining = Math.max(0, Math.round((state.turnEndsAt - app.socket.serverNow()) / 1000));
    const minutes = Math.floor(remaining / 60);
    const seconds = String(remaining % 60).padStart(2, '0');
    return `⏱ ${minutes}:${seconds}`;
}

function boardView() {
    const state = app.game;
    const list = playerList(state);
    const my = me();
    const host = isHost();
    const turnPlayer = state.turnPlayerId ? state.players[state.turnPlayerId] : null;
    const myTurn = state.turnPlayerId === app.myPlayerId;
    const combat = state.combat;

    return h('div', { class: 'screen' },
        header(`Mesa · ${state.joinCode}`, {
            back: () => leaveCurrentGame(),
            right: h('button', { class: 'btn-icon', onClick: shareInvite, 'aria-label': 'Compartir' }, '📤')
        }),

        turnPlayer && h('div', { class: `turn-banner ${myTurn ? 'turn-mine' : ''}` },
            h('span', {}, myTurn ? '¡Tu turno!' : `Turno de ${turnPlayer.name}`),
            state.turnEndsAt && h('span', { id: 'turn-countdown', class: 'countdown' }, turnCountdownText())
        ),

        combat && h('button', { class: 'card combat-banner', onClick: () => { app.viewBoardDuringCombat = false; render(); } },
            '⚔️ Combate en curso — toca para verlo'),

        my && myPlayerCard(my, state, myTurn),

        h('div', { class: 'player-grid' },
            list.filter(p => p.playerId !== app.myPlayerId).map(p => otherPlayerCard(p, state, host))
        ),

        h('div', { class: 'sticky-actions' },
            !combat && h('button', {
                class: 'btn btn-secondary btn-big',
                onClick: () => sendEvent(actor => events.combatStart(actor, actor))
            }, '⚔️ ¡A luchar!'),
            myTurn && h('button', {
                class: 'btn btn-primary btn-big',
                onClick: () => sendEvent(actor => events.endTurn(actor))
            }, '⏭️ Pasar turno')
        )
    );
}

function statStepper(label, value, onMinus, onPlus, { minusDisabled = false, plusDisabled = false } = {}) {
    return h('div', { class: 'stepper' },
        h('span', { class: 'stepper-label' }, label),
        h('div', { class: 'stepper-controls' },
            h('button', { class: 'btn-step', disabled: minusDisabled, onClick: onMinus }, '−'),
            h('span', { class: 'stepper-value' }, String(value)),
            h('button', { class: 'btn-step', disabled: plusDisabled, onClick: onPlus }, '+')
        )
    );
}

function myPlayerCard(my, state, myTurn) {
    const minLevel = state.settings?.minLevel ?? 1;
    const maxLevel = state.settings?.maxLevel ?? 10;
    return h('div', { class: `card me-card ${myTurn ? 'me-turn' : ''}` },
        h('div', { class: 'me-head' },
            avatarBadge(my, 'lg'),
            h('div', { class: 'grow' },
                h('strong', { class: 'me-name' }, my.name, state.hostId === my.playerId ? ' 👑' : ''),
                h('small', { class: 'muted' }, playerTraitsText(my))),
            crownButton(my, state),
            h('button', { class: 'btn btn-mini btn-ghost', onClick: () => openHeroModal() }, 'Editar héroe')
        ),
        h('div', { class: 'me-stats' },
            statStepper('Nivel', my.level,
                () => sendEvent(actor => events.decLevel(actor)),
                () => sendEvent(actor => events.incLevel(actor)),
                { minusDisabled: my.level <= minLevel, plusDisabled: my.level >= maxLevel }),
            statStepper('Equipo', my.gearBonus,
                () => sendEvent(actor => events.decGear(actor)),
                () => sendEvent(actor => events.incGear(actor))),
            h('div', { class: 'power-chip' },
                h('small', {}, 'Poder'),
                h('strong', {}, String(combatPower(my))))
        )
    );
}

function playerTraitsText(player) {
    const parts = [];
    const classes = activeClasses(player).map(c => CLASS_LABELS[c]).join(' + ');
    const races = activeRaces(player).map(r => RACE_LABELS[r]).join(' + ');
    parts.push(classes || CLASS_LABELS.NONE);
    parts.push(races || RACE_LABELS.HUMAN);
    return parts.join(' · ');
}

function crownButton(player, state) {
    // Fallback for a host who dismissed the automatic prompt: the win can
    // still be confirmed from the candidate's card.
    if (!isHost() || state.winnerId || state.phase !== PHASES.IN_GAME) return null;
    if (!canBeConfirmedWinner(player, state.settings)) return null;
    return h('button', {
        class: 'btn btn-mini btn-primary', title: 'Confirmar victoria',
        onClick: async () => {
            const ok = await confirmDialog({
                title: `¿${player.name} gana la partida?`, yes: 'Confirmar victoria'
            });
            if (ok) sendMessage(messages.gameOver(state.gameId, player.playerId));
        }
    }, '👑 Corona');
}

function otherPlayerCard(player, state, host) {
    const isTurn = state.turnPlayerId === player.playerId;
    return h('div', { class: `card player-card ${player.isConnected ? '' : 'row-off'} ${isTurn ? 'player-turn' : ''}` },
        h('div', { class: 'player-card-head' },
            avatarBadge(player),
            h('div', { class: 'grow' },
                h('strong', {}, player.name, player.playerId === state.hostId ? ' 👑' : ''),
                h('small', { class: 'muted' }, playerTraitsText(player))),
            crownButton(player, state),
            !player.isConnected && host && h('button', {
                class: 'btn-icon sm', title: 'Expulsar',
                onClick: async () => {
                    const ok = await confirmDialog({
                        title: `Expulsar a ${player.name}`, yes: 'Expulsar', danger: true
                    });
                    if (ok) sendMessage(messages.kickPlayer(player.playerId));
                }
            }, '✕')
        ),
        h('div', { class: 'player-card-stats' },
            h('span', {}, `Nv ${player.level}`),
            h('span', {}, `Eq ${fmtSigned(player.gearBonus)}`),
            h('span', { class: 'power' }, `⚡ ${combatPower(player)}`)
        )
    );
}

// ============== Hero editor (class / race) ==============

function openHeroModal() {
    const renderContent = () => {
        const my = me();
        if (!my) return h('p', {}, '…');
        return h('div', {},
            heroPickRow('Clase', CLASSES, my.characterClass, CLASS_LABELS,
                value => sendEvent(actor => events.setClass(actor, value, false))),
            h('label', { class: 'field field-row' },
                h('span', {},
                    h('span', { class: 'field-label' }, 'Súper Munchkin'),
                    h('small', { class: 'muted' }, 'Permite una segunda clase')),
                h('input', {
                    class: 'switch', type: 'checkbox', checked: my.hasSuperMunchkin,
                    onChange: e => sendEvent(actor => events.setSuperMunchkin(actor, e.target.checked))
                })),
            my.hasSuperMunchkin && heroPickRow('Segunda clase', CLASSES, my.secondaryClass, CLASS_LABELS,
                value => sendEvent(actor => events.setClass(actor, value, true))),

            heroPickRow('Raza', RACES, my.characterRace, RACE_LABELS,
                value => sendEvent(actor => events.setRace(actor, value, false))),
            h('label', { class: 'field field-row' },
                h('span', {},
                    h('span', { class: 'field-label' }, 'Mestizo'),
                    h('small', { class: 'muted' }, 'Permite una segunda raza')),
                h('input', {
                    class: 'switch', type: 'checkbox', checked: my.hasHalfBreed,
                    onChange: e => sendEvent(actor => events.setHalfBreed(actor, e.target.checked))
                })),
            my.hasHalfBreed && heroPickRow('Segunda raza', RACES, my.secondaryRace, RACE_LABELS,
                value => sendEvent(actor => events.setRace(actor, value, true))),

            abilitiesBox(my)
        );
    };

    let contentHost;
    const close = openModal({
        title: 'Tu héroe',
        content: contentHost = h('div', { class: 'hero-editor' }, renderContent()),
        actions: [h('button', { class: 'btn btn-primary', onClick: () => close() }, 'Listo')]
    });

    // Selections round-trip through the server; refresh the sheet when the
    // snapshot lands so the tapped chip actually lights up.
    const unsubscribe = app.socket?.on('state', () => {
        if (!document.body.contains(contentHost)) { unsubscribe?.(); return; }
        clear(contentHost);
        contentHost.append(renderContent());
    });
}

function heroPickRow(label, options, current, labels, onPick) {
    return h('div', { class: 'field' },
        h('span', { class: 'field-label' }, label),
        h('div', { class: 'chip-row' },
            options.map(option => h('button', {
                type: 'button',
                class: `chip ${option === current ? 'selected' : ''}`,
                onClick: () => onPick(option)
            }, labels[option])))
    );
}

function abilitiesBox(player) {
    const items = [];
    for (const cls of activeClasses(player)) items.push(...(ABILITIES[cls] || []));
    for (const race of activeRaces(player)) items.push(...(ABILITIES[race] || []));
    if (items.length === 0) return null;
    return h('div', { class: 'abilities-box' },
        h('span', { class: 'field-label' }, 'Habilidades'),
        items.map(a => h('p', { class: `ability ${a.auto ? 'ability-auto' : ''}` },
            a.auto ? '⚙️ ' : '✋ ', a.text)),
        h('small', { class: 'muted' }, '⚙️ la app lo aplica · ✋ aplícalo tú con el modificador')
    );
}

// ============== Game: combat ==============

function combatView() {
    const state = app.game;
    const combat = state.combat;
    const mainPlayer = state.players[combat.mainPlayerId];
    const helper = combat.helperPlayerId ? state.players[combat.helperPlayerId] : null;
    const result = calculateResult(combat, state);
    const iAmMain = combat.mainPlayerId === app.myPlayerId;
    const winning = result.outcome === OUTCOMES.WIN;

    return h('div', { class: 'screen combat' },
        header('⚔️ Combate', {
            back: () => { app.viewBoardDuringCombat = true; render(); }
        }),

        h('div', { class: 'vs-panel' },
            h('div', { class: `vs-side ${winning ? 'vs-winning' : ''}` },
                h('small', {}, 'Héroes'),
                h('strong', { class: 'vs-power' }, String(result.heroesPower)),
                h('span', { class: 'vs-names' },
                    mainPlayer ? mainPlayer.name : '?', helper ? ` + ${helper.name}` : '')),
            h('div', { class: 'vs-mid' }, 'VS'),
            h('div', { class: `vs-side ${winning ? '' : 'vs-winning'}` },
                h('small', {}, 'Monstruos'),
                h('strong', { class: 'vs-power' }, String(result.monstersPower)),
                h('span', { class: 'vs-names' }, `${(combat.monsters || []).length} monstruo(s)`))
        ),
        h('div', { class: `outcome-line ${winning ? 'outcome-win' : 'outcome-lose'}` },
            winning
                ? (result.warriorTieBreak ? '¡Victoria por Guerrero (gana el empate)!' : '¡Vais ganando!')
                : `Vais perdiendo — os falta ${fmtSigned(result.marginToWin)} para ganar`),

        h('div', { class: 'card' },
            h('div', { class: 'card-head-row' },
                h('h3', { class: 'card-title' }, 'Monstruos'),
                h('button', { class: 'btn btn-mini btn-secondary', onClick: () => openAddMonsterModal() }, '+ Añadir')),
            (combat.monsters || []).length === 0
                ? h('p', { class: 'muted' }, 'Añade el monstruo al que os enfrentáis.')
                : h('div', { class: 'list' }, combat.monsters.map(monster =>
                    h('div', { class: 'list-row' },
                        h('span', { class: 'monster-icon' }, monster.isUndead ? '🧟' : '👹'),
                        h('div', { class: 'grow' },
                            h('strong', {}, monster.name),
                            h('small', { class: 'muted' },
                                `Nivel ${monster.baseLevel}`,
                                monster.flatModifier ? ` (${fmtSigned(monster.flatModifier)})` : '',
                                ` · ${monster.treasures} 💰 · +${monster.levels} nv`,
                                monster.isUndead ? ' · No-Muerto' : '')),
                        h('div', { class: 'row-actions' },
                            h('button', {
                                class: 'btn-icon sm',
                                onClick: () => sendEvent(actor =>
                                    events.combatUpdateMonster(actor, { ...monster, flatModifier: monster.flatModifier - 1 }))
                            }, '−'),
                            h('button', {
                                class: 'btn-icon sm',
                                onClick: () => sendEvent(actor =>
                                    events.combatUpdateMonster(actor, { ...monster, flatModifier: monster.flatModifier + 1 }))
                            }, '+'),
                            h('button', {
                                class: 'btn-icon sm',
                                onClick: () => sendEvent(actor => events.combatRemoveMonster(actor, monster.id))
                            }, '✕'))
                    )))
        ),

        h('div', { class: 'card' },
            h('h3', { class: 'card-title' }, 'Modificadores rápidos'),
            h('div', { class: 'mod-row' },
                statStepper('Héroes', combat.heroModifier,
                    () => sendEvent(actor => events.combatSetModifier(actor, 'HEROES', combat.heroModifier - 1)),
                    () => sendEvent(actor => events.combatSetModifier(actor, 'HEROES', combat.heroModifier + 1))),
                statStepper('Monstruos', combat.monsterModifier,
                    () => sendEvent(actor => events.combatSetModifier(actor, 'MONSTER', combat.monsterModifier - 1)),
                    () => sendEvent(actor => events.combatSetModifier(actor, 'MONSTER', combat.monsterModifier + 1)))
            )
        ),

        h('div', { class: 'card' },
            h('div', { class: 'card-head-row' },
                h('h3', { class: 'card-title' }, 'Ayudante'),
                helper && (iAmMain || combat.helperPlayerId === app.myPlayerId) && h('button', {
                    class: 'btn btn-mini btn-ghost',
                    onClick: () => sendEvent(actor => events.combatRemoveHelper(actor))
                }, 'Quitar')),
            helper
                ? h('div', { class: 'list-row' },
                    avatarBadge(helper),
                    h('div', { class: 'grow' },
                        h('strong', {}, helper.name),
                        h('small', { class: 'muted' }, `Aporta ${combatPower(helper)} de poder`)))
                : h('div', { class: 'chip-row' },
                    playerList(state)
                        .filter(p => p.playerId !== combat.mainPlayerId)
                        .map(p => h('button', {
                            class: 'chip',
                            onClick: () => sendEvent(actor => events.combatAddHelper(actor, p.playerId))
                        }, `${avatarEmoji(p.avatarId, p.gender)} ${p.name}`)),
                    Object.keys(state.players).length <= 1 && h('p', { class: 'muted' }, 'Nadie puede ayudar.'))
        ),

        bonusesCard(combat),
        breakdownCard(combat, state),

        h('div', { class: 'sticky-actions' },
            iAmMain
                ? [
                    h('button', {
                        class: `btn btn-big ${winning ? 'btn-primary' : 'btn-danger'}`,
                        onClick: () => resolveCombat(result)
                    }, winning
                        ? `🏆 ¡Victoria! +${result.totalLevels} nv, +${result.totalTreasures} 💰`
                        : '💀 Aceptar derrota'),
                    h('button', { class: 'btn btn-secondary btn-big', onClick: () => openRunAwayModal() },
                        '🏃 Intentar huir')
                ]
                : h('p', { class: 'muted center' },
                    `${mainPlayer ? mainPlayer.name : 'El jugador principal'} resuelve el combate.`)
        )
    );
}

function bonusesCard(combat) {
    return h('div', { class: 'card' },
        h('div', { class: 'card-head-row' },
            h('h3', { class: 'card-title' }, 'Bonos temporales'),
            h('button', { class: 'btn btn-mini btn-secondary', onClick: () => openAddBonusModal() }, '+ Añadir')),
        (combat.tempBonuses || []).length === 0
            ? h('p', { class: 'muted' }, 'Pociones, hechizos, cartas de un solo uso…')
            : h('div', { class: 'list' }, combat.tempBonuses.map(bonus =>
                h('div', { class: 'list-row' },
                    h('div', { class: 'grow' },
                        h('strong', {}, bonus.label),
                        h('small', { class: 'muted' },
                            ` ${fmtSigned(bonus.amount)} a ${bonus.appliesTo === 'HEROES' ? 'héroes' : 'monstruos'}`)),
                    h('button', {
                        class: 'btn-icon sm',
                        onClick: () => sendEvent(actor => events.combatRemoveBonus(actor, bonus.id))
                    }, '✕'))))
    );
}

function breakdownCard(combat, state) {
    const breakdown = getBreakdown(combat, state, BREAKDOWN_LABELS);
    const renderSide = (title, sources) => h('div', { class: 'grow' },
        h('strong', { class: 'muted' }, title),
        sources.map(s => h('div', { class: 'bd-row' },
            h('span', {}, s.label), h('span', {}, fmtSigned(s.amount)))));
    // Every broadcast re-renders the screen; without remembering the toggle,
    // the accordion would snap shut each time anyone changes anything.
    return h('details', {
        class: 'card breakdown',
        open: app.breakdownOpen || false,
        onToggle: e => { app.breakdownOpen = e.target.open; }
    },
        h('summary', { class: 'card-title' }, 'Desglose del poder'),
        h('div', { class: 'bd-cols' },
            renderSide('Héroes', breakdown.heroSources),
            renderSide('Monstruos', breakdown.monsterSources))
    );
}

async function resolveCombat(result) {
    const combat = app.game?.combat;
    if (!combat) return;
    const ok = await confirmDialog({
        title: result.outcome === OUTCOMES.WIN ? 'Confirmar victoria' : 'Confirmar derrota',
        text: result.outcome === OUTCOMES.WIN
            ? `Ganáis ${result.totalLevels} nivel(es) y ${result.totalTreasures} tesoro(s)` +
              (result.helperLevelsGained ? `; el ayudante Elfo sube ${result.helperLevelsGained}.` : '.')
            : 'Perder significa sufrir el Mal Rollo de cada monstruo.',
        yes: result.outcome === OUTCOMES.WIN ? '¡Victoria!' : 'Aceptar derrota',
        danger: result.outcome !== OUTCOMES.WIN
    });
    if (!ok) return;
    const badStuffMonsters = result.outcome === OUTCOMES.WIN ? [] : [...(combat.monsters || [])];
    sendEvent(actor => events.combatEnd(actor, result.outcome, {
        levelsGained: result.totalLevels,
        treasuresGained: result.totalTreasures,
        helperLevelsGained: result.helperLevelsGained
    }));
    if (badStuffMonsters.length) showBadStuff(badStuffMonsters);
}

function showBadStuff(monsters) {
    const withText = monsters.filter(m => (m.badStuff || '').trim());
    const close = openModal({
        title: '💀 Mal Rollo',
        content: h('div', {},
            h('p', { class: 'muted' }, 'Aplica en la mesa el Mal Rollo de cada monstruo:'),
            withText.length
                ? withText.map(m => h('p', {}, h('strong', {}, `${m.name}: `), m.badStuff))
                : h('p', {}, 'Consulta las cartas de los monstruos.')),
        actions: [h('button', { class: 'btn btn-primary', onClick: () => close() }, 'Hecho')]
    });
}

function openRunAwayModal() {
    const my = me();
    const combat = app.game?.combat;
    if (!my || !combat) return;
    const mods = runAwayModifiers(my);
    const bonus = mods.reduce((sum, [, amount]) => sum + amount, 0);
    const monsters = [...(combat.monsters || [])];

    let rolled = null;
    const body = h('div', { class: 'runaway' });

    const renderStep = () => {
        clear(body);
        if (rolled === null) {
            body.append(
                h('button', {
                    class: 'die-btn', 'aria-label': 'Tirar el dado',
                    onClick: () => {
                        rolled = 1 + Math.floor(Math.random() * 6);
                        renderStep();
                    }
                }, '🎲'),
                h('p', { class: 'muted center' }, '¡Toca el dado para intentar huir!'),
                h('p', { class: 'muted center' }, `Escapas con un ${RUN_AWAY_THRESHOLD} o más.`)
            );
        } else {
            const total = rolled + bonus;
            const escaped = total >= RUN_AWAY_THRESHOLD;
            body.append(
                h('div', { class: 'roll-result' }, `⚀⚁⚂⚃⚄⚅`[rolled - 1]),
                h('p', { class: 'center big' }, `Total: ${total}`),
                mods.length > 0 && h('p', { class: 'muted center' },
                    `Dado ${rolled} ` + mods.map(([race, amount]) =>
                        `${fmtSigned(amount)} ${RACE_LABELS[race]}`).join(' ')),
                h('p', { class: `center ${escaped ? 'ok' : 'bad'}` },
                    escaped ? `Con ${total} escapas 🏃💨` : `Con ${total} te atrapan…`),
                h('p', { class: 'muted center' },
                    'Verifica objetos o cartas que cambien la huida y confirma el resultado real.'),
                h('div', { class: 'modal-actions' },
                    h('button', {
                        class: 'btn btn-danger',
                        onClick: () => finish(false)
                    }, 'ATRAPADO 💀'),
                    h('button', {
                        class: 'btn btn-primary',
                        onClick: () => finish(true)
                    }, 'ESCAPÉ 🏃'))
            );
        }
    };

    const close = openModal({
        title: '¡HUIDA!',
        content: body,
        actions: []
    });

    const finish = success => {
        close();
        sendEvent(actor => events.playerRoll(actor, rolled, 'RUN_AWAY', success));
        sendEvent(actor => events.combatEnd(actor, success ? OUTCOMES.ESCAPE : OUTCOMES.LOSE));
        if (!success) showBadStuff(monsters);
    };

    renderStep();
}

function openAddMonsterModal() {
    const results = h('div', { class: 'list search-results' });
    let searchTimer = null;

    const searchInput = h('input', {
        class: 'input', placeholder: 'Buscar en el catálogo…', autocomplete: 'off',
        onInput: e => {
            const query = e.target.value.trim();
            clearTimeout(searchTimer);
            searchTimer = setTimeout(async () => {
                clear(results);
                if (!query) return;
                try {
                    const response = await fetch(`/api/monsters?q=${encodeURIComponent(query)}`);
                    if (!response.ok) throw new Error('Búsqueda no disponible');
                    const rows = await response.json();
                    if (rows.length === 0) {
                        results.append(h('p', { class: 'muted' }, 'Sin resultados. Créalo abajo.'));
                        return;
                    }
                    for (const row of rows) {
                        results.append(h('button', {
                            class: 'list-row list-btn',
                            onClick: () => {
                                addMonsterToCombat({
                                    name: row.name,
                                    baseLevel: row.level,
                                    flatModifier: row.modifier || 0,
                                    treasures: row.treasures ?? 1,
                                    levels: row.levels ?? 1,
                                    isUndead: Boolean(row.isUndead),
                                    badStuff: row.badStuff || ''
                                });
                                close();
                            }
                        },
                            h('span', { class: 'monster-icon' }, row.isUndead ? '🧟' : '👹'),
                            h('div', { class: 'grow' },
                                h('strong', {}, row.name),
                                h('small', { class: 'muted' },
                                    `Nivel ${row.level} · ${row.treasures ?? 1} 💰 · +${row.levels ?? 1} nv`))));
                    }
                } catch {
                    results.append(h('p', { class: 'muted' }, 'No se pudo buscar en el catálogo.'));
                }
            }, 250);
        }
    });

    const close = openModal({
        title: 'Añadir monstruo',
        class: 'modal-tall',
        content: h('div', {},
            searchInput,
            results,
            h('details', { class: 'manual-monster' },
                h('summary', {}, 'Crear monstruo a mano'),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, 'Nombre'),
                    h('input', { class: 'input', id: 'nm-name', maxlength: '80' })),
                h('div', { class: 'field-cols' },
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, 'Nivel (1–20)'),
                        h('input', { class: 'input', id: 'nm-level', type: 'number', min: '1', max: '20', value: '1' })),
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, 'Modif. (±10)'),
                        h('input', { class: 'input', id: 'nm-mod', type: 'number', min: '-10', max: '10', value: '0' }))),
                h('div', { class: 'field-cols' },
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, 'Tesoros'),
                        h('input', { class: 'input', id: 'nm-treasures', type: 'number', min: '0', max: '10', value: '1' })),
                    h('label', { class: 'field' },
                        h('span', { class: 'field-label' }, 'Niveles que da'),
                        h('input', { class: 'input', id: 'nm-levels', type: 'number', min: '1', max: '5', value: '1' }))),
                h('label', { class: 'field field-row' },
                    h('span', { class: 'field-label' }, 'No-Muerto 🧟'),
                    h('input', { class: 'switch', id: 'nm-undead', type: 'checkbox' })),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, 'Mal Rollo'),
                    h('input', { class: 'input', id: 'nm-badstuff', maxlength: '200', placeholder: 'Qué pasa si pierdes…' })),
                h('button', {
                    class: 'btn btn-primary',
                    onClick: () => {
                        const name = document.getElementById('nm-name').value.trim();
                        if (!name) return toast('Ponle nombre al monstruo', 'error');
                        const monster = {
                            name,
                            baseLevel: Number(document.getElementById('nm-level').value) || 1,
                            flatModifier: Number(document.getElementById('nm-mod').value) || 0,
                            treasures: Number(document.getElementById('nm-treasures').value ?? 1),
                            levels: Number(document.getElementById('nm-levels').value) || 1,
                            isUndead: document.getElementById('nm-undead').checked,
                            badStuff: document.getElementById('nm-badstuff').value.trim()
                        };
                        addMonsterToCombat(monster, { contribute: true });
                        close();
                    }
                }, 'Añadir al combate'))
        )
    });
}

function addMonsterToCombat(data, { contribute = false } = {}) {
    sendEvent(actor => events.combatAddMonster(actor, monsterInstance(data)));
    // Signed-in players also grow the shared catalog, like the app. The fight
    // never waits on it: this is best-effort and quiet on failure.
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
        })).catch(() => { /* the monster is already in the fight */ });
    }
}

function openAddBonusModal() {
    const close = openModal({
        title: 'Bono temporal',
        content: h('div', {},
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, 'Nombre de la carta'),
                h('input', { class: 'input', id: 'bn-label', maxlength: '40', placeholder: 'Poción de…' })),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, 'Cantidad (± poder)'),
                h('input', { class: 'input', id: 'bn-amount', type: 'number', value: '1' })),
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, 'Se aplica a'),
                h('div', { class: 'segmented', id: 'bn-side' },
                    h('button', { class: 'segment selected', dataset: { side: 'HEROES' }, onClick: pickSide }, 'Héroes'),
                    h('button', { class: 'segment', dataset: { side: 'MONSTER' }, onClick: pickSide }, 'Monstruos')))
        ),
        actions: [
            h('button', { class: 'btn btn-ghost', onClick: () => close() }, 'Cancelar'),
            h('button', {
                class: 'btn btn-primary',
                onClick: () => {
                    const label = document.getElementById('bn-label').value.trim() || 'Bonus';
                    const amount = Number(document.getElementById('bn-amount').value) || 0;
                    const appliesTo = document.querySelector('#bn-side .segment.selected')?.dataset.side || 'HEROES';
                    sendEvent(actor => events.combatAddBonus(actor, tempBonus({ label, amount, appliesTo })));
                    close();
                }
            }, 'Añadir')
        ]
    });

    function pickSide(e) {
        document.querySelectorAll('#bn-side .segment').forEach(b => b.classList.remove('selected'));
        e.currentTarget.classList.add('selected');
    }
}

// ============== Game: results ==============

function resultsView() {
    const state = app.game;
    const winner = state.winnerId ? state.players[state.winnerId] : null;
    const standings = playerList(state).slice().sort((a, b) =>
        (b.level - a.level) || (combatPower(b) - combatPower(a)));

    return h('div', { class: 'screen results' },
        h('div', { class: 'hero' },
            h('div', { class: 'hero-die' }, '🏆'),
            h('h1', { class: 'hero-title' }, winner ? `¡${winner.name} gana!` : 'Partida terminada'),
            winner && h('div', { class: 'avatar xl' }, avatarEmoji(winner.avatarId, winner.gender))
        ),
        h('div', { class: 'card' },
            h('h3', { class: 'card-title' }, 'Clasificación final'),
            h('div', { class: 'list' }, standings.map((p, index) =>
                h('div', { class: 'list-row' },
                    h('span', { class: 'rank' }, `${index + 1}º`),
                    avatarBadge(p),
                    h('div', { class: 'grow' }, h('strong', {}, p.name)),
                    h('span', {}, `Nv ${p.level}`),
                    h('span', { class: 'muted' }, ` 💰 ${p.treasures || 0}`))))
        ),
        h('div', { class: 'sticky-actions' },
            h('button', {
                class: 'btn btn-primary btn-big',
                onClick: () => { cleanupGame(); nav('#/home'); }
            }, 'Volver al inicio'))
    );
}

// ============== Ranking / history ==============

function rankingView() {
    const container = h('div', { class: 'card' }, h('p', { class: 'muted' }, 'Cargando…'));

    (async () => {
        try {
            const token = session.authToken;
            const reply = token
                ? (await authenticatedRequest(WS_URL, token, messages.getLeaderboard())).reply
                : await oneOffRequest(WS_URL, messages.getLeaderboard());
            clear(container);
            const rows = reply.leaderboard || [];
            if (reply.me) {
                container.append(h('div', { class: 'me-rank' },
                    h('strong', {}, reply.me.rank ? `Tu posición: ${reply.me.rank}º` : 'Fuera del top'),
                    h('span', { class: 'muted' },
                        ` · ${reply.me.wins} victorias de ${reply.me.gamesPlayed} (${winRate(reply.me)}%)`)));
            }
            if (rows.length === 0) {
                container.append(h('p', { class: 'muted' }, 'Aún no hay partidas registradas.'));
                return;
            }
            container.append(h('div', { class: 'list' }, rows.map((entry, index) =>
                h('div', { class: 'list-row' },
                    h('span', { class: 'rank' }, `${index + 1}º`),
                    h('span', { class: 'avatar' }, avatarEmoji(entry.avatarId, entry.gender)),
                    h('div', { class: 'grow' }, h('strong', {}, entry.username)),
                    h('span', {}, `🏆 ${entry.wins}`),
                    h('span', { class: 'muted' }, ` ${winRate(entry)}%`)))));
        } catch (err) {
            clear(container);
            container.append(h('p', { class: 'muted' }, errorText(err)));
        }
    })();

    return h('div', { class: 'screen' },
        header('Ranking', { back: () => nav('#/home') }),
        container
    );
}

function winRate(entry) {
    return entry.gamesPlayed > 0 ? Math.floor((entry.wins * 100) / entry.gamesPlayed) : 0;
}

function historyView() {
    if (!session.authToken) {
        nav('#/auth');
        return h('div', {});
    }
    const container = h('div', { class: 'card' }, h('p', { class: 'muted' }, 'Cargando…'));

    (async () => {
        try {
            const { reply } = await authenticatedRequest(
                WS_URL, session.authToken, messages.getHistory(session.profile.id));
            clear(container);
            const stats = reply.stats || { wins: 0, gamesPlayed: 0 };
            container.append(h('div', { class: 'me-rank' },
                h('strong', {}, `${stats.wins} victorias`),
                h('span', { class: 'muted' }, ` de ${stats.gamesPlayed} partidas (${winRate(stats)}%)`)));
            const games = reply.games || [];
            if (games.length === 0) {
                container.append(h('p', { class: 'muted' }, 'Todavía no has jugado ninguna partida.'));
                return;
            }
            container.append(h('div', { class: 'list' }, games.map(game =>
                h('div', { class: 'list-row' },
                    h('span', {}, game.didIWin ? '🏆' : '⚔️'),
                    h('div', { class: 'grow' },
                        h('strong', {}, game.didIWin ? 'Victoria' : (game.winnerName ? `Ganó ${game.winnerName}` : 'Sin ganador')),
                        h('small', { class: 'muted' },
                            `${new Date(game.endedAt).toLocaleDateString('es')} · ${game.playerCount} jugadores`))))));
        } catch (err) {
            clear(container);
            container.append(h('p', { class: 'muted' }, errorText(err)));
        }
    })();

    return h('div', { class: 'screen' },
        header('Mi historial', { back: () => nav('#/home') }),
        container
    );
}

// ============== Root render ==============

function gameView() {
    const state = app.game;
    if (!state) {
        // Landed on #/game without a live session (reload): offer to resume.
        const seat = session.seat;
        return h('div', { class: 'screen' },
            header('Partida', { back: () => nav('#/home') }),
            h('div', { class: 'card center-card' },
                seat
                    ? [
                        h('p', {}, `Tenías una partida en curso (${seat.joinCode}).`),
                        h('button', {
                            class: 'btn btn-primary btn-big',
                            disabled: app.busy,
                            onClick: () => resumeSeat()
                        }, app.busy ? 'Conectando…' : 'Reanudar partida')
                    ]
                    : h('p', { class: 'muted' }, 'No estás en ninguna partida.'))
        );
    }
    if (state.phase === PHASES.FINISHED) return resultsView();
    if (state.phase === PHASES.LOBBY) return lobbyView();
    if (state.combat && !app.viewBoardDuringCombat) return combatView();
    return boardView();
}

let lastScreenKey = null;

function render() {
    const root = document.getElementById('app');
    if (!root) return;
    const { name, arg } = route();

    let view;
    switch (name) {
        case 'home': view = homeView(); break;
        case 'auth': view = authView(); break;
        case 'create': view = createView(); break;
        case 'join': view = joinView(arg); break;
        case 'game': view = gameView(); break;
        case 'ranking': view = rankingView(); break;
        case 'history': view = historyView(); break;
        default: view = homeView();
    }

    // The entry animation plays only when the screen actually changes. Every
    // server broadcast re-renders, and replaying the fade then made the whole
    // page flicker on each event.
    const screenKey = [
        name,
        app.game ? app.game.phase : '-',
        app.game?.combat && !app.viewBoardDuringCombat ? 'combat' : '-'
    ].join('|');
    if (screenKey !== lastScreenKey) {
        view.classList.add('screen-enter');
        lastScreenKey = screenKey;
    }

    const scrollY = window.scrollY;
    clear(root);
    const banner = connectionBanner();
    if (banner) root.append(banner);
    root.append(view);
    if (!view.classList.contains('screen-enter')) {
        window.scrollTo(0, scrollY);
    }
}

// The turn countdown re-paints without a full re-render.
setInterval(() => {
    const el = document.getElementById('turn-countdown');
    if (el) el.textContent = turnCountdownText();
}, 500);

// iOS Safari suspends the page when the phone locks or the tab goes to the
// background; coming back must retry the socket immediately, not in 30s.
document.addEventListener('visibilitychange', () => {
    if (!document.hidden) app.socket?.nudge();
});
window.addEventListener('pageshow', () => app.socket?.nudge());
window.addEventListener('online', () => app.socket?.nudge());

window.addEventListener('hashchange', () => {
    closeAllModals();
    app.winnerPromptOpen = false;
    render();
});

render();
