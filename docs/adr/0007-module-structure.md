# ADR-0007. Структура модулей

- Статус: Предложено
- Дата: 2026-11-09

## Контекст

Нужно разделить: сгенерированный эталон API, публичный API, нативный движок, медиа, UI и тесты. Кроме того, протокольный код на чистом Kotlin (SDP, RTP/RTCP, STUN) полезен и клиенту (тесты, отладка, munging SDP), и будущему серверу ([ADR-0009](0009-server-side-use.md)).

## Решение

```
krtc-idl          сгенерированный W3C-скелет (есть). Служебный, не публикуется как основной артефакт
krtc-protocol     чистый Kotlin, без нативных зависимостей, sans-IO:
                    SDP (разбор/сборка, JSEP-атрибуты), RTP/RTCP (заголовки, расширения, фидбэк),
                    STUN (сообщения, атрибуты). Все таргеты, включая JS/Wasm
krtc-core         публичный API (ADR-0004) + expect/actual:
                    nativeMain/jvmAndAndroidMain → krtc-native, webMain → браузер
krtc-native       C++-шим (ADR-0002), сборка libwebrtc (ADR-0008), cinterop и JNI.
                    Публикуется как платформенные артефакты (.aar, .xcframework, .klib, jar с нативными либами)
krtc-media        источники и приёмники: камера, экран, файлы, фейковые устройства (ADR-0006)
krtc-compose      видеорендереры для Compose Multiplatform
krtc-test-utils   loopback-пары, сетевые эмуляторы, фейковые устройства, хелперы WPT
krtc-conformance  порт тестов WPT (spec/wpt-map.json как план)
samples/          Android, iOS, desktop, web
krtc-signaling    протокол сигнализации SFU (общий для сервера и клиента)
krtc-sfu          встраиваемый SFU на krtc-protocol (ADR-0010, 0011, 0013)
krtc-sfu-ktor     маршрут Ktor для сигнализации SFU
krtc-sfu-server   готовое приложение SFU
krtc-client       клиент комнат SFU на krtc-core (ADR-0012)
```

**Зависимости** (стрелка — «зависит от»):
- `krtc-core` → `krtc-native` (кроме браузера), `krtc-protocol`;
- `krtc-media` → `krtc-core`;
- `krtc-compose` → `krtc-core`;
- `krtc-conformance` → `krtc-core`, `krtc-test-utils`;
- `krtc-sfu` → `krtc-protocol` (без `krtc-native`).

**Публикация:** BOM `krtc-bom` выравнивает версии. Group id выбирается перед первым релизом. Сейчас пакет-заглушка — `krtc.*` (`KOTLIN_PACKAGE` в `tools/spec-inventory/scope.mjs`).

## Альтернативы

- **Один большой модуль**: неприемлемо из-за размера (рендереры тянут Compose, медиа — тяжёлые зависимости) и невозможности переиспользовать протокольный код на сервере.
- **Разбор SDP только средствами libwebrtc**: недоступно в браузере и на будущем сервере, тяжело в тестах.

## Последствия

- `krtc-protocol` можно начинать писать и тестировать сразу, не дожидаясь сборки libwebrtc.
- Нужна дисциплина в `krtc-protocol`: никаких сокетов, потоков и `expect`-зависимостей от платформы. Только данные и чистые функции (sans-IO, как в str0m).

## Как проверить / когда пересмотреть

- При первых двух модулях (`krtc-protocol`, `krtc-core`) убедиться, что граф зависимостей не даёт циклов и `krtc-protocol` собирается под все таргеты, включая `wasmJs`.
