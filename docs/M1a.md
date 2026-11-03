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

## M1 step 1 (build 0.2.0): speed tools

Bar buttons: **width** · **quality** · **×N frames in flight** (1–3) · **draw / fit / 1:1** scale mode.
Second stats line: `enc` (phone JPEG) · `net` (network + WebSocket delivery) · `wait` (queued in car) ·
`dec` (car JPEG decode) · `draw` (canvas draw), all in ms. URL shortcut: `/?w=320&q=35&n=2&mode=draw`.

MCU1 measured with one frame in flight (M1a.4): 432×960 q50 → 4.1 fps, 239 ms, decode 85 ms;
320×710 q35 → 4.4 fps, 230 ms, decode 45 ms. Decode is not the bottleneck; ~185 ms is elsewhere.

MCU1 measured with 0.2.0 (2026-11-02):

| Settings | fps | delay ms | net | dec | draw |
| --- | --- | --- | --- | --- | --- |
| 320 q35 ×1 fit | 4.9 | 187 | 129 | 47 | 11 |
| 320 q35 ×2 fit | 4.7 | 413 | 185 | 197 | 11 |
| **320 q35 ×2 1:1** | **13.5** | **155** | 72 | 56 | 14 |
| 432 q35 ×2 1:1 | 7.9 | 255 | 121 | 93 | 16 |
| 540 q35 ×2 fit | 3.4 | 502 | 215 | 234 | 23 |

**CSS scaling of the canvas is the MCU1 bottleneck** (~3× slower). 0.2.1 adds mode `draw`:
the canvas has the on-screen size and `drawImage` scales each frame instead of CSS.

0.2.1 `draw` mode on MCU1: 320 ×2 → 4.0 fps / 421 ms (dec 92, draw 114); 432 ×2 → 4.4 fps / 475 ms
(dec 138, draw 112). **The cost follows the number of pixels shown, not the frame size**: ~230k px
on screen ≈ 13 fps, ~850k px ≈ 4 fps. 0.2.2 adds mode `img` (two `<img>` elements, no canvas).

0.2.2 `img` mode on MCU1: 432 ×2 → 4.9 fps / 382 ms (load+paint 172 ms). Slightly better than `draw`,
same ceiling. **Decision for MCU1:** render apps on a car-shaped virtual display and show it 1:1
(~400k px ≈ 8–10 fps); offer a "large" stretched view (~4–5 fps) as an option.

## 0.4.0 — partial updates, GPU view, settings panel

- **Partial updates:** the phone diffs each capture against what the car already shows (32 px tiles,
  `FrameDiff`) and sends only the changed rectangle; nothing at all when the screen is static. Full frame
  when >60 % changed, on resize/connect/`full` request, and every 6 s. Header grew to 20 bytes (+ rect).
  The car draws partial frames in order; only frames older than a queued full frame are skipped.
- **View `gpu` (default):** canvas at frame size, scaled with `translateZ(0) scale(s)` — tests whether MCU1
  composites the scale on the GPU instead of the slow software stretch measured for `fit`.
- **⚙ settings panel** replaces the cycling buttons: options are *selected*, so MCU1's duplicated tap events
  can no longer skip a value. Bar buttons accept one action per 0.6–1 s.
- **Touch diagnostics:** the phone answers every gesture with `ti:1|0|x`; the bar shows `touch sent→ok`.

## 0.4.1 — MJPEG views (no JavaScript per frame)

The `net` time of 70–200 ms for 6–15 KB frames pointed at MCU1's single JavaScript thread, not the
network: every frame went WebSocket → Blob → Image → decode → canvas in slow JS. Views **mjpeg** (default,
scaled) and **mj1:1** use `<img src="/mjpeg">` (multipart/x-mixed-replace): the browser decodes and
paints natively. The phone paces the stream by TCP backpressure (32 KB send buffer, newest capture only),
so frames cannot pile up. Touch and stats still use the WebSocket; in these views fps comes from the phone.

## 0.4.2 — adaptive resolution in motion

Field report (0.4.0, 540px gpu): Claude app 15–17 fps, Waze 2–6 fps. Partial updates make static UIs
cheap; Waze changes nearly the whole screen every frame, so every frame is a full 540×1200 decode.
Now, when >50 % of the frame changes on 3 frames in a row, the phone captures at 60 % width (≈2.8× fewer
pixels to decode); after 1.5 s of small or no changes it returns to full width. The car keeps the same
on-screen size (softer picture while moving), ⚡ in the bar shows motion mode. ⚙ → Auto on/off.

## 0.4.3 — several rectangles per update

Field report: 17–20 fps idle, 7–10 fps while typing. Typing changes two far-apart areas (keyboard at the
bottom, text field at the top); one bounding box covered almost the whole screen. `FrameDiff.changedRects`
now groups changed tile rows into bands and sends up to 4 rectangles (closest bands merged first);
full frame if their total exceeds 60 %. Header quality field bit 15 = more rectangles of this update follow
(the car counts fps per complete update).

## Result: MJPEG wins on MCU1 (2026-11-02, 0.4.3)

**mjpeg view, 320px · q25 · ×2 · Auto ✓ (⚡ motion → 240×532): 39.7 fps, wait 1 ms, 4 KB/frame, touch 9→9.**
About 10× the best canvas result (4–5 fps stretched). Bypassing MCU1's JavaScript thread was the key;
the browser's native multipart decoder keeps up. MJPEG is the default view from here on; next: find the
largest size/quality that stays smooth, then the car-shaped display (step 2b) on top of MJPEG.

## 0.4.4 — Auto resolution off by default

Field report: Auto made it worse — each switch resizes the capture (full frame, layout change), and
switching back and forth stuttered. MJPEG at small sizes is fast enough without it. Auto now defaults to
Off; when on, it switches at most once per 5 s and needs 3 s of calm to return to full size.

## 0.4.5 — stuck-picture watchdog

Field report: mjpeg showed 9 fps but the picture stayed the same while the screen moved. In MJPEG views
`upd` now shows how much of the phone screen really changed (it was always 100 %), and the car samples the
shown picture (16×16) every 1.3 s: `pic ok` = it changes, `same` = the phone screen is still too,
`STUCK` = the phone reports changes but the car picture is frozen. After ~4 s STUCK the page reopens the
stream (`↻N` counts restarts); the new ↻ bar button does the same by hand.
How to read it: `upd` ≈ 0 % while moving → the phone captures a still screen (phone side);
`upd` > 0 and `pic STUCK` → the car browser froze the stream (car side).

## 0.4.6 — research-based MCU1 levers (2026-11-02)

Research (TMC forums, Tesla Android release notes, other mirroring projects):
- Every working MCU1 mirroring stack uses MJPEG-style frames — Tesla Android's single-board stack ran a
  modified mjpg_streamer. H.264 in the browser needs WebCodecs/MSE/WASM (Castla, ws-scrcpy, tinyh264):
  none exist on MCU1. So MJPEG is the ceiling; the remaining levers are pixels and painting.
- QtWebKit honours `image-rendering` (WebKit bug 113405): optimizeSpeed / -webkit-optimize-contrast
  select cheap nearest-neighbour scaling instead of smooth scaling in the software painter.
- Old WebKit may keep restarting decode of a multipart image when parts arrive faster than it paints —
  matches "fps 9 but the picture does not move".
- MCU1 owners report a faster UI/browser with Data Sharing (Safety & Security) and Live Traffic off,
  Dashcam/Sentry off, fewer map redraws, and a two-wheel reboot.

Changes: ⚙ Scaling **Fast** (default, nearest neighbour) / Smooth; ⚙ **Max fps** 10/15/20/30/∞
(default 20) — the phone spaces MJPEG parts so the browser can paint each one. cfg gained field 7.

## 0.5.0 — profiler, less garbage, frame buffer (MCU1 focus)

- **⏱ profiler** (bar button, or `?prof=1`): records 5 s, then shows a table on the car screen and saves
  `TeslaDroid-prof-<time>.json` to the phone's Downloads (summary also in the app). Canvas views: per update
  queue / decode / hold / draw / paint ms, arrival gap and **shown gap** (spacing on screen), bytes, changed
  area; `js lag` = how late a 16 ms timer fires (GC, layout). MJPEG views: the shown picture is sampled every
  40 ms to count frames that really changed on screen (sampling cost is reported). Ends with one verdict.
- **GC:** Image decoders reused from a pool, JPEG passed as a view of the received buffer (no copy), buffer
  released once the Blob holds it, touch dots reused, watchdog hashes the picture to a number instead of a
  3 KB string every 1.3 s.
- **Frame buffer** (⚙ Off / 1 / 2 / Auto, canvas views only, default Off): a jitter buffer that fills to the
  target, then shows one update per measured interval, pace bent ±15–20 % to hold the fill level. Frames are
  acked on decode so the phone keeps sending. A touch empties it for 1.5 s. Mock test with uneven 60–160 ms
  arrivals: shown-gap p95 86 → 55 ms, max 112 → 64 ms, at +80 ms delay.

## First MCU1 profiles (2026-11-03, 0.5.0, 432 px, browser in the lower half under the car's map)

| View | shown fps | queue med | decode med | paint med / p95 | js lag p95 | stalls >100 ms |
| --- | --- | --- | --- | --- | --- | --- |
| mjpeg q25 | 3.9* | — | — | — | 336 ms | 19 |
| gpu q35, buffer off | 2.1 | 127 | 135 | 71 / 227 | 277 ms | 19 |
| gpu q35, buffer 2 | 2.4 | 173 | 148 | 72 / 225 | 181 ms | 12 |

\* only 20 samples in 5 s: the sampler itself was starved, so 3.9 is a floor of what it could see.
Reading: the page's main thread is blocked in 100–330 ms chunks. A 2.7 KB partial rect "decoding" for
135 ms is not decode work: its onload waits behind those stalls. Paint 71–267 ms per update points at
repainting the CSS-scaled 432×960 canvas. The frame buffer adds 265 ms and gains nothing at 2 fps → keep Off.
0.5.1: the sampler copies a 96×96 crop (was: whole frame scaled, ~10 ms per sample) and reports how many
fps it could possibly see.

## Second MCU1 profiles (0.5.1, Waze, browser in the lower half)

| View | size | js lag p95 | stalls >100 ms | note |
| --- | --- | --- | --- | --- |
| mjpeg q25 | 320×710 | 196 ms | 19 | page alive, ~8 samples/s |
| mjpeg q25 | 432×960 | 1544 ms | 5 (each 1+ s) | page frozen |
| mj1:1 q25 | 432×960 | 1293 ms | 5 | no scaling, still frozen → pixel count, not scaling |
| 1:1 canvas q35 | 432×960 | 735 ms | 11 | 1.1 fps, decode 1138 ms |

The phone sent ~13 fps every time and its TCP send never blocked (wait 0 ms): the browser keeps reading
the stream but cannot decode and paint 432×960 at 13 fps, so its page thread freezes. Pixels per second is
the limit; the canvas path is far worse than MJPEG.

0.5.3: **Max fps = Auto** (new default). The page reports every second how late a 100 ms timer fired; the
phone lowers the MJPEG rate (×0.6 above 150 ms, ×0.85 above 80 ms, +1 fps below 40 ms, 2–30 fps) and also
slows down when no report arrives for 2.5 s. Stats show `pace N lag M`. Defaults for new installs: 320 px, q25.

## 0.5.4 — Auto pacing was too eager

Field test of 0.5.3: Auto pushed the rate to 2 fps. MCU1's page lags ~85–105 ms (p95) even at 2 fps, so the
80/150 ms thresholds always fired. A fixed 15 fps at 320 px felt smoother. Now: default Max fps 15;
Auto reacts only to real overload (>300 ms slow down, >600 ms ×0.6, <200 ms +1) within 5–20 fps.
Note: the profiler's "shown fps" samples only the picture centre; in Waze the centre (the car arrow)
barely changes, so that number undercounts. Use feel + js lag.

## 0.5.5 — landscape phone

Field: 320 px is unusable (too small), 432 freezes MCU1, full screen is not available on MCU1. The browser
sits in the lower half of the portrait screen: a wide, short area (~1160 × 650). A portrait phone frame
fitted into it is only ~300 px wide; a landscape frame of the same pixel count fills the width.
720×324 (233 k px, like 320×710) shows at ~1160×520. The capture now follows phone rotation (display
listener → resize + full frame); widths 640 and 800 added.

## Known pitfalls (Pixel 7, 2026-11-02)

- **Phone Wi‑Fi must be off while streaming.** With Wi‑Fi and hotspot on together, the car loads the
  page but the WebSocket stream keeps reconnecting. Wi‑Fi is only needed to (re)start the helper.
- **Tesla may prefer home Wi‑Fi** over the phone hotspot near home; then nothing reaches the phone.
- After a network change, tap ■ Stop → ▶ Start so the VPN tunnels are rebuilt.

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
