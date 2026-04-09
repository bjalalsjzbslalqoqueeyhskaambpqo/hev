#!/bin/bash
set -uo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

if [ "$(id -u)" -ne 0 ]; then error "Ejecutar como root."; exit 1; fi

info "Actualizando paquetes..."
apt-get update -qq
apt-get install -y -qq curl wget python3 python3-pip ca-certificates iproute2 unzip systemd

FRESH_INSTALL=true
if [ -f /opt/btserver/token.txt ] && systemctl is-active --quiet btserver 2>/dev/null; then
    FRESH_INSTALL=false
    warn "Instalación existente → modo ACTUALIZACIÓN"
else
    info "No se detectó instalación previa → modo INSTALACIÓN NUEVA"
fi

mkdir -p /opt/btserver

if [ -f /opt/btserver/token.txt ] && [ -s /opt/btserver/token.txt ]; then
    PANEL_TOKEN=$(cat /opt/btserver/token.txt)
    info "Token existente conservado."
else
    PANEL_TOKEN=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | head -c 64 || true)
    echo "${PANEL_TOKEN}" > /opt/btserver/token.txt
    chmod 600 /opt/btserver/token.txt
    info "Nuevo token generado."
fi

PANEL_PORT=8090
SERVER_IP=$(ip -4 addr show scope global | awk '/inet /{print $2}' | cut -d/ -f1 | head -1 || echo "0.0.0.0")

info "Activando BBR..."
if sysctl net.ipv4.tcp_available_congestion_control 2>/dev/null | grep -q bbr; then
    echo "net.core.default_qdisc=fq" >> /etc/sysctl.conf
    echo "net.ipv4.tcp_congestion_control=bbr" >> /etc/sysctl.conf
    sysctl -p -q 2>/dev/null || true
    info "BBR activado."
else
    warn "BBR no disponible en este kernel."
fi

info "Escribiendo smux_socks.py (SOCKS5 interno placeholder)..."
cat > /opt/btserver/smux_socks.py << 'PYEOF'
#!/usr/bin/env python3
import select
import socket
import struct
import threading

LISTEN_ADDR = ("127.0.0.1", 10809)


def read_exact(sock, n):
    data = b""
    while len(data) < n:
        part = sock.recv(n - len(data))
        if not part:
            raise ConnectionError("closed")
        data += part
    return data


def relay_bidi(a, b):
    sockets = [a, b]
    try:
        while True:
            readable, _, _ = select.select(sockets, [], [], 60)
            if not readable:
                continue
            for src in readable:
                dst = b if src is a else a
                chunk = src.recv(65536)
                if not chunk:
                    return
                dst.sendall(chunk)
    except Exception:
        pass
    finally:
        try:
            a.close()
        except Exception:
            pass
        try:
            b.close()
        except Exception:
            pass


def handle_client(client):
    remote = None
    try:
        head = read_exact(client, 2)
        ver, n_methods = head[0], head[1]
        if ver != 5:
            return
        methods = read_exact(client, n_methods)
        if 0x00 not in methods:
            client.sendall(b"\x05\xff")
            return
        client.sendall(b"\x05\x00")

        req = read_exact(client, 4)
        ver, cmd, _, atyp = req
        if ver != 5 or cmd != 1:
            client.sendall(b"\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00")
            return

        if atyp == 1:
            host = socket.inet_ntoa(read_exact(client, 4))
        elif atyp == 3:
            dlen = read_exact(client, 1)[0]
            host = read_exact(client, dlen).decode(errors="replace")
        elif atyp == 4:
            host = socket.inet_ntop(socket.AF_INET6, read_exact(client, 16))
        else:
            client.sendall(b"\x05\x08\x00\x01\x00\x00\x00\x00\x00\x00")
            return
        port = struct.unpack("!H", read_exact(client, 2))[0]

        remote = socket.create_connection((host, port), timeout=10)
        bind_host, bind_port = remote.getsockname()[:2]
        bind_ip = socket.inet_aton(bind_host) if "." in bind_host else b"\x00\x00\x00\x00"
        client.sendall(b"\x05\x00\x00\x01" + bind_ip + struct.pack("!H", bind_port))
        relay_bidi(client, remote)
    except Exception:
        try:
            client.sendall(b"\x05\x01\x00\x01\x00\x00\x00\x00\x00\x00")
        except Exception:
            pass
    finally:
        try:
            client.close()
        except Exception:
            pass
        if remote:
            try:
                remote.close()
            except Exception:
                pass


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(LISTEN_ADDR)
srv.listen(512)
print(f"smux socks5 interno en {LISTEN_ADDR[0]}:{LISTEN_ADDR[1]}")
while True:
    conn, _ = srv.accept()
    threading.Thread(target=handle_client, args=(conn,), daemon=True).start()
PYEOF
chmod +x /opt/btserver/smux_socks.py

info "Escribiendo btserver.py..."
cat > /opt/btserver/btserver.py << 'PYEOF'
#!/usr/bin/env python3
import json
import socket
import struct
import threading
import time
from pathlib import Path

PORT = 80
SMUX_UPSTREAM = ("127.0.0.1", 10809)
DB_PATH = Path("/opt/btserver/clients.json")
DB_CACHE = {}
DB_MTIME = 0.0
DB_LOCK = threading.Lock()

TYPE_OPEN  = 0x01
TYPE_DATA  = 0x02
TYPE_CLOSE = 0x03


def load_db():
    global DB_CACHE, DB_MTIME
    with DB_LOCK:
        if not DB_PATH.exists():
            DB_PATH.write_text("{}")
            DB_CACHE = {}
            DB_MTIME = DB_PATH.stat().st_mtime
            return DB_CACHE
        try:
            current_mtime = DB_PATH.stat().st_mtime
        except Exception:
            return DB_CACHE
        if current_mtime == DB_MTIME and DB_CACHE:
            return DB_CACHE
        try:
            DB_CACHE = json.loads(DB_PATH.read_text())
            DB_MTIME = current_mtime
        except Exception:
            DB_CACHE = {}
        return DB_CACHE


def ensure_client(db, client_id):
    item = db.get(client_id)
    now = int(time.time())
    if not item:
        return "UNKNOWN", 0, "", 0
    expires_at = int(item.get("expires_at", 0))
    name = str(item.get("name", "")).strip() or "sin-nombre"
    if now > expires_at:
        return "EXPIRED", 0, name, expires_at
    days_left = max(0, (expires_at - now + 86399) // 86400)
    return "VALID", days_left, name, expires_at


def reject(sock, state):
    payload = (
        "HTTP/1.1 403 Forbidden\r\n"
        "Connection: close\r\n"
        f"X-Status: {state}\r\nX-Auth-State: {state}\r\nX-Days-Left: 0\r\n\r\n"
    ).encode()
    try:
        sock.sendall(payload)
    except Exception:
        pass
    try:
        sock.close()
    except Exception:
        pass


def read_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def handle_mux_tunnel(client):
    streams = {}
    streams_lock = threading.Lock()
    write_lock = threading.Lock()

    def send_frame(type_, stream_id, data=b""):
        with write_lock:
            header = struct.pack("!B I I", type_, stream_id, len(data))
            try:
                client.sendall(header + data)
            except Exception:
                pass

    def upstream_to_client(stream_id, upstream_conn):
        try:
            while True:
                data = upstream_conn.recv(65536)
                if not data:
                    break
                send_frame(TYPE_DATA, stream_id, data)
        except Exception:
            pass
        send_frame(TYPE_CLOSE, stream_id)
        with streams_lock:
            streams.pop(stream_id, None)
        try:
            upstream_conn.close()
        except Exception:
            pass

    try:
        while True:
            header = read_exact(client, 9)
            type_, stream_id, length = struct.unpack("!B I I", header)
            data = read_exact(client, length) if length > 0 else b""

            if type_ == TYPE_OPEN:
                try:
                    upstream_conn = socket.create_connection(SMUX_UPSTREAM, 5)
                    with streams_lock:
                        streams[stream_id] = upstream_conn
                    threading.Thread(
                        target=upstream_to_client,
                        args=(stream_id, upstream_conn),
                        daemon=True
                    ).start()
                except Exception:
                    send_frame(TYPE_CLOSE, stream_id)

            elif type_ == TYPE_DATA:
                with streams_lock:
                    upstream_conn = streams.get(stream_id)
                if upstream_conn:
                    try:
                        upstream_conn.sendall(data)
                    except Exception:
                        send_frame(TYPE_CLOSE, stream_id)
                        with streams_lock:
                            streams.pop(stream_id, None)

            elif type_ == TYPE_CLOSE:
                with streams_lock:
                    upstream_conn = streams.pop(stream_id, None)
                if upstream_conn:
                    try:
                        upstream_conn.close()
                    except Exception:
                        pass

    except Exception:
        pass
    finally:
        with streams_lock:
            for conn in streams.values():
                try:
                    conn.close()
                except Exception:
                    pass
            streams.clear()
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
                    sock.settimeout(0.3)
                if header_complete_at and (time.time() - header_complete_at) > 0.2:
                    break
            except socket.timeout:
                if b"\r\n\r\n" in raw:
                    break
                return

        action = ""
        for line in raw.decode(errors="replace").splitlines():
            lower = line.lower()
            if lower.startswith("action:"):
                action = line.split(":", 1)[1].strip().lower()

        response = (
            b"HTTP/1.1 101 Switching Protocols\r\n"
            b"Upgrade: websocket\r\nConnection: Upgrade\r\n"
            b"X-Status: DEV-BYPASS\r\nX-Auth-State: DEV-BYPASS\r\n"
            b"X-Name: dev-mode\r\nX-Expire: 0\r\nX-Days-Left: 9999\r\n\r\n"
        )

        if action in ("tunnel", "tunnel-fast"):
            sock.sendall(response)
            sock.settimeout(None)
            handle_mux_tunnel(sock)
        elif action == "auth":
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
chmod +x /opt/btserver/btserver.py

info "Escribiendo panel.py..."
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
        return self.headers.get("X-Token", "").strip() == load_token()

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
                result.append({"id": cid, "name": data.get("name", "sin-nombre"),
                    "expires_at": exp, "days_left": days_left(exp), "active": now_ts() <= exp})
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
            json_resp(self, 200, {"id": cid, "name": item.get("name", "sin-nombre"),
                "expires_at": exp, "days_left": days_left(exp), "active": now_ts() <= exp})
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
            db[cid] = {"name": name, "created_at": db.get(cid, {}).get("created_at", now),
                "expires_at": now + max(days, 0) * 86400}
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
            json_resp(self, 200, {"ok": True, "id": cid, "name": item.get("name"), "days_left": days_left(exp)})
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
After=network.target smux-socks.service

[Service]
ExecStart=/usr/bin/python3 /opt/btserver/btserver.py
Restart=always
RestartSec=2
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
SVCEOF

cat > /etc/systemd/system/smux-socks.service << 'SVCEOF'
[Unit]
Description=BlackTunnel SMUX SOCKS5 Internal
After=network.target

[Service]
ExecStart=/usr/bin/python3 /opt/btserver/smux_socks.py
Restart=always
RestartSec=2
LimitNOFILE=65535

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
systemctl enable smux-socks btserver btpanel

info "Iniciando servicios..."
for svc in smux-socks btserver btpanel; do
    if systemctl restart "$svc" 2>/dev/null; then
        info "  ✓ $svc OK"
    else
        warn "  ✗ $svc falló — revisa: journalctl -u $svc -n 30"
    fi
done

echo ""
echo "================================================"
[ "$FRESH_INSTALL" = true ] && echo "  INSTALACION COMPLETA" || echo "  ACTUALIZACION COMPLETA"
echo "================================================"
echo ""
echo "  URL PANEL:  http://${SERVER_IP}:${PANEL_PORT}"
echo "  TOKEN:      ${PANEL_TOKEN}"
echo "  BBR: $(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || echo 'no disponible')"
echo "================================================"
