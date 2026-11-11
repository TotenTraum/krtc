# krtc-meet

Пример приложения для звонков на Compose Multiplatform поверх `krtc-client` и `krtc-native` ([ADR-0018](../docs/adr/0018-krtc-meet.md)). Один код интерфейса для desktop (JVM), Android и iOS.

Возможности:
- вход в комнату `krtc-sfu-server`;
- своё превью и видео собеседников, подсветка говорящих;
- экраны собеседников отдельными плитками;
- выключение микрофона и камеры;
- выбор микрофона и динамиков на экране входа и во время звонка (кнопка «Звук»). Выбор сохраняется до закрытия приложения.

## Запуск

Нужен сервер (`krtc-sfu-server`, см. его README). Адрес задаётся в окне входа, например `ws://<LAN-IP>:8080`.

```bash
./gradlew :krtc-meet:run
```

Запускаемое приложение `.app` (macOS) с описаниями доступа к камере и микрофону. Без них macOS молча отказывает:

```bash
./gradlew :krtc-meet:createDistributable
```

```bash
open krtc-meet/build/compose/binaries/main/app/krtc-meet.app
```

Установщики: `:krtc-meet:packageDmg` (macOS), `packageMsi` (Windows), `packageDeb` (Linux). Каждый собирается на своей ОС. Сборка `.dmg` есть в CI, артефакт `krtc-meet-macos`.

### Android

Модуль `krtc-meet-android`. Камера и микрофон запрашиваются при запуске. Сервер по умолчанию `ws://10.0.2.2:8080` — это хост, если смотреть из эмулятора.

```bash
./gradlew :krtc-meet-android:installDebug
```

Для проверок значения задаются через extras: `adb shell am start -n krtc.meet/krtc.meet.android.MainActivity --es server ws://<LAN-IP>:8080 --es name phone --es camera test --ez testAudio true --ez autojoin true`. В CI есть артефакт `krtc-meet-android`.

### iOS

Приложение собирается без проекта Xcode: Kotlin-исполняемый файл (`UIApplicationMain` и `ComposeUIViewController`) упаковывается в `.app` с `Info.plist` и подписывается ad-hoc. Это только для симулятора.

```bash
./gradlew :krtc-meet:assembleIosSimulatorApp
```

```bash
xcrun simctl install booted krtc-meet/build/ios/krtc-meet.app && xcrun simctl launch booted krtc.meet
```

Переменные `KRTC_MEET_*` передаются в симулятор с префиксом `SIMCTL_CHILD_`. Для устройства нужен проект Xcode с подписью: он показывает `MainViewController()` из `krtc-meet` (фреймворк `iosArm64`). Его пока нет.

На экране входа:
- «Тестовый звук» заменяет микрофон тоном и не воспроизводит чужой звук. Удобно, когда несколько окон открыто на одной машине;
- «Тестовая картинка» заменяет камеру.

## Для проверок

Переменные окружения:

| Переменная | Что делает |
|---|---|
| `KRTC_MEET_SERVER`, `KRTC_MEET_ROOM`, `KRTC_MEET_NAME` | Значения по умолчанию на экране входа |
| `KRTC_MEET_CAMERA=test\|none` | Тестовая картинка или без видео |
| `KRTC_MEET_TEST_AUDIO=1` | Тестовый звук |
| `KRTC_MEET_AUTOJOIN=1` | Войти сразу |
| `KRTC_MEET_SNAPSHOT=<file.png>` | Через `KRTC_MEET_SNAPSHOT_AFTER` секунд (по умолчанию 10) отрисовать интерфейс в PNG. Отрисовка идёт вне экрана, разрешение на запись экрана не нужно |
