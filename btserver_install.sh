#!/bin/bash
set -euo pipefail

mkdir -p /opt/btserver

cat > /opt/btserver/btserver.py << 'PYEOF'
import socket
import struct
import threading
import time

PORT = 80
BUF = 65536


def recv_exact(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise ConnectionError()
        b += c
    return b


def send_frame(s, lock, cmd, sid, data=b""):
    with lock:
        s.sendall(struct.pack(">BBHI", 1, cmd, len(data), sid) + data)


def qc(fn):
    try:
        fn()
    except Exception:
        pass


class Stream:
    def __init__(self, sid, sock, lock):
        self.sid, self.sock, self.lock = sid, sock, lock
        self.dst, self.buf, self.closed = None, bytearray(), False

    def feed(self, data):
        if self.closed:
            return

        if self.dst is None:
            self.buf.extend(data)
            if b"\n" in self.buf:
                line, rest = self.buf.split(b"\n", 1)
                self.buf = rest
                host, port = line.decode(errors="replace").strip().rsplit(":", 1)
                try:
                    self.dst = socket.create_connection((host.strip(), int(port)), 10)
                    self.dst.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    if self.buf:
                        self.dst.sendall(bytes(self.buf))
                        self.buf = bytearray()
                    threading.Thread(target=self._up, daemon=True).start()
                except Exception:
                    self.close()
        else:
            try:
                self.dst.sendall(data)
            except Exception:
                self.close()

    def _up(self):
        b = bytearray(BUF)
        try:
            while not self.closed:
                n = self.dst.recv_into(b)
                if not n:
                    break
                send_frame(self.sock, self.lock, 2, self.sid, bytes(b[:n]))
        except Exception:
            pass
        finally:
            send_frame(self.sock, self.lock, 1, self.sid)
            self.closed = True

    def close(self):
        if self.closed:
            return
        self.closed = True
        qc(lambda: self.dst.close() if self.dst else None)
        send_frame(self.sock, self.lock, 1, self.sid)


def handle_smux(sock, addr):
    lock, streams = threading.Lock(), {}
    try:
        sock.settimeout(300)
        while True:
            _, cmd, length, sid = struct.unpack(">BBHI", recv_exact(sock, 8))
            data = recv_exact(sock, length) if length else b""
            if cmd == 0:
                streams[sid] = Stream(sid, sock, lock)
            elif cmd == 1:
                s = streams.pop(sid, None)
                if s:
                    s.closed = True
                    qc(lambda: s.dst.close() if s.dst else None)
            elif cmd == 2:
                s = streams.get(sid)
                if s:
                    s.feed(data)
            elif cmd == 3:
                send_frame(sock, lock, 3, 0)
    except Exception:
        pass
    finally:
        for s in list(streams.values()):
            s.closed = True
            qc(lambda: s.dst.close() if s.dst else None)
        qc(sock.close)


def handle(sock, addr):
    try:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(10)

        raw = b""
        while b"\r\n\r\n" not in raw:
            c = sock.recv(4096)
            if not c:
                return
            raw += c
            if len(raw) > 65536:
                return

        action = ""
        for line in raw.decode(errors="replace").splitlines():
            if line.lower().startswith("action:"):
                action = line.split(":", 1)[1].strip().lower()
                break

        if not action:
            try:
                raw2 = b""
                while b"\r\n\r\n" not in raw2:
                    c = sock.recv(4096)
                    if not c:
                        return
                    raw2 += c
                for line in raw2.decode(errors="replace").splitlines():
                    if line.lower().startswith("action:"):
                        action = line.split(":", 1)[1].strip().lower()
                        break
            except Exception:
                pass

        if action in ("tunnel", "tunnel-fast"):
            sock.sendall(
                b"HTTP/1.1 101 Switching Protocols\r\n"
                b"Upgrade: websocket\r\n"
                b"Connection: Upgrade\r\n"
                b"X-Status: OK\r\n\r\n"
            )
            sock.settimeout(None)
            handle_smux(sock, addr)
        elif action == "auth":
            sock.sendall(
                b"HTTP/1.1 101 Switching Protocols\r\n"
                b"Upgrade: websocket\r\n"
                b"Connection: Upgrade\r\n"
                b"X-Status: OK\r\n"
                b"X-Name: test\r\n"
                b"X-Expire: unlimited\r\n"
                b"X-Days-Left: 0\r\n"
                b"X-Premium: 1\r\n\r\n"
            )
            qc(sock.close)
        else:
            qc(sock.close)
    except Exception:
        qc(sock.close)


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", PORT))
srv.listen(512)
print(f"escuchando :{PORT}")
while True:
    try:
        conn, addr = srv.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()
    except Exception:
        time.sleep(0.1)
PYEOF

cat > /etc/systemd/system/btserver.service << 'SVCEOF'
[Unit]
Description=BlackTunnel Server
After=network.target

[Service]
ExecStart=/usr/bin/python3 /opt/btserver/btserver.py
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
SVCEOF

systemctl daemon-reload
systemctl enable btserver
systemctl restart btserver
systemctl status btserver --no-pager
