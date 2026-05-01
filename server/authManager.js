const crypto = require('crypto');

function createAuthManager({ db, logger, sendError, jwtSecret, jwtExpirySeconds }) {
    const authRateLimits = new Map(); // IP -> { attempts, lastAttempt }
    const RATE_LIMIT_MAX_ATTEMPTS = 5;
    const RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000;

    function signToken(payload) {
        const header = { alg: 'HS256', typ: 'JWT' };
        const now = Math.floor(Date.now() / 1000);
        const fullPayload = { ...payload, iat: now, exp: now + jwtExpirySeconds };
        const encodedHeader = Buffer.from(JSON.stringify(header)).toString('base64url');
        const encodedPayload = Buffer.from(JSON.stringify(fullPayload)).toString('base64url');
        const signature = crypto
            .createHmac('sha256', jwtSecret)
            .update(`${encodedHeader}.${encodedPayload}`)
            .digest('base64url');
        return `${encodedHeader}.${encodedPayload}.${signature}`;
    }

    function verifyToken(token) {
        try {
            const [encodedHeader, encodedPayload, providedSignature] = token.split('.');
            if (!encodedHeader || !encodedPayload || !providedSignature) {
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
            if (payload.exp && Math.floor(Date.now() / 1000) > payload.exp) {
                logger.info('Token expired');
                return null;
            }

            return payload;
        } catch (error) {
            return null;
        }
    }

    function isRateLimited(ip) {
        const record = authRateLimits.get(ip);
        if (!record) {
            return false;
        }

        if (Date.now() - record.lastAttempt > RATE_LIMIT_WINDOW_MS) {
            authRateLimits.delete(ip);
            return false;
        }

        return record.attempts >= RATE_LIMIT_MAX_ATTEMPTS;
    }

    function recordAuthAttempt(ip, success) {
        if (success) {
            authRateLimits.delete(ip);
            return;
        }

        const record = authRateLimits.get(ip) || { attempts: 0, lastAttempt: 0 };
        record.attempts += 1;
        record.lastAttempt = Date.now();
        authRateLimits.set(ip, record);
    }

    function isValidInput(text, maxLength) {
        return text && typeof text === 'string' && text.length > 0 && text.length <= maxLength;
    }

    function buildAuthSuccess(user, token) {
        return {
            type: 'AUTH_SUCCESS',
            user: {
                id: user.id,
                username: user.username,
                email: user.email,
                avatarId: user.avatarId
            },
            token
        };
    }

    function handleRegister(ws, message) {
        let { username, email, password, avatarId } = message;

        if (!isValidInput(username, 20) || !isValidInput(password, 100)) {
            sendError(ws, 'INVALID_DATA', 'Invalid or too long username/password');
            return;
        }

        if (!email) {
            const sanitizedUsername = username.toLowerCase().replace(/[^a-z0-9]/g, '') || 'user';
            email = `${sanitizedUsername}@munchkin.local`;
        } else if (!isValidInput(email, 100)) {
            sendError(ws, 'INVALID_DATA', 'Email too long');
            return;
        }

        db.createUser(username, email, password, avatarId || 0)
            .then(user => {
                logger.info(`User registered: ${user.username} (${user.id})`);
                const token = signToken({ id: user.id, username: user.username, email: user.email });
                ws.userId = user.id;
                ws.send(JSON.stringify(buildAuthSuccess(user, token)));
            })
            .catch(err => {
                logger.error('Register failed:', err.message);
                if (err.message === 'EMAIL_EXISTS') {
                    sendError(ws, 'EMAIL_EXISTS', 'El email ya esta registrado');
                } else {
                    sendError(ws, 'REGISTER_FAILED', 'Error al registrar usuario');
                }
            });
    }

    function handleLogin(ws, message) {
        const clientIp = ws.clientIp || 'unknown';
        if (isRateLimited(clientIp)) {
            sendError(ws, 'RATE_LIMITED', 'Demasiados intentos. Espera 15 minutos.');
            return;
        }

        const { email, password } = message;
        if (!isValidInput(email, 100) || !isValidInput(password, 100)) {
            sendError(ws, 'INVALID_DATA', 'Invalid input format');
            return;
        }

        db.verifyUser(email, password)
            .then(user => {
                if (!user) {
                    recordAuthAttempt(clientIp, false);
                    logger.info(`Login failed for ${email}`);
                    sendError(ws, 'AUTH_FAILED', 'Email o contrasena incorrectos');
                    return;
                }

                recordAuthAttempt(clientIp, true);
                logger.info(`User logged in: ${user.username}`);

                const token = signToken({ id: user.id, username: user.username, email: user.email });
                ws.userId = user.id;
                ws.send(JSON.stringify(buildAuthSuccess(user, token)));
            })
            .catch(err => {
                logger.error('Login error:', err);
                sendError(ws, 'LOGIN_ERROR', 'Error interno al iniciar sesion');
            });
    }

    function handleLoginWithToken(ws, message) {
        const { token } = message;
        if (!token) {
            sendError(ws, 'AUTH_FAILED', 'Missing token');
            return;
        }

        const payload = verifyToken(token);
        if (!payload) {
            logger.info('Invalid or expired token presented');
            sendError(ws, 'AUTH_FAILED', 'Session expired');
            return;
        }

        db.getUserById(payload.id)
            .then(user => {
                if (!user) {
                    sendError(ws, 'AUTH_FAILED', 'User not found');
                    return;
                }

                logger.info(`User logged in via token: ${user.username}`);
                ws.userId = user.id;
                ws.send(JSON.stringify(buildAuthSuccess(user, token)));
            })
            .catch(err => {
                logger.error('Token login error:', err);
                sendError(ws, 'LOGIN_ERROR', 'Internal error');
            });
    }

    function handleUpdateProfile(ws, message) {
        const { userId, username, password } = message;

        if (!ws.userId || ws.userId !== userId) {
            logger.warn(`Unauthorized profile update attempt. Session: ${ws.userId}, Target: ${userId}`);
            sendError(ws, 'FORBIDDEN', 'No tienes permiso para editar este perfil');
            return;
        }

        if (username && !isValidInput(username, 20)) {
            sendError(ws, 'INVALID_DATA', 'Username too long');
            return;
        }

        if (password && !isValidInput(password, 100)) {
            sendError(ws, 'INVALID_DATA', 'Password too long');
            return;
        }

        db.updateUser(userId, username, password)
            .then(user => {
                logger.info(`Profile updated for user: ${user.username}`);
                ws.send(JSON.stringify({
                    type: 'PROFILE_UPDATED',
                    user: {
                        id: user.id,
                        username: user.username,
                        email: user.email,
                        avatarId: user.avatarId
                    }
                }));
            })
            .catch(err => {
                logger.error('Update profile error:', err);
                sendError(ws, 'UPDATE_FAILED', 'Error al actualizar perfil');
            });
    }

    return {
        handleRegister,
        handleLogin,
        handleLoginWithToken,
        handleUpdateProfile
    };
}

module.exports = { createAuthManager };
