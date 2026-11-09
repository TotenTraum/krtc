# WebRTC spec coverage

Computed from `spec-coverage.yaml` (IDL: `@webref/idl@3.86.0`). Counts `full` as covered, `partial` as half; `na` items are left out of the denominator.
*WPT direct*: share of items with a web-platform-tests file named after or touching them (see [WPT.md](WPT.md), WPT `264db3f3c045`).

| Spec | Tier | Items | WPT direct | android | ios | macos | jvm | js | wasmJs | linux | mingw |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `webrtc` | core | 411 | 94% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `webrtc-stats` | core | 252 | 65% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `webrtc-svc` | core | 1 | 100% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `webrtc-encoded-transform` | core | 109 | 55% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `webrtc-priority` | core | 9 | 78% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `webrtc-ice` | core | 11 | 82% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `mediacapture-streams` | core | 176 | 85% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `mediacapture-extensions` | core | 99 | 26% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `screen-capture` | core | 63 | 71% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `audio-output` | core | 5 | 100% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `mst-content-hint` | core | 7 | 100% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `mediacapture-transform` | extended | 16 | 81% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `mediacapture-region` | extended | 5 | 100% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `mediacapture-viewport` | extended | 1 | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `mediacapture-surface-control` | extended | 8 | 13% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `mediacapture-handle-actions` | extended | 14 | 14% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `capture-handle-identity` | extended | 10 | 60% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `prefer-current-tab` | extended | 1 | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `image-capture` | extended | 108 | 95% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `mediastream-recording` | extended | 42 | 93% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `webrtc-diagnostic-logging` | extended | 5 | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% | 0% |
| `webrtc-identity` | excluded | 46 | 65% | — | — | — | — | — | — | — | — |
| `mediacapture-fromelement` | excluded | 5 | 80% | — | — | — | — | — | — | — | — |
| `mediacapture-automation` | excluded | 15 | 20% | — | — | — | — | — | — | — | — |
| **core total** | | | | **0%** | **0%** | **0%** | **0%** | **0%** | **0%** | **0%** | **0%** |

## Items by kind

| Kind | Count |
|---|---:|
| field | 603 |
| enum-value | 204 |
| attribute | 160 |
| operation | 115 |
| dictionary | 107 |
| interface | 59 |
| enum | 56 |
| constructor | function Object() { [native code] }1111111111111111111111111111111111111 |
| event-handler | 53 |
| typedef | 12 |
| includes | 6 |
| callback | 4 |
| mixin | 2 |
| maplike | 1 |

Core items without a linked test: **1143**.
