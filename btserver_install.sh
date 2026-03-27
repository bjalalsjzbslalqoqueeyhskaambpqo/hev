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
import json
import socket
import threading
import time
from pathlib import Path

PORT = 80
XRAY_ADDR = ("127.0.0.1", 10809)
DB_PATH = Path("/opt/btserver/clients.json")


def load_db():
    if not DB_PATH.exists():
        DB_PATH.write_text("{}")
    try:
        return json.loads(DB_PATH.read_text())
    except Exception:
        return {}


def save_db(db):
    DB_PATH.write_text(json.dumps(db, indent=2, sort_keys=True))


def ensure_client(db, client_id):
    current = db.get(client_id)
    now = int(time.time())
    if not current:
        return "UNKNOWN", 0, 0
    expires_at = int(current.get("expires_at", 0))
    days_left = max(0, (expires_at - now + 86399) // 86400)
    if days_left <= 0:
        return "EXPIRED", 0, expires_at
    return "VALID", days_left, expires_at


def reject(sock, status, message):
    payload = (
        f"HTTP/1.1 {status}\r\n"
        "Connection: close\r\n"
        f"X-Auth-State: {message}\r\n"
        "X-Days-Left: 0\r\n\r\n"
    ).encode()
    try:
        sock.sendall(payload)
    except Exception:
        pass
    try:
        sock.close()
    except Exception:
        pass


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

        action = ""
        client_id = ""
        for line in raw.decode(errors="replace").splitlines():
            if line.lower().startswith("action:"):
                action = line.split(":", 1)[1].strip().lower()
            if line.lower().startswith("x-client-id:"):
                client_id = line.split(":", 1)[1].strip()

        if not client_id:
            reject(sock, "403 Forbidden", "INVALID")
            return

        db = load_db()
        auth_state, days_left, expires_at = ensure_client(db, client_id)
        if auth_state != "VALID":
            reject(sock, "403 Forbidden", auth_state)
            return

        if action in ("tunnel", "tunnel-fast"):
            sock.sendall(
                b"HTTP/1.1 101 Switching Protocols\r\n"
                b"Upgrade: websocket\r\n"
                b"Connection: Upgrade\r\n"
                b"X-Auth-State: VALID\r\n"
                + f"X-Days-Left: {days_left}\r\n\r\n".encode()
            )
            sock.settimeout(None)
            handle_tunnel(sock)
        elif action == "auth":
            response = (
                b"HTTP/1.1 101 Switching Protocols\r\n"
                b"Upgrade: websocket\r\n"
                b"Connection: Upgrade\r\n"
                b"X-Auth-State: VALID\r\n"
                + f"X-Days-Left: {days_left}\r\n\r\n".encode()
            )
            sock.sendall(response)
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

cat > /opt/btserver/manage_client.py << 'PYEOF'
#!/usr/bin/env python3
import json
import sys
import time
from pathlib import Path

DB_PATH = Path("/opt/btserver/clients.json")


def load_db():
    if not DB_PATH.exists():
        DB_PATH.write_text("{}")
    try:
        return json.loads(DB_PATH.read_text())
    except Exception:
        return {}


def save_db(db):
    DB_PATH.write_text(json.dumps(db, indent=2, sort_keys=True))


def print_usage():
    print("Uso:")
    print("  manage_client.py add <client_id> <days>")
    print("  manage_client.py list")


def add_days(client_id: str, days: int):
    db = load_db()
    now = int(time.time())
    current = db.get(client_id, {})
    expires_at = int(current.get("expires_at", now))
    base = expires_at if expires_at > now else now
    db[client_id] = {"expires_at": base + days * 86400}
    save_db(db)
    print(f"OK client_id={client_id} days+={days} expires_at={db[client_id]['expires_at']}")


def list_clients():
    db = load_db()
    now = int(time.time())
    for client_id, data in sorted(db.items()):
        exp = int(data.get("expires_at", 0))
        days_left = max(0, (exp - now + 86399) // 86400)
        print(f"{client_id} days_left={days_left} expires_at={exp}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print_usage()
        raise SystemExit(1)
    cmd = sys.argv[1].lower()
    if cmd == "add" and len(sys.argv) == 4:
        add_days(sys.argv[2].strip(), int(sys.argv[3]))
    elif cmd == "list" and len(sys.argv) == 2:
        list_clients()
    else:
        print_usage()
        raise SystemExit(1)
PYEOF
chmod +x /opt/btserver/manage_client.py

cat > /opt/btserver/panel.py << 'PYEOF'
#!/usr/bin/env python3
import html
import json
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

DB_PATH = Path("/opt/btserver/clients.json")
PORT = 8090


def load_db():
    if not DB_PATH.exists():
        DB_PATH.write_text("{}")
    try:
        return json.loads(DB_PATH.read_text())
    except Exception:
        return {}


def save_db(db):
    DB_PATH.write_text(json.dumps(db, indent=2, sort_keys=True))


def add_days(client_id: str, days: int):
    db = load_db()
    now = int(time.time())
    current = db.get(client_id, {})
    expires_at = int(current.get("expires_at", now))
    base = expires_at if expires_at > now else now
    db[client_id] = {"expires_at": base + max(days, 0) * 86400}
    save_db(db)


def list_rows():
    db = load_db()
    now = int(time.time())
    rows = []
    for client_id, data in sorted(db.items()):
        exp = int(data.get("expires_at", 0))
        days_left = max(0, (exp - now + 86399) // 86400)
        rows.append((client_id, days_left, exp))
    return rows


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        rows = list_rows()
        table = "".join(
            f"<tr><td>{html.escape(cid)}</td><td>{days}</td><td>{exp}</td></tr>"
            for cid, days, exp in rows
        )
        body = f"""
        <html><body style="font-family:sans-serif;max-width:760px;margin:24px auto;">
        <h2>BT Server Panel</h2>
        <form method="POST" action="/add">
            <label>ID cliente:</label><br/>
            <input name="client_id" style="width:100%;padding:8px"/><br/><br/>
            <label>Días a agregar:</label><br/>
            <input name="days" value="30" type="number" min="0" style="width:100%;padding:8px"/><br/><br/>
            <button type="submit">Guardar</button>
        </form>
        <h3>Clientes</h3>
        <table border="1" cellpadding="6" cellspacing="0">
            <tr><th>Client ID</th><th>Días restantes</th><th>Expires At (unix)</th></tr>
            {table}
        </table>
        </body></html>
        """
        encoded = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_POST(self):
        if self.path != "/add":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode()
        data = urllib.parse.parse_qs(raw)
        client_id = data.get("client_id", [""])[0].strip()
        days_raw = data.get("days", ["0"])[0].strip()
        try:
            days = int(days_raw)
        except Exception:
            days = 0
        if client_id:
            add_days(client_id, days)
        self.send_response(303)
        self.send_header("Location", "/")
        self.end_headers()


if __name__ == "__main__":
    print(f"panel escuchando en :{PORT}")
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
PYEOF
chmod +x /opt/btserver/panel.py

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

cat > /etc/systemd/system/btpanel.service << 'SVCEOF'
[Unit]
Description=BlackTunnel Server Panel
After=network.target

[Service]
ExecStart=/usr/bin/python3 /opt/btserver/panel.py
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
SVCEOF

systemctl daemon-reload
systemctl daemon-reload
systemctl enable xray btserver btpanel
systemctl restart xray btserver btpanel
systemctl status xray --no-pager
systemctl status btserver --no-pager
systemctl status btpanel --no-pager
