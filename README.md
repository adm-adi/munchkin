# Munchkin Mesa Tracker

Aplicacion multijugador en tiempo real para partidas de Munchkin, compuesta por un cliente nativo Android (Kotlin + Jetpack Compose), una Web PWA instalable (ES Modules sin paso de build) y un servidor WebSocket/HTTP autoritativo en Node.js 20+ con SQLite.

---

## Arquitectura actual

- **Servidor autoritativo (`server/`)**: Node.js 20+ (`createAppServer` en [server/server.js](server/server.js)) sobre HTTP(S) + WebSockets (`ws`), persistencia SQLite en modo WAL con cola serie `withWriteLock`, busqueda de monstruos FTS5 (`monsters_fts`), revocacion de JWT mediante `token_version` y modulos por dominio (`authManager.js`, `catalogManager.js`, `combatManager.js`, `gameAdminManager.js`, `historyManager.js`, `turnManager.js`, `validation.js`, `staticFiles.js`, `logger.js`).
- **Cliente Web PWA (`server/public/`)**: SPA sin paso de compilacion servida por el propio backend con cache en memoria (`ETag` + Brotli/gzip), Service Worker offline (`sw.js`), manifiesto (`manifest.webmanifest`) y CSP estricto (cero estilos en linea). En iPhone/iPad: abrir la URL del servidor en Safari y usar **"Anadir a pantalla de inicio"**.
- **Cliente Android (`app/`)**: Kotlin + Jetpack Compose + Material 3 con inyeccion de dependencias explicita (`MunchkinApp.container: AppContainer` en [AppContainer.kt](app/src/main/java/com/munchkin/app/di/AppContainer.kt)), ViewModels divididos (`AuthViewModel`, `LobbyViewModel`, `GameSessionViewModel`, `CatalogViewModel`), navegacion Compose `NavHost` de 14 rutas en [MainActivity.kt](app/src/main/java/com/munchkin/app/MainActivity.kt), cliente WebSocket Ktor CIO (`GameClient.kt` `Closeable` + `lifecycleMutex` + `nudge()`), `EncryptedSharedPreferences` con recuperacion de KeyStore (`SessionManager.kt`) y autoactualizador seguro desde GitHub Releases (`UpdateChecker.kt`).
- **Sistema visual unificado (`DESIGN.md`)**: Paleta **Munchkin Dungeon Edition** compartida entre Android y Web PWA (superficies piedra oscura `#0F0A08`/`#1A110D`/`#271A14`, carmesi `#CC2020`, oro `#D4960A`, brasa `#E85D00`), contraste WCAG AA y tabla canonica de 8 avatares (`0..7`).

El flujo es siempre unidireccional con servidor autoritativo:
`UI -> ViewModel / app.mjs -> WebSocket -> server.js -> STATE_SNAPSHOT / EVENT_BROADCAST -> cliente`.

---

## Endpoint universal y codigos QR

- **WebSocket de produccion**: `wss://munchking-sirpepo.duckdns.org:8765` (configurado en [ServerConfig.kt](app/src/main/java/com/munchkin/app/network/ServerConfig.kt) via `BuildConfig`).
- **URL universal de QR y deep link**: `https://munchking-sirpepo.duckdns.org/#/join/<CODE>`.
  - En Android, [JoinCodeParser.kt](app/src/main/java/com/munchkin/app/network/JoinCodeParser.kt) extrae unicamente el `joinCode` (ignorando cualquier `wsUrl` heredado) y conecta siempre al `ServerConfig.WS_URL` configurado.

---

## Caracteristicas principales

- Partidas de 2 a 6 jugadores (modo estandar nivel 10 o Super Munchkin nivel 20).
- Estado de juego, combates, desempates y temporizador de turno autoritativos en servidor.
- Reconexion segura mediante rotacion de `reconnectToken` (SHA-256) y respaldo en Room (`MunchkinDatabase`).
- Catalogo global de monstruos con busqueda FTS5 y creacion autenticada.
- Cuentas con registro/login, actualizacion de perfil, eliminacion de cuenta (`DELETE_ACCOUNT`), historial privado (protegido contra IDOR) y leaderboard global.
- Localizacion completa en **Espanol (`es`)**, **Ingles (`en`)** y **Frances (`fr`)** verificada en CI con `:app:lintDebug`.

---

## Compilacion, pruebas y verificacion

### Servidor (`server/`)

```bash
npm --prefix server ci
npm --prefix server audit --audit-level=high
npm --prefix server run check
npm --prefix server test
```

Arranque local (requiere `JWT_SECRET` de al menos 32 caracteres; ver [server/.env.example](server/.env.example)):

```bash
cd server
JWT_SECRET="replace-with-at-least-32-character-random-secret" npm start
# Web PWA disponible en http://localhost:8765
```

### Cliente Android (`app/`)

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:minifyReleaseWithR8 :app:assembleDebug
```

- APK debug: `app/build/outputs/apk/debug/app-debug.apk`
- APK release firmado (requiere credenciales en `local.properties` o variables de entorno): `./gradlew :app:assembleRelease`
- Apuntar a un servidor local/staging sin editar codigo Kotlin:

```bash
./gradlew :app:assembleDebug -PmunchkinScheme=ws -PmunchkinHost=10.0.2.2 -PmunchkinPort=8765
```

### Sincronizacion de versiones y sonda de seguridad (`scripts/`)

```bash
python scripts/bump_version.py --check
MUNCHKIN_SERVER_URL=ws://127.0.0.1:8765 python scripts/security_verify.py
```

En Windows PowerShell:

```powershell
$env:MUNCHKIN_SERVER_URL = "ws://127.0.0.1:8765"; python scripts/security_verify.py
```

---

## Despliegue (Docker y VPS hardened)

- **Docker**: [Dockerfile](Dockerfile) (`node:20-bookworm-slim`, usuario no privilegiado `node`, `HEALTHCHECK` sobre `/health`) y [compose.yml](compose.yml) (sistema de archivos en solo lectura, `no-new-privileges:true`, volumenes persistentes `munchkin-data` y `munchkin-logs`).
- **VPS Debian/Ubuntu (`systemd`)**: [server/setup.sh](server/setup.sh) crea el usuario de sistema `munchkin`, genera `/etc/munchkin/server.env` (`0600`), almacena SQLite en `/var/lib/munchkin/munchkin.db` y configura la unidad `systemd` endurecida. Copias de seguridad en caliente con [server/scripts/backup_db.sh](server/scripts/backup_db.sh). Ver detalles operativos en [server/README.md](server/README.md).
- **CI/CD y releases**: `.github/workflows/ci.yml`, `.github/workflows/release.yml` (publica APK + `.apk.sha256`), `.github/dependabot.yml`, `scripts/verify_releases_secrets.py` y `deploy_updates.ps1`.

---

## Estructura del proyecto

```text
munchkin/
├── app/src/main/java/com/munchkin/app/
│   ├── MunchkinApp.kt                # Application y AppContainer DI
│   ├── MainActivity.kt               # Compose NavHost (14 rutas) y deep links
│   ├── di/AppContainer.kt            # Contenedor DI y GameSessionCoordinator
│   ├── core/                         # Logica pura (Models, Events, Combat, CombatCalculator, GameEngine, Abilities, GameLogEntry)
│   ├── data/                         # SessionManager (EncryptedSharedPreferences), Room (MunchkinDatabase), Prefs
│   ├── network/                      # GameClient (Ktor CIO), Protocol, ServerConfig, JoinCodeParser
│   ├── update/UpdateChecker.kt       # Autoactualizador HTTPS + .sha256 + certificado + anti-rollback
│   ├── util/LocaleManager.kt         # Selector ES / EN / FR
│   ├── viewmodel/                    # Auth/Lobby/GameSession/Catalog ViewModels, SessionGateways, GameConnectionSupport, UiModels
│   └── ui/                           # theme/ (DESIGN.md), components/ (15), screens/ (15)
├── server/
│   ├── server.js & *Manager.js       # Servidor WebSocket/HTTP modular y managers de dominio
│   ├── db.js                         # SQLite WAL + withWriteLock + FTS5 + token_version
│   ├── validation.js & staticFiles.js & logger.js & jsconfig.json
│   ├── .env.example & setup.sh & scripts/backup_db.sh
│   ├── *.test.js & webclient.test.mjs & testServer.js
│   └── public/                       # Web PWA (index.html, sw.js, manifest.webmanifest, css/app.css, js/*.mjs)
├── scripts/                          # bump_version.py, security_verify.py, verify_releases_secrets.py
├── .github/                          # workflows/{ci,release}.yml, dependabot.yml
├── DESIGN.md                         # Especificacion visual Dungeon Edition
├── Dockerfile & compose.yml & .dockerignore
└── deploy_updates.ps1
```
