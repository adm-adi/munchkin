/**
 * Client-side reducer for authoritative server state.
 *
 * Port of core/GameEngine.kt: it only folds server-validated event broadcasts
 * into the local snapshot the UI renders, and deliberately does not validate —
 * rejecting something the server already applied could only cause divergence.
 * Snapshot-first events (combat, turns, rolls) arrive as full STATE_SNAPSHOTs
 * instead of broadcasts, so most branches here cover the lightweight events;
 * the rest exist for parity with the Kotlin reducer.
 *
 * All functions are pure: they take a plain-JSON game state (as decoded off the
 * wire) and return a new one. structuredClone keeps the implementation honest.
 */

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

/** PlayerState.combatPower = level + gear + temp bonus. */
export function combatPower(player) {
    return (player.level || 1) + (player.gearBonus || 0) + (player.tempCombatBonus || 0);
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

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

function updatePlayer(state, playerId, update) {
    const player = state.players[playerId];
    if (!player) return;
    update(player);
}

function orderedIds(state) {
    if (Array.isArray(state.playerOrder) && state.playerOrder.length > 0) return state.playerOrder;
    return Object.keys(state.players).sort();
}

/**
 * Applies one server-broadcast event, returning the next state. The seq
 * counter advances like the Kotlin engine's; a dropped connection is followed
 * by a fresh WELCOME snapshot so there is no gap to recover from.
 */
export function applyEvent(prevState, event) {
    if (!prevState) return prevState;
    const state = structuredClone(prevState);
    state.seq = (state.seq || 0) + 1;

    const minLevel = state.settings?.minLevel ?? 1;
    const maxLevel = state.settings?.maxLevel ?? 10;

    switch (event.type) {
        case 'PLAYER_JOIN': {
            const meta = event.playerMeta;
            state.players[meta.playerId] = {
                playerId: meta.playerId,
                name: meta.name,
                avatarId: meta.avatarId || 0,
                gender: meta.gender || 'NA',
                characterClass: 'NONE',
                characterRace: 'HUMAN',
                secondaryClass: 'NONE',
                secondaryRace: 'HUMAN',
                level: 1,
                gearBonus: 0,
                tempCombatBonus: 0,
                treasures: 0,
                raceIds: [],
                classIds: [],
                hasHalfBreed: false,
                hasSuperMunchkin: false,
                lastKnownIp: event.lastKnownIp || null,
                isConnected: true,
                lastRoll: null,
                reachedMaxLevelViaCombat: false
            };
            break;
        }
        case 'PLAYER_LEAVE':
            delete state.players[event.actorId];
            break;
        case 'PLAYER_ROLL': {
            updatePlayer(state, event.actorId, p => { p.lastRoll = event.result; });
            const inCombatRoll = state.combat &&
                (event.purpose === 'COMBAT' || event.purpose === 'RUN_AWAY');
            if (inCombatRoll) {
                state.combat.lastDiceRoll = {
                    playerId: event.actorId,
                    playerName: state.players[event.actorId]?.name || 'Unknown',
                    result: event.result,
                    success: event.success === true,
                    timestamp: event.timestamp || Date.now(),
                    purpose: event.purpose
                };
            } else if (state.phase === 'LOBBY' && allPlayersRolled(state)) {
                // Tie for highest: those players re-roll.
                const players = Object.values(state.players);
                const maxRoll = Math.max(...players.map(p => p.lastRoll || 0));
                const tied = players.filter(p => p.lastRoll === maxRoll);
                if (tied.length > 1) {
                    for (const p of tied) p.lastRoll = null;
                }
            }
            break;
        }
        case 'GAME_START': {
            const players = Object.values(state.players);
            const best = players.reduce(
                (acc, p) => ((p.lastRoll || 0) > (acc?.lastRoll || 0) ? p : acc),
                null
            );
            let startingPlayerId = best && (best.lastRoll || 0) > 0 ? best.playerId : null;
            if (!startingPlayerId) startingPlayerId = orderedIds(state)[0] || null;
            state.phase = 'IN_GAME';
            state.turnPlayerId = startingPlayerId;
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
            const order = orderedIds(state);
            const idx = order.indexOf(current);
            if (idx === -1) break;
            let next = current;
            for (let i = 1; i <= order.length; i++) {
                const candidate = state.players[order[(idx + i) % order.length]];
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
            break;

        case 'SET_NAME':
            updatePlayer(state, event.targetPlayerId, p => { p.name = event.name; });
            break;
        case 'SET_AVATAR':
            updatePlayer(state, event.targetPlayerId, p => { p.avatarId = event.avatarId; });
            break;
        case 'SET_GENDER':
            updatePlayer(state, event.targetPlayerId, p => { p.gender = event.gender; });
            break;
        case 'INC_LEVEL':
            updatePlayer(state, event.targetPlayerId, p => {
                p.level = clamp(p.level + (event.amount ?? 1), minLevel, maxLevel);
            });
            break;
        case 'DEC_LEVEL':
            updatePlayer(state, event.targetPlayerId, p => {
                p.level = clamp(p.level - (event.amount ?? 1), minLevel, maxLevel);
            });
            break;
        case 'INC_GEAR':
            updatePlayer(state, event.targetPlayerId, p => { p.gearBonus += event.amount ?? 1; });
            break;
        case 'DEC_GEAR':
            updatePlayer(state, event.targetPlayerId, p => { p.gearBonus -= event.amount ?? 1; });
            break;
        case 'SET_HALF_BREED':
            updatePlayer(state, event.targetPlayerId, p => {
                p.hasHalfBreed = event.enabled === true;
                if (!p.hasHalfBreed) p.secondaryRace = 'HUMAN';
            });
            break;
        case 'SET_SUPER_MUNCHKIN':
            updatePlayer(state, event.targetPlayerId, p => {
                p.hasSuperMunchkin = event.enabled === true;
                if (!p.hasSuperMunchkin) p.secondaryClass = 'NONE';
            });
            break;
        case 'SET_CLASS':
            updatePlayer(state, event.targetPlayerId, p => {
                if (event.isSecondary) p.secondaryClass = event.newClass;
                else p.characterClass = event.newClass;
            });
            break;
        case 'SET_RACE':
            updatePlayer(state, event.targetPlayerId, p => {
                if (event.isSecondary) p.secondaryRace = event.newRace;
                else p.characterRace = event.newRace;
            });
            break;

        case 'COMBAT_START':
            state.combat = {
                mainPlayerId: event.mainPlayerId,
                helperPlayerId: null,
                monsters: [],
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
                state.combat.helperPlayerId = event.helperId;
            }
            break;
        case 'COMBAT_REMOVE_HELPER':
            if (state.combat) state.combat.helperPlayerId = null;
            break;
        case 'COMBAT_ADD_MONSTER':
            if (state.combat) state.combat.monsters.push(event.monster);
            break;
        case 'COMBAT_REMOVE_MONSTER':
            if (state.combat) {
                state.combat.monsters = state.combat.monsters.filter(m => m.id !== event.monsterId);
            }
            break;
        case 'COMBAT_UPDATE_MONSTER':
            if (state.combat) {
                state.combat.monsters = state.combat.monsters.map(
                    m => (m.id === event.monster.id ? event.monster : m)
                );
            }
            break;
        case 'COMBAT_ADD_BONUS':
            if (state.combat) state.combat.tempBonuses.push(event.bonus);
            break;
        case 'COMBAT_REMOVE_BONUS':
            if (state.combat) {
                state.combat.tempBonuses = state.combat.tempBonuses.filter(b => b.id !== event.bonusId);
            }
            break;
        case 'COMBAT_MODIFY_MODIFIER':
            if (state.combat) {
                if (event.target === 'HEROES') state.combat.heroModifier += event.delta;
                else state.combat.monsterModifier += event.delta;
            }
            break;
        case 'COMBAT_SET_MODIFIER':
            if (state.combat) {
                if (event.target === 'HEROES') state.combat.heroModifier = event.value;
                else state.combat.monsterModifier = event.value;
            }
            break;
        case 'COMBAT_END': {
            const combat = state.combat;
            state.combat = null;
            if (combat && event.outcome === 'WIN') {
                updatePlayer(state, combat.mainPlayerId, p => {
                    p.level = Math.min(p.level + (event.levelsGained || 0), maxLevel);
                    p.treasures = (p.treasures || 0) + (event.treasuresGained || 0);
                });
                if (combat.helperPlayerId && (event.helperLevelsGained || 0) > 0) {
                    updatePlayer(state, combat.helperPlayerId, p => {
                        p.level = Math.min(p.level + event.helperLevelsGained, maxLevel);
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
    if (!prevState || !prevState.players[playerId]) return prevState;
    const state = structuredClone(prevState);
    state.players[playerId].isConnected = isConnected;
    return state;
}
