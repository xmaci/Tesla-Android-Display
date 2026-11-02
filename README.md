# Tesla Android Display

**UA** · Особистий проєкт: застосунки Android-телефона на екрані Tesla через браузер машини й роздачу Wi‑Fi з телефона. Без Android Auto, без боксів у USB.
**EN** · Personal project: run Android phone apps on the Tesla screen through the car browser over the phone hotspot. No Android Auto, no USB boxes.

Targets: Tesla MCU1 (Tegra), MCU2 (Intel), MCU3 (Ryzen) · Android 13+ (tested on Pixel 7).

## Status

| Milestone | State |
| --- | --- |
| M0 · Browser capability probe | ready to test in the car → [docs/M0-probe.md](docs/M0-probe.md) |
| M1 · Core streaming | next, after M0 results |
| M2 · Automation, UI, audio (MVP) | planned |
| M3 · Phase 2 (anti-jamming, voice, extras) | planned |
| M4 · Phase 3 | planned |

## Layout

```
web/probe/            M0 probe page (ES5) + test assets
tools/probe-server/   phone-side test server (Python, stdlib only)
docs/                 UA + EN docs
```

Planned: `android/` (Kotlin app), `web/legacy/` (MCU1 client), `web/modern/` (MCU2/3 client).
