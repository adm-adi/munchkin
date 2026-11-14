# Munchkin Server & Web PWA (`server/`)

Servidor WebSocket y HTTP autoritativo en Node.js 20+ con persistencia SQLite (WAL + FTS5) y cliente Web PWA integrado (`server/public/`).

---

## Modulos principales

- `server.js`: Exporta `createAppServer(options)` e inicia el servidor HTTP(S) + WebSocket (`ws`, `maxPayload: 50 * 1024`, heartbeat ping/pong cada 25 s, validacion `Origin` en `verifyClient`, cabeceras `helmet` con CSP estricto, endpoints `/health` y `/api/monsters`, limites de tasa LRU acotados y apagado ordenado en `SIGTERM`/`SIGINT`).
- `db.js`: SQLite (`sqlite3`) en modo `WAL` (`synchronous = NORMAL`, `foreign_keys = ON`, `busy_timeout = 5000`), escrituras serializadas mediante `withWriteLock`, tabla virtual FTS5 `monsters_fts` para busquedas por prefijo, columna `users.token_version` para revocar JWTs y persistencia de `active_games` e historial.
- `authManager.js`: `bcryptjs` asincrono (max. 72 bytes UTF-8), JWT HS256 estricto (`alg === 'HS256'`, `exp` finito, comprobacion de `token_version`), `REGISTER`, `LOGIN`, `LOGIN_WITH_TOKEN`, `UPDATE_PROFILE` (exige `currentPassword` al cambiar contrasena) y `DELETE_ACCOUNT`.
- `catalogManager.js`, `combatManager.js`, `gameAdminManager.js`, `historyManager.js`, `turnManager.js`: Logica modular de catalogo (soporta 0 tesoros, omite `createdBy`), combate autoritativo, administracion de sala, historial protegido contra IDOR, ranking en camelCase y temporizadores de turno acotados.
- `validation.js`: Validadores y normalizadores puros sincronizados con `core/Models.kt` y `core/Combat.kt`.
- `staticFiles.js`: Cache en memoria de `server/public/` con `ETag` SHA-256, precompresion Brotli (`br`) y `gzip`, proteccion contra *path traversal* y dotfiles, y soporte `304 Not Modified`.
- `logger.js`: Logger Winston con rotacion diaria (`MUNCHKIN_LOG_DIR`, por defecto `server/logs`).
- `jsconfig.json`: Comprobacion estatica TypeScript (`checkJs: true`, `noEmit: true`) sobre `*.js`.
- `public/`: Web PWA (**Munchkin Dungeon Edition**, `index.html`, `sw.js`, `manifest.webmanifest`, `css/app.css`, `js/{protocol,combat,engine,net,session,i18n,ui,app}.mjs`).

---

## Variables de entorno

Plantilla completa en [server/.env.example](.env.example):

| Variable | Descripcion | Valor por defecto / Nota |
|---|---|---|
| `NODE_ENV` | Entorno de ejecucion | `production` |
| `PORT` | Puerto TCP HTTP/WebSocket | `8765` |
| `JWT_SECRET` | Clave de firma HS256 (**obligatoria**, min. 32 chars) | Generar con `openssl rand -hex 48` |
| `MUNCHKIN_DB_PATH` | Ruta de la base de datos SQLite | Produccion: `/var/lib/munchkin/munchkin.db` |
| `MUNCHKIN_LOG_DIR` | Directorio de logs rotativos | `server/logs` (VPS: `/opt/munchkin-server/logs`) |
| `MUNCHKIN_BACKUP_DIR` | Directorio de copias de seguridad | `/var/lib/munchkin/backups` |
| `MUNCHKIN_BACKUP_AGE_RECIPIENT` | Clave publica `age` para cifrar backups (opcional) | - |
| `MUNCHKIN_BACKUP_PASSPHRASE` | Frase AES-256-CBC PBKDF2 si no se usa `age` (opcional) | - |
| `MUNCHKIN_ALLOWED_ORIGINS` | Lista CSV de origenes WebSocket adicionales permitidos | `""` |
| `MUNCHKIN_TRUST_PROXY` | Usar `X-Forwarded-For` detras de proxy inverso (`1`/`true`) | `0` |
| `MUNCHKIN_TLS_KEY` / `MUNCHKIN_TLS_CERT` | Certificados TLS directos si no termina TLS el proxy | `key.pem` / `cert.pem` (falla rapido si se configuran y faltan) |
| `MUNCHKIN_CREATE_GAME_LIMIT` | Limite de creacion de salas por minuto e IP | `10` |
| `MUNCHKIN_MAX_ACTIVE_GAMES` | Maximo de salas activas simultaneas en memoria | `500` |
| `MUNCHKIN_REGISTER_LIMIT` | Limite de registros por IP (configurable en tests) | - |

---

## Despliegue

### Opcion A: Docker / Docker Compose (recomendado)

Desde la raiz del repositorio:

```bash
export JWT_SECRET="$(openssl rand -hex 48)"
docker compose up -d --build
```

[Dockerfile](../Dockerfile) usa `node:20-bookworm-slim` con usuario no privilegiado `node` y `HEALTHCHECK` sobre `GET /health`. [compose.yml](../compose.yml) monta volumenes persistentes (`munchkin-data` en `/var/lib/munchkin` y `munchkin-logs` en `/app/server/logs`) con sistema de archivos en solo lectura (`read_only: true`, `no-new-privileges:true`).

### Opcion B: VPS Debian / Ubuntu (`setup.sh` + `systemd`)

1. Copiar `server/` al host destino (por ejemplo `/opt/munchkin-server`) y ejecutar como `root`:

```bash
cd /opt/munchkin-server
bash setup.sh
```

`setup.sh` realiza:
- Instalacion de Node.js 20 mediante repositorio APT firmado con clave GPG de NodeSource.
- Creacion del usuario sin privilegios `munchkin` (`/usr/sbin/nologin`).
- Creacion de `/etc/munchkin/server.env` (`chmod 0600`) con `JWT_SECRET` aleatorio generado automaticamente y `MUNCHKIN_DB_PATH=/var/lib/munchkin/munchkin.db`.
- Instalacion de dependencias de produccion con `npm ci --omit=dev`.
- Configuracion de `/etc/systemd/system/munchkin.service` endurecida (`User=munchkin`, `ProtectSystem=strict`, `NoNewPrivileges=true`, `PrivateTmp=true`, `ReadWritePaths=/var/lib/munchkin /opt/munchkin-server/logs`).
- Apertura de reglas UFW (`443/tcp` para proxy TLS inverso Nginx/Caddy y `8765/tcp` si se usa WSS directo).

2. Gestion del servicio:

```bash
systemctl status munchkin
journalctl -u munchkin -f
systemctl restart munchkin
systemctl stop munchkin
```

### Backups SQLite en caliente

```bash
bash /opt/munchkin-server/scripts/backup_db.sh
```

Ejecuta `.backup` de SQLite con `umask 077` (`chmod 0600`), cifra opcionalmente con `age` (`MUNCHKIN_BACKUP_AGE_RECIPIENT`) o OpenSSL AES-256-CBC PBKDF2 (`MUNCHKIN_BACKUP_PASSPHRASE`) y elimina copias con mas de 7 dias.

---

## Endpoints y URLs de produccion

- **WebSocket**: `wss://munchking-sirpepo.duckdns.org:8765`
- **Web PWA y QR universal**: `https://munchking-sirpepo.duckdns.org` (`https://munchking-sirpepo.duckdns.org/#/join/<CODE>`)
- **HTTP Health**: `GET /health` -> `{ "status": "ok", "games": <n>, "uptime": <sec> }`
- **HTTP Catalogo**: `GET /api/monsters?q=...&expansion=...&limit=...&offset=...` (limitado a 60 req/min por IP)

---

## Validacion, auditoria y pruebas

```bash
npm --prefix server ci
npm --prefix server audit --audit-level=high
npm --prefix server run check
npm --prefix server test

# Sonda de seguridad contra instancia en ejecucion
MUNCHKIN_SERVER_URL=ws://127.0.0.1:8765 python scripts/security_verify.py
```
