/**
 * Modular combat & curse UI helpers for the Web PWA (Dungeon Edition).
 * Strict CSP compliant: zero inline style attributes.
 */

import { BONUS_TARGETS, activeCurse } from '../protocol.mjs';
import { t } from '../i18n.mjs';
import { h, fmtSigned, openModal } from '../ui.mjs';

/**
 * 1-tap Munchkin card presets bar for active combat.
 */
export function combatPresetsBar({ onAddPreset, disabled = false }) {
    const presets = [
        { side: BONUS_TARGETS.MONSTER, label: t('combat.presetAncient'), amount: 10 },
        { side: BONUS_TARGETS.MONSTER, label: t('combat.presetEnraged'), amount: 5 },
        { side: BONUS_TARGETS.MONSTER, label: t('combat.presetBaby'), amount: -5 },
        { side: BONUS_TARGETS.HEROES, label: t('combat.presetPotion2'), amount: 2 },
        { side: BONUS_TARGETS.HEROES, label: t('combat.presetPotion3'), amount: 3 },
        { side: BONUS_TARGETS.HEROES, label: t('combat.presetPotion5'), amount: 5 }
    ];

    return h('div', { class: 'stack-sm' },
        h('span', { class: 'field-label' }, t('combat.presets')),
        h('div', { class: 'chip-grid' }, presets.map(p =>
            h('button', {
                class: 'chip',
                type: 'button',
                disabled,
                onclick: () => onAddPreset(p.side, p.label, p.amount)
            }, p.label)
        ))
    );
}

/**
 * Helper treasure deal control shown when a helper joins combat.
 */
export function helperTreasureDealBar({ currentShare, totalTreasures, canEdit, onChange }) {
    const share = Number.isFinite(currentShare) ? currentShare : 0;
    const maxShare = Math.min(10, Math.max(1, totalTreasures || 1));

    return h('div', { class: 'flat-row' },
        h('span', { class: 'badge badge-gold' },
            currentShare !== null && currentShare !== undefined
                ? t('combat.helperTreasuresDeal', { share: currentShare, total: totalTreasures })
                : t('combat.helperTreasures')
        ),
        canEdit && h('div', { class: 'row-wrap' },
            h('button', {
                class: 'icon-btn',
                type: 'button',
                disabled: share <= 0,
                'aria-label': `${t('combat.helperTreasures')} −1`,
                onclick: () => onChange(Math.max(0, share - 1))
            }, '−'),
            h('span', { class: 'badge tabular' }, String(share)),
            h('button', {
                class: 'icon-btn',
                type: 'button',
                disabled: share >= maxShare,
                'aria-label': `${t('combat.helperTreasures')} +1`,
                onclick: () => onChange(Math.min(maxShare, share + 1))
            }, '+')
        )
    );
}

/**
 * Active curses section displayed inside the hero sheet modal.
 */
export function activeCursesSection({ player, editable, onAddCurse, onRemoveCurse }) {
    const curses = Array.isArray(player.activeCurses) ? player.activeCurses : [];

    return h('div', { class: 'stack-sm' },
        h('div', { class: 'section-bar' },
            h('h3', { class: 'section-heading' }, t('curses.title')),
            editable && h('button', {
                class: 'btn btn-ghost',
                type: 'button',
                onclick: () => openAddCurseModal({ onConfirm: onAddCurse })
            }, `+ ${t('curses.add')}`)
        ),
        curses.length === 0
            ? h('p', { class: 'muted-text' }, t('curses.none'))
            : h('div', { class: 'list-divide' }, curses.map(c => {
                const parts = [];
                if (c.combatPenalty) parts.push(`${fmtSigned(c.combatPenalty)} ⚔️`);
                if (c.runAwayPenalty) parts.push(`${fmtSigned(c.runAwayPenalty)} 🏃`);
                parts.push(c.combatsRemaining > 0 ? `${c.combatsRemaining}x` : t('curses.persistent'));
                return h('div', { class: 'flat-row', dataset: { key: `curse-${c.id}` } },
                    h('div', { class: 'stack-sm grow' },
                        h('strong', {}, c.name),
                        h('span', { class: 'muted-text' }, parts.join(' · '))
                    ),
                    editable && h('button', {
                        class: 'icon-btn icon-btn-danger',
                        type: 'button',
                        'aria-label': `${t('common.remove')} ${c.name}`,
                        onclick: () => onRemoveCurse(c.id)
                    }, '✕')
                );
            }))
    );
}

/**
 * Modal for adding a preset or custom Munchkin curse.
 */
export function openAddCurseModal({ onConfirm }) {
    let name = '';
    let combatPenalty = '0';
    let runAwayPenalty = '0';
    let combatsRemaining = '0';

    const presets = [
        { name: t('curses.presetSexChange'), combatPenalty: -5, runAwayPenalty: 0, combatsRemaining: 1 },
        { name: t('curses.presetChicken'), combatPenalty: 0, runAwayPenalty: -1, combatsRemaining: 0 },
        { name: t('curses.presetNoArmor'), combatPenalty: 0, runAwayPenalty: 0, combatsRemaining: 0 }
    ];

    const modal = openModal({
        title: t('curses.add'),
        dismissible: true,
        body: [
            h('div', { class: 'stack-sm' }, presets.map(p =>
                h('button', {
                    class: 'btn btn-ghost btn-block',
                    type: 'button',
                    onclick: () => {
                        onConfirm(activeCurse(p));
                        modal.close();
                    }
                }, p.name)
            )),
            h('hr', { class: 'divider' }),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('curses.name')),
                h('input', {
                    class: 'input',
                    maxlength: '40',
                    value: name,
                    oninput: (e) => { name = e.target.value; }
                })
            ),
            h('div', { class: 'grid-2' },
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('curses.combatPen')),
                    h('input', {
                        class: 'input',
                        type: 'number',
                        min: '-20',
                        max: '20',
                        value: combatPenalty,
                        oninput: (e) => { combatPenalty = e.target.value; }
                    })
                ),
                h('label', { class: 'field' },
                    h('span', { class: 'field-label' }, t('curses.runPen')),
                    h('input', {
                        class: 'input',
                        type: 'number',
                        min: '-5',
                        max: '5',
                        value: runAwayPenalty,
                        oninput: (e) => { runAwayPenalty = e.target.value; }
                    })
                )
            ),
            h('label', { class: 'field' },
                h('span', { class: 'field-label' }, t('curses.combats')),
                h('input', {
                    class: 'input',
                    type: 'number',
                    min: '0',
                    max: '10',
                    value: combatsRemaining,
                    oninput: (e) => { combatsRemaining = e.target.value; }
                })
            ),
            h('div', { class: 'modal-actions' },
                h('button', { class: 'btn btn-ghost', type: 'button', onclick: () => modal.close() }, t('common.cancel')),
                h('button', {
                    class: 'btn btn-primary',
                    type: 'button',
                    onclick: () => {
                        const trimmed = name.trim();
                        if (!trimmed) return;
                        onConfirm(activeCurse({
                            name: trimmed,
                            combatPenalty: Math.max(-20, Math.min(20, Number(combatPenalty) || 0)),
                            runAwayPenalty: Math.max(-5, Math.min(5, Number(runAwayPenalty) || 0)),
                            combatsRemaining: Math.max(0, Math.min(10, Number(combatsRemaining) || 0))
                        }));
                        modal.close();
                    }
                }, t('common.add'))
            )
        ]
    });
}
