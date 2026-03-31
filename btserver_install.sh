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

info "Instalando/actualizando Xray..."
if bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install; then
    info "Xray instalado correctamente."
else
    error "Falló la instalación de Xray."
    exit 1
fi

info "Activando BBR..."
if sysctl net.ipv4.tcp_available_congestion_control 2>/dev/null | grep -q bbr; then
    echo "net.core.default_qdisc=fq" >> /etc/sysctl.conf
    echo "net.ipv4.tcp_congestion_control=bbr" >> /etc/sysctl.conf
    sysctl -p -q 2>/dev/null || true
    info "BBR activado."
else
    warn "BBR no disponible en este kernel."
fi

XRAY_CONFIG=/usr/local/etc/xray/config.json
mkdir -p "$(dirname "$XRAY_CONFIG")"

if [ "$FRESH_INSTALL" = true ] || [ ! -f "$XRAY_CONFIG" ]; then
    info "Escribiendo configuración de Xray..."
    cat > "$XRAY_CONFIG" << 'XEOF'
{
  "log": { "loglevel": "warning" },
  "dns": {
    "servers": ["8.8.8.8", "1.1.1.1"],
    "queryStrategy": "UseIPv4"
  },
  "policy": {
    "levels": {
      "0": {
        "handshake": 4,
        "connIdle": 0,
        "uplinkOnly": 0,
        "downlinkOnly": 0,
        "bufferSize": 512
      }
    },
    "system": {
      "udpTimeout": 0,
      "connIdle": 0,
      "downlinkOnly": 0,
      "uplinkOnly": 0
    }
  },
  "inbounds": [
    {
      "port": 10809,
      "listen": "127.0.0.1",
      "protocol": "vless",
      "settings": {
        "clients": [{ "id": "a3482e88-686a-4a58-8126-99c9df64b7bf" }],
        "decryption": "none"
      },
      "streamSettings": { "network": "tcp", "security": "none" }
    }
  ],
  "outbounds": [
    {
      "protocol": "freedom",
      "settings": { "domainStrategy": "UseIPv4" },
      "streamSettings": {
        "sockopt": {
          "tcpFastOpen": true,
          "tcpCongestion": "bbr",
          "tcpKeepAliveInterval": 30,
          "tcpKeepAliveIdle": 60,
          "tcpUserTimeout": 10000
        }
      }
    }
  ]
}
XEOF
else
    warn "Config de Xray existente conservada."
fi

info "Escribiendo btserver.py..."
cat > /opt/btserver/btserver.py << 'PYEOF'
#!/usr/bin/env python3
import asyncio
import json
import struct
import time
from pathlib import Path

PORT = 80
XRAY_HOST = "127.0.0.1"
XRAY_PORT = 10809
DB_PATH = Path("/opt/btserver/clients.json")

TYPE_OPEN  = 0x01
TYPE_DATA  = 0x02
TYPE_CLOSE = 0x03

DB_CACHE = {}
DB_MTIME = 0.0


def load_db():
    global DB_CACHE, DB_MTIME
    if not DB_PATH.exists():
        DB_PATH.write_text("{}")
        DB_CACHE = {}
        DB_MTIME = DB_PATH.stat().st_mtime
        return DB_CACHE
    try:
        mtime = DB_PATH.stat().st_mtime
    except Exception:
        return DB_CACHE
    if mtime == DB_MTIME and DB_CACHE:
        return DB_CACHE
    try:
        DB_CACHE = json.loads(DB_PATH.read_text())
        DB_MTIME = mtime
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


async def read_exact(reader, n):
    data = await reader.readexactly(n)
    return data


async def pipe(reader, writer):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except Exception:
        pass
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def handle_mux_tunnel(client_reader, client_writer):
    streams = {}
    write_lock = asyncio.Lock()

    async def send_frame(type_, stream_id, data=b""):
        async with write_lock:
            header = struct.pack("!B I I", type_, stream_id, len(data))
            client_writer.write(header + data)
            await client_writer.drain()

    async def xray_to_client(stream_id, xray_reader):
        try:
            while True:
                data = await xray_reader.read(65536)
                if not data:
                    break
                await send_frame(TYPE_DATA, stream_id, data)
        except Exception:
            pass
        await send_frame(TYPE_CLOSE, stream_id)
        streams.pop(stream_id, None)

    try:
        while True:
            header = await read_exact(client_reader, 9)
            type_, stream_id, length = struct.unpack("!B I I", header)
            data = await read_exact(client_reader, length) if length > 0 else b""

            if type_ == TYPE_OPEN:
                try:
                    xray_reader, xray_writer = await asyncio.open_connection(XRAY_HOST, XRAY_PORT)
                    streams[stream_id] = (xray_reader, xray_writer)
                    asyncio.ensure_future(xray_to_client(stream_id, xray_reader))
                except Exception:
                    await send_frame(TYPE_CLOSE, stream_id)

            elif type_ == TYPE_DATA:
                pair = streams.get(stream_id)
                if pair:
                    _, xray_writer = pair
                    try:
                        xray_writer.write(data)
                        await xray_writer.drain()
                    except Exception:
                        await send_frame(TYPE_CLOSE, stream_id)
                        streams.pop(stream_id, None)

            elif type_ == TYPE_CLOSE:
                pair = streams.pop(stream_id, None)
                if pair:
                    try:
                        _, xray_writer = pair
                        xray_writer.close()
                        await xray_writer.wait_closed()
                    except Exception:
                        pass

    except Exception:
        pass
    finally:
        for _, (_, xray_writer) in list(streams.items()):
            try:
                xray_writer.close()
                await xray_writer.wait_closed()
            except Exception:
                pass
        streams.clear()
        try:
            client_writer.close()
            await client_writer.wait_closed()
        except Exception:
            pass


async def handle(reader, writer):
    try:
        writer.transport.set_write_buffer_limits(high=65536, low=32768)
        raw = b""
        header_complete_at = None

        while True:
            try:
                chunk = await asyncio.wait_for(reader.read(4096), timeout=10)
                if not chunk:
                    return
                raw += chunk
                if len(raw) > 65536:
                    return
                if b"\r\n\r\n" in raw and header_complete_at is None:
                    header_complete_at = time.monotonic()
                if header_complete_at and (time.monotonic() - header_complete_at) > 0.2:
                    break
            except asyncio.TimeoutError:
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

        async def reject(state):
            payload = (
                "HTTP/1.1 403 Forbidden\r\n"
                "Connection: close\r\n"
                f"X-Status: {state}\r\nX-Auth-State: {state}\r\nX-Days-Left: 0\r\n\r\n"
            ).encode()
            try:
                writer.write(payload)
                await writer.drain()
            except Exception:
                pass
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

        if not client_id:
            await reject("INVALID")
            return

        db = load_db()
        state, days_left, name, expires_at = ensure_client(db, client_id)
        if state != "VALID":
            await reject(state)
            return

        response = (
            b"HTTP/1.1 101 Switching Protocols\r\n"
            b"Upgrade: websocket\r\nConnection: Upgrade\r\n"
            b"X-Status: VALID\r\nX-Auth-State: VALID\r\n"
            + f"X-Name: {name}\r\nX-Expire: {expires_at}\r\nX-Days-Left: {days_left}\r\n\r\n".encode()
        )

        if action in ("tunnel", "tunnel-fast"):
            writer.write(response)
            await writer.drain()
            await handle_mux_tunnel(reader, writer)
        elif action == "auth":
            writer.write(response)
            await writer.drain()
            writer.close()
            await writer.wait_closed()
        else:
            writer.close()
            await writer.wait_closed()

    except Exception:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def main():
    server = await asyncio.start_server(
        handle,
        "0.0.0.0",
        PORT,
        limit=65536,
        backlog=512
    )
    print(f"escuchando :{PORT}")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())

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
After=network.target xray.service

[Service]
ExecStart=/usr/bin/python3 /opt/btserver/btserver.py
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
systemctl enable xray btserver btpanel

info "Iniciando servicios..."
for svc in xray btserver btpanel; do
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
