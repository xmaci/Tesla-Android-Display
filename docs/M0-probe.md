# M0 — Tesla browser probe

**UA** · Перевіряємо, що реально вміє браузер вашої Tesla (MCU1 Tegra, MCU2 Intel, MCU3 Ryzen), перш ніж будувати трансляцію.
**EN** · We measure what your Tesla browser can really do before building the streaming layer.

## Що потрібно · What you need

- Pixel 7 з [Termux](https://f-droid.org/packages/com.termux/) (з F-Droid, не з Google Play) · Pixel 7 with Termux from F-Droid
- Файли цього репозиторію на телефоні · This repo on the phone
- 10 хвилин у машині · 10 minutes in the car

## Кроки · Steps

1. **Termux:** `pkg update && pkg install python git`
2. **Отримати код · Get the code**
   - Публічний репо · public repo: `git clone https://github.com/xmaci/tesla-android-display`
   - Приватний · private: завантажте ZIP з GitHub, потім `termux-setup-storage` і `unzip ~/storage/downloads/tesla-android-display-main.zip`
3. **Увімкнути роздачу · Turn on the hotspot** — Налаштування → Мережа → Точка доступу. Безпека **WPA2-Personal**, діапазон спершу **2.4 GHz** (для MCU1). · Security WPA2-Personal, band 2.4 GHz first.
4. **Підключити Tesla до роздачі · Join the Tesla to the hotspot** (Wi‑Fi в машині).
5. **Запустити сервер · Start the server**
   ```
   cd tesla-android-display
   python tools/probe-server/probe_server.py
   ```
   Він покаже адреси, напр. `http://192.168.x.1:8080/`. Беріть ту, що з підмережі роздачі. · Use the address on the hotspot subnet.
6. **У браузері Tesla відкрийте цю адресу · Open it in the Tesla browser.**
7. Натисніть **«Запустити все»**, дочекайтесь «Готово» (~40 с). · Tap **Run all**, wait for Done.
8. **«Тест звуку»** → відповісти, чи чути тон (гучність машини вгору). · **Audio test** → answer if you heard the tone.
9. Торкніться сірого поля одним, двома, трьома пальцями. · Touch the grey box with 1–3 fingers.
10. **«Надіслати»** — результат збережеться на телефоні в `tools/probe-server/results/`. · **Send** saves results on the phone.
11. **Тест у русі · Driving test** (пасажир, не водій): під час руху перевірте, чи сторінка лишається відкритою й реагує на дотик. Запишіть, що сталося. · Passenger only: while moving, note whether the page stays open and responds.

Якщо машина не бачить мережу — спробуйте 5 GHz і запишіть, що спрацювало. · If the car can't see the network, try 5 GHz and note what worked.

## Що надіслати мені · What to send back

- JSON-файл з `results/` (або вміст поля JSON зі сторінки) · the JSON file
- Діапазон Wi‑Fi, що спрацював · which Wi‑Fi band worked
- Чи працює сторінка в русі · whether the page works while driving
- Версія ПЗ Tesla (Controls → Software) · Tesla software version

## Що перевіряє проба · What the probe measures

| Test | Why it matters |
| --- | --- |
| JS features (ES6, Promise, WebSocket binary, createImageBitmap, WebAssembly) | Which client bundle the car can run |
| Canvas putImageData 1024×576 | Raw drawing speed |
| WebSocket round trip, 4 MB download | Touch latency and Wi‑Fi bandwidth to the car |
| JPEG stream 800×480 and 1280×720 | Fps of the `legacy` video path (MCU1) |
| MSE H.264 baseline 576p and main 720p | Whether the `standard` hardware-decoded path works |
| WebCodecs, H.265 type checks | Whether the `hq` path is possible (MCU3) |
| Web Audio PCM tone | Whether sound can go through the browser instead of Bluetooth |
| Touch / pointer / mouse events, max fingers | How the car delivers taps and gestures |
