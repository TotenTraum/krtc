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

// Вывод: каждый кадр собеседника в ARGB (0xAARRGGBB), уже повёрнутый.
client.participants.value.first().video.value?.let { track ->
    (track as NativeTrack).setFrameListener { frame -> render(frame.width, frame.height, frame.argb) }
}
```

Звук: `AudioDevice.PLATFORM` — системный микрофон и динамик (Apple, Linux, Windows). `AudioDevice.SYNTHETIC` — PCM через `pushAudio`/`pullAudio`; на Android к нему подключается `AndroidAudio(factory).start()`. Подробности по платформам — в [ADR-0014](../docs/adr/0014-spike-krtc-native.md#медиа-камера-свои-кадры-вывод).

## Подготовка

Готовые libwebrtc от LiveKit (релиз `webrtc-a545de4`) распаковываются в `.deps/<платформа>` (каталог не коммитится). Платформы: `mac-arm64`, `linux-arm64`, `linux-x64`, `ios-simulator-arm64`, `ios-device-arm64`, `android-arm64`, `win-x64`:

```bash
cd krtc-native/.deps && for p in mac-arm64 linux-arm64 linux-x64 ios-simulator-arm64 ios-device-arm64 android-arm64 win-x64; do mkdir -p $p && curl -sSLo $p/w.zip https://github.com/livekit/rust-sdks/releases/download/webrtc-a545de4/webrtc-$p-release.zip && unzip -qo $p/w.zip -d $p; done
```

Linux собирается в Docker (`docker-build-shim.sh`, clang 21). Windows x64 — тоже в Docker, кросс-компиляцией: clang-cl и lld-link 21, MSVC CRT и Windows SDK через xwin (`docker-build-shim.sh win`, `docker/windows/Dockerfile`; сборка образа принимает лицензию Microsoft на эти файлы). Результат — одна `krtc.dll` (прослойка, libwebrtc, JNI) для JVM и `mingwX64`. Kotlin/Native-приложению под Windows нужна `krtc.dll` рядом с `.exe`. Android — Android NDK (`ANDROID_NDK_HOME` или новейший в `~/Library/Android/sdk/ndk`).

## Сборка

```bash
./gradlew :krtc-native:linkDemoDebugExecutableMacosArm64 :krtc-native:linkDemoDebugExecutableLinuxArm64 :krtc-native:linkDemoDebugExecutableLinuxX64 :krtc-native:linkDemoDebugExecutableIosSimulatorArm64 :krtc-native:linkDemoDebugExecutableMingwX64 :krtc-native:jvmJar :krtc-native-android-demo:assembleDebug
```

## Демо

Запусти `krtc-sfu-server` с `--announce <LAN-IP>` (libwebrtc не ходит через loopback; `--bind` не нужен) и открой вкладку Chrome в комнате `test`.

Аргументы демо: адрес сервера, комната, имя, секунды, видеокодек (`-` — без предпочтения) и флаги:
- `push-video` — кадры, сгенерированные в Kotlin (API своих кадров);
- `camera` — камера 0 (на macOS запускай из Terminal.app, чтобы система спросила разрешение);
- `restart-ice` — ICE restart на 5-й секунде;
- `chat` — сообщение в комнату каждую секунду (data channels); полученные сообщения демо печатает всегда.

Принятое видео отрисовывается через frame listener, демо печатает средний цвет кадра.

```bash
krtc-native/build/bin/macosArm64/demoDebugExecutable/krtc-native-demo.kexe ws://<LAN-IP>:8092 test mac-client 30 video/H264
```

```bash
docker run --rm --platform linux/arm64 -v "$PWD/krtc-native/build/bin/linuxArm64/demoDebugExecutable:/app" debian:bookworm-slim /app/krtc-native-demo.kexe ws://<LAN-IP>:8092 test linux-client 30 video/H264
```

```bash
xcrun simctl spawn booted "$PWD/krtc-native/build/bin/iosSimulatorArm64/demoDebugExecutable/krtc-native-demo.kexe" ws://<LAN-IP>:8092 test ios-client 30 video/H264
```

```bash
./gradlew :krtc-native:runJvmDemo --args="ws://<LAN-IP>:8092 test jvm-client 30 video/H264"
```

```bash
adb shell am start -n krtc.demo.android/.DemoActivity --es args "'ws://<LAN-IP>:8092 test android-client 30 video/VP8'"
```

Вывод Android-демо — в `adb logcat -s System.out`.

Windows: `krtc-native/build/bin/mingwX64/demoDebugExecutable` (`krtc-native-demo.exe` и `krtc.dll`) копируется на машину целиком.

```bat
krtc-native-demo.exe ws://<LAN-IP>:8092 test windows-client 30 video/H264
```
