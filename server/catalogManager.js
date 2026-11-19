const { clampInt, sanitizeConditionalModifier, extractConditionalModifiersFromText } = require('./validation');

function createCatalogManager({ db, sendError, safeSend, logger }) {
    const sendJson = typeof safeSend === 'function'
        ? safeSend
        : (ws, payload) => {
            if (ws && ws.readyState === 1) {
                ws.send(JSON.stringify(payload));
            }
        };

    const catalogAddRateLimits = new Map();
    const CATALOG_ADD_MAX_PER_MINUTE = 10;
    const CATALOG_ADD_WINDOW_MS = 60 * 1000;
    const catalogSearchRateLimits = new Map();
    const CATALOG_SEARCH_MAX_PER_MINUTE = 60;
    const CATALOG_SEARCH_WINDOW_MS = 60 * 1000;
    const MAX_RATE_LIMIT_ENTRIES = 10_000;

    function isCatalogSearchRateLimited(ip) {
        const record = catalogSearchRateLimits.get(ip);
        if (!record) return false;
        if (Date.now() - record.windowStart > CATALOG_SEARCH_WINDOW_MS) {
            catalogSearchRateLimits.delete(ip);
            return false;
        }
        return record.count >= CATALOG_SEARCH_MAX_PER_MINUTE;
    }

    function recordCatalogSearch(ip) {
        const now = Date.now();
        const record = catalogSearchRateLimits.get(ip);
        const updated = (!record || now - record.windowStart > CATALOG_SEARCH_WINDOW_MS)
            ? { count: 1, windowStart: now }
            : { count: record.count + 1, windowStart: record.windowStart };

        catalogSearchRateLimits.delete(ip);
        catalogSearchRateLimits.set(ip, updated);
        while (catalogSearchRateLimits.size > MAX_RATE_LIMIT_ENTRIES) {
            const oldestKey = catalogSearchRateLimits.keys().next().value;
            if (oldestKey === undefined) break;
            catalogSearchRateLimits.delete(oldestKey);
        }
    }

    function isCatalogAddRateLimited(userId) {
        const record = catalogAddRateLimits.get(userId);
        if (!record) return false;
        if (Date.now() - record.windowStart > CATALOG_ADD_WINDOW_MS) {
            catalogAddRateLimits.delete(userId);
            return false;
        }
        return record.count >= CATALOG_ADD_MAX_PER_MINUTE;
    }

    function recordCatalogAdd(userId) {
        const now = Date.now();
        const record = catalogAddRateLimits.get(userId);
        const updated = (!record || now - record.windowStart > CATALOG_ADD_WINDOW_MS)
            ? { count: 1, windowStart: now }
            : { count: record.count + 1, windowStart: record.windowStart };

        catalogAddRateLimits.delete(userId);
        catalogAddRateLimits.set(userId, updated);
        while (catalogAddRateLimits.size > MAX_RATE_LIMIT_ENTRIES) {
            const oldestKey = catalogAddRateLimits.keys().next().value;
            if (oldestKey === undefined) break;
            catalogAddRateLimits.delete(oldestKey);
        }
    }

    function handleCatalogSearch(ws, message) {
        const clientIp = ws.clientIp || 'unknown';
        if (isCatalogSearchRateLimited(clientIp)) {
            sendError(ws, 'RATE_LIMITED', 'Demasiadas búsquedas. Espera un momento.');
            return;
        }
        recordCatalogSearch(clientIp);

        const { query, expansion, limit, offset, isUndead, minLevel, maxLevel } = message;

        if (typeof query !== 'string') {
            sendJson(ws, { type: "CATALOG_SEARCH_RESULT", results: [] });
            return;
        }

        if (query !== '__ALL__' && query.trim().length < 2) {
            sendJson(ws, { type: "CATALOG_SEARCH_RESULT", results: [] });
            return;
        }

        if (query.length > 50) {
            sendError(ws, 'INVALID_DATA', 'Query too long');
            return;
        }

        const effectiveQuery = query === '__ALL__' ? '' : query.trim();
        const safeOptions = {
            expansion: typeof expansion === 'string' && expansion.trim()
                ? expansion.trim().slice(0, 40)
                : undefined,
            limit: clampInt(limit, 1, 50, 20),
            offset: clampInt(offset, 0, 10_000, 0),
            viewerUserId: ws.userId || null,
            isUndead: typeof isUndead === 'boolean' ? isUndead : null,
            minLevel: minLevel !== undefined && minLevel !== null ? clampInt(minLevel, 1, 20, 1) : null,
            maxLevel: maxLevel !== undefined && maxLevel !== null ? clampInt(maxLevel, 1, 20, 20) : null
        };

        db.searchMonsters(effectiveQuery, safeOptions)
            .then(results => {
                logger.info(`🔍 Search '${effectiveQuery || '__ALL__'}' returned ${results.length} monsters`);
                sendJson(ws, {
                    type: "CATALOG_SEARCH_RESULT",
                    results
                });
            })
            .catch(err => {
                logger.error("Search error:", err);
                sendError(ws, "SEARCH_ERROR", "Error al buscar monstruos");
            });
    }

    function handleCatalogAdd(ws, message) {
        let { monster } = message;

        if (!ws.userId) {
            sendError(ws, "UNAUTHORIZED", "Debes iniciar sesión para añadir monstruos");
            return;
        }

        if (!monster || typeof monster.name !== 'string' || !monster.name.trim()) {
            sendError(ws, "INVALID_DATA", "Datos de monstruo inválidos");
            return;
        }

        const rawBadStuff = typeof monster.badStuff === 'string'
            ? monster.badStuff.trim().slice(0, 200)
            : '';
        const explicitMods = Array.isArray(monster.conditionalModifiers)
            ? monster.conditionalModifiers.slice(0, 10).map(sanitizeConditionalModifier).filter(Boolean)
            : [];
        const conditionalModifiers = explicitMods.length > 0
            ? explicitMods
            : extractConditionalModifiersFromText(rawBadStuff);

        const sanitizedMonster = {
            name: monster.name.trim().slice(0, 80),
            level: clampInt(monster.level, 1, 20, 1),
            modifier: clampInt(monster.modifier, -10, 10, 0),
            treasures: clampInt(monster.treasures, 0, 10, 1),
            levels: clampInt(monster.levels, 1, 5, 1),
            isUndead: monster.isUndead === true,
            // Dropped before, so a monster contributed by a player lost its Bad
            // Stuff on the way into the catalog — and the Bad Stuff is the whole
            // consequence of losing a fight to it.
            badStuff: rawBadStuff,
            expansion: typeof monster.expansion === 'string' && monster.expansion.trim()
                ? monster.expansion.trim().slice(0, 40)
                : 'base',
            conditionalModifiers,
            canEdit: true
        };
        monster = sanitizedMonster;

        const rateLimitKey = ws.userId;
        if (isCatalogAddRateLimited(rateLimitKey)) {
            sendError(ws, "RATE_LIMITED", "Demasiados monstruos añadidos. Espera un momento.");
            return;
        }
        recordCatalogAdd(rateLimitKey);

        db.addMonster(sanitizedMonster, ws.userId)
            .then(id => {
                logger.info(`🆕 Added monster: ${monster.name} (${id})`);
                sendJson(ws, {
                    type: "CATALOG_ADD_SUCCESS",
                    monster: { ...sanitizedMonster, id }
                });
            })
            .catch(err => {
                logger.error("Add monster error:", err);
                sendError(ws, "ADD_MONSTER_ERROR", "Error al guardar monstruo");
            });
    }

    function handleCatalogUpdate(ws, message) {
        if (!ws.userId) {
            sendError(ws, "UNAUTHORIZED", "Debes iniciar sesión para editar monstruos");
            return;
        }
        const { monsterId, monster } = message;
        if (typeof monsterId !== 'string' || !monsterId.trim() || !monster || typeof monster.name !== 'string' || !monster.name.trim()) {
            sendError(ws, "INVALID_DATA", "Datos de monstruo inválidos");
            return;
        }
        if (isCatalogAddRateLimited(ws.userId)) {
            sendError(ws, "RATE_LIMITED", "Demasiadas ediciones. Espera un momento.");
            return;
        }
        recordCatalogAdd(ws.userId);

        const rawBadStuff = typeof monster.badStuff === 'string' ? monster.badStuff.trim().slice(0, 200) : '';
        const explicitMods = Array.isArray(monster.conditionalModifiers)
            ? monster.conditionalModifiers.slice(0, 10).map(sanitizeConditionalModifier).filter(Boolean)
            : [];
        const sanitizedMonster = {
            name: monster.name.trim().slice(0, 80),
            level: clampInt(monster.level, 1, 20, 1),
            modifier: clampInt(monster.modifier, -10, 10, 0),
            treasures: clampInt(monster.treasures, 0, 10, 1),
            levels: clampInt(monster.levels, 1, 5, 1),
            isUndead: monster.isUndead === true,
            badStuff: rawBadStuff,
            expansion: typeof monster.expansion === 'string' && monster.expansion.trim()
                ? monster.expansion.trim().slice(0, 40)
                : 'base',
            conditionalModifiers: explicitMods.length > 0 ? explicitMods : extractConditionalModifiersFromText(rawBadStuff)
        };

        db.updateMonster(monsterId.trim(), sanitizedMonster, ws.userId)
            .then(result => {
                if (result.error === 'NOT_FOUND') {
                    sendError(ws, 'INVALID_DATA', 'Monstruo no encontrado');
                } else if (result.error === 'FORBIDDEN') {
                    sendError(ws, 'FORBIDDEN', 'Solo el autor puede editar este monstruo');
                } else if (result.error === 'DUPLICATE') {
                    sendError(ws, 'INVALID_DATA', 'Ya existe otro monstruo con ese nombre y nivel');
                } else {
                    sendJson(ws, { type: 'CATALOG_UPDATE_SUCCESS', monster: result.monster });
                }
            })
            .catch(err => {
                logger.error("Update monster error:", err);
                sendError(ws, "SERVER_ERROR", "Error al actualizar monstruo");
            });
    }

    function handleCatalogDelete(ws, message) {
        if (!ws.userId) {
            sendError(ws, "UNAUTHORIZED", "Debes iniciar sesión para eliminar monstruos");
            return;
        }
        const { monsterId } = message;
        if (typeof monsterId !== 'string' || !monsterId.trim()) {
            sendError(ws, "INVALID_DATA", "ID de monstruo inválido");
            return;
        }
        db.deleteMonster(monsterId.trim(), ws.userId)
            .then(result => {
                if (result.error === 'NOT_FOUND') {
                    sendError(ws, 'INVALID_DATA', 'Monstruo no encontrado');
                } else if (result.error === 'FORBIDDEN') {
                    sendError(ws, 'FORBIDDEN', 'Solo el autor puede eliminar este monstruo');
                } else {
                    sendJson(ws, { type: 'CATALOG_DELETE_SUCCESS', monsterId: result.id });
                }
            })
            .catch(err => {
                logger.error("Delete monster error:", err);
                sendError(ws, "SERVER_ERROR", "Error al eliminar monstruo");
            });
    }

    return {
        handleCatalogSearch,
        handleCatalogAdd,
        handleCatalogUpdate,
        handleCatalogDelete
    };
}

module.exports = {
    createCatalogManager
};
