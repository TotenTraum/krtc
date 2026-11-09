# ADR-0011. `krtc-sfu` на Kotlin Multiplatform: общее ядро, платформенные швы

- Статус: Принято
- Дата: 2026-11-09
- Уточняет: [ADR-0010](0010-sfu-before-1.0.md) — первая версия сервера была только для JVM.

## Контекст

Весь код krtc пишется на Kotlin Multiplatform. Для сервера допустимы таргеты Kotlin/Native (Linux, macOS) наряду с JVM. Первая версия `krtc-sfu` использовала JCE, BouncyCastle, NIO и `java.util.concurrent` прямо в логике SFU.

## Решение

**Всё в `commonMain`, кроме трёх платформенных швов** (`expect`/`actual`):

| Шов | JVM | Kotlin/Native |
|---|---|---|
| AES-CTR, AES-GCM, HMAC-SHA1, CSPRNG | JCE (AES-NI) | OpenSSL 3 EVP через cinterop |
| DTLS-SRTP сервер и самоподписанный сертификат | BouncyCastle `bctls` на виртуальном потоке | OpenSSL `DTLS_server_method`, memory BIO на вход, свой BIO с колбэком на выход (границы датаграмм сохраняются), одна корутина на сессию |
| UDP | NIO `DatagramChannel` × N с `SO_REUSEPORT` | POSIX-сокеты × N с `SO_REUSEPORT` |

**Общий код:**
- SRTP/SRTCP, комнаты, переговоры, ICE-lite, RTCP, сигнализация (Ktor CIO + WebSockets — они есть и для натива), метрики.
- Тестовая страница вкомпилирована строкой: у натива нет ресурсов classpath. Её генерирует задача `generateWeb`.

**Многопоточность без JVM-примитивов:**
- шард — `newSingleThreadContext` и ограниченный `Channel`: при перегрузке пакеты отбрасываются и учитываются;
- блокировки — `kotlinx.atomicfu.locks`;
- конкурентные таблицы — `io.ktor.util.collections.ConcurrentMap`;
- счётчики — `kotlin.concurrent.atomics`, свои у каждого шарда;
- время — `TimeSource.Monotonic`;
- логирование — `KtorSimpleLogger`.

**OpenSSL на нативе:**
- macOS — Homebrew `openssl@3`, линкуется статически: исполняемый файл не зависит от системных библиотек.
- Linux — системные `libssl`/`libcrypto` (OpenSSL 3), линкуются динамически. Путь задаётся `-Popenssl.prefix`.
- Макросы, которых не видит cinterop, обёрнуты в короткие C-функции прямо в `openssl.def`.

## Альтернативы

| Вариант | Почему нет |
|---|---|
| Криптография и DTLS на чистом Kotlin | AES без аппаратного ускорения в разы медленнее. DTLS с ECDHE/ECDSA/X.509 — собственная реализация криптопротокола, высокий риск |
| ktor-network для UDP | Аллокации на каждую датаграмму и корутинный оверхед на горячем пути. POSIX/NIO проще и быстрее |
| Только Kotlin/Native | JVM-сборка полезна для отладки, профилирования и использования внутри JVM-сервисов |

## Последствия

- Одни и те же тесты (`commonTest`: векторы RFC 3711/7714, переговоры) идут на JVM и macOS-нативе.
- Сборка Linux-бинарей требует Linux-хоста с OpenSSL 3. На macOS для Linux проверяется только компиляция.
- Нативный исполняемый файл для macOS — ~16 МБ (debug), стартует за миллисекунды.

## Как проверено

- `jvmTest` и `macosArm64Test` зелёные, `compileKotlinLinuxX64` проходит.
- В Chromium через нативный и JVM-сервер:
  - аудио и видео (VP8 640×360, 15 кадров/с) ходят без потерь;
  - SRTCP работает в обе стороны;
  - при искусственных 5% потерь (`--simulate-loss 5`) сервер восстанавливает пакеты через NACK/RTX без единого PLI.
