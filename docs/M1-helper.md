# M1 step 2a — privileged helper (one-time pairing)

**UA** · Щоб показувати застосунки на окремому екрані у формі авто й працювати із заблокованим телефоном,
TeslaDroid запускає «помічника» з правами `shell` через бездротове налагодження. Робиться один раз;
помічник працює до перезавантаження телефона.
**EN** · For the car-shaped display and a locked phone, TeslaDroid starts a helper with `shell` rights over
Wireless debugging. Pair once; the helper keeps running until the phone reboots.

## Why

Android only lets privileged code put other apps on a separate (virtual) display and inject touch into it.
The `shell` user (what `adb shell` runs as) has those rights. TeslaDroid talks to the phone's own adbd
over Wireless debugging — no PC, no root, no Shizuku. Library: LibADB Android (Apache-2.0).

## Steps (phone on Wi‑Fi, e.g. at home)

1. **Developer options:** Settings → About phone → tap *Build number* 7×.
2. In TeslaDroid tap **Pair (Wireless debugging)**. A TeslaDroid notification appears and Developer options open.
3. Open **Wireless debugging** → turn it on → **Pair device with pairing code**. A 6‑digit code is shown.
4. Pull down the notification shade → TeslaDroid notification → **Enter code** → type the 6 digits → send.
5. The notification shows *Paired ✓* and then *running: TDHELPER … uid=2000*. Done.

After a reboot: connect to any Wi‑Fi, turn on Wireless debugging, tap **Start helper** (no new pairing).
You can turn Wireless debugging off afterwards; the helper keeps running.

## How it works

- `AdbManager`: RSA key + self-signed certificate (BouncyCastle), stored in the app's private files.
- `PairingService`: finds the pairing port by mDNS (`_adb-tls-pairing._tcp`), takes the code from a
  notification reply, pairs, then calls `Helper.start`.
- `Helper.start`: connects over TLS (`_adb-tls-connect._tcp`) and runs
  `CLASSPATH=<apk> setsid app_process /system/bin com.xmaci.tesladroid.helper.HelperMain <token>`.
- `HelperMain` (runs as uid 2000): control socket on `127.0.0.1:7790`; every request must start with a
  random token kept in the app's private files, so other apps cannot use it.
  Log: `/data/local/tmp/tesladroid-helper.log`.

**Touch unlock:** newer Android blocks Accessibility for sideloaded apps and may hide
"Allow restricted settings". When the helper starts, TeslaDroid also runs (as shell)
`appops set … ACCESS_RESTRICTED_SETTINGS allow` and adds its touch service to
`enabled_accessibility_services`, keeping any services already enabled.

2a only proves the helper runs as `shell`. 2b: virtual display 800×480 / 1024×600 + capture.
2c: launching apps on it and touch injection.
