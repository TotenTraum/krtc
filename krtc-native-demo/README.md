# krtc-native-demo

Диагностический клиент на `krtc-client` и `krtc-native`: общий код для JVM, Android и Kotlin/Native (macOS, Linux, iOS, Windows). Демо находится в отдельном модуле; библиотека `krtc-native` не зависит от SDK комнат.

Подготовка libwebrtc и нативных инструментов описана в [krtc-native/README.md](../krtc-native/README.md#подготовка).

## Сборка

Из корня репозитория, выбрав нужные платформы:

```bash
./gradlew :krtc-native-demo:linkDemoDebugExecutableMacosArm64 :krtc-native-demo:linkDemoDebugExecutableLinuxArm64 :krtc-native-demo:linkDemoDebugExecutableLinuxX64 :krtc-native-demo:linkDemoDebugExecutableIosSimulatorArm64 :krtc-native-demo:linkDemoDebugExecutableIosArm64 :krtc-native-demo:linkDemoDebugExecutableMingwX64 :krtc-native-demo:jvmDemoDistZip :krtc-native-android-demo:assembleDebug
```

`krtc-native-android-demo` — Android-приложение, использующее общий код этого модуля. Имена исполняемых файлов и архивов сохранены; задачи и результаты сборки находятся в `krtc-native-demo`.

## Демо

Запусти `krtc-sfu-server` с `--announce <LAN-IP>` (libwebrtc не ходит через loopback; `--bind` не нужен) и открой вкладку Chrome в комнате `test`.

Аргументы демо: адрес сервера, комната, имя, секунды, видеокодек (`-` — без предпочтения) и флаги:
- `push-video` — кадры, сгенерированные в Kotlin (API своих кадров);
- `camera` — камера 0 (на macOS запускай из Terminal.app, чтобы система спросила разрешение);
- `restart-ice` — ICE restart на 5-й секунде;
- `chat` — сообщение в комнату каждую секунду (data channels); полученные сообщения демо печатает всегда;
- `screen` — показ экрана после входа:
  - macOS: запускать из Terminal.app, первый раз система спросит разрешение на запись экрана, и показ заработает со следующего запуска;
  - Linux: нужен X11 (`DISPLAY`);
  - Android: системный диалог, выбрать «весь экран»; звук других приложений идёт вместе с экраном (нужен RECORD_AUDIO);
  - звук экрана берётся автоматически: macOS — ScreenCaptureKit, Linux — монитор PulseAudio или PipeWire, Windows — WASAPI loopback.

Захват экрана (`createScreenSource`): macOS 13+ — ScreenCaptureKit и для экранов, и для окон (система сама уменьшает картинку на видеокарте и отдаёт NV12 без копирования; неподвижная картинка повторяется дважды в секунду); раньше и при ошибке — DesktopCapturer libwebrtc. Windows — экраны через DXGI, окна через Windows Graphics Capture (Windows 10 1809+; видит игры на DirectX), иначе GDI. Linux — X11. Для динамичного видео и игр ставьте треку `contentHint = "motion"`: кодер держит частоту кадров, а не чёткость.

Принятое видео отрисовывается через frame listener, демо печатает средний цвет кадра.

```bash
krtc-native-demo/build/bin/macosArm64/demoDebugExecutable/krtc-native-demo.kexe ws://<LAN-IP>:8092 test mac-client 30 video/H264
```

```bash
docker run --rm --platform linux/arm64 -v "$PWD/krtc-native-demo/build/bin/linuxArm64/demoDebugExecutable:/app" debian:bookworm-slim /app/krtc-native-demo.kexe ws://<LAN-IP>:8092 test linux-client 30 video/H264
```

```bash
xcrun simctl spawn booted "$PWD/krtc-native-demo/build/bin/iosSimulatorArm64/demoDebugExecutable/krtc-native-demo.kexe" ws://<LAN-IP>:8092 test ios-client 30 video/H264
```

```bash
./gradlew :krtc-native-demo:runJvmDemo --args="ws://<LAN-IP>:8092 test jvm-client 30 video/H264"
```

```bash
adb shell am start -n krtc.demo.android/.DemoActivity --es args "'ws://<LAN-IP>:8092 test android-client 30 video/VP8'"
```

Вывод Android-демо — в `adb logcat -s System.out`.

Windows: `krtc-native-demo/build/bin/mingwX64/demoDebugExecutable` (`krtc-native-demo.exe` и `krtc.dll`) копируется на машину целиком.

```bat
krtc-native-demo.exe ws://<LAN-IP>:8092 test windows-client 30 video/H264
```

## Сборки CI

[`.github/workflows/builds.yml`](../.github/workflows/builds.yml) на каждый push в `main` и вручную (Actions → builds → Run workflow) прогоняет тесты и собирает тестовые сборки. Их можно скачать на странице запуска, в разделе Artifacts. Хранятся 14 дней.

| Артефакт | Что внутри | Как запустить |
|---|---|---|
| `krtc-macos-arm64` | `krtc-sfu.kexe`, `krtc-native-demo.kexe` | `chmod +x *.kexe`, затем `xattr -d com.apple.quarantine *.kexe` (файл из браузера), запуск как в разделе «Демо» |
| `krtc-ios` | демо для симулятора и для устройства (без подписи) | Симулятор: `xcrun simctl spawn booted ./krtc-native-demo-simulator.kexe ws://<LAN-IP>:8092 test ios 30`. Сборку для устройства нужно подписать самому |
| `krtc-jvm-demo-macos` | `krtc-native-jvm-demo.zip` (JVM-клиент, macOS arm64) | Java 21+, `unzip`, затем `krtc-native-jvm-demo/bin/krtc-native-demo ws://<LAN-IP>:8092 test jvm 30` |
| `krtc-jvm-demo-linux` | `krtc-native-jvm-demo-linux.zip` (JVM-клиент, Linux x64 и arm64) | Java 21+, `unzip`, затем `sh krtc-native-jvm-demo/bin/krtc-native-demo ws://<LAN-IP>:8092 test jvm 30` |
| `krtc-meet-macos`, `krtc-meet-linux`, `krtc-meet-windows` | приложение krtc-meet: `.dmg`, `.deb` (x64), `.msi` (x64), без подписи | См. [krtc-meet/README.md](../krtc-meet/README.md) |
| `krtc-linux-x64`, `krtc-linux-arm64` | `krtc-sfu.kexe`, `krtc-native-demo.kexe` (оба зависят только от glibc, OpenSSL вшит) | `chmod +x *.kexe`, затем `./krtc-sfu.kexe --announce <LAN-IP>` и демо как в разделе «Демо» |
| `krtc-sfu-jvm` | `krtc-sfu-jvm.zip` (SFU на JVM, любая ОС) | Java 21+, `unzip`, затем `krtc-sfu-jvm/bin/krtc-sfu --announce <LAN-IP>` (Windows: `bin\krtc-sfu.bat`) и открыть `http://<host>:8080` |
| `krtc-android` | `krtc-native-demo.apk` (arm64) | `adb install krtc-native-demo.apk`, запуск как в разделе «Демо» |
| `krtc-windows-x64` | `krtc-native-demo.exe` + `krtc.dll`, `krtc-native-jvm-demo-windows.zip` | `krtc-native-demo.exe ws://<LAN-IP>:8092 test windows 30` (`krtc.dll` рядом с exe). JVM: Java 21+, распаковать, `bin\krtc-native-demo.bat ws://...`. SFU для Windows — `krtc-sfu-jvm` |

Ограничения:
- **Windows кросс-компилируется на Linux** (clang-cl и xwin в Docker). На настоящем Windows сборки ещё не запускались ([ADR-0014](../docs/adr/0014-spike-krtc-native.md)). Нативного SFU под Windows нет: в Windows-сборке его заменяет `krtc-sfu-jvm`.
- **Linux arm64:** прослойка собирается в эмулируемом arm64-контейнере, это самый долгий шаг.

Сборки можно получить и локально, теми же задачами Gradle: `:krtc-sfu-server:jvmDistZip`, `:krtc-native-demo:jvmDemoDistZip`, `linkDemoDebugExecutable<Target>`. Какие нативные библиотеки попадут в JVM-jar, задают свойства:
- `-Pkrtc.jvm.macos=false`;
- `-Pkrtc.jvm.windows=false`;
- `-Pkrtc.jvm.linux=false` (Linux x64 и arm64 собираются в Docker);
- `-Pkrtc.jvm.windowsDll=<путь>` — готовая `krtc.dll` вместо сборки в Docker. Так CI собирает `.msi` на Windows, где Docker нет.
