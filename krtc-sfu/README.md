# krtc-sfu

Сервер комнат (SFU) на Kotlin Multiplatform. Браузеры подключаются по WebRTC, сервер пересылает аудио (Opus) и видео (VP8/H.264) без декодирования.
Решения: [ADR-0010](../docs/adr/0010-sfu-before-1.0.md) (архитектура SFU), [ADR-0011](../docs/adr/0011-sfu-kotlin-multiplatform.md) (KMP: общее ядро, JVM и Kotlin/Native).

## Запуск

Kotlin/Native (macOS, нужен `brew install openssl@3`):

```bash
./gradlew :krtc-sfu:linkReleaseExecutableMacosArm64
```

```bash
krtc-sfu/build/bin/macosArm64/releaseExecutable/krtc-sfu.kexe --http-port 8080 --udp-port 50000 --announce 127.0.0.1
```

JVM:

```bash
./gradlew :krtc-sfu:runJvm -PsfuArgs="--http-port 8080 --udp-port 50000 --announce 127.0.0.1"
```

Откройте `http://localhost:8080/` в двух вкладках и войдите в одну комнату. Видео можно выбрать: тестовая анимация, камера или без видео. Кнопки q/h/f на плитке собеседника переключают слой simulcast. Чтобы вкладки не играли звук друг друга, добавьте к адресу `?mute=1`.

| Параметр | Переменная | По умолчанию | Что это |
|---|---|---|---|
| `--http-port` | `KRTC_HTTP_PORT` или `PORT` | 8080 | HTTP: тестовая страница, `/ws`, `/metrics` |
| `--udp-port` | `KRTC_UDP_PORT` | 50000 | Единственный UDP-порт для всех медиа |
| `--announce` | `KRTC_ANNOUNCE` | 127.0.0.1 | Адрес в ICE-кандидате: публичный IP сервера |
| `--shards` | `KRTC_SHARDS` | число ядер | Рабочие потоки и UDP-сокеты |
| `--simulate-loss` | `KRTC_SIMULATE_LOSS` | 0 | Только для тестов: % отбрасываемых исходящих видеопакетов |

Для доступа не с localhost браузеру нужен HTTPS (getUserMedia). Сервер ставится за обратный прокси с TLS.

## Как устроено

```
браузер ──WebSocket /ws──► signaling (Ktor CIO) ──► Sfu: offer/answer, комнаты      [commonMain]
   │
   └──UDP :50000 (STUN │ DTLS │ SRTP/SRTCP) ──► UdpServer: сокет на шард, SO_REUSEPORT  [jvm: NIO │ native: POSIX]
                    STUN ─► ICE-lite
                    DTLS ─► DtlsEndpoint ─► ключи SRTP/SRTCP                         [jvm: BouncyCastle │ native: OpenSSL]
                    SRTP ─► шард комнаты ─► Room.forward:
                              расшифровать 1 раз → для каждого получателя:
                              переписать SSRC/seq/ts → зашифровать → sendto
                    видео: слой simulcast по rid → у каждого получателя свой слой,
                           переключение на ключевом кадре, своя нумерация seq/ts/PictureID
                    SRTCP ─► NACK → повтор из кэша слоя (RTX)
                             PLI/FIR → PLI отправителю
                             SR → переслать получателям
                    сервер → отправителю: REMB раз в секунду, PLI при новом зрителе
```

Раскладка SDP у каждого участника (сервер всегда отправляет offer):
- `0` — его микрофон;
- `1` — его камера; сервер просит simulcast `a=simulcast:recv q;h;f`, и браузер сам отправляет три слоя (¼, ½ и полное разрешение);
- `2+2i` и `3+2i` — аудио и видео i-го собеседника, или `inactive`: слот свободен или собеседник без видео. Свободные слоты переиспользуются.

## Протокол сигнализации

`ws://host/ws?room=<комната>&name=<имя>`, JSON-сообщения:

| Направление | Сообщение |
|---|---|
| сервер → клиент | `{"type":"welcome","id","room","peers":[{"id","name"}]}` |
| сервер → клиент | `{"type":"offer","version","sdp","mids":{"<mid>":"<id участника>"}}` |
| клиент → сервер | `{"type":"answer","version","sdp"}` |
| клиент → сервер | `{"type":"layer","peer","layer":"q"\|"h"\|"f"}` — желаемый слой simulcast для видео участника |
| сервер → клиент | `{"type":"peer-joined","id","name"}`, `{"type":"peer-left","id"}` |
| сервер → клиент | `{"type":"speaking","id","speaking":true\|false}` |

Клиент на `offer`:
1. Вызывает `setRemoteDescription`.
2. У трансиверов `0` (микрофон) и `1` (камера) ставит `direction = 'sendonly'` и `replaceTrack(...)`.
3. Отвечает `answer`.

Пример — [index.html](src/commonMain/web/index.html).

## Метрики

`GET /metrics`:
- `participants`, `rooms`;
- `rtpIn`/`rtpOut`, `bytesIn`/`bytesOut`, `rtcpIn`/`rtcpOut`;
- `srtpFailures`, `droppedOverload`;
- `keyframeRequests`, `nacksAnswered`;
- `stunRequests`, `stunRejected`, `dtlsConnected`, `dtlsFailed`, `unknownSource`;
- `shardQueueDepth`.

## Тесты и бенчмарк

```bash
./gradlew :krtc-protocol:jvmTest :krtc-sfu:jvmTest :krtc-sfu:macosArm64Test
```

```bash
./gradlew :krtc-sfu:benchmark
```

## Ограничения текущей версии

- SVC (VP9/AV1 с пространственными слоями) не поддерживается, только simulcast.
- Нет оценки полосы по получателям (TWCC) — отправителю задаётся фиксированный REMB 1,5 Мбит/с.
- Сервер не запрашивает повторы у отправителя: потери на участке от отправителя до сервера не восстанавливаются, только от сервера до получателя.
- Только IPv4, нет TURN/TCP и авторизации в сигнализации.
- Linux-бинари собираются на Linux-хосте с OpenSSL 3.
