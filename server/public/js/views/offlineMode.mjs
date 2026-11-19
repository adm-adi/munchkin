/**
 * Offline Pass & Play mode (#/offline) for Munchkin Mesa Tracker Web PWA.
 * Runs 100% locally in browser with zero server/network using pure engine.mjs + combat.mjs,
 * persisted in localStorage with a 5-step Undo stack.
 * Strict CSP compliant: zero inline style attributes.
 */

import {
    CLASSES, BASE_CLASSES, RACES, BASE_RACES, BONUS_TARGETS, OUTCOMES, PHASES,
    uuid, events, monsterInstance, tempBonus
} from '../protocol.mjs';
import { applyEvent, playerList, combatPower } from '../engine.mjs';
import { calculateResult } from '../combat.mjs';
import { t, avatarEmoji, classLabel, raceLabel } from '../i18n.mjs';
import { h, fmtSigned, toast, confirmDialog, openModal } from '../ui.mjs';
import { combatPresetsBar, helperTreasureDealBar, activeCursesSection } from './combatHelpers.mjs';

const STORAGE_KEY = 'munchkin.offlineGame.v1';
const MAX_UNDO = 5;

const offlineState = {
    game: null,
    activePlayerId: null,
    undoStack: [],
    setupPlayers: [
        { name: 'Jugador 1', avatarId: 0, gender: 'M' },
        { name: 'Jugador 2', avatarId: 1, gender: 'F' }
    ],
    newPlayerName: '',
    epicMode: false,
    expansionsEnabled: false
};

function saveLocal() {
    try {
        if (!offlineState.game) {
            localStorage.removeItem(STORAGE_KEY);
        } else {
            localStorage.setItem(STORAGE_KEY, JSON.stringify({
                game: offlineState.game,
                activePlayerId: offlineState.activePlayerId
            }));
        }
    } catch {
        // Storage full or restricted
    }
}

export function loadOfflineGame() {
    if (offlineState.game) return offlineState.game;
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        if (parsed && parsed.game && parsed.game.players) {
            offlineState.game = parsed.game;
            offlineState.activePlayerId = parsed.activePlayerId || parsed.game.turnPlayerId || Object.keys(parsed.game.players)[0];
        }
    } catch {
        offlineState.game = null;
    }
    return offlineState.game;
}

function dispatchOffline(event, onRender) {
    if (!offlineState.game) return;
    offlineState.undoStack.push(JSON.parse(JSON.stringify(offlineState.game)));
    if (offlineState.undoStack.length > MAX_UNDO) offlineState.undoStack.shift();
    offlineState.game = applyEvent(offlineState.game, event);
    offlineState.game.canUndo = offlineState.undoStack.length > 0;
    if (event.type === 'END_TURN' && offlineState.game.turnPlayerId) {
        offlineState.activePlayerId = offlineState.game.turnPlayerId;
    }
    saveLocal();
    onRender();
}

function undoOffline(onRender) {
    const prev = offlineState.undoStack.pop();
    if (!prev) return;
    offlineState.game = prev;
    offlineState.game.canUndo = offlineState.undoStack.length > 0;
    saveLocal();
    onRender();
}

function startLocalGame(onRender) {
    const valid = offlineState.setupPlayers
        .map(p => ({ ...p, name: p.name.trim().slice(0, 20) }))
        .filter(p => p.name.length > 0);
    if (valid.length < 2) {
        toast(t('lobby.needTwoPlayers'), 'err');
        return;
    }
    const players = {};
    const order = [];
    valid.slice(0, 6).forEach((p, idx) => {
        const pid = `local-${idx + 1}`;
        order.push(pid);
        players[pid] = {
            playerId: pid,
            name: p.name,
            avatarId: p.avatarId ?? idx,
            gender: p.gender || 'M',
            level: 1,
            gearBonus: 0,
            tempCombatBonus: 0,
            treasures: 0,
            characterClass: 'NONE',
            characterRace: 'HUMAN',
            secondaryClass: 'NONE',
            secondaryRace: 'HUMAN',
            hasHalfBreed: false,
            hasSuperMunchkin: false,
            activeCurses: [],
            isConnected: true,
            lastRoll: null,
            reachedMaxLevelViaCombat: false
        };
    });

    offlineState.undoStack = [];
    offlineState.activePlayerId = order[0];
    offlineState.game = {
        gameId: `offline-${uuid().slice(0, 8)}`,
        joinCode: 'LOCAL',
        epoch: 0,
        seq: 1,
        hostId: order[0],
        players,
        combat: null,
        phase: PHASES.IN_GAME,
        winnerId: null,
        turnPlayerId: order[0],
        turnEndsAt: null,
        canUndo: false,
        playerOrder: order,
        createdAt: Date.now(),
        settings: {
            minLevel: 1,
            maxLevel: offlineState.epicMode ? 20 : 10,
            tiesGoToMonsters: true,
            levelTenOnlyCombat: true,
            allowLevelTenOverride: false,
            expansionsEnabled: offlineState.expansionsEnabled,
            turnTimerSeconds: 0
        }
    };
    saveLocal();
    onRender();
}

function openOfflineAddMonsterModal(actorId, onRender) {
    let name = '';
    let level = '4';
    let treasures = '1';
    let levels = '1';
    let isUndead = false;
    let badStuff = '';

    const modal = openModal({
        title: t('monsterModal.title'),
        dismissible: true,
        body: [
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('monsterModal.name')),
                h('input', { class: 'input', maxlength: '80', oninput: e => { name = e.target.value; } })
            ),
            h('div', { class: 'grid-2' },
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('monsterModal.levelRange')),
                    h('input', { class: 'input', type: 'number', min: '1', max: '20', value: level, oninput: e => { level = e.target.value; } })
                ),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('monsterModal.treasures')),
                    h('input', { class: 'input', type: 'number', min: '0', max: '10', value: treasures, oninput: e => { treasures = e.target.value; } })
                )
            ),
            h('div', { class: 'grid-2' },
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('monsterModal.levelsReward')),
                    h('input', { class: 'input', type: 'number', min: '1', max: '5', value: levels, oninput: e => { levels = e.target.value; } })
                ),
                h('label', { class: 'checkbox-row' },
                    h('input', { type: 'checkbox', onchange: e => { isUndead = e.target.checked; } }),
                    h('span', {}, `🧟 ${t('monsterModal.undead')}`)
                )
            ),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('monsterModal.badStuff')),
                h('input', { class: 'input', maxlength: '200', oninput: e => { badStuff = e.target.value; } })
            ),
            h('div', { class: 'modal-actions' },
                h('button', { class: 'btn btn-ghost', onclick: () => modal.close() }, t('common.cancel')),
                h('button', {
                    class: 'btn btn-primary',
                    onclick: () => {
                        const cleanName = name.trim();
                        if (!cleanName) return;
                        dispatchOffline(events.combatAddMonster(actorId, monsterInstance({
                            name: cleanName,
                            baseLevel: Math.max(1, Math.min(20, Number(level) || 1)),
                            treasures: Math.max(0, Math.min(10, Number(treasures) || 1)),
                            levels: Math.max(1, Math.min(5, Number(levels) || 1)),
                            isUndead,
                            badStuff: badStuff.trim()
                        })), onRender);
                        modal.close();
                    }
                }, t('common.add'))
            )
        ]
    });
}

function openOfflineHeroModal(playerId, onRender) {
    const buildNodes = () => {
        const g = offlineState.game;
        const p = g?.players?.[playerId];
        if (!p) return [];
        const expansions = Boolean(g.settings?.expansionsEnabled);
        const availClasses = expansions ? CLASSES : BASE_CLASSES;
        const availRaces = expansions ? RACES : BASE_RACES;
        const applyAndRefresh = (ev) => {
            dispatchOffline(ev, onRender);
            modal.setBody(buildNodes());
        };
        return [
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('hero.class')),
                h('div', { class: 'chip-grid' }, availClasses.map(c => h('button', {
                    class: 'chip',
                    'aria-pressed': p.characterClass === c ? 'true' : 'false',
                    onclick: () => applyAndRefresh(events.setClass(playerId, c, false))
                }, classLabel(c))))
            ),
            h('div', { class: 'field' },
                h('span', { class: 'field-label' }, t('hero.race')),
                h('div', { class: 'chip-grid' }, availRaces.map(r => h('button', {
                    class: 'chip',
                    'aria-pressed': p.characterRace === r ? 'true' : 'false',
                    onclick: () => applyAndRefresh(events.setRace(playerId, r, false))
                }, raceLabel(r))))
            ),
            h('hr', { class: 'divider' }),
            activeCursesSection({
                player: p,
                editable: true,
                onAddCurse: (curse) => applyAndRefresh(events.addCurse(playerId, curse)),
                onRemoveCurse: (cid) => applyAndRefresh(events.removeCurse(playerId, cid))
            })
        ];
    };

    const p = offlineState.game?.players?.[playerId];
    if (!p) return;
    const modal = openModal({
        title: p.name,
        dismissible: true,
        body: buildNodes()
    });
}

export function renderOfflineView({ onBack, onRender }) {
    loadOfflineGame();
    const state = offlineState.game;

    if (!state) {
        return h('section', { class: 'screen', dataset: { key: 'view-offline-setup' } },
            h('div', { class: 'page-head' },
                h('button', { class: 'btn btn-ghost', onclick: onBack }, `← ${t('common.back')}`),
                h('h1', { class: 'page-title' }, `📱 ${t('offline.title')}`)
            ),
            h('div', { class: 'card stack' },
                h('div', { class: 'list-divide' }, offlineState.setupPlayers.map((p, idx) =>
                    h('div', { class: 'flat-row', dataset: { key: `off-setup-${idx}` } },
                        h('input', {
                            class: 'input grow',
                            maxlength: '20',
                            value: p.name,
                            placeholder: t('offline.playerName'),
                            oninput: (e) => { offlineState.setupPlayers[idx].name = e.target.value; }
                        }),
                        offlineState.setupPlayers.length > 2 && h('button', {
                            class: 'icon-btn icon-btn-danger',
                            type: 'button',
                            onclick: () => {
                                offlineState.setupPlayers.splice(idx, 1);
                                onRender();
                            }
                        }, '✕')
                    )
                )),
                offlineState.setupPlayers.length < 6 && h('button', {
                    class: 'btn btn-ghost btn-block',
                    type: 'button',
                    onclick: () => {
                        const n = offlineState.setupPlayers.length + 1;
                        offlineState.setupPlayers.push({ name: `Jugador ${n}`, avatarId: (n - 1) % 8, gender: 'M' });
                        onRender();
                    }
                }, `+ ${t('offline.addPlayer')}`),
                h('label', { class: 'checkbox-row' },
                    h('input', {
                        type: 'checkbox',
                        checked: offlineState.epicMode,
                        onchange: (e) => { offlineState.epicMode = e.target.checked; }
                    }),
                    h('span', {}, t('create.epicMode'))
                ),
                h('label', { class: 'checkbox-row' },
                    h('input', {
                        type: 'checkbox',
                        checked: offlineState.expansionsEnabled,
                        onchange: (e) => { offlineState.expansionsEnabled = e.target.checked; }
                    }),
                    h('span', {}, t('create.expansions'))
                ),
                h('button', {
                    class: 'btn btn-primary btn-block',
                    type: 'button',
                    onclick: () => startLocalGame(onRender)
                }, t('offline.start'))
            )
        );
    }

    const list = playerList(state);
    const activeId = state.players[offlineState.activePlayerId] ? offlineState.activePlayerId : list[0]?.playerId;
    const activePlayer = state.players[activeId];
    const combat = state.combat;

    return h('section', { class: 'screen', dataset: { key: 'view-offline-game' } },
        h('div', { class: 'page-head' },
            h('button', { class: 'btn btn-ghost', onclick: onBack }, `← ${t('common.back')}`),
            h('div', { class: 'row-wrap' },
                h('h1', { class: 'page-title' }, t('offline.title')),
                h('span', { class: 'badge badge-gold' }, t('offline.badge'))
            ),
            h('div', { class: 'row-wrap' },
                offlineState.undoStack.length > 0 && h('button', {
                    class: 'btn btn-ghost',
                    onclick: () => undoOffline(onRender)
                }, `↩️ ${t('action.undo')}`),
                h('button', {
                    class: 'btn btn-outline-danger',
                    onclick: async () => {
                        const ok = await confirmDialog({
                            title: t('offline.reset'),
                            message: t('offline.reset'),
                            confirmText: t('common.confirm'),
                            danger: true
                        });
                        if (ok) {
                            offlineState.game = null;
                            offlineState.undoStack = [];
                            saveLocal();
                            onRender();
                        }
                    }
                }, t('offline.reset'))
            )
        ),

        // Quick Pass & Play seat switcher
        h('div', { class: 'card stack-sm' },
            h('span', { class: 'field-label' }, t('offline.activeSeat', { name: activePlayer?.name || '' })),
            h('div', { class: 'chip-grid' }, list.map(p =>
                h('button', {
                    class: 'chip',
                    'aria-pressed': p.playerId === activeId ? 'true' : 'false',
                    onclick: () => {
                        offlineState.activePlayerId = p.playerId;
                        saveLocal();
                        onRender();
                    }
                }, `${avatarEmoji(p.avatarId, p.gender)} ${p.name} (Nv ${p.level})`)
            ))
        ),

        combat
            ? (() => {
                const calc = calculateResult(combat, state);
                const monsters = combat.monsters || [];
                const isWin = calc.outcome === OUTCOMES.WIN && monsters.length > 0;
                const helperP = combat.helperPlayerId ? state.players[combat.helperPlayerId] : null;
                const others = list.filter(p => p.playerId !== combat.mainPlayerId);
                return h('div', { class: 'stack' },
                    h('div', { class: 'versus-strip' },
                        h('div', { class: 'versus-side' },
                            h('span', { class: 'section-heading' }, t('combat.heroes')),
                            h('span', { class: 'versus-score is-heroes tabular' }, String(calc.heroesPower))
                        ),
                        h('div', { class: 'versus-vs' }, 'VS'),
                        h('div', { class: 'versus-side' },
                            h('span', { class: 'section-heading' }, t('combat.monsters')),
                            h('span', { class: 'versus-score is-monsters tabular' }, String(calc.monstersPower))
                        )
                    ),
                    h('div', { class: `combat-outcome-pill ${isWin ? 'is-win' : 'is-lose'}` },
                        monsters.length === 0 ? t('combat.needMonsters')
                            : isWin ? t('combat.winning') : t('combat.losingGap', { gap: calc.marginToWin })
                    ),
                    h('div', { class: 'card stack-sm' },
                        h('div', { class: 'section-bar' },
                            h('h2', { class: 'section-heading' }, t('combat.monsters')),
                            h('button', {
                                class: 'btn btn-primary',
                                onclick: () => openOfflineAddMonsterModal(activeId, onRender)
                            }, t('combat.addMonsterBtn'))
                        ),
                        monsters.length === 0
                            ? h('p', { class: 'muted-text' }, t('combat.noMonstersHint'))
                            : h('div', { class: 'list-divide' }, monsters.map(m =>
                                h('div', { class: 'flat-row' },
                                    h('span', { class: 'grow' }, `${m.name} (Nv ${m.baseLevel}${m.flatModifier ? ` ${fmtSigned(m.flatModifier)}` : ''})`),
                                    h('button', {
                                        class: 'icon-btn icon-btn-danger',
                                        onclick: () => dispatchOffline(events.combatRemoveMonster(activeId, m.id), onRender)
                                    }, '✕')
                                )
                            ))
                    ),
                    h('div', { class: 'card stack-sm' },
                        h('h2', { class: 'section-heading' }, t('combat.helper')),
                        h('div', { class: 'chip-grid' },
                            h('button', {
                                class: 'chip',
                                'aria-pressed': !combat.helperPlayerId ? 'true' : 'false',
                                onclick: () => dispatchOffline(events.combatRemoveHelper(activeId), onRender)
                            }, t('combat.noHelper')),
                            others.map(p => h('button', {
                                class: 'chip',
                                'aria-pressed': combat.helperPlayerId === p.playerId ? 'true' : 'false',
                                onclick: () => dispatchOffline(events.combatAddHelper(activeId, p.playerId), onRender)
                            }, `${p.name} (${combatPower(p)})`))
                        ),
                        helperP && helperTreasureDealBar({
                            currentShare: combat.helperTreasureShare,
                            totalTreasures: monsters.reduce((s, m) => s + (m.treasures ?? 1), 0),
                            canEdit: true,
                            onChange: (share) => dispatchOffline(events.combatSetHelperTreasures(activeId, share), onRender)
                        })
                    ),
                    h('div', { class: 'card' },
                        combatPresetsBar({
                            onAddPreset: (side, label, amount) => dispatchOffline(
                                events.combatAddBonus(activeId, tempBonus({ label, amount, appliesTo: side })),
                                onRender
                            )
                        })
                    ),
                    h('div', { class: 'action-grid' },
                        h('button', {
                            class: 'btn btn-ghost btn-block',
                            onclick: () => dispatchOffline(events.combatEnd(activeId, OUTCOMES.ESCAPE, { cancelled: true }), onRender)
                        }, t('combat.cancel')),
                        h('button', {
                            class: `btn ${isWin ? 'btn-primary' : 'btn-outline-danger'} btn-block`,
                            disabled: monsters.length === 0,
                            onclick: () => dispatchOffline(events.combatEnd(activeId, calc.outcome, {
                                levelsGained: calc.totalLevels,
                                treasuresGained: calc.totalTreasures,
                                helperLevelsGained: calc.helperLevelsGained
                            }), onRender)
                        }, isWin ? t('combatEnd.winYes') : t('combatEnd.loseYes'))
                    )
                );
            })()
            : h('div', { class: 'stack' },
                activePlayer && h('article', { class: 'hero-card is-turn is-me' },
                    h('div', { class: 'hero-head' },
                        h('div', { class: 'hero-identity' },
                            h('span', { class: 'avatar-badge' }, avatarEmoji(activePlayer.avatarId, activePlayer.gender)),
                            h('div', { class: 'stack-sm' },
                                h('h2', { class: 'hero-name' }, activePlayer.name),
                                h('span', { class: 'hero-meta' }, `${raceLabel(activePlayer.characterRace)} · ${classLabel(activePlayer.characterClass)}`)
                            )
                        ),
                        h('div', { class: 'hero-power-box' },
                            h('div', { class: 'hero-power tabular' }, String(combatPower(activePlayer))),
                            h('div', { class: 'hero-power-label' }, t('board.power'))
                        )
                    ),
                    h('div', { class: 'stat-controls' },
                        h('div', { class: 'stepper' },
                            h('span', { class: 'stepper-label' }, t('board.level')),
                            h('div', { class: 'stepper-row' },
                                h('button', { class: 'icon-btn', onclick: () => dispatchOffline(events.decLevel(activeId), onRender) }, '−'),
                                h('strong', { class: 'stepper-val tabular' }, String(activePlayer.level)),
                                h('button', { class: 'icon-btn', onclick: () => dispatchOffline(events.incLevel(activeId), onRender) }, '+')
                            )
                        ),
                        h('div', { class: 'stepper' },
                            h('span', { class: 'stepper-label' }, t('board.gear')),
                            h('div', { class: 'stepper-row' },
                                h('button', { class: 'icon-btn', onclick: () => dispatchOffline(events.decGear(activeId), onRender) }, '−'),
                                h('strong', { class: 'stepper-val tabular' }, String(activePlayer.gearBonus || 0)),
                                h('button', { class: 'icon-btn', onclick: () => dispatchOffline(events.incGear(activeId), onRender) }, '+')
                            )
                        )
                    ),
                    h('div', { class: 'row-wrap' },
                        h('button', {
                            class: 'btn btn-ghost',
                            onclick: () => openOfflineHeroModal(activeId, onRender)
                        }, t('board.editHero')),
                        h('button', {
                            class: 'btn btn-outline-danger',
                            onclick: () => dispatchOffline(events.playerDeath(activeId), onRender)
                        }, `💀 ${t('action.deathLoot')}`)
                    )
                ),
                h('div', { class: 'action-grid' },
                    h('button', {
                        class: 'btn btn-secondary btn-block',
                        onclick: () => dispatchOffline(events.combatStart(activeId, activeId), onRender)
                    }, `⚔️ ${t('board.startCombat')}`),
                    h('button', {
                        class: 'btn btn-primary btn-block',
                        onclick: () => dispatchOffline(events.endTurn(activeId), onRender)
                    }, `⏭️ ${t('board.endTurn')}`)
                )
            )
    );
}
