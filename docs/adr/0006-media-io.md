# ADR-0006. Медиа-ввод/вывод и рендеринг

- Статус: Предложено
- Дата: 2026-11-09

## Контекст

`getUserMedia`, `getDisplayMedia`, `enumerateDevices`, `setSinkId` и рендеринг видео в спецификации опираются на браузер. На нативных платформах это разные системные API с разными разрешениями и жизненными циклами.

## Решение

**Берём платформенные реализации, которые уже есть в libwebrtc,** и пишем свои только там, где их нет.

| Платформа | Камера | Экран | Аудио |
|---|---|---|---|
| Android | `Camera2Capturer` (`sdk/android`) | `ScreenCapturerAndroid` (MediaProjection) | Java ADM (`sdk/android`), AudioManager |
| iOS | `RTCCameraVideoCapturer` (AVFoundation) | ReplayKit Broadcast Extension — свой модуль, передача кадров через App Group | Audio Unit ADM, AVAudioSession под управлением приложения |
| macOS | AVFoundation (`modules/video_capture`) | ScreenCaptureKit (`modules/desktop_capture`) | CoreAudio ADM |
| Linux | V4L2 (`modules/video_capture`) | PipeWire / X11 (`modules/desktop_capture`) | PulseAudio / ALSA ADM |
| Windows | Media Foundation / DirectShow | DXGI / WGC (`modules/desktop_capture`) | Core Audio ADM |
| Браузер | нативно | нативно | нативно |

**Разрешения** (камера, микрофон, экран) запрашивает приложение. krtc даёт помощники и понятные ошибки (`NotAllowedError`) по спецификации, но не владеет UI.

**Пользовательские источники и приёмники:**
- `VideoTrackSource` для своих кадров (I420, NV12, нативные текстуры, CVPixelBuffer);
- `AudioTrackSource` для PCM;
- `VideoSink`/`AudioSink` для доступа к декодированным кадрам.

Это закрывает обработку, оверлеи, запись и серверные сценарии.

**Режим без устройств:** фабрика с dummy ADM и без захвата для серверов, CI и тестов ([ADR-0009](0009-server-side-use.md)).

**Рендеринг** — отдельный модуль `krtc-compose` (Compose Multiplatform):
- Android: `SurfaceViewRenderer`/`TextureView`;
- Apple: Metal (`MTKView`);
- desktop: Skia;
- браузер: `<video>`.

Ядро от Compose не зависит.

**Аппаратные кодеки:** MediaCodec (Android), VideoToolbox (Apple), Media Foundation / NVENC / VA-API (desktop) — через фабрики кодеков libwebrtc и патчи из открытых сборок (webrtc-java, LiveKit, GetStream). Программные — VP8/VP9/AV1/Opus из libwebrtc, H.264 — см. этап 8 плана (лицензии).

## Альтернативы

- **Свой захват на всех платформах через krtc-media**: дублирует libwebrtc без выгоды. Исключение — iOS Broadcast Extension и, возможно, CameraX на Android, если `Camera2Capturer` окажется недостаточен.

## Последствия

- Большая часть захвата бесплатна, но поведение (выбор формата, constraints) определяется реализацией libwebrtc. Constraints спецификации придётся маппить на её возможности, а несоответствия отражать в матрице как `partial`.
- iOS-экран требует отдельного таргета расширения в приложении пользователя: нужен шаблон и документация.

## Как проверить / когда пересмотреть

- M2 дорожной карты: getUserMedia + рендеринг на всех платформах. Матрица покрытия для `mediacapture-streams` и `screen-capture` заполнена честными статусами.
