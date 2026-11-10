# krtc-sfu-server

Готовое приложение-сервер комнат: библиотеки [krtc-sfu](../krtc-sfu/README.md) и `krtc-sfu-ktor`, тестовая страница и `/metrics`. Чтобы встроить SFU в свой сервер, смотри [krtc-sfu](../krtc-sfu/README.md).

Сервер комнат (SFU) на Kotlin Multiplatform. Браузеры подключаются по WebRTC, сервер пересылает аудио (Opus) и видео (VP8/H.264) без декодирования.
Решения: [ADR-0010](../docs/adr/0010-sfu-before-1.0.md) (архитектура SFU), [ADR-0011](../docs/adr/0011-sfu-kotlin-multiplatform.md) (KMP: общее ядро, JVM и Kotlin/Native).

## Запуск

Kotlin/Native (macOS, нужен `brew install openssl@3`):

```bash
./gradlew :krtc-sfu-server:linkReleaseExecutableMacosArm64
```

```bash
krtc-sfu-server/build/bin/macosArm64/releaseExecutable/krtc-sfu.kexe --http-port 8080 --udp-port 50000 --announce 127.0.0.1
```

JVM:

```bash
./gradlew :krtc-sfu-server:runJvm -PsfuArgs="--http-port 8080 --udp-port 50000 --announce 127.0.0.1"
```

Откройте `http://localhost:8080/` в двух вкладках и войдите в одну комнату. Видео можно выбрать: тестовая анимация, камера или без видео. Кнопки q/h/f на плитке собеседника переключают слой simulcast. Чтобы вкладки не играли звук друг друга, добавьте к адресу `?mute=1`.

| Параметр | Переменная | По умолчанию | Что это |
|---|---|---|---|
| `--http-port` | `KRTC_HTTP_PORT` или `PORT` | 8080 | HTTP: тестовая страница, `/ws`, `/metrics`, `/stats` |
| `--udp-port` | `KRTC_UDP_PORT` | 50000 | Единственный порт для всех медиа: UDP и (ICE-TCP) TCP |
| `--bind` | `KRTC_BIND` | 0.0.0.0 | Локальный адрес. `0.0.0.0` — все IPv4-адреса машины, `::` — все IPv4 и IPv6: на каждый адрес свои сокеты, ответ уходит с того адреса, на который писал клиент |
| `--announce` | `KRTC_ANNOUNCE` | 127.0.0.1 | Адреса ICE-кандидатов через запятую, по убыванию предпочтения: публичные IPv4 и IPv6 сервера |
| `--ice-tcp` | `KRTC_ICE_TCP` | true | Принимать ICE по TCP (RFC 6544) на том же порту — для сетей, где закрыт UDP |
| `--ice-udp` | `KRTC_ICE_UDP` | true | `false` — только TCP-кандидаты (сети без UDP, тесты) |
| `--shards` | `KRTC_SHARDS` | число ядер | Рабочие потоки и UDP-сокеты |
| `--simulate-loss` | `KRTC_SIMULATE_LOSS` | 0 | Только для тестов: % отбрасываемых исходящих видеопакетов |
| `--simulate-uplink-loss` | `KRTC_SIMULATE_UPLINK_LOSS` | 0 | Только для тестов: % отбрасываемых входящих видеопакетов |
| `--simulate-downlink-kbps` | `KRTC_SIMULATE_DOWNLINK_KBPS` | 0 | Только для тестов: ограничить канал до каждого получателя (token bucket, лишнее отбрасывается) |

Для доступа не с localhost браузеру нужен HTTPS (getUserMedia). Сервер ставится за обратный прокси с TLS.

## Как устроено

```
браузер ──WebSocket /ws──► signaling (Ktor CIO) ──► Sfu: offer/answer, комнаты      [commonMain]
   │
   ├──UDP :50000 (STUN │ DTLS │ SRTP/SRTCP) ──► UdpServer: на каждый локальный адрес сокет на шард, SO_REUSEPORT  [jvm: NIO │ native: POSIX]
   └──TCP :50000 (то же, кадры RFC 4571) ──► TcpServer: ICE-TCP, пассивные кандидаты
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
                             transport-cc от получателя → оценка канала (GCC: задержка + потери)
                             → слои simulcast подбираются под оценку
                    сервер → отправителю: transport-cc каждые 50 мс (его GCC), REMB как потолок, PLI при новом зрителе
                    DTLS (данные) ─► SCTP + DCEP (шард комнаты) ─► каналы krtc / krtc-lossy:
                              конверт → политика хоста → получателям, с id отправителя
```

Раскладка SDP у каждого участника (сервер всегда отправляет offer):
- `0` — его микрофон;
- `1` — его камера; сервер просит simulcast `a=simulcast:recv q;h;f`, и браузер сам отправляет три слоя (¼, ½ и полное разрешение);
- `d` — data channels (SCTP поверх DTLS, [ADR-0016](../docs/adr/0016-data-channels.md));
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
| сервер → клиент | `{"type":"bye","reason"}` — сервер завершает сессию (выгнан, вытеснен, потеря связи) |

Клиент на `offer`:
1. Вызывает `setRemoteDescription`.
2. У трансиверов `0` (микрофон) и `1` (камера) ставит `direction = 'sendonly'` и `replaceTrack(...)`.
3. Отвечает `answer`.

Сообщения комнаты идут не через WebSocket, а через data channels. Клиент открывает `krtc` (надёжный) и `krtc-lossy` (`ordered: false, maxRetransmits: 0`). Каждое сообщение — двоичный конверт: `1 | число id | (длина, id)* | длина, тема | данные`. Клиент указывает id получателей (пусто — все), сервер — id отправителя (пусто — сам сервер).

Пример — [index.html](src/commonMain/web/index.html).

## Метрики

`GET /metrics`:
- `participants`, `rooms`;
- `rtpIn`/`rtpOut`, `bytesIn`/`bytesOut`, `rtcpIn`/`rtcpOut`;
- `srtpFailures`, `droppedOverload`;
- `keyframeRequests`, `nacksAnswered` (повторы получателям из кэша), `nacksSent` и `rtxRecovered` (потери на входе от отправителя);
- `dataMessagesIn`, `dataMessagesOut`, `dataMessagesDropped` — сообщения data channels;
- `stunRequests`, `stunRejected`, `dtlsConnected`, `dtlsFailed`, `unknownSource`;
- `shardQueueDepth`.

`GET /stats` (то же, что `Sfu.linkStats()`) — по каждому участнику: `downlinkEstimateBps` (оценка канала до него по transport-cc; 0, если клиент не шлёт feedback), `downlinkAckedBps`, `downlinkLossPercent` и `videoLayers` — какой слой simulcast он сейчас получает от каждого отправителя.

## Тесты и бенчмарк

```bash
./gradlew :krtc-protocol:jvmTest :krtc-signaling:jvmTest :krtc-sfu:jvmTest :krtc-sfu:macosArm64Test :krtc-client:jvmTest
```

```bash
./gradlew :krtc-sfu:benchmark
```

## Ограничения текущей версии

- SVC (VP9/AV1 с пространственными слоями) не поддерживается, только simulcast.
- Нет TURN/TLS на 443 и probing в оценке канала ([ADR-0015](../docs/adr/0015-sfu-transport-and-congestion.md)).
- Нет авторизации в сигнализации этого приложения (во встраиваемом SFU — `JoinPolicy`).
- Data channels: без I-DATA и приоритетов каналов ([ADR-0016](../docs/adr/0016-data-channels.md)).
- Linux-бинари собираются на Linux-хосте с OpenSSL 3.
