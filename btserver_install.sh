#!/bin/bash
set -euo pipefail

mkdir -p /opt/btserver

curl -L "https://github.com/go-gost/gost/releases/download/v3.2.6/gost_3.2.6_linux_amd64.tar.gz" \
  -o /opt/btserver/gost.tar.gz
tar -xzf /opt/btserver/gost.tar.gz -C /opt/btserver
chmod +x /opt/btserver/gost

cat > /opt/btserver/btserver.py << 'PYEOF'
import socket
import threading

PORT = 80
GOST_RELAY_ADDR = ("127.0.0.1", 18080)


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
        upstream = socket.create_connection(GOST_RELAY_ADDR, 5)
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
                b"X-Status: OK\r\n\r\n"
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
After=network.target

[Service]
ExecStart=/bin/bash -lc '/opt/btserver/gost -L "relay+tcp://127.0.0.1:18080?notls=true" & exec /usr/bin/python3 /opt/btserver/btserver.py'
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
SVCEOF

systemctl daemon-reload
systemctl enable btserver
systemctl restart btserver
systemctl status btserver --no-pager
