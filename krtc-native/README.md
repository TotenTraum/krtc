# krtc-native (spike)

Бэкенд `krtc-core` на libwebrtc через собственную C-прослойку (ADR-0002). Итоги и ограничения — [ADR-0014](../docs/adr/0014-spike-krtc-native.md).

| Платформа | Таргет | Привязка | Звонок с Chrome |
|---|---|---|---|
| macOS arm64 | `macosArm64` | cinterop | проверен |
| Linux arm64, x64 | `linuxArm64`, `linuxX64` | cinterop, изолированный `libkrtc_webrtc.a` | проверен в Docker |
| iOS | `iosSimulatorArm64`, `iosArm64` | cinterop | симулятор проверен, устройство — только сборка |
| desktop JVM (macOS arm64) | `jvm` | JNI, dylib в jar | проверен |
| Android arm64 | `android` | JNI, `.so` в AAR | проверен на эмуляторе |
| Windows x64 | `mingwX64`, `jvm` | cinterop через импорт-библиотеку `krtc.dll`; JNI, `krtc.dll` в jar | только сборка; Wine: звонок `mingwX64` с macOS-клиентом, JNI до сети. На Windows не запускался |

Публичный API общий (`commonMain`): `NativePeerConnectionFactory`, `NativeTrack`, `AudioDevice`. Data channels — через `RTCPeerConnection.createDataChannel` и `ondatachannel` из `krtc-core` ([ADR-0016](../docs/adr/0016-data-channels.md)).

## Использование

На Windows при `platformVideoCodecs=true` фабрика ищет аппаратный H.264 через Media Foundation
(драйверы NVIDIA/AMD/Intel, поддерживающие этот API). Если такой кодировщик обнаружен и
`preferredVideoCodec` не задан, клиент предпочитает H.264 при согласовании. Явное предпочтение
кодека имеет приоритет; без аппаратного H.264 сохраняется обычный выбор кодеков.

Аппаратный путь использует NV12, constrained baseline / packetization-mode 1 и режим низкой задержки.
При неподдерживаемых параметрах, ошибке драйвера, смене несовместимого размера или таймауте
выходного кадра libwebrtc переключается на программный OpenH264 в том же RTP-потоке.
При отсутствии Media Foundation (например, Windows N без Media Feature Pack) библиотека
продолжает работать с программными кодеками. `platformVideoCodecs=false` оставляет программный VP8.
Это аппаратное кодирование с копированием кадров через системную память, без zero-copy захвата.

В журнале приложения `screen out: … encoder=MediaFoundation H264` показывает аппаратный путь;
после fallback имя меняется на программный кодировщик. Причины отказа доступны в `getStats()`
как дополнительные записи `type=krtc-encoder-diagnostic` (последние 64 события на весь процесс,
чтение не удаляет события; `id` позволяет убрать повторы). Они содержат имя MFT, размеры/FPS,
этап и HRESULT ошибки, таймаут или несовместимость выходного H264. `tMs` — монотонное время,
`tid` — Windows thread ID. Сообщения также пишутся в stderr. `krtc-meet` переносит новые события
в обычный локальный журнал и серверный журнал, если его отправка включена.

Windows smoke-тест собирается внутри `krtc-win-toolchain` командой `./test-windows-encoder.sh`.
Полученный `.deps/build/tests/windows-encoder/windows-encoder-test.exe` запускается на Windows:
без аргументов допускает любой кодировщик, `--require-hardware` требует реальную работу GPU,
`--require-software` проверяет окружение без GPU (в том числе Wine). Он также принудительно
проверяет fallback при ошибке инициализации и при ошибке кодирования первого кадра.

Для примера с `RoomClient` подключаются обе библиотеки: `krtc-native` и `krtc-client`. Сам бэкенд не зависит от SDK комнат.

```kotlin
val factory = NativePeerConnectionFactory(preferredVideoCodec = "video/H264")
val camera = factory.createCameraSource()                 // macOS/iOS/Linux/Windows; на Android — AndroidCamera(context, factory.createVideoSource())
    ?: factory.createVideoSource()                        // или свои кадры: source.pushArgb(w, h, pixels) / pushI420(...)
val client = RoomClient("wss://sfu.example.com", factory)
client.join("team", "Alice", microphone = factory.createAudioTrack(), camera = factory.createVideoTrack(camera))

// Сообщения комнаты (data channels через SFU).
launch { client.data.collect { m -> println("${m.from}: ${m.payload.decodeToString()}") } }
client.dataOpen.first { it }
client.sendData("привет".encodeToByteArray(), topic = "chat")                 // всем; to = listOf(id) — адресно
client.sendData(cursorBytes, topic = "cursor", reliable = false)              // без повторов

// Демонстрация экрана (ADR-0017): дисплей или окно; на Android — AndroidScreenCapture после диалога согласия.
val screen = factory.createScreenSource(nativeScreenSources().firstOrNull())   // null — нет захвата или не разрешено
val sound = factory.createSystemAudioSource()                                  // системный звук; null — недоступно
screen?.let { client.setScreen(factory.createVideoTrack(it, "screen"), sound?.let { s -> factory.createAudioTrack(s, "screen-audio") }) }
// client.setScreen(null) — прекратить. Свой PCM: factory.createAudioSource().push(samples, channels, sampleRate).
// Экран собеседника: participant.screen и participant.screenAudio (StateFlow треков).

// Вывод: каждый кадр собеседника в ARGB (0xAARRGGBB), уже повёрнутый.
client.participants.value.first().video.value?.let { track ->
    (track as NativeTrack).setFrameListener { frame -> render(frame.width, frame.height, frame.argb) }
}
```

Шумоподавление: кроме обработки libwebrtc (`setAudioProcessing`) есть нейросетевое — `setNeuralNoiseSuppression(true)`, RNNoise на 48 кГц после обработки и до шлюза ввода. Оно убирает изменчивый шум (клавиатура, вентилятор, улица), который классическое пропускает; белый шум в тесте почти исчезает. Выключено по умолчанию.

Частота микрофона может быть ниже 48 кГц (например, у Bluetooth-гарнитуры): перед RNNoise звук пересчитывается в 48 кГц и обратно. При смене частоты или числа каналов состояние обработки сбрасывается. Проверка 8/16/32/48 кГц, моно/стерео и смены форматов после сборки прослойки: `sh ./test-denoiser.sh .deps/mac-arm64/mac-arm64-release .deps/build/mac-arm64 macos` (на Linux — пути своей сборки и `linux`).

Звук: `AudioDevice.PLATFORM` — системный микрофон и динамик (Apple, Linux, Windows). Устройства перечисляются в `factory.audioInputs` и `factory.audioOutputs`, выбираются через `setAudioInput(i)` и `setAudioOutput(i)`, в том числе во время звонка. Флаг демо `devices` печатает их список. `AudioDevice.SYNTHETIC` — PCM через `pushAudio`/`pullAudio`; на Android к нему подключается `AndroidAudio(factory).start()`. Подробности по платформам — в [ADR-0014](../docs/adr/0014-spike-krtc-native.md#медиа-камера-свои-кадры-вывод).

Повторный выбор активного устройства не перезапускает звук, в том числе когда единственное устройство уже используется по умолчанию. Проверки без звукового оборудования: `sh ./test-audio-devices.sh` (повторный выбор, выбор другого устройства, неверные индексы и ошибки переключения/перезапуска).

Явно выбранные устройства сохраняются в фабрике по идентификатору (если он доступен) и имени и восстанавливаются после создания PeerConnection. `audioInputIndex` / `audioOutputIndex` читают текущий индекс из ADM; если бэкенд не поддерживает чтение, возвращают индекс последнего успешно применённого устройства в актуальном списке. Тесты выбора проверяют также сброс на системное устройство и перестановку Bluetooth-устройств.

На macOS 13+ список источников и захват используют ScreenCaptureKit напрямую, с одинаковыми ID дисплеев и окон. Окна без заголовка показываются под именем приложения; окна на других рабочих столах и поверх остальных не отбрасываются. `createScreenSource(null)` выбирает основной дисплей. Захват считается запущенным после получения первого кадра. Проверка преобразования метаданных без разрешения «Запись экрана»: `sh ./test-macos-screen-sources.sh` (macOS 13+, Xcode).

## Подготовка

Готовые libwebrtc от LiveKit (релиз `webrtc-a545de4`) распаковываются в `.deps/<платформа>` (каталог не коммитится). Туда же скачиваются исходники [RNNoise](https://github.com/xiph/rnnoise) 0.2 (Xiph, BSD-3-Clause; архив сверяется по sha256): они компилируются в прослойку на всех платформах и дают `setNeuralNoiseSuppression`. Платформы: `mac-arm64`, `linux-arm64`, `linux-x64`, `ios-simulator-arm64`, `ios-device-arm64`, `android-arm64`, `win-x64`:

```bash
krtc-native/fetch-deps.sh                    # все платформы; или только нужные: fetch-deps.sh mac-arm64 android-arm64
```

Linux собирается в Docker (`docker-build-shim.sh`, clang 21). Для desktop JVM на Linux `docker-build-shim.sh <x64|arm64> jni` собирает ещё `libkrtc_jni.so`. Она изолирована так же, как библиотека Kotlin/Native: наружу видны только точки входа JNI, а libc++ и BoringSSL из libwebrtc остаются внутри. Нужна ей только glibc. Windows x64 — тоже в Docker, кросс-компиляцией: clang-cl и lld-link 21, MSVC CRT и Windows SDK через xwin (`docker-build-shim.sh win`, `docker/windows/Dockerfile`; сборка образа принимает лицензию Microsoft на эти файлы). Результат — одна `krtc.dll` (прослойка, libwebrtc, JNI) для JVM и `mingwX64`. Kotlin/Native-приложению под Windows нужна `krtc.dll` рядом с `.exe`. Android — Android NDK (`ANDROID_NDK_HOME` или новейший в `~/Library/Android/sdk/ndk`).

## Сборка

Библиотека JVM (свойства ниже позволяют выбрать платформы JNI):

```bash
./gradlew :krtc-native:jvmJar
```

Демо-клиент, его исполняемые файлы и JVM-дистрибутив вынесены в [krtc-native-demo](../krtc-native-demo/README.md). Android-приложение демо — `krtc-native-android-demo`.

Какие нативные библиотеки попадут в JVM-jar:
- `-Pkrtc.jvm.macos=false`;
- `-Pkrtc.jvm.windows=false`;
- `-Pkrtc.jvm.linux=false`;
- `-Pkrtc.jvm.windowsDll=<путь>` — готовая `krtc.dll` вместо сборки в Docker.
