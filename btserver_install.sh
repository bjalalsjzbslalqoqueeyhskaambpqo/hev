#!/bin/bash
set -euo pipefail

mkdir -p /opt/btserver

bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install

cat > /usr/local/etc/xray/config.json << 'EOF'
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "port": 10809,
      "listen": "127.0.0.1",
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "a3482e88-686a-4a58-8126-99c9df64b7bf"
          }
        ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "none"
      }
    }
  ],
  "outbounds": [
    { "protocol": "freedom" }
  ]
}
EOF

cat > /opt/btserver/btserver.py << 'PYEOF'
import socket
import threading
import time

PORT = 80
XRAY_ADDR = ("127.0.0.1", 10809)
MAX_ACTIVE_TUNNELS = 16
TUNNEL_TTL_SECONDS = 15 * 60
tunnel_slots = threading.BoundedSemaphore(MAX_ACTIVE_TUNNELS)


def pipe(src, dst, deadline):
    try:
        while True:
            if time.time() >= deadline:
                break
            remaining = max(0.2, deadline - time.time())
            src.settimeout(min(5.0, remaining))
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except Exception:
            pass


def handle_tunnel(client):
    upstream = None
    acquired = False
    try:
        acquired = tunnel_slots.acquire(timeout=30)
        if not acquired:
            return
        upstream = socket.create_connection(XRAY_ADDR, 5)
        deadline = time.time() + TUNNEL_TTL_SECONDS
        t1 = threading.Thread(target=pipe, args=(client, upstream, deadline), daemon=True)
        t2 = threading.Thread(target=pipe, args=(upstream, client, deadline), daemon=True)
        t1.start()
        t2.start()
        t1.join()
        t2.join()
    except Exception:
        pass
    finally:
        try:
            if upstream:
                upstream.close()
        except Exception:
            pass
        if acquired:
            try:
                tunnel_slots.release()
            except Exception:
                pass
        try:
            client.close()
        except Exception:
            pass


def handle(sock):
    try:
        sock.settimeout(60)
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

        if action in ("tunnel", "tunnel-fast"):
            sock.sendall(
                b"HTTP/1.1 101 Switching Protocols\r\n"
                b"Upgrade: websocket\r\n"
                b"Connection: Upgrade\r\n"
                b"X-Status: OK\r\n"
                b"X-Name: test-user\r\n"
                b"X-Expire: 2099-12-31\r\n"
                b"X-Days-Left: 9999\r\n"
                b"X-Premium: 1\r\n"
                b"X-Created: 2026-01-01\r\n"
                b"X-Tunnel-TTL: 900\r\n\r\n"
            )
            sock.settimeout(None)
            handle_tunnel(sock)
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
            sock.close()
        else:
            sock.close()
    except Exception:
        try:
            sock.close()
        except Exception:
            pass


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", PORT))
srv.listen(512)
print(f"escuchando :{PORT}")
while True:
    conn, _ = srv.accept()
    threading.Thread(target=handle, args=(conn,), daemon=True).start()
PYEOF

cat > /etc/systemd/system/btserver.service << 'SVCEOF'
[Unit]
Description=BlackTunnel Server
After=network.target xray.service

[Service]
ExecStart=/usr/bin/python3 /opt/btserver/btserver.py
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
SVCEOF

systemctl daemon-reload
systemctl daemon-reload
systemctl enable xray btserver
systemctl restart xray btserver
systemctl status xray --no-pager
systemctl status btserver --no-pager
