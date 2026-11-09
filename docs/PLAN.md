# krtc — план подготовки WebRTC-библиотеки на Kotlin Multiplatform

Цель: KMP-библиотека с полным покрытием спецификаций WebRTC (W3C API + поведение IETF-протоколов).

## Этап 0. Определение «полного покрытия»

### 0.1. Спецификации W3C (API-слой)

| Спецификация | Что входит |
|---|---|
| WebRTC 1.0 | `RTCPeerConnection`, `RTCRtpSender`/`Receiver`/`Transceiver`, `RTCDataChannel`, `RTCDtlsTransport`, `RTCIceTransport`, `RTCSctpTransport`, `RTCCertificate`, `RTCDTMFSender`, `RTCIceCandidate`, `RTCSessionDescription`, `RTCError` |
| WebRTC Extensions | `setCodecPreferences`, кодек в encodings, `RTCRtpHeaderExtensionCapability` и пр. |
| WebRTC Statistics Identifiers | все типы `RTCStats` |
| WebRTC-SVC | `scalabilityMode` |
| Simulcast | RID, `sendEncodings` |
| WebRTC Encoded Transform | `RTCRtpScriptTransform`, insertable streams (E2EE) |
| WebRTC Priority Control | `priority`, `networkPriority` |
| Media Capture and Streams | `MediaStream`, `MediaStreamTrack`, constraints/capabilities, `enumerateDevices`, `devicechange` |
| Screen Capture | `getDisplayMedia` |
| mediacapture-extensions, Audio Output Devices | `setSinkId` и др. |
| WebRTC Identity | исключить из скоупа (почти нигде не реализована) |

### 0.2. Протоколы IETF (поведение на проводе)

- JSEP (RFC 9429), SDP (RFC 8866), BUNDLE (RFC 9143), rtcp-mux (RFC 5761).
- ICE (RFC 8445), Trickle ICE (RFC 8838), STUN (RFC 8489), TURN (RFC 8656), mDNS-кандидаты.
- DTLS-SRTP (RFC 5764), SCTP over DTLS (RFC 8261), DataChannel/DCEP (RFC 8831/8832).
- RTP/RTCP (RFC 3550, 4585), RID/Simulcast (RFC 8851/8853), TWCC, REMB.
- Кодеки: Opus, G.711, VP8, VP9, H.264, AV1, H.265 (опционально).

### 0.3. Уровни поддержки

Каждая фича × платформа: `full` / `partial` / `na` (платформа не умеет) / `planned`.
«Полное покрытие» = весь IDL представлен в API; там, где платформа физически не умеет —
документированная `NotSupportedError`.

### 0.4. Целевые платформы

`androidTarget`, `iosArm64`/`iosSimulatorArm64`, `macosArm64`/`macosX64`, `jvm` (desktop Win/Linux/mac),
`js(browser)`, `wasmJs`, `linuxX64`/`linuxArm64`, `mingwX64`. watchOS/tvOS — вне скоупа.

## Этап 1. Инвентаризация спецификации и матрица трассируемости

1. Машиночитаемый WebIDL из [w3c/webref](https://github.com/w3c/webref) (`@webref/idl`).
2. Генератор (`tools/spec-inventory`):
   - чек-лист всех интерфейсов / атрибутов / методов / событий / словарей / enum;
   - (далее) скелет Kotlin API в `commonMain`.
3. `spec/spec-coverage.yaml`: ID → платформа → статус → тест. По нему CI строит отчёт покрытия.
Статус: этап 1 сделан, см. `tools/spec-inventory`, `spec/` и модуль `krtc-idl` (Kotlin-скелет собирается под все 10 таргетов).
WPT привязаны в `spec/WPT.md`: прямой тест есть у 860 из 1137 core-пунктов, 77 не покрыты вообще (в основном `mediacapture-extensions`).
Инвентарь на `@webref/idl@3.86.0`: 1419 пунктов (core — 1143).

4. Связь с [web-platform-tests](https://github.com/web-platform-tests/wpt/tree/master/webrtc) (`webrtc/`, `mediacapture-streams/`).

## Этап 2. Изучение существующих решений

Статус: сделан, см. [research/existing-solutions.md](research/existing-solutions.md) (данные на 2026-11-09, stable — M157).

| Проект | Что взять |
|---|---|
| libwebrtc (Google) | Основной движок: медиа, кодеки, AEC/NS/AGC, ICE, DTLS |
| shepeliev/webrtc-kmp | Опыт KMP-обёртки и её ограничения |
| devopvoid/webrtc-java | JNI для desktop JVM, сборка libwebrtc под Win/Linux |
| GetStream/webrtc | Патчи и prebuilt-сборки |
| libdatachannel | Лёгкая C-альтернатива без медиадвижка |
| Pion (Go), webrtc-rs | Чистые реализации — оценка стоимости «с нуля» |
| aiortc | Читаемая реализация протоколов |

## Этап 3. Архитектурные решения (ADR)

Статус: решения оформлены в [docs/adr](adr/README.md), все со статусом «Предложено».
ADR-0001, 0002 и 0008 подтверждаются spike (этап 10). Ниже — краткое содержание.

- **ADR-1. Движок:** обёртка над libwebrtc (не реализация с нуля).
- **ADR-2. Биндинг:** собственный C ABI-шим `krtc-native` поверх C++ API libwebrtc;
  K/N — через `cinterop`, JVM/Android — через JNI. Официальные `org.webrtc`/`WebRTC.framework`
  не дают Encoded Transform и расходятся по поведению. Допустимый компромисс для MVP — официальные SDK.
- **ADR-3. Браузер (JS/Wasm):** тонкий маппинг на нативный API через `external`.
- **ADR-4. Модель API:** Promise → `suspend`, события → `Flow`, dictionary → `data class`,
  enum → `enum class`, `DOMException`/`RTCError` → `sealed class RTCException`, `AutoCloseable` + Cleaner.
  Максимальная близость к W3C.
- **ADR-5. Потоки:** маршалинг на signaling/worker/network-треды libwebrtc, настраиваемый диспетчер событий.
- **ADR-6. Медиа-I/O:** Camera2/CameraX, AVFoundation, V4L2, Media Foundation; экран — MediaProjection,
  ReplayKit, ScreenCaptureKit, PipeWire/X11, DXGI; аудиосессии; рендеринг — модуль под Compose Multiplatform.
- **ADR-7. Модули:**
  ```
  krtc-idl           (сгенерированный W3C-скелет, служебный)
  krtc-protocol      (чистый Kotlin, sans-IO: SDP, RTP/RTCP, STUN — для клиента и будущего сервера)
  krtc-core          (публичный API, expect/actual)
  krtc-native        (C++ → C шим, сборка libwebrtc)
  krtc-media         (захват камеры, микрофона, экрана)
  krtc-compose       (видео-рендереры)
  krtc-test-utils    (loopback, фейковые устройства, сетевые эмуляторы)
  krtc-conformance   (порт WPT-тестов)
  samples/           (Android, iOS, desktop, web)
  ```
- **ADR-8. Версия libwebrtc:** привязка к stable Chromium milestone, обновление каждые 2 milestone и
  внеочередное при исправлениях безопасности, свой набор патчей, свои prebuilt-артефакты.
- **ADR-9. Сервер:** серверный участник без устройств и пересылка закодированных кадров — в скоупе 1.0;
  полноценный SFU — отдельный модуль `krtc-sfu` на `krtc-protocol` после 1.0.

## Этап 4. Сборка libwebrtc (критический путь)

1. depot_tools + `gn`/`ninja`, конфигурации для android arm64/x64, ios, macos, linux x64/arm64, windows x64/arm64.
2. Флаги: `rtc_include_tests=false`, `is_component_build=false`, `use_custom_libcxx` (критично для K/N и JNI),
   H.264 (`rtc_use_h264`, `proprietary_codecs`), аппаратные кодеки.
3. Кэш (sccache / remote cache) и prebuilt-артефакты — пользователи не собирают libwebrtc сами.
4. Упаковка: `.aar`, `.xcframework` (+SPM/CocoaPods), нативные библиотеки в JVM-jar по classifier, `.klib`.
5. Контроль размера: strip, LTO, отключение модулей.

## Этап 5. Проектирование API

1. Скелет из IDL → ручная доводка до идиоматичного Kotlin.
2. API-ревью-документ с примерами: 1-на-1, simulcast, DataChannel, E2EE, статистика, ICE restart, perfect negotiation.
3. binary-compatibility-validator с первого дня.
4. KDoc со ссылками на разделы спецификаций, Dokka.

## Этап 6. Тестирование

| Уровень | Как |
|---|---|
| Unit | `commonTest`, мок-движок |
| Loopback | Два `RTCPeerConnection` в одном процессе |
| Conformance | Порт WPT → матрица покрытия |
| Interop | Chrome/Firefox/Safari через Playwright, Pion, aiortc |
| Сеть | `tc netem`, эмулятор libwebrtc, coturn в Docker |
| Фаззинг | SDP/STUN-парсинг |
| Стабильность | ASan/LSan для шима, soak-тесты |
| Устройства | Фейковые камеры/микрофоны в CI, Firebase Test Lab / BrowserStack |

## Этап 7. CI/CD

- Матрица: macOS (iOS/macOS/xcframework), Linux (Android/JVM/linux/JS), Windows (mingw/JVM).
- Отдельный пайплайн сборки libwebrtc с кэшем.
- Эмуляторы/симуляторы для instrumented-тестов.
- Отчёт о покрытии спецификации на каждый PR.
- Публикация: Maven Central, SPM/CocoaPods.

## Этап 8. Лицензии

- libwebrtc: BSD-3 + патентный грант; сохранить NOTICE всех third_party.
- H.264/H.265/AAC: OpenH264 от Cisco, только аппаратные кодеки или опциональный модуль.
- Лицензия krtc: Apache-2.0 или BSD-3; автогенерация third-party notices.

## Этап 9. Команда

C++/libwebrtc (узкое место), KMP/Gradle/cinterop, Android, iOS/macOS, сети и медиа, DevOps.

## Этап 10. Прототип (spike)

Сквозной срез на Android, iOS, JVM, JS: шим `PeerConnectionFactory` → loopback с аудио и DataChannel →
звонок с Chrome → замеры размера, времени сборки, стоимости JNI/cinterop.

## Дорожная карта

| Milestone | Содержание |
|---|---|
| M1 | PeerConnection, SDP, ICE, Trickle, DataChannel |
| M2 | getUserMedia, рендеринг, базовые кодеки, `getStats` |
| M3 | Transceivers, `setParameters`, simulcast, SVC, `setCodecPreferences`, DTMF |
| M4 | getDisplayMedia, enumerateDevices, setSinkId, constraints |
| M5 | Encoded Transform, Priority, полная статистика |
| M6 | Conformance ≥ 95% по WPT, стабилизация API, 1.0 |

Оценка подготовки: 8–12 недель для 3–4 человек. Риски: сборка libwebrtc, конфликты `libc++` с K/N,
аудиосессии iOS/Android, патенты на кодеки, размер бинарей.
