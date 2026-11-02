#!/usr/bin/env python3
"""M0 probe server — runs on a phone (Termux), a laptop or a public VM.

Serves web/probe/ over HTTP, a WebSocket at /ws and an MJPEG stream at /mjpeg,
used by the probe page to measure what the Tesla browser can do.
Results posted by the page are saved to tools/probe-server/results/.

Python 3.8+, standard library only.
    python probe_server.py [--port 8080]

Note: the Tesla browser refuses private addresses (10.x, 172.16-31.x,
192.168.x). For a quick test, run this on a public server and open
http://<public-ip>.sslip.io:<port>/ in the car.
"""
import argparse
import base64
import datetime
import glob
import hashlib
import json
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
WEB_ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "web", "probe"))
RESULTS = os.path.join(HERE, "results")
ASSETS = os.path.join(WEB_ROOT, "assets")
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

MAX_POST = 1 << 20          # 1 MB per results upload
MAX_BULK = 16 << 20         # 16 MB per throughput test
MAX_MJPEG_FRAMES = 600


def load_frames(size):
    frames = []
    for path in sorted(glob.glob(os.path.join(ASSETS, "frames-%s" % size, "*.jpg"))):
        with open(path, "rb") as f:
            frames.append(f.read())
    if not frames:
        raise SystemExit("missing JPEG frames in assets/frames-%s" % size)
    return frames


FRAMES = {"800": load_frames("800"), "1280": load_frames("1280")}
MJPEG_RESULTS = {}           # client ip -> last MJPEG measurement
MJPEG_LOCK = threading.Lock()


# ---------- minimal RFC 6455 framing ----------

def ws_send(sock, payload, opcode):
    header = bytearray([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header.append(n)
    elif n < 65536:
        header.append(126)
        header += struct.pack(">H", n)
    else:
        header.append(127)
        header += struct.pack(">Q", n)
    sock.sendall(bytes(header) + payload)


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def ws_recv(sock):
    b1, b2 = recv_exact(sock, 2)
    opcode = b1 & 0x0F
    n = b2 & 0x7F
    if n == 126:
        n = struct.unpack(">H", recv_exact(sock, 2))[0]
    elif n == 127:
        n = struct.unpack(">Q", recv_exact(sock, 8))[0]
    if n > MAX_POST:
        raise ConnectionError("frame too large")
    mask = recv_exact(sock, 4) if b2 & 0x80 else None
    data = bytearray(recv_exact(sock, n))
    if mask:
        for i in range(n):
            data[i] ^= mask[i % 4]
    return opcode, bytes(data)


class Handler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=WEB_ROOT, **kw)

    def handle(self):
        # Clients (the car, phones) drop connections all the time; don't spam tracebacks.
        try:
            super().handle()
        except (ConnectionError, OSError):
            pass

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        sys.stdout.write("%s %s\n" % (self.client_address[0], fmt % args))

    def send_json(self, obj, code=200):
        out = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    # ---- HTTP ----
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/ws":
            return self.handle_ws()
        if url.path == "/mjpeg":
            return self.handle_mjpeg(parse_qs(url.query))
        if url.path == "/mjpeg-result":
            with MJPEG_LOCK:
                res = MJPEG_RESULTS.get(self.client_address[0])
            return self.send_json(res or {"pending": True})
        if url.path == "/":
            self.path = "/index.html"
        return super().do_GET()

    def do_POST(self):
        if not self.path.startswith("/results"):
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_POST:
            self.send_error(413)
            return
        body = self.rfile.read(length)
        os.makedirs(RESULTS, exist_ok=True)
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        path = os.path.join(RESULTS, "probe-%s.json" % stamp)
        try:
            data = json.loads(body.decode("utf-8"))
            data["_server"] = {"client_ip": self.client_address[0], "saved": stamp}
            with MJPEG_LOCK:
                if self.client_address[0] in MJPEG_RESULTS:
                    data["_server"]["mjpeg"] = MJPEG_RESULTS[self.client_address[0]]
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
        except ValueError:
            with open(path + ".raw", "wb") as f:
                f.write(body)
        print("saved results ->", path)
        self.send_json({"ok": True})

    # ---- MJPEG (multipart/x-mixed-replace) ----
    def handle_mjpeg(self, q):
        size = (q.get("size") or ["800"])[0]
        frames = FRAMES.get(size, FRAMES["800"])
        n = min(int((q.get("n") or ["150"])[0]), MAX_MJPEG_FRAMES)
        fps = float((q.get("fps") or ["0"])[0])     # 0 = as fast as the client takes it
        boundary = "probeframe"
        sock = self.connection
        # Small send buffer: the server can only run ahead of the browser by ~1 frame,
        # so the achieved rate approximates what the car actually consumes.
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 64 * 1024)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=%s" % boundary)
        self.send_header("Connection", "close")
        self.end_headers()
        sent, nbytes, t0 = 0, 0, time.time()
        try:
            for i in range(n):
                frame = frames[i % len(frames)]
                part = ("--%s\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n" % (boundary, len(frame))).encode()
                self.wfile.write(part + frame + b"\r\n")
                self.wfile.flush()
                sent += 1
                nbytes += len(frame)
                if fps > 0:
                    delay = t0 + sent / fps - time.time()
                    if delay > 0:
                        time.sleep(delay)
            self.wfile.write(("--%s--\r\n" % boundary).encode())
            self.wfile.flush()
        except (ConnectionError, OSError):
            pass
        sec = max(time.time() - t0, 1e-6)
        res = {"size": size, "frames": sent, "seconds": round(sec, 2),
               "fps": round(sent / sec, 1), "mbit": round(nbytes * 8 / sec / 1e6, 1)}
        with MJPEG_LOCK:
            MJPEG_RESULTS[self.client_address[0]] = res
        print("mjpeg", self.client_address[0], res)
        self.close_connection = True

    # ---- WebSocket ----
    def handle_ws(self):
        key = self.headers.get("Sec-WebSocket-Key")
        if not key or "websocket" not in self.headers.get("Upgrade", "").lower():
            self.send_error(400)
            return
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        sock = self.connection
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print("ws open", self.client_address[0])
        try:
            while True:
                opcode, data = ws_recv(sock)
                if opcode == 0x8:
                    ws_send(sock, b"", 0x8)
                    break
                if opcode == 0x9:
                    ws_send(sock, data, 0xA)
                    continue
                if opcode == 0x1:
                    self.ws_command(sock, data.decode("utf-8", "replace"))
        except (ConnectionError, OSError, ValueError):
            pass
        print("ws closed", self.client_address[0])
        self.close_connection = True

    def ws_command(self, sock, cmd):
        # ping:<t>             -> pong:<t>
        # bulk:<bytes>:<chunk> -> binary chunks, then text "bulk-done"
        # jpeg:<800|1280>:<i>  -> JPEG frame i (client asks for each frame)
        parts = cmd.split(":")
        if parts[0] == "ping":
            ws_send(sock, ("pong:" + parts[1]).encode(), 0x1)
        elif parts[0] == "bulk":
            total = min(int(parts[1]), MAX_BULK)
            chunk = max(1024, min(int(parts[2]), 1 << 20))
            block = os.urandom(chunk)
            sent = 0
            while sent < total:
                ws_send(sock, block, 0x2)
                sent += chunk
            ws_send(sock, b"bulk-done", 0x1)
        elif parts[0] == "jpeg":
            frames = FRAMES.get(parts[1], FRAMES["800"])
            i = int(parts[2]) if len(parts) > 2 else 0
            ws_send(sock, frames[i % len(frames)], 0x2)
        else:
            ws_send(sock, ("error:unknown " + cmd).encode(), 0x1)


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    # Android/Termux: the hotspot interface is often not the default route.
    for cmd in (["ifconfig"], ["ip", "-4", "addr"]):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=3).stdout
        except (OSError, subprocess.SubprocessError):
            continue
        ips.update(re.findall(r"inet (?:addr:)?(\d+\.\d+\.\d+\.\d+)", out))
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    args = ap.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.daemon_threads = True
    print("Serving", WEB_ROOT)
    for ip in local_ips() or ["<ip>"]:
        print("Listening on  http://%s:%d/" % (ip, args.port))
    print("The Tesla browser blocks private IPs (10.x/172.16-31.x/192.168.x);"
          " use a public address, e.g. http://<public-ip>.sslip.io/")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
