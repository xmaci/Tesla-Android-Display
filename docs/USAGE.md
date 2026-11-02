# Daily use · Щоденне користування (TeslaDroid 0.3.x)

## A. Every trip · Кожна поїздка

1. **Phone Wi‑Fi off:** pull down Quick Settings → tap **Internet** → turn **Wi‑Fi off**. Mobile data stays on.
2. **Hotspot on:** Quick Settings → **Hotspot** tile on (or Settings → Network & internet → Hotspot & tethering → Wi‑Fi hotspot).
3. **Tesla on the hotspot:** in the car tap the **Wi‑Fi icon** (top of the screen) → choose the **Pixel hotspot**.
   - Near home the car may pick home Wi‑Fi instead — choose the hotspot manually.
4. **Open TeslaDroid** on the phone → check **4. Helper** shows ✅ `TDHELPER … uid=2000`.
   - If ❌: the phone was rebooted → do part B first.
5. Tap **▶ Start** → allow the **VPN** prompt if shown → screen recording: **Entire screen** → **Start**.
6. Check the status lines: Address ✅ 7.7.7.8 · Stream ✅ port 7777 · Touch ✅.
7. **In the Tesla browser** open the bookmark, or type `http://7.7.7.8.sslip.io:7777/`.
8. Phone screen appears in the car. Keep the phone **unlocked, screen on** (until step 2b is released).

**Stop after the trip:** TeslaDroid → **■ Stop** (or the **Stop** button in the notification), hotspot off.

## B. After a phone reboot (helper start, ~1 min) · Після перезавантаження телефона

The helper stops when the phone restarts. Pairing is kept — **no code needed**.

1. Phone **Wi‑Fi on** and connected to any network (home, office, another phone's hotspot).
   Your own hotspot must be **off** for this step.
2. Settings → System → **Developer options** → **Wireless debugging** → switch **On** → confirm **Allow**.
3. Open **TeslaDroid** → **4. Helper** → tap **Start helper** (not *Pair*).
4. Wait ~5 s → ✅ `TDHELPER … uid=2000`.
5. Turn **Wireless debugging off** (optional) and **Wi‑Fi off**. The helper keeps running.
6. Continue with part A.

## C. One-time setup (already done on your Pixel) · Разове налаштування

- Pairing (Pair + 6‑digit code) — only again if you uninstall TeslaDroid or tap *Revoke debugging authorisations*.
- Tesla browser bookmark: open the link → tap **☆** → it appears in the browser's favourites.
- Optional: in the Tesla, open home Wi‑Fi → **Forget**, so the car never prefers it over the hotspot.

## D. If it does not work · Якщо не працює

| Symptom | Fix |
| --- | --- |
| Tesla: "page not available" | Tesla not on the hotspot, or TeslaDroid not started → A3, A5 |
| Page opens, keeps "Connecting…" / reconnecting | Phone Wi‑Fi still on → turn it off, ■ Stop → ▶ Start |
| Diagnostics: no lines from `10.72.87.x` | Car traffic not reaching the phone → check A3 |
| Helper ❌ | Phone rebooted → part B |
| "touch off" in the car bar | Start helper again (part B), it re-enables touch |
| Picture frozen | Reload the page in the Tesla; if still frozen ■ Stop → ▶ Start |
