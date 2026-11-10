# ADR-0014. Итоги spike `krtc-native`: C-прослойка над готовой libwebrtc

- Статус: Принято для macOS arm64, Linux arm64 и x64, iOS (симулятор; устройство — только сборка), desktop JVM и Android arm64: звонок с Chrome через `krtc-sfu` работает на каждой (см. «Звонок», «Linux», «iOS», «JVM и Android»). Windows не проверен
- Дата: 2026-11-10
- Проверяет: [ADR-0001](0001-engine-libwebrtc.md), [ADR-0002](0002-binding-c-abi-shim.md), [ADR-0008](0008-libwebrtc-versioning.md), [ADR-0012](0012-kmp-client.md)

## Что сделано

1. **Движок.** Готовая статическая libwebrtc от LiveKit, релиз `webrtc-a545de4` (2026-10-23), архив `webrtc-mac-arm64-release.zip`:
   - 277 МБ в архиве, `libwebrtc.a` — 741 МБ;
   - SHA-256 архива: `153a1f1a…ff09e206`;
   - лежит в `krtc-native/.deps`, в репозиторий не входит.
2. **C-прослойка** `krtc-native/src/shim/krtc.h` / `krtc.cpp`: 36 функций с плоским C ABI.
   - фабрика со своими тремя потоками; аудиоустройство на выбор — синтетическое (`krtc_audio.cpp`: PCM приложения или тестовый тон, без звуковой карты) или системное;
   - видеокодеки платформы (`krtc_apple.mm`: `RTCDefaultVideoEncoderFactory`/`DecoderFactory` — VideoToolbox H.264/H.265, VP8, VP9, AV1) с откатом на VP8;
   - `getStats` (JSON отчёта libwebrtc), предпочтение кодека (`setCodecPreferences`) и выбор кодека отправки (`RTCRtpEncodingParameters.codec`);
   - PeerConnection (Unified Plan): описания и answer асинхронно, события `connectionState` и `track`;
   - трансиверы, треки, тестовый источник видео, счётчик входящих кадров.

   Собирается `build-shim.sh` с дефайнами из `webrtc.ninja` и `-fvisibility=hidden`. Скомпилировалась с первого раза.
3. **Бэкенд `krtc-core`** (`krtc-native`, `macosArm64`):
   - cinterop поверх `krtc.h`; прослойка и libwebrtc упакованы в klib, фреймворки macOS — в её `linkerOpts`;
   - `NativePeerConnectionFactory`, `NativePeerConnection`, трансиверы и треки реализуют интерфейсы `krtc-core`;
   - асинхронность через `suspendCancellableCoroutine`, колбэки с потока сигнализации кладутся в `Flow` без блокировки (ADR-0005).
4. **Демо-клиент** `krtc-native-demo`: `krtc-client` + наш бэкенд, входит в комнату `krtc-sfu`, публикует тестовое видео и печатает статистику принятого видео.

## Результаты

| Вопрос | Ответ |
|---|---|
| Совместимость `libc++` на macOS | Проблемы нет: сборка LiveKit для macOS сделана с `use_custom_libcxx=false`, то есть против системной `libc++` (`std::__1`). Прослойка собирается Apple clang 21 из Xcode |
| Совместимость `libc++` на Linux | Решено (см. «Linux»): у LiveKit `use_custom_libcxx=true` (`std::__Cr`), прослойка собирается clang 21 против их встроенной `libc++` (`-nostdinc++`, их `__config_site`) |
| Линковка | C-тест: 15,8 МБ. Kotlin/Native-демо (debug): 22,6 МБ. Ни одной неразрешённой зависимости |
| Изоляция символов | Наша прослойка экспортирует только `krtc_*`. Но сборка LiveKit сделана с `rtc_enable_symbol_export=true`, поэтому исполняемый файл экспортирует 2652 символа libwebrtc. Для продукта — своя сборка без экспорта (ADR-0008) или `-exported_symbols_list` |
| **Конфликт TLS-библиотек** | Внутри `libwebrtc.a` экспортированы BoringSSL-функции (`SSL_new`, `SSL_CTX_new`, `EVP_EncryptInit_ex`…). `krtc-sfu` на нативе использует OpenSSL с теми же именами. **Клиентский движок и нативный SFU нельзя линковать в один бинарь** без переименования символов BoringSSL (`boringssl_prefix_symbols`) в собственной сборке |
| Кодеки | Встроенной C++-фабрики видеокодеков в сборке нет, зато есть ObjC-фабрики SDK. Через `ObjCToNativeVideoEncoderFactory` получаем H.264 (VideoToolbox), VP8, VP9, AV1; без них — шаблонная фабрика VP8 |
| Выбор кодека отправки | Как answerer libwebrtc отправляет **первым кодеком предложения**, а не своего ответа: `setCodecPreferences` меняет только порядок в answer. Нужный кодек задаётся после согласования через `RTCRtpEncodingParameters.codec` (`sender.setParameters`). Альтернатива — порядок кодеков в предложении SFU |
| Формат `getStats` | `RTCStatsReport::ToJson()` отдаёт массив объектов статистики, а не объект по id |

## Звонок

Нативный `krtc-native-demo` (Kotlin/Native + `krtc-client` + этот бэкенд + libwebrtc) и вкладка Chrome в одной комнате `krtc-sfu-server` (2026-11-10):

| Направление | Результат |
|---|---|
| Соединение | ICE и DTLS-SRTP за ~3 с; на сервере `dtlsConnected=2`, `srtpFailures=0`, `stunRejected=0` |
| Chrome → нативный клиент | ~15 кадров/с. Сначала 640×360, затем сервер переключил на верхний слой simulcast 1280×720 |
| Нативный клиент → Chrome | VP8 640×360, 14,5 кадров/с, потерь 0. С предпочтением H.264: кодер VideoToolbox (`encoderImplementation`), Chrome декодирует H.264 640×360 |
| Звук нативный клиент → Chrome | Тестовый тон 440 Гц через синтетическое аудиоустройство, Opus ~48 пакетов/с. В Chrome RMS принятого трека 0,158, чередуется с тишиной по схеме 0,5 с / 0,5 с |
| Звук Chrome → нативный клиент | ~37 пакетов Opus/с, RMS декодированного микса ~0,1 |
| Завершение | `leave()` и освобождение PeerConnection, треков и фабрики — без ошибок, код выхода 0 |

**Первая попытка не прошла ICE (`FAILED`)**, хотя сервер принимал проверки:
- `krtc-sfu-server` слушал `0.0.0.0` и объявлял кандидат `127.0.0.1`;
- libwebrtc не использует loopback и отправляет проверки с адреса сетевого интерфейса;
- ответ уходил с другого исходного адреса, чем тот, куда клиент стучался, и libwebrtc его отбрасывал (Chrome при этом работал через loopback).

**Решение:** сервер слушает и объявляет один и тот же адрес — добавлена опция `--bind`. Для продакшна это и так обязательное правило. Позже стоит отвечать с адреса назначения входящего пакета (`IP_PKTINFO`), тогда `0.0.0.0` тоже будет работать.

## Linux

Linux arm64 и x64 проверены в Docker на маке (2026-11-10): сборки LiveKit `webrtc-linux-arm64-release.zip` (164 МБ, SHA-256 `e87dff71…b5d13c5f`) и `webrtc-linux-x64-release.zip` (148 МБ, SHA-256 `2ece73b9…443ac0bd`). x64 собирается и запускается в amd64-контейнере (эмуляция); прослойка компилируется ~4 минуты, исполняемый файл кросс-компилирует Kotlin/Native.

**Сборка прослойки.** В контейнере `krtc-native/docker/Dockerfile` (Debian bookworm, clang 21 с apt.llvm.org) скриптом `docker-build-shim.sh`. Кодеки — `krtc_software.cpp`: VP8 и VP9 (libvpx), H.264 (OpenH264 и FFmpeg), AV1 (libaom и dav1d).

**Три проблемы линковки с Kotlin/Native и их решения:**

| Проблема | Решение |
|---|---|
| PulseAudio-модуль libwebrtc вызывает `XOpenDisplay`/`XQueryKeymap`, чтобы отслеживать нажатия клавиш | Переходники в прослойке подгружают `libX11.so.6` через `dlopen`; без неё клавиши просто не отслеживаются. Клиенту не нужен X11 |
| Внутри libwebrtc — свои `libc++abi` (`std::terminate`, `std::logic_error`, …) и BoringSSL; первая конфликтует с `libstdc++` рантайма Kotlin/Native, BoringSSL — с OpenSSL из Ktor-движка curl | **Изоляция символов:** `ld.lld -r` собирает прослойку и только нужные ей объекты libwebrtc в один объект, `objcopy --keep-global-symbols` оставляет глобальными только 36 функций `krtc_*`. Результат — `libkrtc_webrtc.a` (35 МБ), его и упаковывает klib |
| В libgcc из sysroot Kotlin/Native (gcc 8.3, glibc 2.25) нет outline-атомиков AArch64 (`__aarch64_cas4_acq`, …) | В тот же объект добавлены builtins compiler-rt |

Из библиотек остаются нужны только glibc и `libgcc_s`; демо запускается в голом `debian:bookworm-slim`. `krtc-client` на Linux теперь использует Ktor CIO вместо curl: CIO не тянет статический OpenSSL (после изоляции curl тоже совместим, но CIO легче).

**Звонок** Linux-клиента в контейнере (SFU и Chrome на маке):

| Направление | Результат |
|---|---|
| Соединение | ICE и DTLS-SRTP прошли через NAT Docker (кандидат peer-reflexive) |
| Linux → Chrome | arm64 и x64: H.264 (OpenH264) 640×360, 14–15 кадров/с, потерь 0; тон доходит, RMS в Chrome ~0,15–0,3 |
| Chrome → Linux | Видео 640×360, затем 1280×720, ~15 кадров/с; Opus ~37 пакетов/с, уровень ~0,1 |

Выход: изоляция символов, задуманная «для продукта», понадобилась уже сейчас. На macOS её стоит сделать тем же способом (`ld -r` + `-exported_symbols_list`), тогда клиент и нативный SFU можно будет линковать в один бинарь.

## iOS

Сборки LiveKit `webrtc-ios-simulator-arm64-release.zip` (SHA-256 `3a1e245f…7d8d240a`) и `webrtc-ios-device-arm64-release.zip` (`a4297461…d86428a3`), обе с системной `libc++`, как на macOS. Прослойка — тот же `krtc_apple.mm` (VideoToolbox), `build-shim.sh … ios-simulator | ios`; таргеты `iosSimulatorArm64` и `iosArm64`, фреймворки iOS в `linkerOpts.ios`.

- Единственная правка: в iOS-сборке `AudioDeviceModule::GetPlayoutAudioParameters`/`GetRecordAudioParameters` — чисто виртуальные (`#if WEBRTC_IOS`), синтетическое устройство их реализует.
- Демо — исполняемый файл Kotlin/Native, запускается в симуляторе через `xcrun simctl spawn` без приложения.
- Звонок из симулятора (iPhone 17 Pro, iOS 26): H.264 через VideoToolbox 640×360, ~14 кадров/с, потерь 0; тон доходит; видео Chrome 1280×720 и Opus принимаются.
- `iosArm64` (устройство) собирается и линкуется, на телефоне не запускался.

## JVM и Android

По ADR-0002 — один JNI-слой поверх того же `krtc.h`: `krtc_jni.cpp` (C++) и `Jni.kt` + `JniWebRtc.kt` в общем source set `jvmAndAndroidMain`. Публичный API общий для всех платформ: `NativePeerConnectionFactory` и `NativeTrack` объявлены `expect` в `commonMain`, поэтому приложение создаёт бэкенд прямо из общего кода. Демо (`runDemo`) тоже общее.

- **Библиотека** `libkrtc_jni` (`build-jni.sh`) экспортирует только `Java_krtc_native_*` и `JNI_OnLoad`; libwebrtc, её C++-рантайм и BoringSSL остаются внутри — это и есть изоляция символов на macOS для JVM.
- **Desktop JVM (macOS arm64):** dylib лежит в jar (`krtc/native/macos-arm64/`) и распаковывается при загрузке. Звонок: H.264 (VideoToolbox) 640×360 без потерь, тон доходит, видео Chrome 1280×720 принимается.
- **Android arm64:** сборка LiveKit `webrtc-android-arm64-release.zip` (SHA-256 `1e481a9b…5b5b68f3`) собрана с `libc++` NDK; прослойка и `.so` — NDK r29, `libc++_static`, AAR несёт `jni/arm64-v8a/libkrtc_jni.so` (21 МБ). Кодеки — программные из `krtc_software.cpp`: в этой сборке только VP8 и AV1 (H.264 и VP9 у LiveKit на Android рассчитаны на MediaCodec через Java-классы `org.webrtc`, их пока не подключали).
- **Совместимость `libc++`:** libwebrtc для Android собрана против более новой `libc++` из Chromium и ссылается на `std::__ndk1::__hash_memory`, которого нет в NDK r29. Прослойка определяет его тем же алгоритмом, что и запасной вариант в новых заголовках (`krtc_android.cpp`); `.so` линкуется с `--no-undefined`, так что других пропусков нет.
- **Звонок** из эмулятора (Android 16, arm64, `krtc-native-android-demo`): VP8 640×360, 15 кадров/с, потерь 0; тон доходит (RMS в Chrome ~0,13–0,17); видео Chrome 1280×720 и Opus принимаются, уровень ~0,106.
- **Пакет `krtc.native`:** `native` — ключевое слово Java. Kotlin и JNI это не мешает, но из Java классы недоступны и такое имя нельзя взять namespace Android-приложения. Перед публикацией пакет стоит переименовать.

## Медиа: камера, свои кадры, вывод

Общий API (`commonMain`, все платформы):
- `NativePeerConnectionFactory.createVideoSource()`: приложение кладёт кадры `pushI420` или `pushArgb`. `createVideoTrack(source)` делает из источника трек.
- `createCameraSource(index, w, h, fps)` и `nativeCameras()` — встроенный захват камеры.
- `NativeTrack.setFrameListener { frame -> }`: каждый кадр трека (принятого или своего), уже повёрнутый, в ARGB (`0xAARRGGBB`). Массив переиспользуется между кадрами одного размера.
- `NativeVideoSource` потокобезопасен: кадры после `close()` отбрасываются, поэтому поток захвата может пережить источник.

Прослойка: `krtc::PushVideoSource`; ARGB-sink через libyuv (`I420ToARGB`, `ARGBToI420`). В JNI кадр передаётся в Kotlin через `int[]`, который переиспользуется.

| Платформа | Камера | Проверено |
|---|---|---|
| macOS, iOS | `RTCCameraVideoCapturer` (AVFoundation) → свой `RTCVideoCapturerDelegate` → `ObjCToNativeVideoFrameBuffer`. Перед стартом запрашивается разрешение; при отказе — `null` | Камера перечисляется («Камера (MacBook Air)»). Захват не проверен: процессу из этой сессии macOS не показывает запрос (у приложения-хоста нет описания доступа к камере), и честно выдаётся `null`. Из Terminal.app запрос появится |
| Linux | V4L2 через классы `DeviceInfoV4l2` и `VideoCaptureModuleV4L2` напрямую. `VideoCaptureFactory` не годится: тянет камеру через PipeWire/xdg-portal, а ей нужен glib | Перечисление в контейнере (камер нет); бинарь по-прежнему зависит только от glibc |
| Android | `AndroidCamera` (Camera2 в Kotlin): YUV_420_888 → I420 → `pushI420` с поворотом сенсора | Эмулятор: Chrome получает 720×1280 VP8 с картинкой виртуальной камеры, потерь 0 |
| Android, звук | `AndroidAudio`: AudioRecord (`VOICE_COMMUNICATION`, системные AEC/NS) → `pushAudio`, `pullAudio` → AudioTrack | Собирается; эмулятор запускался без звука |

Вывод проверен везде, где был звонок:
- **macOS (native и JVM):** кадры Chrome отрисованы, средний цвет (106, 35, 108) совпадает с тем, что Chrome показывает у себя (112, 47, 110);
- **Android:** приняты и отрисованы и кадры Chrome, и камера второго Android-клиента;
- **push:** кадры, сгенерированные в Kotlin, Chrome принимает 640×360 с ожидаемым цветом.

## Что не проверено

- **Системное аудиоустройство** (`AudioDevice.PLATFORM`): собирается, в звонке не проверялось, чтобы не шуметь.
- **Simulcast H.264 через VideoToolbox**: идёт только один слой из трёх; не разбирались.
- **Windows**; **iOS на устройстве**; **desktop JVM на Linux и Windows** (нужны `libkrtc_jni` под них).
- **Камера на macOS** (нужен запуск из приложения с описанием доступа или из Terminal.app), **на Linux** (нет устройства), **на iOS** (у симулятора нет камеры).
- **Android:** MediaCodec (аппаратный H.264), звук `AndroidAudio` в звонке, ABI кроме arm64-v8a.
- **Системное аудио на Linux** (PulseAudio/ALSA).

## Решения по итогам

1. **Путь ADR-0002 работает**: плоский C ABI, cinterop, всё упаковано в klib. Отката на официальные SDK не требуется.
2. **Готовые сборки LiveKit — для разработки и spike.** Для продукта — своя сборка (ADR-0008), в которой:
   - переименованы символы BoringSSL;
   - выключен экспорт символов libwebrtc;
   - на Linux прослойка собирается тем же clang и встроенной `libc++`.
3. **Следующие шаги для клиента:**
   - изоляция символов и для Kotlin/Native на macOS/iOS (как на Linux);
   - Android: MediaCodec, остальные ABI;
   - Windows (сборки LiveKit `win-x64`/`win-arm64` есть);
   - переименовать пакет `krtc.native`.
