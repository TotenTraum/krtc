# ADR-0012. KMP-клиент для `krtc-sfu`: аудио, видео, simulcast

- Статус: Предложено (каркас реализован, медиа-бэкенды — по этапам ниже)
- Дата: 2026-11-09
- Связано: [ADR-0002](0002-binding-c-abi-shim.md) (нативный бэкенд), [ADR-0003](0003-browser-targets.md) (браузер), [ADR-0004](0004-api-model.md) (модель API), [ADR-0010](0010-sfu-before-1.0.md)/[0011](0011-sfu-kotlin-multiplatform.md) (сервер)

## Контекст

Серверу нужен клиент, написанный один раз для Android, iOS, desktop (JVM) и веба:
- вход в комнату;
- микрофон и камера;
- приём аудио и видео собеседников;
- индикатор «говорит»;
- выбор качества (simulcast).

Клиент должен стоять на нашей библиотеке — W3C API на KMP (ADR-0004). Её нативный движок (libwebrtc через `krtc-native`) ещё не собран: он ждёт spike, этап 10 плана.

## Решение

**Слои:**

```
samples/krtc-meet      Compose Multiplatform приложение (Android, iOS, desktop, web)
        │
krtc-compose           VideoView(track) и сетка участников (ADR-0006)
        │
krtc-client            SDK комнаты: сигнализация, переговоры, участники, качество      ← общий код
        │
krtc-core              W3C API: RTCPeerConnection, трансиверы, треки, MediaDevices     ← интерфейсы
        │                 (минимальный срез сейчас, полный — по krtc-idl)
        ├── web:     браузерный WebRTC (ADR-0003)
        ├── native:  libwebrtc через krtc-native (ADR-0002)
        └── interim: org.webrtc (Android), WebRTC.framework (iOS), webrtc-java (desktop)
```

**Главное решение — `krtc-core` состоит из интерфейсов плюс фабрики.**

```kotlin
interface PeerConnectionFactory {
    fun createPeerConnection(configuration: RTCConfiguration): RTCPeerConnection
}

Krtc.factory = <платформенный бэкенд>
```

Почему это важно:
- `krtc-client` и приложения пишутся и тестируются уже сейчас, на поддельной фабрике.
- Бэкенды подключаются без изменения API. Временные (официальные SDK платформ, webrtc-java) дают рабочие мобильные и desktop-клиенты до готовности `krtc-native`, а потом заменяются им.
- Имена и семантика — как в W3C: `setRemoteDescription`, `createAnswer`, `RTCRtpTransceiver.direction`, `sender.replaceTrack`. Promise становится `suspend`, события — `Flow`/`StateFlow` (ADR-0004).

**`krtc-client`, общий код:**

```kotlin
val room = RoomClient("wss://sfu.example.com")
room.join(roomName = "team", displayName = "Аня", microphone = mic, camera = cam)

room.state             // StateFlow<RoomState>: Connecting → Connected(selfId) → Disconnected(reason)
room.participants      // StateFlow<List<RemoteParticipant>>
participant.audio      // StateFlow<MediaStreamTrack?>
participant.video      // StateFlow<MediaStreamTrack?>  → VideoView(track) в krtc-compose
participant.speaking   // StateFlow<Boolean>
room.setVideoQuality(participant.id, VideoQuality.LOW)   // simulcast-слой на сервере
room.leave()
```

- **Сигнализация** — Ktor WebSocket-клиент, протокол сервера (`welcome`, `offer`, `answer`, `peer-joined`, `peer-left`, `speaking`, `layer`). Модель сообщений — `@Serializable` классы. Следующий шаг: вынести их в общий модуль и использовать в сервере, чтобы клиент и сервер не расходились.
- **Переговоры** — offer'ы обрабатываются строго по очереди: `setRemoteDescription` → микрофон и камера в m-line `0`/`1` (`sendonly` + `replaceTrack`) → `createAnswer` → `setLocalDescription` → `answer`. Треки собеседников сопоставляются участникам через `mids` из offer.
- **Simulcast** клиенту настраивать не нужно: сервер просит его в offer, браузер или libwebrtc создают три кодировки сами (проверено в Chromium). Клиент только сообщает желаемое качество для каждого собеседника.
- **Потоки** — всё состояние в `StateFlow`. Работа идёт на `Dispatchers.Default`, UI собирает потоки на своём диспетчере (ADR-0005).

**Платформы и бэкенды:**

| Платформа | Бэкенд `krtc-core` | Когда |
|---|---|---|
Клиент (`krtc-core`, `krtc-client`, `krtc-protocol`) собирается под все таргеты ниже уже сейчас. Медиа появляется, когда для таргета подключён бэкенд.

| Платформа | Таргет Kotlin | WebSocket (Ktor) | Бэкенд `krtc-core` | Когда |
|---|---|---|---|---|
| Android | `android` | OkHttp | Временно `org.webrtc`, затем `krtc-native` (JNI) | C4 → C5 |
| iOS | `iosArm64`, `iosSimulatorArm64` | Darwin | Временно `WebRTC.framework` (stasel), затем `krtc-native` (cinterop) | C4 → C5 |
| macOS (нативно) | `macosArm64` | Darwin | Временно `WebRTC.framework`, затем `krtc-native` (cinterop) | C4 → C5 |
| Linux (нативно) | `linuxX64`, `linuxArm64` | curl | `krtc-native` (cinterop): готового SDK для Linux нет | C5 |
| Windows (нативно) | `mingwX64` | WinHTTP | `krtc-native` (cinterop): готового SDK для Windows нет | C5 |
| Desktop JVM | `jvm` | CIO | Временно webrtc-java, затем `krtc-native` (JNI) | C4 → C5 |
| Web | `js`, `wasmJs` | браузерный | Браузерный WebRTC через `external` | C2 |

Движки WebSocket выбраны родные для каждой платформы, чтобы `wss://` работал с системным TLS и системными сертификатами.

Нативные Linux и Windows получают медиа только через `krtc-native`: для них нет ни официального SDK, ни готовой обёртки. Поэтому spike (этап 10 плана) нужен им в первую очередь.

## Этапы

1. **C1 (сделано).**
   - `krtc-core`: минимальный срез интерфейсов.
   - `krtc-client`: общий код. Компилируется под Android, iOS, macOS, Linux x64/arm64, Windows, JVM, JS и Wasm.
   - Интеграционный тест на JVM против настоящего `krtc-sfu` с поддельным PeerConnection.
2. **C2.** Веб-бэкенд `krtc-core` и демо на Kotlin/JS, которое заменит JS-код тестовой страницы.
3. **C3.** `krtc-compose` (`VideoView`) и `samples/krtc-meet` на Compose Multiplatform для веба.
4. **C4.** Временные бэкенды для Android, iOS и desktop. Тот же `krtc-meet` на всех платформах.
5. **C5.** Переход на `krtc-native` после spike, временные бэкенды удаляются.

Дальше: демонстрация экрана (вторая видео-m-line в уплинке, нужна поддержка на сервере), переподключение (повтор WebSocket и ICE restart), токены доступа.

## Альтернативы

| Вариант | Почему нет |
|---|---|
| `expect class RTCPeerConnection` в `krtc-core` | Временный бэкенд не подменить, а логику клиента не протестировать без настоящего движка |
| Писать клиент сразу на `org.webrtc` / `WebRTC.framework` | Это то же самое, что webrtc-kmp, с его ограничениями (обзор, ADR-0002). Наш API должен стоять между клиентом и движком |
| Своя упрощённая модель вместо W3C в `krtc-core` | Противоречит ADR-0004. Упрощения живут уровнем выше, в `krtc-client` |

## Последствия

- Логика клиента одна на все платформы и проверяется на JVM без медиадвижка.
- Пока нет ни одного бэкенда, `krtc-client` нельзя использовать в приложении. Ближайший — веб (C2).
- Минимальный срез `krtc-core` нужно расширять до полного API (`krtc-idl`, ADR-0004) без ломки уже используемых имён.
