# krtc-sfu

Встраиваемый SFU для серверов на Kotlin Multiplatform (JVM, macOS, Linux). Аудио (Opus), видео (VP8/H.264), simulcast, NACK/RTX, PLI/FIR, «говорит». Без HTTP-сервера: транспорт сигнализации, авторизация и пользователи остаются у твоего сервера.
Архитектура: [ADR-0010](../docs/adr/0010-sfu-before-1.0.md), [ADR-0011](../docs/adr/0011-sfu-kotlin-multiplatform.md), [ADR-0013](../docs/adr/0013-embeddable-sfu.md). Готовое приложение — [krtc-sfu-server](../krtc-sfu-server/README.md).

## Подключение

```kotlin
// build.gradle.kts хоста
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("krtc:krtc-sfu:0.1.0-SNAPSHOT")       // ядро
        implementation("krtc:krtc-sfu-ktor:0.1.0-SNAPSHOT")  // если сигнализация через Ktor
    }
}
```

Локально: `./gradlew publishToMavenLocal` в репозитории krtc и `mavenLocal()` в репозиториях хоста. Group `krtc` — временный.

На Kotlin/Native флаги OpenSSL прописывать не нужно, они едут в klib:
- macOS: OpenSSL 3 из Homebrew линкуется статически;
- Linux: нужны системные `libssl` и `libcrypto` 3.x.

## Встраивание

```kotlin
val sfu = Sfu(
    SfuConfig(
        udpPort = 50000,                         // один UDP-порт на все медиа
        bindAddress = "::",                      // все IPv4 и IPv6 адреса машины; ответ — с того адреса, куда писал клиент
        announcedAddresses = listOf("203.0.113.10", "2001:db8::10"), // публичные IP для ICE-кандидатов
        // iceTcp = true (по умолчанию): ICE по TCP на том же порту для сетей без UDP
        joinPolicy = JoinPolicy { request ->     // твоя авторизация
            val user = auth.verify(request.credentials) ?: return@JoinPolicy JoinDecision.Deny("unauthorized")
            if (!user.canJoin(request.room)) JoinDecision.Deny("forbidden")
            else JoinDecision.Allow(participantId = user.id, displayName = user.name)
        },
    ),
)

// События — в твою логику и аналитику
scope.launch {
    sfu.events.collect { event ->
        when (event) {
            is SfuEvent.ParticipantJoined -> analytics.joined(event.room, event.participantId)
            is SfuEvent.SpeakingChanged -> presence.speaking(event.participantId, event.speaking)
            else -> Unit
        }
    }
}
```

**Сигнализация через Ktor:**

```kotlin
install(WebSockets)
routing {
    authenticate { krtcSfu(sfu, "/rtc") }   // или krtcSfu(sfu, "/rtc") { call -> JoinRequest(...) }
}
```

По умолчанию маршрут берёт `room` и `name` из query, а токен — из `Authorization: Bearer` или `?token=` и передаёт его в `JoinRequest.credentials`.

**Сигнализация через свой транспорт** (gRPC, шина, другой WebSocket):

```kotlin
val sink = object : SignalSink {
    override fun send(message: ServerMessage) { myConnection.trySend(SignalingProtocol.encode(message)) }
    override fun close(reason: String) { myConnection.close(reason) }
}
val participant = sfu.join(JoinRequest(room, name, credentials = token), sink)   // JoinDeniedException при отказе
myConnection.onText { text -> sfu.onMessage(participant, text) }
myConnection.onClose { sfu.leave(participant) }
```

**Управление и мониторинг:**

```kotlin
sfu.kick(userId, "banned")                     // клиенту уходит bye, сессия закрывается
sfu.setMuted(userId, audio = true)             // сервер перестаёт пересылать его звук
sfu.rooms(); sfu.room("team"); sfu.participant(userId)
sfu.metrics()                                  // SfuMetrics: пакеты, байты, ошибки SRTP, DTLS, очереди шардов
sfu.linkStats()                                // по участнику: оценка канала до него (transport-cc), потери, слои simulcast
sfu.close()                                    // bye всем, освобождение порта и потоков
```

## Сеть и перегрузка

Подробности и замеры — [ADR-0015](../docs/adr/0015-sfu-transport-and-congestion.md).
- **transport-cc в обе стороны.** Отправителям SFU каждые 50 мс шлёт feedback, их GCC сам регулирует битрейт. До каждого получателя SFU оценивает канал (задержка + потери) и подбирает ему слои simulcast. Слой, выбранный клиентом через `Layer`, остаётся потолком.
- **Wildcard-bind** (`0.0.0.0` или `::`) открывает сокеты на каждом локальном адресе и отвечает с того, на который писал клиент. IPv6 поддерживается.
- **ICE-TCP** на том же порту — для сетей без UDP.
- **ICE restart** по `ClientMessage.RestartIce`: `krtc-client` просит его сам, когда соединение пропало.

## Протокол

Сообщения — в модуле `krtc-signaling` (`ServerMessage`, `ClientMessage`, `SignalingProtocol`). Его же использует клиент `krtc-client`. Описание формата — в [README приложения](../krtc-sfu-server/README.md#протокол-сигнализации).

## Совместимость API

Публичное API зафиксировано дампами в `api/` и проверяется задачей `./gradlew :krtc-sfu:checkKotlinAbi`. Намеренные изменения фиксируются через `updateKotlinAbi`.

## Тесты и бенчмарк

```bash
./gradlew :krtc-sfu:jvmTest :krtc-sfu:macosArm64Test
```

```bash
./gradlew :krtc-sfu:benchmark
```
