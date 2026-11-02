#!/usr/bin/env python3
"""M0 probe server — runs on the phone (Termux) or a laptop on the same Wi-Fi.

Serves web/probe/ over HTTP and a WebSocket at /ws used by the probe page to
measure latency, throughput and JPEG frame rate in the Tesla browser.
Results posted by the page are saved to tools/probe-server/results/.

Python 3.8+, standard library only.
    python probe_server.py [--port 8080]
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import socket
import struct
import subprocess
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
WEB_ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "web", "probe"))
RESULTS = os.path.join(HERE, "results")
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
ASSETS = os.path.join(WEB_ROOT, "assets")


def load_jpeg(name):
    with open(os.path.join(ASSETS, name), "rb") as f:
        return f.read()


JPEGS = {"800": load_jpeg("frame-800x480.jpg"), "1280": load_jpeg("frame-1280x720.jpg")}


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

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        sys.stdout.write("%s %s\n" % (self.client_address[0], fmt % args))

    # ---- HTTP ----
    def do_GET(self):
        if self.path.startswith("/ws"):
            return self.handle_ws()
        if self.path == "/":
            self.path = "/index.html"
        return super().do_GET()

    def do_POST(self):
        if not self.path.startswith("/results"):
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        os.makedirs(RESULTS, exist_ok=True)
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        path = os.path.join(RESULTS, "probe-%s.json" % stamp)
        try:
            data = json.loads(body.decode("utf-8"))
            data["_server"] = {"client_ip": self.client_address[0], "saved": stamp}
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
        except ValueError:
            with open(path + ".raw", "wb") as f:
                f.write(body)
        print("saved results ->", path)
        out = b'{"ok":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

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
        except (ConnectionError, OSError):
            pass
        print("ws closed", self.client_address[0])
        self.close_connection = True

    def ws_command(self, sock, cmd):
        # ping:<t>            -> pong:<t>
        # bulk:<bytes>:<chunk> -> binary chunks, then text "bulk-done"
        # jpeg:<800|1280>     -> one JPEG frame (client asks again for each frame)
        parts = cmd.split(":")
        if parts[0] == "ping":
            ws_send(sock, ("pong:" + parts[1]).encode(), 0x1)
        elif parts[0] == "bulk":
            total, chunk = int(parts[1]), int(parts[2])
            block = os.urandom(chunk)
            sent = 0
            while sent < total:
                ws_send(sock, block, 0x2)
                sent += chunk
            ws_send(sock, b"bulk-done", 0x1)
        elif parts[0] == "jpeg":
            ws_send(sock, JPEGS.get(parts[1], JPEGS["800"]), 0x2)
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
    # Android/Termux: the hotspot interface (wlan/ap/swlan) is often not the
    # default route, so also read every interface address.
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
    for ip in local_ips() or ["<phone-ip>"]:
        print("Open in the Tesla browser:  http://%s:%d/" % (ip, args.port))
    print("Pixel hotspot address is usually 192.168.x.1 — see README if unsure.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
