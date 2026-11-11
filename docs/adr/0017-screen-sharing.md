# ADR-0017. Демонстрация экрана

- Статус: Принято
- Дата: 2026-11-11
- Уточняет: [ADR-0010](0010-sfu-before-1.0.md), [ADR-0014](0014-spike-krtc-native.md), [ADR-0015](0015-sfu-transport-and-congestion.md)

## Контекст

Участник мог отправлять только микрофон и камеру. Для показа экрана нужны три вещи:
- **второй видеопоток от участника**, независимый от камеры;
- **отдельная обработка:** экран не масштабируют simulcast-слоями, и разрешение для него важнее частоты кадров;
- **захват на каждой платформе клиента.**

## Решение

### SFU

- **`VideoFeed`** — видеопоток участника:
  - камера: до трёх слоёв simulcast;
  - экран: один поток, считается верхним слоем.

  У каждого `VideoFeed` свои SSRC и RTX SSRC, кэш для NACK, слои и ключ (`id` у камеры, `id/screen` у экрана).
- **Через `VideoFeed` идёт вся пересылка видео:** выбор слоя, NACK и RTX, PLI, SR, оценка канала (ADR-0015).
- **Раскладка m-line в предложении:**
  - `0` микрофон, `1` камера, `d` data channels;
  - `s` — показ экрана на отправку, без `a=simulcast`;
  - в каждом слоте собеседника: `2+2i` аудио, `3+2i` камера и `s<i>` его экран (`sendonly`, пока он показывает, иначе `inactive`).

  У экрана свой MediaStream (`screen-<id>`), он не синхронизируется с микрофоном.
- **К какому потоку относится входящий пакет:** по расширению MID, иначе по SSRC из ответа (`a=ssrc` на m-line `s`), иначе по запомненному SSRC. По умолчанию — камера.
- **Сигнализация:**
  - `Offer.screens` — список mid, по которым идут экраны;
  - `ClientMessage.Renegotiate` — клиент просит новое предложение, чтобы начать или прекратить показ (и опубликовать камеру, если вошёл без неё). Направление m-line `s` клиент меняет в ответе на это предложение.
- **API хоста:**
  - `SfuConfig.screenShare` (по умолчанию включено);
  - `ParticipantInfo.sharesScreen` и `screenMuted`;
  - `setMuted(screen = …)`;
  - событие `SfuEvent.ScreenShareChanged`.

### Клиент

- `RoomClient.setScreen(track)`. Начало и конец показа идут через `renegotiate`, замена одного трека экрана другим — без неё.
- `RemoteParticipant.screen` — трек экрана собеседника.
- `setCamera` тоже просит новое предложение, если камера публикуется впервые.

### Захват в `krtc-native`

| Платформа | Как | Источники |
|---|---|---|
| macOS | `DesktopCapturer` из libwebrtc на ScreenCaptureKit; разрешение Screen Recording (`CGRequestScreenCaptureAccess`) | Дисплеи и окна |
| Linux | `XGetImage` по корневому окну X11. libX11 загружается через `dlopen`, клиент не зависит от X11 при линковке. Wayland не поддерживается: нужны PipeWire и портал | Дисплей X11 |
| Windows | `DesktopCapturer` (DXGI/GDI, окна) | Дисплеи и окна |
| iOS | ReplayKit, захват внутри приложения (`startCaptureWithHandler`) | Экран этого приложения |
| Android | `AndroidScreenCapture`: MediaProjection → VirtualDisplay → ImageReader RGBA → `pushRgba`. Foreground-сервис `ScreenCaptureService` типа `mediaProjection` объявлен в манифесте библиотеки | Весь экран или одно приложение (выбор в системном диалоге) |

- **Общий API:**
  - `nativeScreenSources()`;
  - `factory.createScreenSource(source, fps, maxWidth, maxHeight)`;
  - `factory.createScreencastSource()` — для своего захвата;
  - `NativeVideoSource.pushRgba`.
- **Источник экрана помечен как screencast**, его трек получает подсказку контента `detailed`. Кодер libwebrtc сохраняет разрешение и резкость и первой снижает частоту кадров.
- **Кадры масштабируются до `maxWidth × maxHeight`**, по умолчанию 1920×1080.
- **Демо:** флаг `screen` показывает экран после входа (через `renegotiate`). Android-демо сначала открывает системный диалог согласия.
- **Тестовая страница:** кнопка «Показать экран» (`getDisplayMedia`, `contentHint = 'detail'`), экраны собеседников — отдельными плитками.

## Проверка (2026-11-11)

| Что | Как | Результат |
|---|---|---|
| Согласование | `ScreenShareTest`, тест `RoomClientSfuTest` | `s` без simulcast; начало и конец показа через `renegotiate`; `screens` и `mids`; SSRC экрана из ответа; клиент не путает экран с камерой |
| Chrome ↔ Chrome | Две вкладки, экран — canvas 1920×1080 | Камера и экран приходят отдельно; остановка и повторный показ; 0 ошибок SRTP |
| Linux (arm64, Docker с Xvfb) | Демо с `screen`, в Chrome | 1600×900, видны окна X11. Красный фон приходит красным (254, 2, 0): порядок каналов верный |
| Android 16 (эмулятор) | Демо с `screen`, «Share entire screen» в диалоге | 864×1920 (вписано в лимит), интерфейс телефона виден в Chrome |
| macOS | Демо из этой сессии | Отказ, как и ожидалось: процесс без разрешения Screen Recording. Проверить из Terminal.app |
| iOS, Windows | — | Только сборка (ReplayKit не работает в консольном процессе симулятора; Windows без машины) |

## Альтернативы

| Вариант | Почему нет |
|---|---|
| Экран как ещё один слой simulcast камеры | Камера и экран — разные источники с разными SSRC и кодерами. Получатель хочет видеть оба |
| Экран как второй «участник» (отдельный id) | Ломает модель участника (имя, речь, выход); хосту нужно знать, чей это экран |
| Всегда `sendonly` на `s`, без `renegotiate` | Остальным пришлось бы предлагать m-line без медиа, и пустой трек выглядел бы как показ |
| Захват на Linux через `DesktopCapturer` (XDamage/PipeWire) | Тянет libXdamage, libXfixes, libXrandr или glib и портал. `XGetImage` — шесть функций через `dlopen` |

## Последствия

- В предложении SFU появились m-line `s` и `s<i>`. Клиенты, сопоставляющие m-line по `mids`, этого не замечают, а клиенты без поддержки показа просто не используют `s`. `SfuConfig(screenShare = false)` возвращает прежнюю раскладку.
- `ServerMessage.Offer` получил поле `screens` (при отсутствии — пустой список), `ClientMessage` — `Renegotiate`. ABI обновлён.
- Нерешённое:
  - Linux Wayland (PipeWire и портал);
  - системный показ на iOS (Broadcast Upload Extension);
  - звук экрана;
  - simulcast для экрана;
  - проверка на macOS, iOS и Windows вживую.
