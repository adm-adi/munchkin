# Munchkin Mesa Tracker — Plan de Mejoras (4 Fases)

## Objetivo
Implementar las mejoras funcionales, modos de mesa, analítica/catálogo y paridad Web PWA + QA con paridad completa **Servidor + Android + Web PWA (ES/EN/FR)** y retrocompatibilidad de protocolo/BD en cada fase.

---

## Fase 1 — Refactor Modular + Mecánicas Core de Munchkin
- [x] **1.1 Refactor base**: modularizar `server/public/js/app.mjs` (vistas separadas) y extraer diálogos/subcomponentes de `CombatScreen.kt` sin alterar comportamiento actual. → Verificar: `npm --prefix server run check`, `npm --prefix server test`, `./gradlew :app:testDebugUnitTest`.
- [x] **1.2 `conditionalModifiers` reales en catálogo y combate**: añadir `conditional_modifiers_json` en SQLite (`db.js`), poblar los modificadores de `monsters_seed.sql`, exponerlos en `CATALOG_SEARCH_RESULT` / `/api/monsters` y precargarlos automáticamente al añadir monstruos en Android y Web PWA. → Verificar: tests de combate con Amazona (+5 vs Hombres), Rey Tut (+4 vs Clérigo), Medusa (+4 vs Elfo).
- [x] **1.3 Razas y clases de expansiones (activables por sala)**: añadir `ORC`, `GNOME` (razas) y `BARD`, `RANGER` (clases) + ajuste de sala `expansionsEnabled` (`GameSettings`), habilidades en `Abilities.kt` / `i18n.mjs` / `strings.xml` (ES/EN/FR). → Verificar: sala base oculta expansiones; sala con expansiones las muestra y valida en servidor.
- [x] **1.4 Atajos de mesa + Maldiciones + Pacto de tesoros + Presets + Undo**:
  - `PLAYER_DEATH` (Saquear el cadáver: `gear = 0`, conserva nivel/raza/clase).
  - `activeCurses` en `PlayerState` (ej. *Cambio de sexo -5 próximo combate*, *Pollo en la cabeza -1 huir*, personalizada) con descuento automático tras combate.
  - `helperTreasureShare` en `CombatState` (pacto de tesoros del ayudante visible en mesa).
  - Presets de 1 toque en combate (*Antiguo +10*, *Enfurecido +5*, *Cría -5*, *Poción +2/+3/+5*).
  - Buffer de `UNDO_LAST` en servidor (últimos 5 snapshots, permitido a Host y jugador en turno).
  → Verificar: tests unitarios/protocolo + Android lint/tests + verificación visual Playwright.

## Fase 2 — Modos de Mesa (TV Espectador, Offline Pass & Play, Pausa de Reloj)
- [x] **2.1 Pausa/reanudación de temporizador de turno**: `PAUSE_TURN_TIMER` / `RESUME_TURN_TIMER` en `turnManager.js`, `Protocol.kt`, `protocol.mjs`, Android y Web PWA. → Verificar: el tiempo restante se congela y reanuda con precisión.
- [x] **2.2 Modo Espectador / Pantalla de Mesa (TV/Tablet)**: mensaje `WATCH_GAME` (solo lectura, no consume plaza de los 6 jugadores) y vista panorámica de alto contraste (`#/tv/<CODE>` en Web PWA + opción Espectador en Android). → Verificar: espectador recibe `STATE_SNAPSHOT` y combates en vivo sin poder mutar estado.
- [x] **2.3 Modo Offline / Pass & Play (Un solo dispositivo)**: partidas locales sin red reutilizando `GameEngine` y `CombatCalculator` en Android (persistencia local) y Web PWA (`localStorage`), con selector rápido de jugador activo. → Verificar: crear y jugar partida completa desconectado.

## Fase 3 — Estadísticas Post-Partida y Catálogo Enriquecido
- [x] **3.1 Métricas por partida y podio post-partida**: extender `games` / `participants` y tabla `game_player_stats` (monstruos derrotados, mayor combate ganado, muertes, huidas, duración) + resumen/podio con premios MVP y leaderboard ampliado (% victorias, racha, monstruos derrotados, muertes). → Verificar: `historyManager.js` + tests de historial/leaderboard.
- [x] **3.2 Catálogo multiidioma y edición por autor**: soporte de traducciones ES/EN/FR para monstruos semilla, filtros rápidos (nivel / No-Muerto / expansión) y `CATALOG_UPDATE` / `CATALOG_DELETE` restringidos a `created_by === ws.userId` (semillas protegidas). → Verificar: tests de permisos IDOR y filtros.

## Fase 4 — Paridad Sensorial Web PWA + Tests E2E Playwright + Auditoría Final
- [x] **4.1 Paridad Web PWA**: Screen Wake Lock API, motor de sonido Web Audio + vibración (`navigator.vibrate`) con toggles en Ajustes, y escáner QR en navegador (`BarcodeDetector` + fallback). → Verificar: toggles y permisos en navegador.
- [x] **4.2 Suite E2E Playwright + Auditoría final**: verificación multi-cliente (host + invitado móvil + espectador TV), auditoría `web-design-guidelines` y verificación completa (`server check/test`, `security_verify.py`, `./gradlew :app:testDebugUnitTest :app:lintDebug :app:minifyReleaseWithR8 :app:assembleDebug`).

## Criterio de Finalización
- [x] `npm --prefix server run check` y `npm --prefix server test` en verde (168/168).
- [x] `./gradlew :app:testDebugUnitTest :app:lintDebug :app:minifyReleaseWithR8 :app:assembleDebug` en verde (paridad ES/EN/FR intacta).
- [x] `python scripts/bump_version.py --check` y `security_verify.py` en verde.
- [x] Verificación visual con Playwright en escritorio y móvil sin violaciones de `DESIGN.md`.
