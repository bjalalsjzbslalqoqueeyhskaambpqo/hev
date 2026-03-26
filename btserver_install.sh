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
import os
import socket
import threading

PORT = 80
XRAY_ADDR = ("127.0.0.1", 10809)
CENTRAL_HOST = os.environ.get("BT_CENTRAL_HOST", "127.0.0.1")
CENTRAL_PORT = int(os.environ.get("BT_CENTRAL_PORT", "80"))


def read_headers(raw: bytes):
    headers = {}
    lines = raw.decode(errors="replace").splitlines()
    for line in lines[1:]:
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        headers[k.strip().lower()] = v.strip()
    return headers


def parse_status_block(raw: bytes):
    lines = raw.decode(errors="replace").splitlines()
    status_code = -1
    if lines and lines[0].startswith("HTTP/1.1"):
        parts = lines[0].split(" ")
        if len(parts) > 1 and parts[1].isdigit():
            status_code = int(parts[1])
    headers = {}
    for line in lines[1:]:
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        headers[k.strip().lower()] = v.strip()
    return status_code, headers


def verify_client_id(client_id: str):
    if not client_id:
        return False, {
            "x-status": "INVALID",
            "x-name": "-",
            "x-expire": "-",
            "x-days-left": "0",
            "x-premium": "0",
        }
    try:
        req = (
            f"BT-VERIFY / HTTP/1.1\r\n"
            f"Host: {CENTRAL_HOST}\r\n"
            f"Auth: {client_id}\r\n\r\n"
        ).encode()
        with socket.create_connection((CENTRAL_HOST, CENTRAL_PORT), 5) as s:
            s.sendall(req)
            raw = b""
            while b"\r\n\r\n" not in raw and len(raw) < 65536:
                chunk = s.recv(4096)
                if not chunk:
                    break
                raw += chunk
        status_code, headers = parse_status_block(raw)
        ok = status_code == 101 and headers.get("x-status", "").upper() == "OK"
        return ok, {
            "x-status": headers.get("x-status", "INVALID"),
            "x-name": headers.get("x-name", "-"),
            "x-expire": headers.get("x-expire", "-"),
            "x-days-left": headers.get("x-days-left", "0"),
            "x-premium": headers.get("x-premium", "0"),
        }
    except Exception:
        return False, {
            "x-status": "CENTRAL_OFFLINE",
            "x-name": "-",
            "x-expire": "-",
            "x-days-left": "0",
            "x-premium": "0",
        }


def pipe(src, dst):
    try:
        while True:
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
    try:
        upstream = socket.create_connection(XRAY_ADDR, 5)
        t1 = threading.Thread(target=pipe, args=(client, upstream), daemon=True)
        t2 = threading.Thread(target=pipe, args=(upstream, client), daemon=True)
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
        try:
            client.close()
        except Exception:
            pass


def handle(sock):
    try:
        sock.settimeout(10)
        raw = b""
        while b"\r\n\r\n" not in raw:
            c = sock.recv(4096)
            if not c:
                return
            raw += c
            if len(raw) > 65536:
                return

        headers = read_headers(raw)
        action = headers.get("action", "").lower()
        client_id = headers.get("auth", "").strip()

        if action in ("tunnel", "tunnel-fast"):
            is_valid, payload = verify_client_id(client_id)
            if not is_valid:
                sock.sendall(
                    b"HTTP/1.1 101 Switching Protocols\r\n"
                    b"Upgrade: websocket\r\n"
                    b"Connection: Upgrade\r\n"
                    + f"X-Status: {payload['x-status']}\r\n".encode()
                    + f"X-Name: {payload['x-name']}\r\n".encode()
                    + f"X-Expire: {payload['x-expire']}\r\n".encode()
                    + f"X-Days-Left: {payload['x-days-left']}\r\n".encode()
                    + f"X-Premium: {payload['x-premium']}\r\n\r\n".encode()
                )
                sock.close()
                return
            sock.sendall(
                b"HTTP/1.1 101 Switching Protocols\r\n"
                b"Upgrade: websocket\r\n"
                b"Connection: Upgrade\r\n"
                + f"X-Status: {payload['x-status']}\r\n".encode()
                + f"X-Name: {payload['x-name']}\r\n".encode()
                + f"X-Expire: {payload['x-expire']}\r\n".encode()
                + f"X-Days-Left: {payload['x-days-left']}\r\n".encode()
                + f"X-Premium: {payload['x-premium']}\r\n\r\n".encode()
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
systemctl enable xray btserver
systemctl restart xray btserver
systemctl status xray --no-pager
systemctl status btserver --no-pager
