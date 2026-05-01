function createCatalogManager({ db, sendError, logger }) {
    const catalogAddRateLimits = new Map();
    const CATALOG_ADD_MAX_PER_MINUTE = 10;
    const CATALOG_ADD_WINDOW_MS = 60 * 1000;

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
        const record = catalogAddRateLimits.get(userId);
        if (!record || Date.now() - record.windowStart > CATALOG_ADD_WINDOW_MS) {
            catalogAddRateLimits.set(userId, { count: 1, windowStart: Date.now() });
        } else {
            record.count++;
        }
    }

    function handleCatalogSearch(ws, message) {
        const { query } = message;

        if (!query || query.length < 2) {
            ws.send(JSON.stringify({ type: "CATALOG_SEARCH_RESULT", results: [] }));
            return;
        }

        if (query.length > 50) {
            sendError(ws, 'INVALID_DATA', 'Query too long');
            return;
        }

        db.searchMonsters(query)
            .then(results => {
                logger.info(`🔍 Search '${query}' returned ${results.length} monsters`);
                ws.send(JSON.stringify({
                    type: "CATALOG_SEARCH_RESULT",
                    results
                }));
            })
            .catch(err => {
                logger.error("Search error:", err);
                sendError(ws, "SEARCH_ERROR", "Error al buscar monstruos");
            });
    }

    function handleCatalogAdd(ws, message) {
        let { monster } = message;

        if (!ws.userId) {
            sendError(ws, "UNAUTHORIZED", "Debes iniciar sesion para anadir monstruos");
            return;
        }

        if (!monster || typeof monster.name !== 'string' || !monster.name.trim()) {
            sendError(ws, "INVALID_DATA", "Datos de monstruo invÃ¡lidos");
            return;
        }

        const sanitizedMonster = {
            name: monster.name.trim().slice(0, 80),
            level: Math.max(1, Math.min(20, Math.round(Number(monster.level) || 1))),
            modifier: Math.max(-20, Math.min(20, Math.round(Number(monster.modifier) || 0))),
            treasures: Math.max(0, Math.min(10, Math.round(Number(monster.treasures) || 1))),
            levels: Math.max(1, Math.min(5, Math.round(Number(monster.levels) || 1))),
            isUndead: monster.isUndead === true
        };
        monster = sanitizedMonster;

        const rateLimitKey = ws.userId;
        if (isCatalogAddRateLimited(rateLimitKey)) {
            sendError(ws, "RATE_LIMITED", "Demasiados monstruos aÃ±adidos. Espera un momento.");
            return;
        }
        recordCatalogAdd(rateLimitKey);

        db.addMonster(sanitizedMonster, ws.userId)
            .then(id => {
                logger.info(`🆕 Added monster: ${monster.name} (${id})`);
                ws.send(JSON.stringify({
                    type: "CATALOG_ADD_SUCCESS",
                    monster: { ...sanitizedMonster, id, createdBy: ws.userId }
                }));
            })
            .catch(err => {
                logger.error("Add monster error:", err);
                sendError(ws, "ADD_MONSTER_ERROR", "Error al guardar monstruo");
            });
    }

    return {
        handleCatalogSearch,
        handleCatalogAdd
    };
}

module.exports = {
    createCatalogManager
};
