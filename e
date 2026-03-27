#!/bin/bash
set -euo pipefail

mkdir -p /opt/btserver

bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install

PANEL_TOKEN=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | head -c 64)
PANEL_PORT=8090
SERVER_IP=$(curl -s https://api.ipify.org || hostname -I | awk '{print $1}')

cat > /usr/local/etc/xray/config.json << 'XEOF'
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
XEOF

echo "${PANEL_TOKEN}" > /opt/btserver/token.txt
chmod 600 /opt/btserver/token.txt

cat > /opt/btserver/btserver.py << 'PYEOF'
#!/usr/bin/env python3
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


def ensure_client(db, client_id):
    item = db.get(client_id)
    now = int(time.time())
    if not item:
        return "UNKNOWN", 0, ""
    expires_at = int(item.get("expires_at", 0))
    name = str(item.get("name", "")).strip() or "sin-nombre"
    if now > expires_at:
        return "EXPIRED", 0, name
    days_left = max(0, (expires_at - now + 86399) // 86400)
    return "VALID", days_left, name


def reject(sock, state):
    payload = (
        "HTTP/1.1 403 Forbidden\r\n"
        "Connection: close\r\n"
        f"X-Auth-State: {state}\r\n"
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
        header_complete_at = None
        while True:
            try:
                chunk = sock.recv(4096)
                if not chunk:
                    return
                raw += chunk
                if len(raw) > 65536:
                    return
                if b"\r\n\r\n" in raw and header_complete_at is None:
                    header_complete_at = time.time()
                    sock.settimeout(0.2)
                if header_complete_at and (time.time() - header_complete_at) > 0.12:
                    break
            except socket.timeout:
                if b"\r\n\r\n" in raw:
                    break
                return

        action = ""
        client_id = ""
        for line in raw.decode(errors="replace").splitlines():
            lower = line.lower()
            if lower.startswith("action:"):
                action = line.split(":", 1)[1].strip().lower()
            if lower.startswith("x-client-id:"):
                client_id = line.split(":", 1)[1].strip()

        if not client_id:
            reject(sock, "INVALID")
            return

        db = load_db()
        state, days_left, name = ensure_client(db, client_id)
        if state != "VALID":
            reject(sock, state)
            return

        response = (
            b"HTTP/1.1 101 Switching Protocols\r\n"
            b"Upgrade: websocket\r\n"
            b"Connection: Upgrade\r\n"
            b"X-Auth-State: VALID\r\n"
            + f"X-Name: {name}\r\n".encode()
            + f"X-Days-Left: {days_left}\r\n\r\n".encode()
        )

        if action in ("auth", "tunnel", "tunnel-fast"):
            sock.sendall(response)
            if action == "auth":
                sock.close()
            else:
                sock.settimeout(None)
                handle_tunnel(sock)
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
chmod +x /opt/btserver/btserver.py

cat > /opt/btserver/panel.py << PYEOF
#!/usr/bin/env python3
import json
import time
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

DB_PATH = Path("/opt/btserver/clients.json")
TOKEN_PATH = Path("/opt/btserver/token.txt")
PORT = ${PANEL_PORT}


def load_token():
    return TOKEN_PATH.read_text().strip()


def load_db():
    if not DB_PATH.exists():
        DB_PATH.write_text("{}")
    try:
        return json.loads(DB_PATH.read_text())
    except Exception:
        return {}


def save_db(db):
    DB_PATH.write_text(json.dumps(db, indent=2, sort_keys=True))


def now_ts():
    return int(time.time())


def days_left(expires_at):
    return max(0, (int(expires_at) - now_ts() + 86399) // 86400)


def json_resp(handler, code, data):
    body = json.dumps(data, ensure_ascii=False).encode()
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def auth(self):
        token = self.headers.get("X-Token", "").strip()
        return token == load_token()

    def read_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length == 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode())
        except Exception:
            return {}

    def do_GET(self):
        if not self.auth():
            json_resp(self, 401, {"error": "unauthorized"})
            return

        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        params = parse_qs(parsed.query)

        if path == "/clients":
            db = load_db()
            result = []
            for cid, data in sorted(db.items()):
                exp = int(data.get("expires_at", 0))
                result.append({
                    "id": cid,
                    "name": data.get("name", "sin-nombre"),
                    "expires_at": exp,
                    "days_left": days_left(exp),
                    "active": now_ts() <= exp
                })
            json_resp(self, 200, {"clients": result, "total": len(result)})
            return

        if path == "/client":
            cid = params.get("id", [""])[0].strip()
            if not cid:
                json_resp(self, 400, {"error": "falta id"})
                return
            db = load_db()
            item = db.get(cid)
            if not item:
                json_resp(self, 404, {"error": "no encontrado"})
                return
            exp = int(item.get("expires_at", 0))
            json_resp(self, 200, {
                "id": cid,
                "name": item.get("name", "sin-nombre"),
                "expires_at": exp,
                "days_left": days_left(exp),
                "active": now_ts() <= exp
            })
            return

        json_resp(self, 404, {"error": "ruta no encontrada"})

    def do_POST(self):
        if not self.auth():
            json_resp(self, 401, {"error": "unauthorized"})
            return

        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        body = self.read_body()

        if path == "/client/create":
            cid = str(body.get("id", "")).strip()
            name = str(body.get("name", "sin-nombre")).strip() or "sin-nombre"
            days = int(body.get("days", 30))
            if not cid:
                json_resp(self, 400, {"error": "falta id"})
                return
            db = load_db()
            now = now_ts()
            db[cid] = {
                "name": name,
                "created_at": db.get(cid, {}).get("created_at", now),
                "expires_at": now + max(days, 0) * 86400
            }
            save_db(db)
            json_resp(self, 200, {"ok": True, "id": cid, "name": name, "days": days})
            return

        if path == "/client/delete":
            cid = str(body.get("id", "")).strip()
            if not cid:
                json_resp(self, 400, {"error": "falta id"})
                return
            db = load_db()
            if cid not in db:
                json_resp(self, 404, {"error": "no encontrado"})
                return
            db.pop(cid)
            save_db(db)
            json_resp(self, 200, {"ok": True})
            return

        if path == "/client/update":
            cid = str(body.get("id", "")).strip()
            if not cid:
                json_resp(self, 400, {"error": "falta id"})
                return
            db = load_db()
            item = db.get(cid)
            if not item:
                json_resp(self, 404, {"error": "no encontrado"})
                return

            if "name" in body:
                item["name"] = str(body["name"]).strip() or "sin-nombre"

            base_exp = int(item.get("expires_at", now_ts()))
            base = base_exp if base_exp > now_ts() else now_ts()

            if "add_days" in body:
                item["expires_at"] = base + max(int(body["add_days"]), 0) * 86400
            elif "sub_days" in body:
                item["expires_at"] = max(0, base_exp - max(int(body["sub_days"]), 0) * 86400)
            elif "set_days" in body:
                item["expires_at"] = now_ts() + max(int(body["set_days"]), 0) * 86400

            new_id = str(body.get("new_id", "")).strip()
            if new_id and new_id != cid:
                db.pop(cid)
                db[new_id] = item
                cid = new_id
            else:
                db[cid] = item

            save_db(db)
            exp = int(item.get("expires_at", 0))
            json_resp(self, 200, {
                "ok": True,
                "id": cid,
                "name": item.get("name"),
                "days_left": days_left(exp)
            })
            return

        json_resp(self, 404, {"error": "ruta no encontrada"})


if __name__ == "__main__":
    print(f"panel api escuchando :{PORT}")
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
Description=BlackTunnel Panel API
After=network.target

[Service]
ExecStart=/usr/bin/python3 /opt/btserver/panel.py
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
SVCEOF

systemctl daemon-reload
systemctl enable xray btserver btpanel
systemctl restart xray btserver btpanel

echo ""
echo "================================================"
echo "  INSTALACION COMPLETA"
echo "================================================"
echo ""
echo "  URL BASE DEL PANEL:"
echo "  http://${SERVER_IP}:${PANEL_PORT}"
echo ""
echo "  TOKEN DE ACCESO:"
echo "  ${PANEL_TOKEN}"
echo ""
echo "  PONELO EN LA APP ASI:"
echo "  BASE_URL = http://${SERVER_IP}:${PANEL_PORT}"
echo "  TOKEN    = ${PANEL_TOKEN}"
echo ""
echo "================================================"
