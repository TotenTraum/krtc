# ADR-0002. Биндинг — собственный C ABI-шим `krtc-native`

- Статус: Предложено (подтверждается spike, этап 10)
- Дата: 2026-11-09

## Контекст

У libwebrtc только C++ API, стабильного C API нет. Ограничения со стороны Kotlin:
- Kotlin/Native через cinterop понимает только C и Objective-C, C++ напрямую не подключить.
- Android и JVM ходят в нативный код через JNI. Panama FFM на Android недоступен.
- Официальные SDK платформ (`org.webrtc` на Android, `WebRTC.framework` на Apple) покрывают спецификацию неполно: нет Encoded Transform, часть параметров и статистики урезана. Кроме того, они расходятся между собой в поведении и именах.
- Для desktop JVM есть зрелый [webrtc-java 1.0](../research/existing-solutions.md), но его JNI-слой нельзя переиспользовать на Android и Kotlin/Native.

## Решение

1. Написать собственный тонкий шим на C++ с **плоским C ABI** (`krtc_*` функции, непрозрачные хэндлы, колбэки через указатели на функции и `void* user_data`).
2. Шим линкуется статически вместе с libwebrtc в одну нативную библиотеку на платформу.
3. Наружу экспортируются только символы `krtc_*` (`-fvisibility=hidden`). C++ и `libc++` libwebrtc не выходят за её пределы.
4. Подключение:
   - **Kotlin/Native** (iOS, macOS, Linux, Windows): cinterop по заголовку `krtc.h`;
   - **Android и JVM**: один JNI-слой поверх того же `krtc.h`. Kotlin-часть общая (`jvmAndAndroidMain`), JNI-код генерируется или пишется один раз.
5. Платформенные части libwebrtc используются внутри шима, а не через их публичные SDK:
   - Android: Java-классы `sdk/android` (Camera2, MediaCodec, ScreenCapturerAndroid) нужны для захвата и аппаратных кодеков. Они поставляются в `.aar` и связываются с шимом через JNI.
   - Apple: компоненты `sdk/objc` (AVFoundation-захват, VideoToolbox, Audio Unit) собираются в шим как Objective-C++.
6. API шима повторяет модель W3C (PeerConnection, Transceiver, Sender, Receiver, DataChannel, Track, Stats), а не C++ API libwebrtc. Kotlin-слой над ним тонкий.

## Альтернативы

| Вариант | Почему нет |
|---|---|
| Официальные SDK платформ (`org.webrtc` + `WebRTC.framework`, как webrtc-kmp) | Быстрый старт, но потолок покрытия задаёт чужой SDK, а desktop и Linux не закрыты. Допустимо как временный путь для MVP, если spike провалится |
| webrtc-java для `jvm` | Готово и зрело, но это отдельный Java API со своей моделью. Покрытие ограничено его поверхностью, и вторую реализацию для Android и K/N всё равно писать. Оставить как запасной вариант и источник идей |
| Panama FFM для JVM | Нет на Android. Возможно позже как оптимизация desktop-JVM поверх того же `krtc.h` |
| Swift/ObjC export из libwebrtc для Apple + cinterop ObjC | Работает только на Apple, другим платформам не помогает |

## Последствия

- Одинаковое покрытие и поведение на всех нативных платформах, Encoded Transform и полная статистика доступны везде.
- Свой C-заголовок становится внутренним контрактом между C++ и Kotlin. Его можно генерировать частично из инвентаря (`spec/inventory.json`).
- Нужна своя сборка libwebrtc + шима для каждого таргета ([ADR-0008](0008-libwebrtc-versioning.md)), чужие prebuilt не подходят.
- **Главный технический риск — `libc++`.** libwebrtc собирается со своей встроенной `libc++` (LiveKit из-за этого требует clang 21+). Полная изоляция C++ внутри библиотеки с плоским C ABI должна это снять: проверяется spike.
- Колбэки из нативных потоков в Kotlin требуют аккуратного управления жизненным циклом хэндлов ([ADR-0005](0005-threading.md)).

## Как проверить / когда пересмотреть

- Spike: `krtc.h` с 10–15 функциями (factory, peer connection, offer/answer, ICE, data channel) собирается с libwebrtc M157. Kotlin/Native (`macosArm64`) и JVM через JNI выполняют loopback.
- Отдельно проверить: размер итоговой библиотеки, отсутствие лишних экспортируемых символов (`nm`), линковку iOS-фреймворка вместе с другим кодом на C++ в приложении.
- Если `libc++` не изолируется — откат на официальные SDK для Android и Apple и на webrtc-java для desktop с новым ADR.
