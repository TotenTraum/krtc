# spec-inventory

Строит инвентарь спецификаций WebRTC из [`@webref/idl`](https://github.com/w3c/webref) и поддерживает матрицу покрытия.

```bash
npm ci
npm run generate            # пересобрать spec/*
npm run generate -- --check # для CI: падает, если spec-coverage.yaml не синхронизирован с IDL
```

- `scope.mjs`: какие IDL-файлы входят в скоуп (`core` / `extended` / `excluded`), список платформ и статусов.
- `generate.mjs`: парсит IDL (webidl2) и пишет в `spec/`:
  - `inventory.json`, `CHECKLIST.md`: генерируются целиком, руками не править;
  - `spec-coverage.yaml`: правится руками (`status`, `tests`, `notes`). При перегенерации правки сохраняются,
    новые пункты добавляются как `planned`, исчезнувшие уходят в секцию `removed:`;
  - `COVERAGE.md`: сводка по `spec-coverage.yaml`.

Обновление версии спецификаций: поднять `@webref/idl` в `package.json`, `npm install`, `npm run generate`, затем просмотреть diff и секцию `removed:`.

## Kotlin-скелет (`krtc-idl`)

```bash
npm run generate:kotlin            # пересобрать krtc-idl/src/commonMain/generated
npm run generate:kotlin -- --check # для CI: падает, если сгенерированный код устарел
```

`generate-kotlin.mjs` проецирует WebIDL уровней `core` и `extended` на Kotlin один к одному (ADR-4 в `docs/PLAN.md`).
Модуль `krtc-idl` — сырой API в форме W3C, его не правят руками. Идиоматичный публичный API (этап 5) строится поверх него.

| IDL | Kotlin |
|---|---|
| interface, mixin, partial | `interface`; наследование и `includes` превращаются в супертипы, partial-определения из других спецификаций вливаются в тип |
| constructor, static | `companion object`, делегирует в `KrtcFactory` (реализуется на каждой платформе, ставится в `Krtc.factory`) |
| attribute / `EventHandler` | `val`/`var` / `Flow<EventType>` (тип события берётся из `@webref/events`) |
| operation с `Promise<T>` | `suspend fun` с результатом `T` |
| dictionary | `data class` (лист) или `open class` (есть наследники), дефолты сохраняются |
| enum | `enum class` со строкой IDL в `value` и `fromValue()` |
| union | `sealed interface` из `value class` |
| maplike/setlike/iterable | супертип `Map`/`Set`/`Iterable` |
| числа | `Int`; `unsigned long` и `long long` → `Long`; `double` → `Double` |
| буферы | `ByteArray` и другие примитивные массивы |
| типы вне скоупа | enum и callback копируются из своей спецификации, остальное — заглушки в `Placeholders.kt` |

- API из спецификаций уровня `extended` помечен opt-in-аннотацией `@KrtcExtendedApi`.
- Устаревшие callback-перегрузки (`createOffer(success, failure)` и т.п.) помечены `@Deprecated`.
- В KDoc каждого элемента есть его ID из `spec-coverage.yaml` и ссылка на спецификацию. Якоря ссылок построены по соглашению `#dom-<тип>-<член>` и могут не совпасть.
- Пакет задаётся в `KOTLIN_PACKAGE` в `scope.mjs`.

## Привязка web-platform-tests

```bash
npm run wpt:fetch              # скачать нужные папки WPT на ревизии из wpt.lock в .cache/wpt (~4 МБ)
npm run wpt:fetch -- --update  # передвинуть wpt.lock на свежий master WPT
npm run wpt:map                # spec/wpt-map.json и spec/WPT.md
npm run all                    # inventory → wpt:map → COVERAGE.md с колонкой WPT → Kotlin-скелет
```

`map-wpt.mjs` статически разбирает тесты (ничего не запускает) и связывает их с пунктами инвентаря. Сила связи:

- **name**: файл назван по пункту (`RTCPeerConnection-createOffer.html`).
- **ref**: тест или его воркер/частный хелпер обращается к пункту (`.createOffer(`, `new RTCPeerConnection(`, ключ `iceServers:`, строка `'max-bundle'`, `onicecandidate`/`addEventListener('icecandidate')`).
  - Общие имена (`id`, `state`, `name`…) засчитываются, только если в тесте назван владелец.
  - Поля словарей связываются через поток типов: тест вызывает `getSettings()`, значит его `width` — это `MediaTrackSettings.width`. Для статистики словарь выбирается по строке `type` (`'outbound-rtp'` → `RTCOutboundRtpStreamStats`).
  - Общие хелперы, которые подключают больше 10 тестов (например, `RTCPeerConnection-helper.js`), не учитываются.
- **idl**: пункт покрыт только idlharness-тестом (проверка наличия и формы).

`ref` означает, что тест затрагивает пункт, а не проверяет его поведение. Это ориентир для портирования тестов, а не доказательство покрытия. Поле `tests` в `spec-coverage.yaml` остаётся для собственных тестов krtc.
