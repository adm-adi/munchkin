# 🗡️ Munchkin Mesa Tracker

Aplicación Android para llevar partidas de Munchkin entre amigos en la misma mesa, con sincronización en tiempo real vía LAN.

## Características

- 📱 **Solo Android** - Diseñado para jugar en la misma mesa
- 🌐 **Sincronización LAN** - WebSocket sobre WiFi/hotspot
- 👥 **2-6 jugadores** - Cada uno controla solo su personaje
- ⚔️ **Calculadora de combate** - Con modificadores condicionales
- 🎨 **UI premium** - Material 3, animaciones y haptics
- 📲 **QR para unirse** - Escanea o introduce manualmente

## Stack Técnico

- **Lenguaje**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Arquitectura**: MVVM + UDF (StateFlow)
- **Red**: Ktor WebSocket (servidor embebido)
- **Persistencia**: Room (próximamente)
- **Mínimo SDK**: API 26 (Android 8.0)

## Compilar

```bash
# Desde la raíz del proyecto
./gradlew assembleDebug

# El APK estará en:
# app/build/outputs/apk/debug/app-debug.apk
```

## Probar en Emulador

### Configuración de 2 emuladores

```bash
# Terminal 1: Emulador A (Host)
emulator -avd Pixel_6_API_30 -port 5554

# Terminal 2: Emulador B (Client)
emulator -avd Pixel_6_API_30_copy -port 5556
```

### Conectar emuladores entre sí

Para que el emulador B conecte al emulador A:

```bash
# Desde el emulador B, usa esta IP para conectar:
# 10.0.2.2:8765
# (10.0.2.2 es el alias del host desde el emulador)

# O usa port forwarding:
adb -s emulator-5556 reverse tcp:8765 tcp:8765
```

### Flujo de prueba

1. **Emulador A**: Abre la app → "Crear Partida" → Anota el código
2. **Emulador B**: Abre la app → "Unirse" → Introduce IP `10.0.2.2:8765` + código
3. **Ambos**: Verificar que aparecen en el lobby
4. **Emulador A**: Pulsar "Iniciar Partida"
5. **Ambos**: Probar cambiar nivel/equipo y verificar sincronización

## Estructura del Proyecto

```
app/src/main/java/com/munchkin/app/
├── core/               # Modelos y lógica de juego
│   ├── Models.kt       # PlayerState, GameState, etc.
│   ├── Combat.kt       # CombatState, MonsterInstance
│   ├── Events.kt       # Todos los eventos del juego
│   ├── GameEngine.kt   # Procesador de eventos (host)
│   └── CombatCalculator.kt
├── network/            # Capa de red
│   ├── Protocol.kt     # Mensajes WebSocket
│   ├── GameServer.kt   # Servidor Ktor (host)
│   └── GameClient.kt   # Cliente Ktor
├── ui/
│   ├── theme/          # Material 3 (colores, tipografía)
│   ├── components/     # Componentes reutilizables
│   └── screens/        # Pantallas (Home, Lobby, Board, etc.)
├── viewmodel/          # GameViewModel
├── MainActivity.kt
└── MunchkinApp.kt
```

## Reglas Implementadas

- ✅ Nivel siempre entre 1 y 10
- ✅ Empates en combate: ganan los monstruos
- ✅ Solo puedes editar tu propio personaje
- ✅ Máximo 1 raza (2 con Mestizo)
- ✅ Máximo 1 clase (2 con Super Munchkin)

## Tests

```bash
# Unit tests
./gradlew :app:test

# Tests específicos
./gradlew :app:test --tests "*.LevelValidationTest"
./gradlew :app:test --tests "*.CombatCalculatorTest"
```

## Próximamente

- [ ] Handover de host (si el anfitrión se desconecta)
- [ ] Persistencia con Room
- [ ] Sonidos y haptics configurables
- [ ] Escáner QR funcional
- [ ] Actualizador in-app desde GitHub

## Licencia

Copyright © 2024. Proyecto personal para uso con amigos.
