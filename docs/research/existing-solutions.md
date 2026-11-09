# Обзор существующих решений (этап 2)

Состояние на 2026-11-09: данные о репозиториях из GitHub API, версия Chromium из chromiumdash.
Цель обзора: понять, на что опираться в krtc и какие пробелы закрывать самим. Выводы оформлены в ADR ([docs/adr](../adr/README.md)).

## Ориентир по версиям

- Chromium stable — **M157** (ветка WebRTC `8103`), ветка M158 уже отрезана.
- Новый milestone выходит примерно раз в 4 недели. Это задаёт ритм обновлений krtc ([ADR-0008](../adr/0008-libwebrtc-versioning.md)).

## Движки и обёртки для клиентов

| Проект | Язык, лицензия | Активность | Платформы | Подход |
|---|---|---|---|---|
| [libwebrtc](https://webrtc.googlesource.com/src/) | C++, BSD-3 | основной движок Chromium | все | Эталонная реализация: медиадвижок, кодеки, AEC/NS/AGC, ICE, DTLS, SCTP, контроль перегрузки |
| [shepeliev/webrtc-kmp](https://github.com/shepeliev/webrtc-kmp) | Kotlin, Apache-2.0 | v0.125.11 от 2025-09-07, больше года без коммитов | Android, iOS, JS/Wasm | KMP-обёртка над официальными SDK платформ (`org.webrtc`, `WebRTC.framework`), ревизия M125 |
| [devopvoid/webrtc-java](https://github.com/devopvoid/webrtc-java) | Java + C++, Apache-2.0 | **v1.0.0 от 2026-11-06**, активен | Windows, macOS, Linux (x64/arm64, Linux arm32) | Тонкий JNI над C++ API libwebrtc, prebuilt-библиотеки, Encoded Transform, SFrame, аппаратные кодеки |
| [GetStream/webrtc-android](https://github.com/GetStream/webrtc-android) | Kotlin, Apache-2.0 | 1.3.10 от 2025-09-11 | Android | Пересобранный `org.webrtc` с патчами + Compose-рендереры. Форк [GetStream/webrtc](https://github.com/GetStream/webrtc) активен |
| [stasel/WebRTC](https://github.com/stasel/WebRTC) | сборочные скрипты | **157.0.0 от 2026-11-05** | iOS, macOS | Prebuilt `WebRTC.xcframework` под каждый milestone (SPM, CocoaPods) |
| [webrtc-sdk/webrtc](https://github.com/webrtc-sdk/webrtc) | C++, BSD-3 | активен, ветки `m1xx_release` | все | Форк libwebrtc с патчами для LiveKit и flutter-webrtc |
| [livekit/rust-sdks](https://github.com/livekit/rust-sdks) (`webrtc-sys`) | Rust + C++, Apache-2.0 | webrtc-sys v0.3.53 от 2026-11-04 | Windows, macOS, Linux, iOS, Android | Собственная C++-прослойка над libwebrtc для другого языка, prebuilt libwebrtc |
| [libdatachannel](https://github.com/paullouisageneau/libdatachannel) | C++ с C API, MPL-2.0 | v0.24.6 от 2026-09-26 | Linux, Android, macOS, iOS, Windows | Свой лёгкий стек: ICE (libjuice), DTLS, SCTP, SRTP. **Без медиадвижка**: нет кодеков, AEC, джиттер-буфера |
| [Pion](https://github.com/pion/webrtc) | Go, MIT | активен, ★16.9k | везде, где есть Go | Чистая реализация стека с нуля, медиа — только транспорт |
| [webrtc-rs](https://github.com/webrtc-rs/webrtc) | Rust, Apache-2.0 | активен | везде, где есть Rust | Порт Pion на Rust |
| [str0m](https://github.com/algesten/str0m) | Rust, MIT | активен | везде, где есть Rust | Sans-IO стек: протоколы без сокетов и потоков, удобно встраивать в сервер |
| [aiortc](https://github.com/aiortc/aiortc) | Python, BSD-3 | активен | везде, где есть Python | Компактная читаемая реализация, полезна для изучения протоколов и как interop-партнёр |
| [SIPSorcery](https://github.com/sipsorcery-org/sipsorcery) | C#, своя лицензия (см. LICENSE) | активен | .NET | Чистая реализация на C# + SIP |

## Серверы (SFU)

| Проект | Язык, лицензия | Активность | Чем полезен krtc |
|---|---|---|---|
| [Jitsi Videobridge](https://github.com/jitsi/jitsi-videobridge) | **Kotlin**/Java, Apache-2.0 | активен | Доказательство, что продакшн-SFU на JVM реален. Образец маршрутизации RTP, simulcast и оценки полосы |
| [LiveKit](https://github.com/livekit/livekit) | Go (Pion), Apache-2.0 | активен, ★22.1k | Архитектура SFU и протокол комнат поверх Pion |
| [mediasoup](https://github.com/versatica/mediasoup) | C++ (воркер) + Node/Rust, ISC | активен | Низкоуровневая модель Producer/Consumer, эффективный воркер на C++ |
| [Janus](https://github.com/meetecho/janus-gateway) | C, GPL-3.0 | активен | Плагинная архитектура. Лицензия GPL исключает заимствование кода |

## Спецификации на горизонте

- [WebRTC Extensions](https://w3c.github.io/webrtc-extensions/): экспериментальные добавки к API. В `@webref/idl` отдельного файла нет, отслеживать вручную.
- [RTP Transport](https://w3c.github.io/webrtc-rtptransport/): доступ к RTP на уровне пакетов, репозиторий обновлялся 2026-10-20. Важен для серверного сценария ([ADR-0009](../adr/0009-server-side-use.md)).

## Выводы

1. **Полноценной KMP-библиотеки WebRTC сейчас нет.**
   - webrtc-kmp — единственная KMP-обёртка, но она ограничена Android, iOS и JS, застряла на M125 и не развивается больше года.
   - Его подход (обёртка над официальными SDK платформ) упирается в покрытие этих SDK: нет Encoded Transform, desktop и Linux.
2. **libwebrtc остаётся единственным реалистичным движком для клиента.** Аналоги без медиадвижка (libdatachannel, Pion, webrtc-rs) не дают AEC, джиттер-буфер, аппаратные кодеки и контроль перегрузки. Писать это самим — годы работы → [ADR-0001](../adr/0001-engine-libwebrtc.md).
3. **Собственная прослойка над C++ API libwebrtc — проверенный путь.**
   - Так сделаны webrtc-java (JNI) и webrtc-sys у LiveKit (Rust).
   - Оба публикуют prebuilt libwebrtc, и оба упираются в сборку и в `libc++`. LiveKit прямо требует clang 21+, потому что компилирует против встроенной (hermetic) `libc++` из артефакта libwebrtc.
   - Для Kotlin/Native это критично: cinterop понимает только C и Objective-C → [ADR-0002](../adr/0002-binding-c-abi-shim.md).
4. **webrtc-java 1.0 — сильный ориентир и запасной план для JVM.** Его набор возможностей (Encoded Transform, SFrame, аппаратные кодеки, захват экрана и системного звука) почти совпадает с целями krtc для desktop. Но он даёт Java API и собственный JNI, который не переиспользовать на Android и Kotlin/Native. Решение — в ADR-0002.
5. **Готовые prebuilt-сборки** (stasel/WebRTC для Apple, GetStream для Android, webrtc-sdk) — хороший источник патчей и ориентир по флагам сборки. Но зависеть от чужих бинарников для полного покрытия нельзя: нужны сборки с нашим шимом внутри → [ADR-0008](../adr/0008-libwebrtc-versioning.md).
6. **Серверный сценарий.**
   - Продакшн-SFU пишут на отдельном лёгком стеке (Pion, mediasoup, JVB), а не на libwebrtc.
   - Sans-IO подход str0m и Kotlin-код JVB показывают, как строить серверное ядро на JVM.
   - Отсюда отдельный модуль протоколов на чистом Kotlin → [ADR-0007](../adr/0007-module-structure.md), [ADR-0009](../adr/0009-server-side-use.md).

## Что взять из каждого проекта

| Откуда | Что |
|---|---|
| webrtc-java | Структура JNI-слоя, набор prebuilt-таргетов, API для Encoded Transform и SFrame, интеграция аппаратных кодеков |
| livekit webrtc-sys | Схема сборки и публикации libwebrtc, решение проблемы `libc++`, патчи |
| stasel/WebRTC, GetStream/webrtc | Флаги gn для Apple и Android, патчи `sdk/objc` и `sdk/android` |
| webrtc-kmp | Ошибки, которых стоит избежать: ручное подключение pod на iOS, отставание ревизии |
| Jitsi Videobridge, str0m | Архитектура будущего `krtc-sfu`: sans-IO ядро, маршрутизация RTP, оценка полосы |
| aiortc, Pion | Партнёры для interop-тестов ([этап 6 плана](../PLAN.md)) |
