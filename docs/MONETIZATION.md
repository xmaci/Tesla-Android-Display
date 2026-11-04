# Monetization plan (agreed 2026-11-03, build later)

**UA** · План підписки для знайомих. Не реалізовано — повернемося пізніше.
**EN** · Subscription plan for friends. Not built yet — to do later.

## Decisions

| Item | Decision |
| --- | --- |
| Audience | Friends only (not public sale) |
| Price | $2 / month ≈ 100 Telegram Stars (buyer); ≈ $1.3 net after Telegram (to verify) |
| Trial | 30 days free, issued by the bot (reinstall cannot reset it) |
| Payment | Telegram bot, Telegram Stars monthly subscription (Telegram requires Stars for digital goods in bots) |
| Manual | Admin command `/grant <device> <days>` for friends who pay directly (Monobank, cash) |
| Bot hosting | Python bot on the Oracle VM (24/7), small database of devices and payments |

## How it works

1. App shows / sends a short device code, e.g. `TD-7K3F-9Q2M` = hash of Android `ANDROID_ID`
   (per app, survives reinstall/updates, changes only on factory reset; no permission, not IMEI).
2. First ▶ without a licence → "Activate via Telegram" → `t.me/<bot>?start=<device code>`.
3. Bot registers the device, gives 30 days, replies with a link that activates the app.
4. Later: "Subscribe" in the bot → Stars subscription (30 days, auto-renew) → bot sends a new code each period.
5. Licence code = device code + expiry date + Ed25519 signature. Private key only on the VM; the app has
   the public key and checks offline. Clock rollback detected (last seen time, network time when online).
6. Bot commands: `/start`, `/subscribe`, `/status`; admin: `/grant`, `/list`.

## What the user sees

- In the car: nothing while active.
- In the app: one quiet line "Активно до DD.MM · Active until DD.MM"; one reminder 3 days before expiry.
- Never cut a running session (safety while driving): expiry blocks only the next ▶ Start.
- After expiry the car page says "Subscription ended — renew in TeslaDroid" (not a black screen).

## Before building

- User creates the bot in @BotFather and sends only its name; the token goes straight to the VM by a
  command, never into chat.
- Verify current Stars subscription rules (period, price, payouts via Fragment) at build time.
- Limits: an APK can be decompiled and the check removed; fine for friends.

Source: https://core.telegram.org/bots/payments-stars
