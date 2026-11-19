/**
 * Client-side reducer for authoritative server state.
 *
 * Port of core/GameEngine.kt: folds server-validated event broadcasts into
 * the local snapshot rendered by the UI without mutating prevState.
 */

const VALID_CLASSES = new Set(['NONE', 'WARRIOR', 'WIZARD', 'THIEF', 'CLERIC']);
const VALID_RACES = new Set(['HUMAN', 'ELF', 'DWARF', 'HALFLING']);
const VALID_GENDERS = new Set(['M', 'F', 'NA']);

const GEAR_MIN = -999;
const GEAR_MAX = 999;
const TEMP_COMBAT_BONUS_MIN = -100;
const TEMP_COMBAT_BONUS_MAX = 100;
const STEP_MAX = 100;
const MAX_AVATAR_ID = 100;
const MAX_NAME_LENGTH = 20;
const MODIFIER_LIMIT = 9999;
const RUN_AWAY_THRESHOLD = 5;

function clampInt(value, min, max, fallback = 0) {
    if (typeof value !== 'number' && typeof value !== 'string') return fallback;
    if (typeof value === 'string' && value.trim() === '') return fallback;
    const n = Math.round(Number(value));
    if (!Number.isFinite(n)) return fallback;
    return Math.max(min, Math.min(max, n));
}

function stepAmount(value) {
    const n = Math.round(Number(value));
    if (!Number.isFinite(n) || n <= 0) return 1;
    return Math.min(STEP_MAX, n);
}

// ============== Derived helpers (Models.kt) ==============

/** PlayerState.activeClasses — every class whose abilities currently apply. */
export function activeClasses(player) {
    const list = [];
    if (player.characterClass && player.characterClass !== 'NONE') list.push(player.characterClass);
    if (
        player.hasSuperMunchkin &&
        player.secondaryClass &&
        player.secondaryClass !== 'NONE' &&
        player.secondaryClass !== player.characterClass
    ) {
        list.push(player.secondaryClass);
    }
    return list;
}

/** PlayerState.activeRaces — HUMAN is the absence of a race card. */
export function activeRaces(player) {
    const list = [];
    if (player.characterRace && player.characterRace !== 'HUMAN') list.push(player.characterRace);
    if (
        player.hasHalfBreed &&
        player.secondaryRace &&
        player.secondaryRace !== 'HUMAN' &&
        player.secondaryRace !== player.characterRace
    ) {
        list.push(player.secondaryRace);
    }
    return list;
}

export function hasClass(player, target) {
    return activeClasses(player).includes(target);
}

export function hasRace(player, target) {
    return activeRaces(player).includes(target);
}

/** PlayerState.combatPower = level + gear + temp bonus (clamped to server bounds). */
export function combatPower(player) {
    const level = player.level || 1;
    const gear = clampInt(player.gearBonus ?? 0, GEAR_MIN, GEAR_MAX, 0);
    const temp = clampInt(player.tempCombatBonus ?? 0, TEMP_COMBAT_BONUS_MIN, TEMP_COMBAT_BONUS_MAX, 0);
    return level + gear + temp;
}

/** GameState.playerList — explicit seat order first, stragglers appended. */
export function playerList(state) {
    if (!state) return [];
    const order = Array.isArray(state.playerOrder) ? state.playerOrder : [];
    if (order.length > 0) {
        const ordered = order.map(id => state.players[id]).filter(Boolean);
        const rest = Object.values(state.players).filter(p => !order.includes(p.playerId));
        return ordered.concat(rest);
    }
    return Object.values(state.players).sort((a, b) => a.playerId.localeCompare(b.playerId));
}

export function allPlayersRolled(state) {
    const players = Object.values(state.players);
    return players.length > 0 && players.every(p => p.lastRoll !== null && p.lastRoll !== undefined);
}

/** Player ids tied for the highest roll (they must re-roll). */
export function tiedPlayerIds(state) {
    if (!allPlayersRolled(state)) return [];
    const players = Object.values(state.players);
    const maxRoll = Math.max(...players.map(p => p.lastRoll || 0));
    const tied = players.filter(p => p.lastRoll === maxRoll);
    return tied.length > 1 ? tied.map(p => p.playerId) : [];
}

/** GameState.canStart — 2+ players, everyone rolled, no tie for highest. */
export function canStart(state) {
    return Object.keys(state.players).length >= 2 &&
        allPlayersRolled(state) &&
        tiedPlayerIds(state).length === 0;
}

/** GameSettings.requiresCombatToWin. */
export function requiresCombatToWin(settings) {
    return Boolean(settings?.levelTenOnlyCombat) && !settings?.allowLevelTenOverride;
}

/**
 * PlayerState.canBeConfirmedWinner — at max level, and (when the room enforces
 * it) that level was reached by killing a monster. `null` means an older server
 * that does not report the flag, which must not lock the game unwinnable.
 */
export function canBeConfirmedWinner(player, settings) {
    const maxLevel = settings?.maxLevel ?? 10;
    if ((player.level || 1) < maxLevel) return false;
    if (!requiresCombatToWin(settings)) return true;
    return player.reachedMaxLevelViaCombat !== false;
}

// ============== Reducer (GameEngine.kt) ==============

function orderedIds(state) {
    const players = state.players || {};
    if (Array.isArray(state.playerOrder) && state.playerOrder.length > 0) {
        const known = state.playerOrder.filter(id => Boolean(players[id]));
        const extra = Object.keys(players).filter(id => !known.includes(id)).sort();
        return known.concat(extra);
    }
    return Object.keys(players).sort();
}

function cloneCombat(combat) {
    if (!combat) return null;
    return {
        ...combat,
        monsters: Array.isArray(combat.monsters) ? [...combat.monsters] : [],
        tempBonuses: Array.isArray(combat.tempBonuses) ? [...combat.tempBonuses] : []
    };
}

/**
 * Applies one server-broadcast event, returning a new state without mutating prevState.
 */
export function applyEvent(prevState, event) {
    if (!prevState || !event) return prevState;

    const state = {
        ...prevState,
        seq: (prevState.seq || 0) + 1
    };

    const minLevel = state.settings?.minLevel ?? 1;
    const maxLevel = state.settings?.maxLevel ?? 10;

    const updatePlayer = (playerId, fn) => {
        const existing = state.players?.[playerId];
        if (!existing) return;
        if (state.players === prevState.players) {
            state.players = { ...prevState.players };
        }
        const copy = { ...existing };
        fn(copy);
        state.players[playerId] = copy;
    };

    switch (event.type) {
        case 'PLAYER_ROLL': {
            const actor = state.players?.[event.actorId];
            if (!actor) break;

            const rollResult = clampInt(event.result, 1, 6, 1);
            state.players = { ...prevState.players };

            if (state.phase === 'LOBBY') {
                // Evaluate ties against the pre-roll state before writing this roll.
                const allPlayers = Object.values(state.players);
                const allRolled = allPlayers.length > 1 &&
                    allPlayers.every(p => p.lastRoll !== null && p.lastRoll !== undefined);
                if (allRolled) {
                    const maxRoll = Math.max(...allPlayers.map(p => p.lastRoll || 0));
                    const tiedAtMax = allPlayers.filter(p => p.lastRoll === maxRoll);
                    if (tiedAtMax.length > 1 && actor.lastRoll === maxRoll) {
                        for (const [pid, p] of Object.entries(state.players)) {
                            if (pid === event.actorId) continue;
                            state.players[pid] = {
                                ...p,
                                lastRoll: p.lastRoll === maxRoll ? null : 0
                            };
                        }
                    }
                }
            }

            const updatedActor = { ...state.players[event.actorId], lastRoll: rollResult };
            state.players[event.actorId] = updatedActor;

            const inCombatRoll = state.combat &&
                (event.purpose === 'COMBAT' || event.purpose === 'RUN_AWAY') &&
                (
                    event.actorId === state.combat.mainPlayerId ||
                    event.actorId === state.combat.helperPlayerId ||
                    event.actorId === state.hostId
                );
            if (inCombatRoll) {
                const runner = (
                    event.purpose === 'RUN_AWAY' &&
                    event.actorId !== state.combat.mainPlayerId &&
                    event.actorId !== state.combat.helperPlayerId &&
                    event.actorId === state.hostId
                )
                    ? (state.players[state.combat.mainPlayerId] || updatedActor)
                    : updatedActor;
                const runBonus = (hasRace(runner, 'ELF') ? 1 : 0) +
                    (hasRace(runner, 'HALFLING') ? -1 : 0);
                const modifier = event.purpose === 'RUN_AWAY'
                    ? clampInt(event.modifier ?? 0, -10, 10, 0)
                    : 0;
                const success = event.purpose === 'RUN_AWAY'
                    ? (rollResult + runBonus + modifier) >= RUN_AWAY_THRESHOLD
                    : event.success === true;
                state.combat = cloneCombat(state.combat);
                state.combat.lastDiceRoll = {
                    playerId: event.actorId,
                    playerName: runner.name || updatedActor.name || 'Unknown',
                    result: rollResult,
                    modifier,
                    success,
                    timestamp: event.timestamp || Date.now(),
                    purpose: event.purpose
                };
            }
            break;
        }
        case 'GAME_START': {
            const order = orderedIds(state);
            let startingPlayerId = null;
            let highestRoll = 0;
            for (const pid of order) {
                const p = state.players[pid];
                if (p && (p.lastRoll || 0) > highestRoll) {
                    highestRoll = p.lastRoll;
                    startingPlayerId = pid;
                }
            }
            state.phase = 'IN_GAME';
            state.turnPlayerId = startingPlayerId || order[0] || state.hostId || null;
            break;
        }
        case 'SWAP_PLAYERS': {
            const p1 = event.targetPlayerId;
            const p2 = event.otherPlayerId;
            if (!p1 || !p2) break;
            const order = [...orderedIds(state)];
            const i1 = order.indexOf(p1);
            const i2 = order.indexOf(p2);
            if (i1 !== -1 && i2 !== -1) {
                [order[i1], order[i2]] = [order[i2], order[i1]];
                state.playerOrder = order;
            }
            break;
        }
        case 'END_TURN': {
            const current = state.turnPlayerId;
            if (!current) break;
            const order = orderedIds(state).filter(id => Boolean(state.players?.[id]));
            if (order.length === 0) break;
            const idx = order.indexOf(current);
            const startIdx = idx === -1 ? 0 : idx;
            let next = current;
            for (let i = 1; i <= order.length; i++) {
                const candidate = state.players[order[(startIdx + i) % order.length]];
                if (candidate && candidate.isConnected === true) {
                    next = candidate.playerId;
                    break;
                }
            }
            state.turnPlayerId = next;
            state.combat = null;
            break;
        }
        case 'GAME_END':
            state.phase = 'FINISHED';
            state.winnerId = event.winnerId ?? null;
            state.combat = null;
            break;

        case 'SET_NAME': {
            const trimmed = typeof event.name === 'string'
                ? event.name.trim().slice(0, MAX_NAME_LENGTH)
                : '';
            if (trimmed) {
                updatePlayer(event.targetPlayerId, p => { p.name = trimmed; });
            }
            break;
        }
        case 'SET_AVATAR':
            updatePlayer(event.targetPlayerId, p => {
                p.avatarId = clampInt(event.avatarId, 0, MAX_AVATAR_ID, p.avatarId ?? 0);
            });
            break;
        case 'SET_GENDER':
            if (VALID_GENDERS.has(event.gender)) {
                updatePlayer(event.targetPlayerId, p => { p.gender = event.gender; });
            }
            break;
        case 'INC_LEVEL':
            updatePlayer(event.targetPlayerId, p => {
                const manualCap = requiresCombatToWin(state.settings)
                    ? Math.max(minLevel, maxLevel - 1)
                    : maxLevel;
                const current = clampInt(p.level ?? 1, minLevel, maxLevel, minLevel);
                if (current < manualCap) {
                    p.level = Math.min(manualCap, current + stepAmount(event.amount));
                } else {
                    p.level = current;
                }
                if (p.level < maxLevel) p.reachedMaxLevelViaCombat = false;
            });
            break;
        case 'DEC_LEVEL':
            updatePlayer(event.targetPlayerId, p => {
                const current = clampInt(p.level ?? 1, minLevel, maxLevel, minLevel);
                p.level = clampInt(current - stepAmount(event.amount), minLevel, maxLevel, minLevel);
                if (p.level < maxLevel) p.reachedMaxLevelViaCombat = false;
            });
            break;
        case 'SET_LEVEL':
            updatePlayer(event.targetPlayerId, p => {
                const manualCap = requiresCombatToWin(state.settings)
                    ? Math.max(minLevel, maxLevel - 1)
                    : maxLevel;
                const prev = clampInt(p.level ?? 1, minLevel, maxLevel, minLevel);
                p.level = clampInt(event.level, minLevel, manualCap, prev);
                if (p.level < maxLevel || prev < maxLevel) {
                    p.reachedMaxLevelViaCombat = false;
                }
            });
            break;
        case 'INC_GEAR':
            updatePlayer(event.targetPlayerId, p => {
                const current = p.gearBonus || 0;
                p.gearBonus = clampInt(current + stepAmount(event.amount), GEAR_MIN, GEAR_MAX, current);
            });
            break;
        case 'DEC_GEAR':
            updatePlayer(event.targetPlayerId, p => {
                const current = p.gearBonus || 0;
                p.gearBonus = clampInt(current - stepAmount(event.amount), GEAR_MIN, GEAR_MAX, current);
            });
            break;
        case 'SET_GEAR':
            updatePlayer(event.targetPlayerId, p => {
                const current = p.gearBonus || 0;
                p.gearBonus = clampInt(event.gear, GEAR_MIN, GEAR_MAX, current);
            });
            break;
        case 'SET_HALF_BREED':
            updatePlayer(event.targetPlayerId, p => {
                p.hasHalfBreed = event.enabled === true;
                if (!p.hasHalfBreed || p.secondaryRace === p.characterRace) {
                    p.secondaryRace = 'HUMAN';
                }
            });
            break;
        case 'SET_SUPER_MUNCHKIN':
            updatePlayer(event.targetPlayerId, p => {
                p.hasSuperMunchkin = event.enabled === true;
                if (!p.hasSuperMunchkin || p.secondaryClass === p.characterClass) {
                    p.secondaryClass = 'NONE';
                }
            });
            break;
        case 'SET_CLASS':
            if (VALID_CLASSES.has(event.newClass)) {
                updatePlayer(event.targetPlayerId, p => {
                    if (event.isSecondary) {
                        p.secondaryClass = (p.hasSuperMunchkin && event.newClass !== p.characterClass)
                            ? event.newClass
                            : 'NONE';
                    } else {
                        p.characterClass = event.newClass;
                        if (!p.hasSuperMunchkin || p.secondaryClass === p.characterClass) {
                            p.secondaryClass = 'NONE';
                        }
                    }
                });
            }
            break;
        case 'SET_RACE':
            if (VALID_RACES.has(event.newRace)) {
                updatePlayer(event.targetPlayerId, p => {
                    if (event.isSecondary) {
                        p.secondaryRace = (p.hasHalfBreed && event.newRace !== p.characterRace)
                            ? event.newRace
                            : 'HUMAN';
                    } else {
                        p.characterRace = event.newRace;
                        if (!p.hasHalfBreed || p.secondaryRace === p.characterRace) {
                            p.secondaryRace = 'HUMAN';
                        }
                    }
                });
            }
            break;

        case 'COMBAT_START':
            state.combat = {
                mainPlayerId: event.mainPlayerId,
                helperPlayerId: null,
                monsters: Array.isArray(event.monsters) ? [...event.monsters] : [],
                tempBonuses: [],
                heroModifier: 0,
                monsterModifier: 0,
                lastDiceRoll: null,
                isActive: true
            };
            break;
        case 'COMBAT_ADD_HELPER':
            if (state.combat &&
                event.helperId !== state.combat.mainPlayerId &&
                state.players[event.helperId]) {
                state.combat = cloneCombat(state.combat);
                state.combat.helperPlayerId = event.helperId;
            }
            break;
        case 'COMBAT_REMOVE_HELPER':
            if (state.combat) {
                state.combat = cloneCombat(state.combat);
                state.combat.helperPlayerId = null;
            }
            break;
        case 'COMBAT_ADD_MONSTER':
            if (state.combat) {
                state.combat = cloneCombat(state.combat);
                state.combat.monsters.push(event.monster);
            }
            break;
        case 'COMBAT_REMOVE_MONSTER':
            if (state.combat) {
                state.combat = {
                    ...cloneCombat(state.combat),
                    monsters: state.combat.monsters.filter(m => m.id !== event.monsterId)
                };
            }
            break;
        case 'COMBAT_UPDATE_MONSTER':
            if (state.combat) {
                state.combat = {
                    ...cloneCombat(state.combat),
                    monsters: state.combat.monsters.map(
                        m => (m.id === event.monster?.id ? event.monster : m)
                    )
                };
            }
            break;
        case 'COMBAT_ADD_BONUS':
            if (state.combat) {
                state.combat = cloneCombat(state.combat);
                state.combat.tempBonuses.push(event.bonus);
            }
            break;
        case 'COMBAT_REMOVE_BONUS':
            if (state.combat) {
                state.combat = {
                    ...cloneCombat(state.combat),
                    tempBonuses: state.combat.tempBonuses.filter(b => b.id !== event.bonusId)
                };
            }
            break;
        case 'COMBAT_SET_MODIFIER':
            if (state.combat) {
                const value = clampInt(event.value, -MODIFIER_LIMIT, MODIFIER_LIMIT, 0);
                state.combat = cloneCombat(state.combat);
                if (event.target === 'HEROES') state.combat.heroModifier = value;
                else state.combat.monsterModifier = value;
            }
            break;
        case 'COMBAT_END': {
            const combat = state.combat;
            state.combat = null;
            if (combat && !event.cancelled && event.outcome === 'WIN') {
                const levelsGained = Math.max(0, Math.round(Number(event.levelsGained) || 0));
                const treasuresGained = Math.max(0, Math.round(Number(event.treasuresGained) || 0));
                const helperLevelsGained = Math.max(0, Math.round(Number(event.helperLevelsGained) || 0));
                updatePlayer(combat.mainPlayerId, p => {
                    const prevLevel = p.level || 1;
                    p.level = Math.min(prevLevel + levelsGained, maxLevel);
                    p.treasures = (p.treasures || 0) + treasuresGained;
                    if (prevLevel < maxLevel && p.level >= maxLevel) {
                        p.reachedMaxLevelViaCombat = true;
                    }
                });
                if (combat.helperPlayerId && helperLevelsGained > 0) {
                    updatePlayer(combat.helperPlayerId, p => {
                        const prevLevel = p.level || 1;
                        p.level = Math.min(prevLevel + helperLevelsGained, maxLevel);
                        if (prevLevel < maxLevel && p.level >= maxLevel) {
                            p.reachedMaxLevelViaCombat = true;
                        }
                    });
                }
            }
            break;
        }

        default:
            break;
    }

    return state;
}

/** PLAYER_STATUS handling — connection flag flips outside the event stream. */
export function applyPlayerStatus(prevState, playerId, isConnected) {
    if (!prevState || !prevState.players?.[playerId]) return prevState;
    return {
        ...prevState,
        players: {
            ...prevState.players,
            [playerId]: {
                ...prevState.players[playerId],
                isConnected
            }
        }
    };
}
