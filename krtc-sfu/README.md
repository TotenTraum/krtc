# krtc-sfu

Встраиваемый SFU для серверов на Kotlin Multiplatform (JVM, macOS, Linux). Аудио (Opus), видео (VP8/H.264), simulcast, NACK/RTX, PLI/FIR, «говорит», сообщения комнаты через data channels, демонстрация экрана ([ADR-0017](../docs/adr/0017-screen-sharing.md)). Без HTTP-сервера: транспорт сигнализации, авторизация и пользователи остаются у твоего сервера.
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
- Linux (x64, arm64): OpenSSL 3 собирается из исходников (`build-openssl.sh`, вызывается Gradle при первой сборке) компилятором и sysroot самого Kotlin/Native и линкуется статически. Бинарю нужна только glibc. Собирать можно и на macOS, и на Linux; нужны `perl` и `make`.

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
val participant = sfu.join(JoinRequest(room, name, credentials = token, resumeToken = resume), sink)   // JoinDeniedException при отказе
myConnection.onText { text -> sfu.onMessage(participant, text) }
myConnection.onClose { sfu.disconnected(participant, sink) }   // или sfu.leave(participant), если возобновление не нужно
```

**Возобновление после обрыва.** В `welcome` клиент получает секрет `resume`. Если сигнализация оборвалась, хост вызывает `disconnected`: участник остаётся в комнате на `SfuConfig.resumeTimeoutMillis` (по умолчанию 20 с), медиа продолжают идти. Клиент подключается заново с этим секретом (`JoinRequest.resumeToken`, в маршруте Ktor — параметр `resume`). Он возвращается под тем же id, получает свежий `welcome` и новое предложение. Не вернулся вовремя — выходит из комнаты.

Как маршрут Ktor отличает обрыв от выхода:
- клиент сам закрыл сокет (кадр закрытия) или прислал `leave` — выход сразу;
- соединение пропало без кадра закрытия — участника ждут.

**Управление и мониторинг:**

```kotlin
sfu.kick(userId, "banned")                     // клиенту уходит bye, сессия закрывается
sfu.setMuted(userId, audio = true)             // сервер перестаёт пересылать его звук
sfu.rooms(); sfu.room("team"); sfu.participant(userId)
sfu.metrics()                                  // SfuMetrics: пакеты, байты, сообщения, ошибки SRTP, DTLS, очереди шардов
sfu.linkStats()                                // по участнику: оценка канала до него (transport-cc), потери, слои simulcast
sfu.close()                                    // bye всем, освобождение порта и потоков
```

## Сообщения комнаты (data channels)

Подробности — [ADR-0016](../docs/adr/0016-data-channels.md). SFU держит с каждым участником SCTP поверх DTLS. Клиенты открывают каналы `krtc` (надёжный) и `krtc-lossy` (без повторов), шлют сообщения с темой и, при желании, списком получателей. SFU подставляет id отправителя и рассылает.

```kotlin
val sfu = Sfu(SfuConfig(
    dataPolicy = { m ->                                   // поток шарда: не блокировать
        when {
            m.topic == "server" -> { myInbox.trySend(m); null }   // сообщение серверу: поглотить
            m.payload.size > 4096 && m.topic == "chat" -> null    // своя модерация
            else -> m                                             // как есть (или m.copy(to = ...))
        }
    },
))
sfu.sendData("team", "Запись началась".encodeToByteArray(), topic = "notice")   // от сервера, всем в комнате
```

`SfuConfig(dataChannels = false)` убирает SCTP из предложения.

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
