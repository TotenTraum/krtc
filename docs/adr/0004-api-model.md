# ADR-0004. Модель публичного API

- Статус: Предложено
- Дата: 2026-11-09

## Контекст

Спецификация описана на WebIDL для JavaScript: Promise, EventTarget, dictionary, union-типы, DOMException.
`krtc-idl` уже проецирует её на Kotlin один к одному, механически. Публичный API должен:
1. оставаться узнаваемым: спецификация служит документацией;
2. быть идиоматичным Kotlin: корутины, null-safety, sealed-типы;
3. не терять ни одного пункта инвентаря: трассируемость через ID в KDoc.

## Решение

**Два слоя:**
- `krtc-idl`: сгенерированный, 1:1, служебный. Источник проверки полноты: каждый пункт инвентаря должен иметь соответствие в публичном API (проверка в CI на этапе 5).
- `krtc-core`: публичный API, написанный вручную на основе скелета. Имена типов и членов берутся из спецификации без переименований, кроме перечисленных ниже правил.

**Правила маппинга:**

| Спецификация | krtc-core |
|---|---|
| `Promise<T>` | `suspend fun ...: T` |
| Атрибут состояния с событием изменения (`connectionState` + `onconnectionstatechange` и т.п.) | `val connectionState: StateFlow<RTCPeerConnectionState>` |
| Остальные события | `val onicecandidate: Flow<RTCPeerConnectionIceEvent>` (горячий `SharedFlow`, без replay) |
| dictionary | `data class` с дефолтами спецификации; иерархии — `open class` или `sealed` |
| enum | `enum class` с `value: String` |
| union | `sealed interface` (как в `krtc-idl`). Для частых случаев — перегрузки функций (`send(String)`, `send(ByteArray)`) |
| `DOMException` / `RTCError` | `sealed class RTCException : Exception` с подклассами по `name` (`InvalidStateError`, `InvalidAccessError`, `OperationError`, `NotSupportedError`, `InvalidModificationError`, `NotAllowedError`, `NotFoundError`, `NotReadableError`, `OverconstrainedError`…), `RTCError` — с `errorDetail` |
| `RTCStatsReport` | `Map<String, RTCStats>` с типизированной иерархией `sealed class RTCStats` по `RTCStatsType` + доступ к сырым полям для расширений |
| Конструкторы | Фабричные функции и конструкторы в `krtc-core`, реализация — через `expect`/`actual` |
| `navigator.mediaDevices` | Объект-точка входа `MediaDevices` (без `Navigator`) |
| Устаревшие callback-перегрузки | Не переносятся (`@Deprecated` остаётся только в `krtc-idl`) |

**Жизненный цикл:** нативные объекты реализуют `AutoCloseable`. `close()` идемпотентен. Страховка от утечек через Cleaner (JVM) и `createCleaner` (Native) с предупреждением в лог, а не тихим освобождением.

**Совместимость:** explicit API mode и binary-compatibility-validator с первого публичного релиза. Расширенный уровень помечен `@KrtcExtendedApi`.

## Альтернативы

- **Публиковать `krtc-idl` как публичный API**: он неидиоматичен (`Flow` для `StateFlow`-состояний, сырые union-типы, перегрузки legacy). Отвергнуто.
- **Своя «упрощённая» модель (как Client API у webrtc-java)**: удобно, но теряется связь со спецификацией и полнота. Высокоуровневые помощники можно добавить позже отдельным модулем поверх `krtc-core`.

## Последствия

- Пользователь, знающий JS API, узнаёт всё сразу.
- Проверка полноты автоматизируется: ID из `spec-coverage.yaml` должны встречаться в KDoc `krtc-core`.
- Ручной слой надо обновлять при изменении спецификации. Генератор показывает разницу (новые пункты появляются в `spec-coverage.yaml` как `planned`).

## Как проверить / когда пересмотреть

- API-ревью на этапе 5 на сценариях: звонок 1-на-1, perfect negotiation, simulcast, DataChannel, E2EE через Encoded Transform, статистика, ICE restart.
