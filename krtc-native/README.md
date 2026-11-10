# krtc-native (spike)

Бэкенд `krtc-core` на libwebrtc через собственную C-прослойку (ADR-0002). Итоги и ограничения — [ADR-0014](../docs/adr/0014-spike-krtc-native.md).

| Платформа | Таргет | Привязка | Звонок с Chrome |
|---|---|---|---|
| macOS arm64 | `macosArm64` | cinterop | проверен |
| Linux arm64, x64 | `linuxArm64`, `linuxX64` | cinterop, изолированный `libkrtc_webrtc.a` | проверен в Docker |
| iOS | `iosSimulatorArm64`, `iosArm64` | cinterop | симулятор проверен, устройство — только сборка |
| desktop JVM (macOS arm64) | `jvm` | JNI, dylib в jar | проверен |
| Android arm64 | `android` | JNI, `.so` в AAR | проверен на эмуляторе |

Публичный API общий (`commonMain`): `NativePeerConnectionFactory`, `NativeTrack`, `AudioDevice`.

## Подготовка

Готовые libwebrtc от LiveKit (релиз `webrtc-a545de4`) распаковываются в `.deps/<платформа>` (каталог не коммитится). Платформы: `mac-arm64`, `linux-arm64`, `linux-x64`, `ios-simulator-arm64`, `ios-device-arm64`, `android-arm64`:

```bash
cd krtc-native/.deps && for p in mac-arm64 linux-arm64 linux-x64 ios-simulator-arm64 ios-device-arm64 android-arm64; do mkdir -p $p && curl -sSLo $p/w.zip https://github.com/livekit/rust-sdks/releases/download/webrtc-a545de4/webrtc-$p-release.zip && unzip -qo $p/w.zip -d $p; done
```

Linux собирается в Docker (`docker-build-shim.sh`, clang 21). Android — Android NDK (`ANDROID_NDK_HOME` или новейший в `~/Library/Android/sdk/ndk`).

## Сборка

```bash
./gradlew :krtc-native:linkDemoDebugExecutableMacosArm64 :krtc-native:linkDemoDebugExecutableLinuxArm64 :krtc-native:linkDemoDebugExecutableLinuxX64 :krtc-native:linkDemoDebugExecutableIosSimulatorArm64 :krtc-native:jvmJar :krtc-native-android-demo:assembleDebug
```

## Демо

Запусти `krtc-sfu-server` с `--bind` и `--announce` на LAN-адресе мака (libwebrtc не ходит через loopback) и открой вкладку Chrome в комнате `test`. Аргументы демо: адрес сервера, комната, имя, секунды, (необязательно) видеокодек.

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
