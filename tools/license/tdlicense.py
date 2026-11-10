#!/usr/bin/env python3
"""
TeslaDroid licence codes — keys and codes, no extra packages (pure Python Ed25519, RFC 8032).

  python3 tdlicense.py keygen
      Creates your signing key in ~/.tesladroid/license_private.key (only you keep it; never commit,
      never send). Prints the PUBLIC key — that one goes into the app (safe to share).

  python3 tdlicense.py make TD-XXXXX-XXXXX 30        # 30 days from today
  python3 tdlicense.py make TD-XXXXX-XXXXX 2027-06-01
  python3 tdlicense.py make TD-XXXXX-XXXXX forever
      Prints the activation code for that phone. It works only on the phone with that device code.

  python3 tdlicense.py check <activation code>
      Shows which device and until when a code is valid (and whether your key signed it).

Code layout (base32, no padding): version(1)=1 · device(6) · expiry day u16 (days since 2026-01-01,
0xFFFF = forever) · Ed25519 signature(64) over the first 9 bytes.
"""
import base64
import datetime
import hashlib
import os
import sys

KEY_FILE = os.path.expanduser("~/.tesladroid/license_private.key")
EPOCH = datetime.date(2026, 1, 1)
FOREVER = 0xFFFF

# ---------------- Ed25519 (RFC 8032, section 6 reference code) ----------------
p = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493


def _inv(x):
    return pow(x, p - 2, p)


d = -121665 * _inv(121666) % p
I = pow(2, (p - 1) // 4, p)


def _xrecover(y):
    xx = (y * y - 1) * _inv(d * y * y + 1)
    x = pow(xx, (p + 3) // 8, p)
    if (x * x - xx) % p != 0:
        x = (x * I) % p
    if x % 2 != 0:
        x = p - x
    return x


By = 4 * _inv(5) % p
Bx = _xrecover(By)
B = (Bx, By, 1, Bx * By % p)


def _add(P, Q):
    A = (P[1] - P[0]) * (Q[1] - Q[0]) % p
    Bv = (P[1] + P[0]) * (Q[1] + Q[0]) % p
    C = 2 * P[3] * Q[3] * d % p
    D = 2 * P[2] * Q[2] % p
    E, F, G, H = Bv - A, D - C, D + C, Bv + A
    return (E * F % p, G * H % p, F * G % p, E * H % p)


def _mul(s, P):
    Q = (0, 1, 1, 0)
    while s > 0:
        if s & 1:
            Q = _add(Q, P)
        P = _add(P, P)
        s >>= 1
    return Q


def _encode(P):
    zi = _inv(P[2])
    x, y = P[0] * zi % p, P[1] * zi % p
    return (y | ((x & 1) << 255)).to_bytes(32, "little")


def _decode(s):
    y = int.from_bytes(s, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    x = _xrecover(y)
    if (x & 1) != sign:
        x = p - x
    P = (x, y, 1, x * y % p)
    if (-x * x + y * y - 1 - d * x * x * y * y) % p != 0:
        raise ValueError("not a point")
    return P


def _h(m):
    return int.from_bytes(hashlib.sha512(m).digest(), "little")


def _expand(seed):
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a, h[32:]


def public_key(seed):
    a, _ = _expand(seed)
    return _encode(_mul(a, B))


def sign(seed, msg):
    a, prefix = _expand(seed)
    A = _encode(_mul(a, B))
    r = _h(prefix + msg) % L
    R = _encode(_mul(r, B))
    k = _h(R + A + msg) % L
    S = (r + k * a) % L
    return R + S.to_bytes(32, "little")


def verify(pub, msg, sig):
    if len(sig) != 64:
        return False
    try:
        R = _decode(sig[:32])
        A = _decode(pub)
    except ValueError:
        return False
    S = int.from_bytes(sig[32:], "little")
    if S >= L:
        return False
    k = _h(sig[:32] + pub + msg) % L
    return _encode(_mul(S, B)) == _encode(_add(R, _mul(k, A)))


# ---------------- codes ----------------
def b32(b):
    return base64.b32encode(b).decode().rstrip("=")


def unb32(s):
    s = s.strip().upper().replace("-", "").replace(" ", "").replace("\n", "")
    return base64.b32decode(s + "=" * (-len(s) % 8))


def device_bytes(device_code):
    raw = device_code.strip().upper()
    if raw.startswith("TD-"):
        raw = raw[3:]
    b = unb32(raw)
    if len(b) != 6:
        raise SystemExit("Device code must look like TD-XXXXX-XXXXX")
    return b


def load_seed():
    if not os.path.exists(KEY_FILE):
        raise SystemExit("No key yet. Run: python3 tdlicense.py keygen")
    return bytes.fromhex(open(KEY_FILE).read().strip())


def cmd_keygen():
    if os.path.exists(KEY_FILE):
        raise SystemExit(f"Key already exists: {KEY_FILE} (delete it only if you really want a new one — "
                         "all codes made with the old key stop working)")
    os.makedirs(os.path.dirname(KEY_FILE), exist_ok=True)
    seed = os.urandom(32)
    fd = os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(seed.hex() + "\n")
    print("Private key saved (keep it, back it up, never send it):", KEY_FILE)
    print("PUBLIC key for the app (send this one):")
    print(public_key(seed).hex())


def cmd_make(device_code, until):
    dev = device_bytes(device_code)
    if until == "forever":
        day = FOREVER
        label = "forever"
    else:
        if until.isdigit():
            date = datetime.date.today() + datetime.timedelta(days=int(until))
        else:
            date = datetime.date.fromisoformat(until)
        day = (date - EPOCH).days
        if not 0 < day < FOREVER:
            raise SystemExit("Date out of range")
        label = date.isoformat()
    payload = bytes([1]) + dev + day.to_bytes(2, "big")
    code = b32(payload + sign(load_seed(), payload))
    print(f"Device {device_code.upper()} · valid until {label}")
    print(code)


def cmd_check(code):
    b = unb32(code)
    if len(b) != 73 or b[0] != 1:
        raise SystemExit("Not a TeslaDroid code")
    dev = "TD-" + b32(b[1:7])[:5] + "-" + b32(b[1:7])[5:]
    day = int.from_bytes(b[7:9], "big")
    until = "forever" if day == FOREVER else (EPOCH + datetime.timedelta(days=day)).isoformat()
    ok = "?"
    if os.path.exists(KEY_FILE):
        ok = "yes" if verify(public_key(load_seed()), b[:9], b[9:]) else "NO"
    print(f"device {dev} · until {until} · signed by your key: {ok}")


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["keygen"]:
        cmd_keygen()
    elif a[:1] == ["make"] and len(a) == 3:
        cmd_make(a[1], a[2])
    elif a[:1] == ["check"] and len(a) == 2:
        cmd_check(a[1])
    else:
        print(__doc__)
