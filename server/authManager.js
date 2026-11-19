const crypto = require('crypto');
const bcrypt = require('bcryptjs');

const MIN_JWT_SECRET_LENGTH = 32;
const MIN_PASSWORD_LENGTH = 8;
const MAX_PASSWORD_BYTES = 72;
const MAX_RATE_LIMIT_ENTRIES = 10_000;

function createAuthManager({ db, logger, sendError, safeSend, jwtSecret, jwtExpirySeconds, onAccountDeleted = undefined }) {
    if (typeof jwtSecret !== 'string' || jwtSecret.length < MIN_JWT_SECRET_LENGTH) {
        throw new Error(`JWT_SECRET must be at least ${MIN_JWT_SECRET_LENGTH} characters long`);
    }

    const sendJson = typeof safeSend === 'function'
        ? safeSend
        : (ws, payload) => {
            if (ws && ws.readyState === 1) {
                ws.send(JSON.stringify(payload));
            }
        };

    const authRateLimits = new Map(); // IP -> { attempts, lastAttempt }
    const inFlightAuthChecks = new Map(); // IP -> count
    const RATE_LIMIT_MAX_ATTEMPTS = 5;
    const RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000;

    // Registration gets its own, stricter budget: login attempts are cheap to
    // retry legitimately, creating accounts is not.
    const registerRateLimits = new Map(); // IP -> { count, windowStart }
    // Configurable so the integration suite can register many accounts from one IP
    // without either disabling the limit in production or asserting against it here.
    const REGISTER_MAX_PER_WINDOW = Number(process.env.MUNCHKIN_REGISTER_LIMIT) || 3;
    const REGISTER_WINDOW_MS = 60 * 60 * 1000;

    /**
     * Inserts/updates a rate-limit entry in O(1) LRU order and evicts the oldest
     * entry when the bounded map exceeds MAX_RATE_LIMIT_ENTRIES.
     * @template V
     * @param {Map<string, V>} map
     * @param {string} key
     * @param {V} entry
     */
    function setRateLimitEntry(map, key, entry) {
        map.delete(key);
        map.set(key, entry);
        while (map.size > MAX_RATE_LIMIT_ENTRIES) {
            const oldestKey = map.keys().next().value;
            if (oldestKey === undefined) break;
            map.delete(oldestKey);
        }
    }

    function isRegisterRateLimited(ip) {
        const record = registerRateLimits.get(ip);
        if (!record) return false;
        if (Date.now() - record.windowStart > REGISTER_WINDOW_MS) {
            registerRateLimits.delete(ip);
            return false;
        }
        return record.count >= REGISTER_MAX_PER_WINDOW;
    }

    function recordRegisterAttempt(ip) {
        const record = registerRateLimits.get(ip);
        const now = Date.now();
        if (!record || now - record.windowStart > REGISTER_WINDOW_MS) {
            setRateLimitEntry(registerRateLimits, ip, { count: 1, windowStart: now });
        } else {
            setRateLimitEntry(registerRateLimits, ip, { count: record.count + 1, windowStart: record.windowStart });
        }
    }

    // Must mirror the Kotlin Gender enum in core/Models.kt (M/F/NA).
    const VALID_ACCOUNT_GENDERS = new Set(['M', 'F', 'NA']);

    function normalizeAccountGender(value) {
        if (VALID_ACCOUNT_GENDERS.has(value)) return value;
        if (value === 'MALE') return 'M';
        if (value === 'FEMALE') return 'F';
        return 'M';
    }

    // Deliberately permissive: enough to catch obvious typos without rejecting
    // valid but unusual addresses.
    function isPlausibleEmail(value) {
        return typeof value === 'string' && /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(value);
    }

    function signToken(payload) {
        const header = { alg: 'HS256', typ: 'JWT' };
        const now = Math.floor(Date.now() / 1000);
        const fullPayload = {
            ...payload,
            tv: payload.tv || 0,
            iat: now,
            exp: now + jwtExpirySeconds
        };
        const encodedHeader = Buffer.from(JSON.stringify(header)).toString('base64url');
        const encodedPayload = Buffer.from(JSON.stringify(fullPayload)).toString('base64url');
        const signature = crypto
            .createHmac('sha256', jwtSecret)
            .update(`${encodedHeader}.${encodedPayload}`)
            .digest('base64url');
        return `${encodedHeader}.${encodedPayload}.${signature}`;
    }

    function verifyToken(token, ip = 'unknown') {
        try {
            if (typeof token !== 'string') return null;
            const parts = token.split('.');
            if (parts.length !== 3) return null;
            const [encodedHeader, encodedPayload, providedSignature] = parts;
            if (!encodedHeader || !encodedPayload || !providedSignature) {
                return null;
            }

            const header = JSON.parse(Buffer.from(encodedHeader, 'base64url').toString());
            if (!header || typeof header !== 'object' || header.alg !== 'HS256') {
                return null;
            }

            const expectedSignature = crypto
                .createHmac('sha256', jwtSecret)
                .update(`${encodedHeader}.${encodedPayload}`)
                .digest('base64url');

            const expectedBuffer = Buffer.from(expectedSignature);
            const providedBuffer = Buffer.from(providedSignature);
            if (
                expectedBuffer.length !== providedBuffer.length ||
                !crypto.timingSafeEqual(expectedBuffer, providedBuffer)
            ) {
                return null;
            }

            const payload = JSON.parse(Buffer.from(encodedPayload, 'base64url').toString());
            if (!payload || typeof payload !== 'object' || !Number.isFinite(payload.exp)) {
                return null;
            }

            if (Math.floor(Date.now() / 1000) > payload.exp) {
                logger.warn(`[IP: ${ip}] Token expired`);
                return null;
            }

            return payload;
        } catch (error) {
            return null;
        }
    }

    async function verifySessionToken(token, ip = 'unknown') {
        const payload = verifyToken(token, ip);
        if (!payload || typeof payload.id !== 'string' || !payload.id) {
            return null;
        }
        const user = await db.getUserById(payload.id);
        if (!user) return null;
        if ((user.token_version || 0) !== (payload.tv || 0)) {
            return null;
        }
        return { user, payload };
    }

    function isRateLimited(ip) {
        const record = authRateLimits.get(ip);
        let attempts = 0;
        if (record) {
            if (Date.now() - record.lastAttempt > RATE_LIMIT_WINDOW_MS) {
                authRateLimits.delete(ip);
            } else {
                attempts = record.attempts;
            }
        }
        const pending = inFlightAuthChecks.get(ip) || 0;
        return (attempts + pending) >= RATE_LIMIT_MAX_ATTEMPTS;
    }

    function beginAuthCheck(ip) {
        inFlightAuthChecks.set(ip, (inFlightAuthChecks.get(ip) || 0) + 1);
    }

    function endAuthCheck(ip) {
        const next = (inFlightAuthChecks.get(ip) || 1) - 1;
        if (next <= 0) inFlightAuthChecks.delete(ip);
        else inFlightAuthChecks.set(ip, next);
    }

    function recordAuthAttempt(ip, success) {
        if (success) {
            authRateLimits.delete(ip);
            return;
        }

        const existing = authRateLimits.get(ip) || { attempts: 0, lastAttempt: 0 };
        setRateLimitEntry(authRateLimits, ip, {
            attempts: existing.attempts + 1,
            lastAttempt: Date.now()
        });
    }

    function isValidInput(text, maxLength) {
        return typeof text === 'string' && text.length > 0 && text.length <= maxLength;
    }

    function validatePasswordPolicy(password, username) {
        if (typeof password !== 'string' || password.length === 0) {
            return 'Contraseña inválida';
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            return `La contraseña debe tener al menos ${MIN_PASSWORD_LENGTH} caracteres`;
        }
        if (Buffer.byteLength(password, 'utf8') > MAX_PASSWORD_BYTES) {
            return `La contraseña no puede superar los ${MAX_PASSWORD_BYTES} bytes`;
        }
        if (typeof username === 'string' && password.trim().toLowerCase() === username.trim().toLowerCase()) {
            return 'La contraseña no puede coincidir con el nombre de usuario';
        }
        return null;
    }

    function buildAuthSuccess(user, token) {
        return {
            type: 'AUTH_SUCCESS',
            user: {
                id: user.id,
                username: user.username,
                email: user.email,
                avatarId: user.avatarId !== undefined ? user.avatarId : (user.avatar_id || 0),
                gender: normalizeAccountGender(user.gender)
            },
            token
        };
    }

    async function handleRegister(ws, message) {
        let { username, email, password, avatarId, gender } = message;

        const clientIp = ws.clientIp || 'unknown';
        if (isRegisterRateLimited(clientIp)) {
            logger.warn(`[IP: ${clientIp}] Registration rate limit exceeded`);
            sendError(ws, 'RATE_LIMITED', 'Demasiadas cuentas creadas. Inténtalo más tarde.');
            return;
        }

        if (!isValidInput(username, 20) || !username.trim()) {
            sendError(ws, 'INVALID_DATA', 'Nombre de usuario inválido');
            return;
        }
        username = username.trim();

        const passwordErr = validatePasswordPolicy(password, username);
        if (passwordErr) {
            sendError(ws, 'INVALID_DATA', passwordErr);
            return;
        }

        if (!email) {
            const sanitizedUsername = username.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 20) || 'user';
            email = `${sanitizedUsername}.${crypto.randomUUID().slice(0, 8)}@munchkin.local`;
        } else if (!isValidInput(email, 100) || !isPlausibleEmail(email.trim())) {
            sendError(ws, 'INVALID_DATA', 'Email inválido');
            return;
        } else {
            email = email.trim().toLowerCase();
        }

        const safeAvatarId = Math.max(0, Math.min(100, Math.round(Number(avatarId) || 0)));
        const safeGender = normalizeAccountGender(gender);
        recordRegisterAttempt(clientIp);

        try {
            const user = await db.createUser(username, email, password, safeAvatarId, safeGender);
            logger.info(`User registered: ${user.username} (${user.id})`);
            const token = signToken({
                id: user.id,
                username: user.username,
                email: user.email,
                tv: user.token_version || 0
            });
            ws.userId = user.id;
            sendJson(ws, buildAuthSuccess(user, token));
        } catch (err) {
            logger.error('Register failed:', err.message);
            if (err.message === 'EMAIL_EXISTS') {
                sendError(ws, 'EMAIL_EXISTS', 'El email ya esta registrado');
            } else if (err.message === 'USERNAME_EXISTS') {
                sendError(ws, 'USERNAME_EXISTS', 'Ese nombre de usuario ya está en uso');
            } else {
                sendError(ws, 'REGISTER_FAILED', 'Error al registrar usuario');
            }
        }
    }

    async function handleLogin(ws, message) {
        const clientIp = ws.clientIp || 'unknown';
        if (isRateLimited(clientIp)) {
            sendError(ws, 'RATE_LIMITED', 'Demasiados intentos. Espera 15 minutos.');
            return;
        }

        const { email, password } = message;
        if (
            !isValidInput(email, 100) ||
            !isValidInput(password, 100) ||
            Buffer.byteLength(password, 'utf8') > MAX_PASSWORD_BYTES
        ) {
            sendError(ws, 'INVALID_DATA', 'Invalid input format');
            return;
        }

        const normalizedIdentifier = email.trim().includes('@') ? email.trim().toLowerCase() : email.trim();
        beginAuthCheck(clientIp);
        try {
            const user = await db.verifyUser(normalizedIdentifier, password);
            if (!user) {
                recordAuthAttempt(clientIp, false);
                logger.warn(`[IP: ${clientIp}] Login failed`);
                sendError(ws, 'AUTH_FAILED', 'Email o contrasena incorrectos');
                return;
            }

            recordAuthAttempt(clientIp, true);
            logger.info(`User logged in: ${user.username}`);

            const token = signToken({
                id: user.id,
                username: user.username,
                email: user.email,
                tv: user.token_version || 0
            });
            ws.userId = user.id;
            sendJson(ws, buildAuthSuccess(user, token));
        } catch (err) {
            logger.error('Login error:', err);
            sendError(ws, 'LOGIN_ERROR', 'Error interno al iniciar sesion');
        } finally {
            endAuthCheck(clientIp);
        }
    }

    async function handleLoginWithToken(ws, message) {
        const clientIp = ws.clientIp || 'unknown';
        if (isRateLimited(clientIp)) {
            sendError(ws, 'RATE_LIMITED', 'Demasiados intentos. Espera 15 minutos.');
            return;
        }

        const { token } = message;
        if (!token || typeof token !== 'string') {
            recordAuthAttempt(clientIp, false);
            sendError(ws, 'AUTH_FAILED', 'Missing token');
            return;
        }

        try {
            const session = await verifySessionToken(token, clientIp);
            if (!session) {
                recordAuthAttempt(clientIp, false);
                logger.warn(`[IP: ${clientIp}] Invalid or expired token presented`);
                sendError(ws, 'AUTH_FAILED', 'Session expired');
                return;
            }

            const { user } = session;
            recordAuthAttempt(clientIp, true);
            logger.info(`User logged in via token: ${user.username}`);
            ws.userId = user.id;
            const refreshedToken = signToken({
                id: user.id,
                username: user.username,
                email: user.email,
                tv: user.token_version || 0
            });
            sendJson(ws, buildAuthSuccess(user, refreshedToken));
        } catch (err) {
            logger.error('Token login error:', err);
            sendError(ws, 'LOGIN_ERROR', 'Internal error');
        }
    }

    async function handleUpdateProfile(ws, message) {
        let { userId, username, password, currentPassword, avatarId, gender } = message;
        const clientIp = ws.clientIp || 'unknown';

        if (!ws.userId || ws.userId !== userId) {
            logger.warn(`Unauthorized profile update attempt. Session: ${ws.userId}, Target: ${userId}`);
            sendError(ws, 'FORBIDDEN', 'No tienes permiso para editar este perfil');
            return;
        }

        if (username !== undefined && username !== null) {
            if (!isValidInput(username, 20) || !username.trim()) {
                sendError(ws, 'INVALID_DATA', 'Nombre de usuario inválido');
                return;
            }
            username = username.trim();
        }

        const hasNewPassword = typeof password === 'string' && password.length > 0;
        if (hasNewPassword) {
            if (isRateLimited(clientIp)) {
                sendError(ws, 'RATE_LIMITED', 'Demasiados intentos. Espera 15 minutos.');
                return;
            }
            const policyErr = validatePasswordPolicy(password, username || '');
            if (policyErr) {
                sendError(ws, 'INVALID_DATA', policyErr);
                return;
            }
            if (
                typeof currentPassword !== 'string' ||
                currentPassword.length === 0 ||
                Buffer.byteLength(currentPassword, 'utf8') > MAX_PASSWORD_BYTES
            ) {
                sendError(ws, 'INVALID_DATA', 'Se requiere la contraseña actual');
                return;
            }
        }

        // avatarId 0 is a valid slot, so absence must be tested explicitly rather
        // than by truthiness.
        let safeAvatarId = null;
        if (avatarId !== undefined && avatarId !== null) {
            const n = Math.round(Number(avatarId));
            if (!Number.isFinite(n) || n < 0 || n > 100) {
                sendError(ws, 'INVALID_DATA', 'Avatar inválido');
                return;
            }
            safeAvatarId = n;
        }

        let safeGender = null;
        if (gender !== undefined && gender !== null) {
            if (!VALID_ACCOUNT_GENDERS.has(gender)) {
                sendError(ws, 'INVALID_DATA', 'Género inválido');
                return;
            }
            safeGender = gender;
        }

        if (!username && !hasNewPassword && safeAvatarId === null && safeGender === null) {
            sendError(ws, 'INVALID_DATA', 'No hay cambios que guardar');
            return;
        }

        if (hasNewPassword) beginAuthCheck(clientIp);
        try {
            if (hasNewPassword) {
                const existingUser = await db.getUserById(userId);
                if (!existingUser) {
                    sendError(ws, 'USER_NOT_FOUND', 'Usuario no encontrado');
                    return;
                }
                const targetUsername = username || existingUser.username;
                const policyErr = validatePasswordPolicy(password, targetUsername);
                if (policyErr) {
                    sendError(ws, 'INVALID_DATA', policyErr);
                    return;
                }
                const matches = await bcrypt.compare(currentPassword, existingUser.password_hash);
                if (!matches) {
                    recordAuthAttempt(clientIp, false);
                    sendError(ws, 'AUTH_FAILED', 'La contraseña actual es incorrecta');
                    return;
                }
                recordAuthAttempt(clientIp, true);
            }

            const user = await db.updateUser(userId, username, hasNewPassword ? password : null, safeAvatarId, safeGender);
            let freshToken = null;
            if (hasNewPassword) {
                freshToken = signToken({
                    id: user.id,
                    username: user.username,
                    email: user.email,
                    tv: user.token_version || 0
                });
            }

            logger.info(`Profile updated for user: ${user.username}`);
            const response = {
                type: 'PROFILE_UPDATED',
                user: {
                    id: user.id,
                    username: user.username,
                    email: user.email,
                    avatarId: user.avatarId !== undefined ? user.avatarId : (user.avatar_id || 0),
                    gender: normalizeAccountGender(user.gender)
                }
            };
            if (freshToken) {
                response.token = freshToken;
            }
            sendJson(ws, response);
        } catch (err) {
            logger.error('Update profile error:', err);
            if (err && err.message === 'USERNAME_EXISTS') {
                sendError(ws, 'USERNAME_EXISTS', 'Ese nombre de usuario ya está en uso');
            } else {
                sendError(ws, 'UPDATE_FAILED', 'Error al actualizar perfil');
            }
        } finally {
            if (hasNewPassword) endAuthCheck(clientIp);
        }
    }

    async function handleDeleteAccount(ws, message) {
        const clientIp = ws.clientIp || 'unknown';
        if (!ws.userId) {
            sendError(ws, 'UNAUTHORIZED', 'Authentication required');
            return;
        }
        if (isRateLimited(clientIp)) {
            sendError(ws, 'RATE_LIMITED', 'Demasiados intentos. Espera 15 minutos.');
            return;
        }

        const { password } = message;
        if (
            typeof password !== 'string' ||
            password.length === 0 ||
            Buffer.byteLength(password, 'utf8') > MAX_PASSWORD_BYTES
        ) {
            sendError(ws, 'INVALID_DATA', 'Se requiere la contraseña');
            return;
        }

        const targetUserId = ws.userId;
        beginAuthCheck(clientIp);
        try {
            const existingUser = await db.getUserById(targetUserId);
            if (!existingUser) {
                sendError(ws, 'USER_NOT_FOUND', 'Usuario no encontrado');
                return;
            }

            const matches = await bcrypt.compare(password, existingUser.password_hash);
            if (!matches) {
                recordAuthAttempt(clientIp, false);
                sendError(ws, 'AUTH_FAILED', 'Contraseña incorrecta');
                return;
            }
            recordAuthAttempt(clientIp, true);

            const deleted = await db.deleteAccount(targetUserId);
            if (!deleted) {
                sendError(ws, 'USER_NOT_FOUND', 'Usuario no encontrado');
                return;
            }

            logger.info(`Account deleted: ${targetUserId}`);
            ws.userId = null;
            if (typeof onAccountDeleted === 'function') {
                onAccountDeleted(targetUserId);
            }
            sendJson(ws, { type: 'ACCOUNT_DELETED' });
        } catch (err) {
            logger.error('Delete account error:', err);
            sendError(ws, 'DELETE_FAILED', 'Error al eliminar la cuenta');
        } finally {
            endAuthCheck(clientIp);
        }
    }

    return {
        handleRegister,
        handleLogin,
        handleLoginWithToken,
        handleUpdateProfile,
        handleDeleteAccount,
        verifyToken,
        verifySessionToken
    };
}

module.exports = { createAuthManager };
