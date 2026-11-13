# Развёртывание krtc-sfu

## Готовые сборки

CI публикует всё, что ставят люди, как релиз GitHub: файлы скачиваются без входа, по постоянным ссылкам.

| Откуда | Что |
|---|---|
| `https://github.com/TotenTraum/krtc/releases/tag/nightly` | Последняя сборка `main`, обновляется при каждом пуше. Прямые ссылки: `…/releases/download/nightly/<файл>` |
| `https://github.com/TotenTraum/krtc/releases/tag/v<версия>` | Релиз по тегу `v*` |
| `ghcr.io/totentraum/krtc-sfu:nightly`, `:sha-<коммит>` | Образ SFU из `main` (linux/amd64, linux/arm64) |
| `ghcr.io/totentraum/krtc-sfu:<версия>`, `:latest` | Образ SFU из релиза |
| `oci://ghcr.io/totentraum/charts/krtc-sfu` | Helm-чарт, на тегах |

Файлы релиза: `krtc-meet-macos-arm64.dmg`, `krtc-meet-windows-x64.msi`, `krtc-meet-linux-amd64.deb`, `krtc-meet-android-arm64.apk`, `krtc-meet-ios-simulator.zip`, `krtc-sfu-linux-x64`, `krtc-sfu-linux-arm64`, `krtc-sfu-macos-arm64`, `krtc-sfu-jvm.zip` и `SHA256SUMS`.

Обновить сервер до последней сборки:

```bash
curl -fLo krtc-sfu https://github.com/TotenTraum/krtc/releases/download/nightly/krtc-sfu-linux-x64 && chmod +x krtc-sfu
```

krtc-meet при запуске сам проверяет, нет ли версии новее, и показывает ссылку на экране входа. Сборки из `main` (версия `1.0.<номер>`) следят за `nightly`, релизные — за последним релизом. Проверку можно выключить в меню «Звук».

## Docker

Образ — нативный сервер на `distroless/cc` (около 64 МБ), запускается не от root, файловая система может быть только для чтения.

```bash
docker run -d --name krtc-sfu --restart unless-stopped \
  -p 8080:8080 -p 50000:50000/udp -p 50000:50000/tcp \
  -e KRTC_ANNOUNCE=<публичный IP> \
  ghcr.io/totentraum/krtc-sfu:nightly
```

- `KRTC_ANNOUNCE` — адрес, по которому клиенты достают медиа. За NAT это внешний адрес.
- Порт медиа внутри и снаружи должен совпадать: сервер объявляет свой порт. Если нужен другой, задайте его и там, и там: `-e KRTC_UDP_PORT=50020 -p 50020:50020/udp -p 50020:50020/tcp`.
- Другие параметры: `KRTC_*` или аргументы после имени образа, см. `krtc-sfu-server`. Журналы клиентов: `--client-logs /var/lib/krtc/client-logs` и том на этот путь.
- Собрать образ самому: `deploy/docker/build-image.sh krtc-sfu:dev --load` (одна архитектура: `PLATFORMS=linux/arm64`).

## Kubernetes (Helm)

```bash
helm install krtc oci://ghcr.io/totentraum/charts/krtc-sfu --version <версия>
# или из репозитория:
helm install krtc deploy/helm/krtc-sfu --set image.tag=nightly
```

Главное — как клиенты достают UDP-порт медиа (`media.mode`):

| Режим | Когда | Что задать |
|---|---|---|
| `hostNetwork` (по умолчанию) | Свои серверы, k3s, VPS: порт открывается на узле | `media.announce`, если внешний IP узла не совпадает с внутренним (обычно в облаке) |
| `loadBalancer` | Облако с балансировщиком UDP+TCP (Kubernetes 1.26+) | `media.announce` — внешний IP балансировщика |
| `nodePort` | Без балансировщика | `media.port` в диапазоне 30000–32767, `media.announce` — внешний IP узла |

- Реплика одна: комнаты живут в памяти процесса. Обновление — `Recreate`.
- HTTPS и `wss://`: `ingress.enabled=true`, `ingress.host`, `ingress.tls.secretName`. Браузеры дают странице микрофон, камеру и показ экрана только по HTTPS. Медиа через ingress не идут.
- Журналы клиентов: `clientLogs.enabled=true`, на диске — `clientLogs.persistence.enabled=true`.
- Пробы — `GET /metrics`. Порт медиа открыть в файрволе или группе безопасности.

Пример для VPS с k3s, где внешний IP узла не виден изнутри:

```bash
helm install krtc deploy/helm/krtc-sfu --set image.tag=nightly --set media.announce=203.0.113.7 \
  --set ingress.enabled=true --set ingress.host=meet.example.com
```
