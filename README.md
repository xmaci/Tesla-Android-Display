# Tesla Android Display

**UA** · Особистий проєкт: застосунки Android-телефона на екрані Tesla через браузер машини й роздачу Wi‑Fi з телефона. Без Android Auto, без боксів у USB.
**EN** · Personal project: run Android phone apps on the Tesla screen through the car browser over the phone hotspot. No Android Auto, no USB boxes.

Targets: Tesla MCU1 (Tegra), MCU2 (Intel), MCU3 (Ryzen) · Android 13+ (tested on Pixel 7).

## Status

| Milestone | State |
| --- | --- |
| M0 · Browser capability probe | done on MCU1 → [docs/M0-probe.md](docs/M0-probe.md) |
| M1a · Phone screen on the car, flow-controlled JPEG + touch | **working on MCU1** (2026-11-02), measuring → [docs/M1a.md](docs/M1a.md) |
| M1 · Virtual display, locked phone, live touch, partial updates | next |
| M2 · Automation, UI, audio (MVP) | planned |
| M3 · Phase 2 (anti-jamming, voice, extras) | planned |
| M4 · Phase 3 | planned |

## Layout

```
android/              Kotlin app (M1a): VPN car address, screen capture, server, touch
web/car/              car client page (ES5), bundled into the APK
web/probe/            M0 probe page (ES5) + test assets
tools/probe-server/   phone-side test server (Python, stdlib only)
docs/                 UA + EN docs
```

Build: `cd android && ./gradlew assembleDebug` (JDK 17+, Android SDK 35) → `app/build/outputs/apk/debug/app-debug.apk`.
