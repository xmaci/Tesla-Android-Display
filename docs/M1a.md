# M1a — phone screen on the Tesla (first real test)

**UA** · Перша версія застосунку: екран телефона в браузері Tesla, напряму через роздачу, з дотиком.
**EN** · First app build: the phone screen in the Tesla browser, directly over the hotspot, with touch.

Goal of this build: prove that on **MCU1** the delay stays under a second (other apps reached 15 s)
and see what fps we really get. Phone must stay **unlocked, screen on** — locked-screen mode is M1.

## How it works

- `CarVpnService` gives the phone the address **7.7.7.8** on a VPN tunnel (only that one address;
  the phone's own internet is untouched). The Tesla browser refuses private IPs but accepts this.
  **Two tunnels are required** (confirmed on Pixel 7, 2026-11-02): Android drops hotspot packets addressed
  to the *active* VPN's own address, so a second helper tunnel (192.168.229.230) becomes the active VPN
  and 7.7.7.8 stays an ordinary local address. One tunnel = the car never connects.
- The car opens **`http://7.7.7.8.sslip.io:7777/`** (the app shows the exact link).
  Tested on MCU1: a raw IP is refused, and port 80 never reached the phone; a name + port 7777 works.
  sslip.io is a public DNS name that just resolves to 7.7.7.8; the video never leaves the hotspot.
- `StreamService` captures the screen and sends **one JPEG at a time**: the next frame goes only after
  the car says it drew the previous one, and only the newest screen is sent. Delay ≈ one frame.
- `TouchService` (Accessibility) turns car taps and swipes into phone gestures.

## Install · Встановлення

1. Download `TeslaDroid-M1a.apk` to the Pixel and open it. Allow "install unknown apps" for the app
   you open it from (Files / Telegram / Chrome).
2. Open **TeslaDroid**.
3. **Touch:** tap *Open Accessibility* → **TeslaDroid** → turn on.
   If the switch is greyed out ("Restricted setting"): *Settings → Apps → TeslaDroid → ⋮ →
   Allow restricted settings*, then try again. (Android does this for sideloaded apps.)
4. **Hotspot on**, Tesla connected to it.
5. Tap **▶ Start** → allow **notifications**, the **VPN** request, then screen recording —
   choose **"Entire screen"** (not a single app).
6. In the Tesla browser open the **green link** shown in the app.
   Add it to the browser favourites (☆) for next time.

## In the car

- Top bar: ◀ Back · ● Home · ▢ Recents · ☰ Notifications · frame width · JPEG quality · live stats.
- Tap = tap, drag = swipe (sent when you lift the finger). Long press = hold.
- Stats: `fps · delay ms · decode ms · KB per frame · size`.

## What to report

Try each width (320 / 432 / 540) with q35 and q50 on a static screen and on Waze while driving
(passenger operates):

| | 320 q35 | 432 q50 | 540 q50 |
| --- | --- | --- | --- |
| fps | | | |
| delay ms | | | |
| decode ms | | | |
| tap feels (instant / ½ s / 1 s+) | | | |

Plus: did the page stay open while driving? Any disconnects?

## Known limits of M1a

- Phone screen must stay on and unlocked (the app keeps it dimmed-on while streaming).
- The picture is the phone's portrait screen, so it uses a narrow strip of the car display.
  M1 renders apps on a separate virtual display shaped like the car screen.
- Touch is sent when the finger lifts (no live drag yet).
- No sound in the browser on MCU1 — use the phone's Bluetooth connection to the car as usual.
- Anyone on your hotspot who knows the link can see and control the phone. Use a hotspot password.
