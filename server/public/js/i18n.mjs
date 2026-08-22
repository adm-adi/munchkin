/**
 * UI strings, Spanish first (the app's default resource language). Kept in one
 * flat dictionary so adding EN/FR later means adding a dictionary, not touching
 * views. Class/race/error labels mirror the Android string resources.
 */

export const CLASS_LABELS = {
    NONE: 'Sin clase',
    WARRIOR: 'Guerrero',
    WIZARD: 'Mago',
    THIEF: 'Ladrón',
    CLERIC: 'Clérigo'
};

export const RACE_LABELS = {
    HUMAN: 'Humano',
    ELF: 'Elfo',
    DWARF: 'Enano',
    HALFLING: 'Mediano'
};

export const GENDER_LABELS = { M: 'Hombre', F: 'Mujer', NA: 'Otro' };

export const CLASS_ICONS = { NONE: '∅', WARRIOR: '⚔️', WIZARD: '🪄', THIEF: '🗡️', CLERIC: '✝️' };
export const RACE_ICONS = { HUMAN: '👤', ELF: '🧝', DWARF: '🧔', HALFLING: '🍄' };

/** Avatar slots mirror AvatarResources.kt: 8 portraits, gendered variants. */
export const AVATARS = [
    { id: 0, name: 'Guerrero', m: '🧑‍🚒', f: '👩‍🚒', emoji: '⚔️' },
    { id: 1, name: 'Clérigo', m: '🧑‍⚕️', f: '👩‍⚕️', emoji: '✝️' },
    { id: 2, name: 'Mago', m: '🧙‍♂️', f: '🧙‍♀️', emoji: '🪄' },
    { id: 3, name: 'Ladrón', m: '🕵️‍♂️', f: '🕵️‍♀️', emoji: '🗡️' },
    { id: 4, name: 'Elfo', m: '🧝‍♂️', f: '🧝‍♀️', emoji: '🏹' },
    { id: 5, name: 'Enano', m: '🧔', f: '👩‍🦰', emoji: '⛏️' },
    { id: 6, name: 'Humano', m: '🧑', f: '👩', emoji: '🛡️' },
    { id: 7, name: 'Mediano', m: '🧑‍🌾', f: '👩‍🌾', emoji: '🍄' }
];

export function avatarEmoji(avatarId, gender) {
    const slot = AVATARS[Math.abs(avatarId ?? 0) % AVATARS.length];
    return gender === 'F' ? slot.f : slot.m;
}

/** Ability reminders (Abilities.kt): automatic = the app applies it. */
export const ABILITIES = {
    WARRIOR: [
        { text: 'Ganas los empates en combate.', auto: true },
        { text: 'Berserk: descarta hasta 3 cartas para +1 al combate cada una.', auto: false }
    ],
    WIZARD: [
        { text: 'Hechizo de encanto: descarta tu mano para encantar un monstruo y llevarte su tesoro.', auto: false },
        { text: 'Hechizo de vuelo: descarta hasta 3 cartas para +1 a la huida cada una.', auto: false }
    ],
    THIEF: [
        { text: 'Puñalada por la espalda: descarta una carta para dar -2 al combate de otro jugador.', auto: false },
        { text: 'Robar: descarta una carta y tira el dado para robar un objeto.', auto: false }
    ],
    CLERIC: [
        { text: '+3 al combate contra No-Muertos.', auto: true },
        { text: 'Resurrección: al descartar, puedes descartar 2 cartas más y robar otras 2.', auto: false }
    ],
    ELF: [
        { text: 'Subes un nivel cada vez que ayudas a matar un monstruo.', auto: true },
        { text: '+1 a la tirada de huida.', auto: true }
    ],
    DWARF: [
        { text: 'Puedes llevar cualquier número de objetos Grandes.', auto: false },
        { text: 'Puedes tener 6 cartas en la mano.', auto: false }
    ],
    HALFLING: [
        { text: 'Cada turno puedes vender un objeto por el doble de su precio.', auto: false },
        { text: '-1 a la tirada de huida.', auto: true }
    ]
};

export const ERROR_MESSAGES = {
    INVALID_JOIN_CODE: 'Código de partida inválido',
    GAME_NOT_FOUND: 'Partida no encontrada',
    GAME_FULL: 'La partida está llena',
    GAME_ALREADY_STARTED: 'La partida ya ha comenzado',
    PLAYER_NOT_FOUND: 'Jugador no encontrado',
    UNAUTHORIZED: 'No autorizado',
    AUTH_FAILED: 'Email o contraseña incorrectos',
    EMAIL_EXISTS: 'El email ya está registrado',
    USERNAME_EXISTS: 'Ese nombre de usuario ya está en uso',
    RATE_LIMITED: 'Demasiados intentos. Espera un momento.',
    FORBIDDEN: 'Acción no permitida',
    COMBAT_ALREADY_ACTIVE: 'Ya hay un combate activo',
    NO_ACTIVE_COMBAT: 'No hay combate activo'
};

export function errorText(err) {
    if (!err) return 'Error desconocido';
    if (err.name === 'TimeoutError') return 'El servidor no respondió a tiempo';
    if (err.name === 'ServerError') {
        return err.message || ERROR_MESSAGES[err.code] || 'Error del servidor';
    }
    return err.message || String(err);
}
