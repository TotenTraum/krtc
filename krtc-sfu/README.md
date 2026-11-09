# krtc-sfu

Сервер голосовых комнат (SFU) на Kotlin/JVM: браузеры подключаются по WebRTC, сервер пересылает Opus без декодирования.
Архитектура и решения — [ADR-0010](../docs/adr/0010-sfu-before-1.0.md).

## Запуск

```bash
./gradlew :krtc-sfu:run --args="--http-port 8080 --udp-port 50000 --announce 127.0.0.1"
```

Откройте `http://localhost:8080/` в двух вкладках и войдите в одну комнату. Галочка «Тестовый сигнал» шлёт тон вместо микрофона.

| Параметр | Переменная | По умолчанию | Что это |
|---|---|---|---|
| `--http-port` | `KRTC_HTTP_PORT` | 8080 | HTTP: тестовая страница, `/ws`, `/metrics` |
| `--udp-port` | `KRTC_UDP_PORT` | 50000 | Единственный UDP-порт для всех медиа |
| `--announce` | `KRTC_ANNOUNCE` | 127.0.0.1 | Адрес в ICE-кандидате: публичный IP сервера |
| `--shards` | `KRTC_SHARDS` | число ядер | Рабочие потоки и UDP-сокеты |

Для доступа не с localhost браузеру нужен HTTPS (getUserMedia). Сервер ставится за обратный прокси с TLS.

## Как устроено

```
браузер ──WebSocket /ws──► SignalingServer ──► Sfu: offer/answer, комнаты
   │
   └──UDP :50000 (STUN │ DTLS │ SRTP) ──► UdpServer (SO_REUSEPORT, сокет на шард)
                                              │ RFC 7983 по первому байту
                     STUN ─► ICE-lite (Sfu.handleStun)
                     DTLS ─► DtlsEndpoint (BouncyCastle, виртуальный поток) ─► ключи SRTP
                     SRTP ─► Shard комнаты ─► Room.forward:
                               расшифровать 1 раз → для каждого слушателя:
                               переписать SSRC/seq/ts → зашифровать → sendto
```

## Протокол сигнализации

`ws://host/ws?room=<комната>&name=<имя>`, JSON-сообщения:

| Направление | Сообщение |
|---|---|
| сервер → клиент | `{"type":"welcome","id","room","peers":[{"id","name"}]}` |
| сервер → клиент | `{"type":"offer","version","sdp","mids":{"<mid>":"<id участника>"}}` |
| клиент → сервер | `{"type":"answer","version","sdp"}` |
| сервер → клиент | `{"type":"peer-joined","id","name"}`, `{"type":"peer-left","id"}` |
| сервер → клиент | `{"type":"speaking","id","speaking":true\|false}` |

Клиент на `offer`:
1. Вызывает `setRemoteDescription`.
2. У трансивера с `mid` `0` ставит `direction = 'sendonly'` и `replaceTrack(микрофон)`.
3. Отвечает `answer`.

Пример — [index.html](src/main/resources/web/index.html).

## Метрики

`GET /metrics`:
- `participants`, `rooms`;
- `rtpIn` / `rtpOut`, `bytesIn` / `bytesOut`;
- `srtpFailures`, `stunRejected`, `dtlsFailed`, `unknownSource`;
- `shardQueueDepth` — очередь каждого шарда, растёт при перегрузке.

## Тесты и бенчмарк

```bash
./gradlew :krtc-protocol:jvmTest :krtc-sfu:test
```

```bash
./gradlew :krtc-sfu:benchmark
```

Бенчмарк гоняет горячий путь `Room.forward` (1 говорящий → 50 слушателей) без браузеров. Он показывает отдельно стоимость криптографии и криптографии вместе с `sendto`, плюс масштабирование по шардам. Результаты и оговорки — в ADR-0010.

## Ограничения текущей версии

- Только аудио (Opus). Видео и simulcast — следующий этап.
- RTCP не пересылается: SR для синхронизации, NACK, PLI и TWCC пока не поддерживаются.
- Нет TURN/TCP и авторизации в сигнализации.
