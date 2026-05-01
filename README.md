# Munchkin Mesa Tracker

Aplicacion Android para gestionar partidas de Munchkin con sincronizacion en tiempo real contra un backend WebSocket autoritativo.

## Estado actual

- Cliente Android en Kotlin + Jetpack Compose
- Backend Node.js + SQLite
- La app apunta por defecto al servidor remoto configurado en [ServerConfig.kt](/D:/IA/Project/Munchkin/munchkin/app/src/main/java/com/munchkin/app/network/ServerConfig.kt:1)
- El flujo activo es siempre `UI -> ViewModel -> GameClient -> server.js -> snapshot/evento -> cliente`

La pila antigua de LAN/host local ya no forma parte del producto.

## Caracteristicas

- Partidas de 2 a 6 jugadores
- Estado de juego autoritativo en el servidor
- Reconexion y persistencia de partidas activas
- Temporizador de turno controlado por backend
- Catalogo global de monstruos
- Historial de partidas, perfil y leaderboard

## Stack tecnico

- Android: Kotlin, Jetpack Compose, Material 3, StateFlow
- Red cliente: Ktor WebSockets
- Backend: Node.js, ws, SQLite
- Serializacion: `kotlinx.serialization` en cliente, JSON plano en servidor

## Compilar

```bash
./gradlew assembleDebug
./gradlew :app:test
```

APK debug:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Backend local

El backend local sigue existiendo para desarrollo, pero el cliente no cambia automaticamente a localhost. Si quieres probar contra un servidor local, cambia temporalmente los valores de [ServerConfig.kt](/D:/IA/Project/Munchkin/munchkin/app/src/main/java/com/munchkin/app/network/ServerConfig.kt:1).

Arranque del servidor:

```bash
cd server
npm install
npm start
```

## Estructura

```text
app/src/main/java/com/munchkin/app/
|- core/
|  |- Models.kt
|  |- Events.kt
|  |- Combat.kt
|  |- CombatCalculator.kt
|  `- GameEngine.kt
|- network/
|  |- Protocol.kt
|  |- GameClient.kt
|  `- ServerConfig.kt
|- ui/
|  |- components/
|  |- screens/
|  `- theme/
|- viewmodel/
|  `- GameViewModel.kt
`- MainActivity.kt

server/
|- server.js
|- db.js
|- turnManager.js
|- combatManager.js
|- catalogManager.js
|- authManager.js
|- historyManager.js
`- gameAdminManager.js
```

## Validacion

Cliente:

```bash
./gradlew :app:test
```

Backend:

```bash
node --check server/server.js
node --check server/db.js
```

## Produccion

Servidor desplegado en Hetzner:

```bash
cd /opt/munchkin-server && git pull && systemctl restart munchkin
```
