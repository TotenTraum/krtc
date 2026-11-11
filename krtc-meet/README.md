# krtc-meet

Пример приложения для звонков на Compose Multiplatform поверх `krtc-client` и `krtc-native` ([ADR-0018](../docs/adr/0018-krtc-meet.md)). Один код интерфейса для desktop (JVM), Android и iOS.

Возможности:
- вход в комнату `krtc-sfu-server`;
- своё превью и видео собеседников, подсветка говорящих;
- экраны собеседников отдельными плитками. К чужому показу экрана можно подключиться и отключиться от него; пока отключено, сервер его не пересылает;
- выключение микрофона и камеры. Собеседники видят, что они выключены;
- показ своего экрана или окна, со звуком системы или без. На Android выбор делается в системном окне;
- чат комнаты по data channel (тема `chat`, совместим с тестовой страницей сервера);
- громкость голоса каждого собеседника и звука его экрана, 0–300 %;
- выбор микрофона и динамиков на экране входа и во время звонка (кнопка «Звук»). Выбор сохраняется до закрытия приложения;
- обработка микрофона в меню «Звук»: адаптивная громкость (автоусиление), шумоподавление, эхоподавление;
- переподключение при обрыве связи с сервером: сессия возобновляется под тем же id, медиа не прерываются. Если сервер её уже забыл, приложение входит заново. Пока связи нет, сверху показывается баннер;
- индикатор связи: качество, задержка до сервера, потери. Плитка собеседника с плохой связью помечается;
- раскладки «Сетка» и «Докладчик»: большая плитка, под ней остальные. По щелчку плитка закрепляется. Без закрепления крупно показывается экран, иначе тот, кто говорил последним;
- слой simulcast по размеру плитки: маленькой плитке сервер шлёт 180p, средней 360p, большой 720p.

Проверки: `./gradlew :krtc-meet:jvmTest`. Два `MeetSession` с настоящим libwebrtc в комнате встроенного SFU:
- состояние «выключено», чат, подписка на экран, громкость, смена обработки;
- слой по размеру плитки, статистика связи;
- обрыв сигнализации (TCP-прокси рвёт соединения) с возобновлением;
- выход в любой момент.

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

Установщики: `:krtc-meet:packageDmg` (macOS), `packageMsi` (Windows), `packageDeb` (Linux). Каждый собирается на своей ОС, в нём только библиотеки своей ОС (свойства `krtc.jvm.*`, см. [krtc-native/README.md](../krtc-native/README.md)). В CI есть все три, без подписи:

| Артефакт | Установка |
|---|---|
| `krtc-meet-macos` (`.dmg`) | Первый запуск: правой кнопкой → «Открыть» |
| `krtc-meet-linux` (`.deb`, x64) | `sudo apt install ./krtc-meet_1.0.0-1_amd64.deb`, затем `/opt/krtc-meet/bin/krtc-meet` |
| `krtc-meet-windows` (`.msi`, x64) | Windows SmartScreen предупредит о неподписанном установщике |

### Linux

Desktop-приложение работает на Linux x64 и arm64: `krtc-native` загружает `libkrtc_jni.so` из jar. Для разработки на Linux достаточно `./gradlew :krtc-meet:run`.

С macOS приложение можно запустить в Linux-контейнере: собрать jar'ы с Compose для Linux и запустить под Xvfb. Нужны пакеты `xvfb`, `libegl1`, `libgl1`, `libxrender1`, `libxtst6`, `libxi6`, `fontconfig`.

```bash
./gradlew :krtc-meet:desktopClasspath -Pkrtc.meet.desktop=linux-arm64 -Pkrtc.jvm.macos=false -Pkrtc.jvm.windows=false
```

Затем в контейнере (например, `eclipse-temurin:21-jdk`) `java -cp` со всеми jar'ами из `krtc-meet/build/desktop-classpath`, главный класс `krtc.meet.MainKt`, с `xvfb-run` и переменными `KRTC_MEET_*`.

### Android

Модуль `krtc-meet-android`. Камера и микрофон запрашиваются при запуске. Сервер по умолчанию `ws://10.0.2.2:8080` — это хост, если смотреть из эмулятора.

```bash
./gradlew :krtc-meet-android:installDebug
```

Переменная `KRTC_MEET_LAYOUT=speaker` (desktop) открывает раскладку «Докладчик». Для проверок значения задаются через extras: `adb shell am start -n krtc.meet/krtc.meet.android.MainActivity --es server ws://<LAN-IP>:8080 --es name phone --es camera test --ez testAudio true --ez autojoin true`. В CI есть артефакт `krtc-meet-android`.

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
