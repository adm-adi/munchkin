/**
 * Panoramic Tabletop TV / Spectator Mode (#/tv/<CODE>) for Munchkin Mesa Tracker.
 * Read-only, high-contrast Dungeon Edition screen optimized for tablets & TVs.
 * Strict CSP compliant: zero inline style attributes.
 */

import { OUTCOMES, PHASES } from '../protocol.mjs';
import { playerList, combatPower } from '../engine.mjs';
import { calculateResult } from '../combat.mjs';
import { t, avatarEmoji, classLabel, raceLabel } from '../i18n.mjs';
import { h, createQrSvg, fmtSigned } from '../ui.mjs';

function tvPlayerTraits(p) {
    const parts = [];
    if (p.characterRace && p.characterRace !== 'HUMAN') parts.push(raceLabel(p.characterRace));
    if (p.hasHalfBreed && p.secondaryRace && p.secondaryRace !== 'HUMAN') parts.push(raceLabel(p.secondaryRace));
    if (p.characterClass && p.characterClass !== 'NONE') parts.push(classLabel(p.characterClass));
    if (p.hasSuperMunchkin && p.secondaryClass && p.secondaryClass !== 'NONE') parts.push(classLabel(p.secondaryClass));
    return parts.length > 0 ? parts.join(' · ') : raceLabel('HUMAN');
}

export function renderTvView({ state, countdown, joinCodeInput, onCodeInput, onWatch, onExit }) {
    if (!state) {
        return h('section', { class: 'screen tv-screen', dataset: { key: 'view-tv-picker' } },
            h('div', { class: 'page-head' },
                h('button', { class: 'btn btn-ghost', onclick: onExit }, `← ${t('common.back')}`),
                h('h1', { class: 'page-title' }, `📺 ${t('tv.openBtn')}`)
            ),
            h('form', {
                class: 'card',
                onsubmit: (e) => {
                    e.preventDefault();
                    onWatch(joinCodeInput);
                }
            },
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('join.codeLabel')),
                    h('input', {
                        class: 'input code-input',
                        placeholder: t('tv.watchCodePlaceholder'),
                        maxlength: '8',
                        value: joinCodeInput,
                        oninput: (e) => onCodeInput(e.target.value.toUpperCase())
                    })
                ),
                h('button', { class: 'btn btn-primary btn-block', type: 'submit' }, `📺 ${t('tv.watchBtn')}`)
            )
        );
    }

    const list = playerList(state);
    const turnPlayer = state.turnPlayerId ? state.players[state.turnPlayerId] : null;
    const combat = state.combat;
    const joinUrl = `${location.origin}/#/join/${encodeURIComponent(state.joinCode)}`;

    return h('section', { class: 'screen tv-screen', dataset: { key: `view-tv-${state.joinCode}` } },
        h('header', { class: 'tv-header' },
            h('div', { class: 'row-wrap' },
                h('button', { class: 'btn btn-ghost', onclick: onExit }, `← ${t('common.back')}`),
                h('h1', { class: 'tv-title tabular' }, t('tv.title', { code: state.joinCode })),
                (state.spectatorCount || 0) > 0 && h('span', { class: 'badge badge-gold' },
                    `👁️ ${t('tv.spectators', { count: state.spectatorCount })}`
                )
            ),
            h('div', { class: 'row-wrap' },
                turnPlayer && state.phase === PHASES.IN_GAME && h('span', { class: 'badge badge-gold tv-turn-chip' },
                    `🎯 ${t('board.turnOf', { name: turnPlayer.name })}`
                ),
                countdown?.text && h('span', {
                    id: 'turn-countdown',
                    class: `timer-pill tv-timer tabular ${countdown.urgent ? 'is-urgent' : ''} ${countdown.paused ? 'is-paused' : ''}`
                }, countdown.text)
            )
        ),

        state.phase === PHASES.LOBBY && h('div', { class: 'tv-lobby-grid' },
            h('div', { class: 'card tv-qr-panel' },
                h('p', { class: 'section-heading' }, t('tv.waitingLobby')),
                h('div', { class: 'qr-frame' }, createQrSvg(joinUrl)),
                h('div', { class: 'join-code-big tabular' }, state.joinCode)
            ),
            h('div', { class: 'tv-players-grid' }, list.map(p =>
                h('article', { class: 'hero-card tv-hero-card', dataset: { key: `tv-p-${p.playerId}` } },
                    h('div', { class: 'hero-head' },
                        h('div', { class: 'hero-identity' },
                            h('span', { class: 'avatar-badge' }, avatarEmoji(p.avatarId, p.gender)),
                            h('div', { class: 'stack-sm' },
                                h('strong', { class: 'tv-player-name' }, p.name),
                                h('span', { class: 'muted-text' }, p.lastRoll != null ? `🎲 ${p.lastRoll}` : '…')
                            )
                        )
                    )
                )
            ))
        ),

        state.phase === PHASES.IN_GAME && combat && (() => {
            const calc = calculateResult(combat, state);
            const mainP = state.players[combat.mainPlayerId];
            const helperP = combat.helperPlayerId ? state.players[combat.helperPlayerId] : null;
            const monsters = combat.monsters || [];
            const isWin = calc.outcome === OUTCOMES.WIN && monsters.length > 0;
            return h('div', { class: 'tv-combat-arena' },
                h('div', { class: 'tv-arena-badge' }, `⚔️ ${t('tv.liveCombat')}`),
                h('div', { class: 'versus-strip tv-versus' },
                    h('div', { class: 'versus-side' },
                        h('span', { class: 'section-heading' }, t('combat.heroes')),
                        h('span', { class: 'versus-score tv-score is-heroes tabular' }, String(calc.heroesPower)),
                        h('strong', { class: 'tv-combat-names' },
                            helperP ? `${mainP?.name || '?'} + ${helperP.name}` : (mainP?.name || '?')
                        ),
                        helperP && combat.helperTreasureShare !== null && combat.helperTreasureShare !== undefined &&
                            h('span', { class: 'badge badge-gold' }, t('combat.helperTreasuresDeal', {
                                share: combat.helperTreasureShare,
                                total: monsters.reduce((s, m) => s + (m.treasures ?? 1), 0)
                            }))
                    ),
                    h('div', { class: 'versus-vs tv-vs' }, 'VS'),
                    h('div', { class: 'versus-side' },
                        h('span', { class: 'section-heading' }, t('combat.monsters')),
                        h('span', { class: 'versus-score tv-score is-monsters tabular' }, String(calc.monstersPower)),
                        h('div', { class: 'stack-sm' }, monsters.map(m =>
                            h('span', { class: 'tv-monster-line tabular' },
                                `${m.name} (Nv ${m.baseLevel}${m.flatModifier ? ` ${fmtSigned(m.flatModifier)}` : ''})`
                            )
                        ))
                    )
                ),
                h('div', { class: `combat-outcome-pill tv-outcome ${isWin ? 'is-win' : 'is-lose'}` },
                    monsters.length === 0
                        ? t('combat.needMonsters')
                        : isWin
                            ? (calc.orcOverkillBonus ? `${t('combat.winning')} · ${t('combat.orcOverkill')}` : t('combat.winning'))
                            : t('combat.losingGap', { gap: calc.marginToWin })
                )
            );
        })(),

        state.phase === PHASES.IN_GAME && h('div', { class: 'tv-players-grid' }, list.map(p => {
            const isTurn = p.playerId === state.turnPlayerId;
            const curses = Array.isArray(p.activeCurses) ? p.activeCurses : [];
            return h('article', {
                class: `hero-card tv-hero-card ${isTurn ? 'is-turn' : ''} ${p.isConnected ? '' : 'is-offline'}`,
                dataset: { key: `tv-p-${p.playerId}` }
            },
                h('div', { class: 'hero-head' },
                    h('div', { class: 'hero-identity' },
                        h('span', { class: 'avatar-badge' }, avatarEmoji(p.avatarId, p.gender)),
                        h('div', { class: 'stack-sm' },
                            h('div', { class: 'row-wrap' },
                                h('h2', { class: 'tv-player-name' }, p.name),
                                p.playerId === state.hostId && h('span', { class: 'badge badge-gold' }, '👑')
                            ),
                            h('span', { class: 'hero-meta' }, tvPlayerTraits(p))
                        )
                    ),
                    h('div', { class: 'hero-power-box' },
                        h('div', { class: 'tv-power-big tabular' }, String(combatPower(p))),
                        h('div', { class: 'hero-power-label' }, t('board.power'))
                    )
                ),
                h('div', { class: 'row-wrap' },
                    h('span', { class: 'badge badge-crimson tabular' }, t('board.lvlShort', { n: p.level })),
                    h('span', { class: 'badge tabular' }, t('board.gearShort', { n: p.gearBonus || 0 })),
                    curses.length > 0 && h('span', { class: 'badge badge-danger' }, `☠️ ${curses.length}`)
                )
            );
        }))
    );
}
