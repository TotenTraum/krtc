# krtc-native (spike)

Бэкенд `krtc-core` на libwebrtc через собственную C-прослойку (ADR-0002). Таргеты: `macosArm64` и `linuxArm64`. Итоги и ограничения — [ADR-0014](../docs/adr/0014-spike-krtc-native.md).

## Подготовка

Готовая libwebrtc от LiveKit (релиз `webrtc-a545de4`) распаковывается в `.deps` (каталог не коммитится):

```bash
mkdir -p krtc-native/.deps && cd krtc-native/.deps && curl -LO https://github.com/livekit/rust-sdks/releases/download/webrtc-a545de4/webrtc-mac-arm64-release.zip && unzip -q webrtc-mac-arm64-release.zip -d mac-arm64
```

Для Linux arm64 — аналогично в `.deps/linux-arm64`:

```bash
mkdir -p krtc-native/.deps && cd krtc-native/.deps && curl -LO https://github.com/livekit/rust-sdks/releases/download/webrtc-a545de4/webrtc-linux-arm64-release.zip && unzip -qo webrtc-linux-arm64-release.zip -d linux-arm64
```

(На macOS `unzip` спросит про пару заголовков sysroot, отличающихся регистром; они не нужны, `-o` отвечает за вас.)

## Сборка

```bash
./gradlew :krtc-native:linkDemoDebugExecutableMacosArm64
```

```bash
./gradlew :krtc-native:linkDemoDebugExecutableLinuxArm64
```

Gradle собирает прослойку (`build-shim.sh`), генерирует cinterop и упаковывает прослойку и libwebrtc в klib. Для Linux прослойка собирается в Docker (`docker-build-shim.sh`, clang 21) и вместе с libwebrtc сводится в `libkrtc_webrtc.a`, который экспортирует только `krtc_*` (ADR-0014, «Linux»). Сам бинарь Kotlin/Native кросс-компилирует на маке.

## Демо

Сначала запусти `krtc-sfu-server` на порту 8092 с `--bind` и `--announce` на LAN-адресе мака (libwebrtc не ходит через loopback) и открой вкладку Chrome в комнате `test`:

```bash
krtc-native/build/bin/macosArm64/demoDebugExecutable/krtc-native-demo.kexe ws://127.0.0.1:8092 test native-client 30 video/H264
```

Linux-демо — в контейнере (нужны только glibc). SFU должен быть доступен по LAN-адресу мака:

```bash
docker run --rm --platform linux/arm64 -v "$PWD/krtc-native/build/bin/linuxArm64/demoDebugExecutable:/app" debian:bookworm-slim /app/krtc-native-demo.kexe ws://<LAN-IP>:8092 test linux-client 30 video/H264
```

Аргументы: адрес сервера, комната, имя, длительность в секундах и (необязательно) предпочтительный видеокодек. Демо отправляет тестовый тон и тестовое видео. Раз в секунду печатает принятое видео, раз в 5 секунд — кодеки и кодер отправки, число принятых аудиопакетов и уровень звука.
