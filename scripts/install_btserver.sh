#!/bin/bash
set -uo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

if [ "$(id -u)" -ne 0 ]; then error "Ejecutar como root."; exit 1; fi

PANEL_PORT=8090
SERVER_IP=$(ip -4 addr show scope global | awk '/inet /{print $2}' | cut -d/ -f1 | head -1 || echo "0.0.0.0")

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

info "Configurando shell restringida para túnel..."
cat > /usr/local/bin/tunnel-only << 'SHEOF'
#!/bin/bash
echo "Tunneling only. No shell access."
sleep 86400
SHEOF
chmod +x /usr/local/bin/tunnel-only
grep -q "^/usr/local/bin/tunnel-only$" /etc/shells 2>/dev/null || echo "/usr/local/bin/tunnel-only" >> /etc/shells

info "Escribiendo panel.py con gestión SSH corregida..."
cat > /opt/btserver/panel.py << PYEOF
#!/usr/bin/env python3
import json
import re
import shutil
import subprocess
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

DB_PATH = Path("/opt/btserver/clients.json")
SSH_DB_PATH = Path("/opt/btserver/ssh_users.json")
TOKEN_PATH = Path("/opt/btserver/token.txt")
PORT = ${PANEL_PORT}

USERNAME_RE = re.compile(r"^[a-z_][a-z0-9_-]{0,31}$")
TUNNEL_SHELL = "/usr/local/bin/tunnel-only"

def load_token():
    return TOKEN_PATH.read_text().strip()

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

def load_json(path):
    if not path.exists():
        path.write_text("{}")
    try:
        return json.loads(path.read_text())
    except Exception:
        return {}

def save_json(path, payload):
    path.write_text(json.dumps(payload, indent=2, sort_keys=True))

def load_db():
    return load_json(DB_PATH)

def save_db(db):
    save_json(DB_PATH, db)

def load_ssh_db():
    return load_json(SSH_DB_PATH)

def save_ssh_db(db):
    save_json(SSH_DB_PATH, db)

def run_cmd(argv, stdin=None):
    return subprocess.run(argv, input=stdin, capture_output=True, text=True)

def user_exists(user):
    return run_cmd(["id", "-u", user]).returncode == 0

def normalize_days(payload):
    # Compatibilidad: acepta set_days, add_days o days.
    if "set_days" in payload:
        return "set", int(payload.get("set_days", 0))
    if "add_days" in payload:
        return "add", int(payload.get("add_days", 0))
    if "days" in payload:
        return "set", int(payload.get("days", 0))
    return "", 0

def upsert_system_user(user, password):
    # Crea usuario si no existe. Si existe, corrige shell para dropbear interno.
    if user_exists(user):
        shell_fix = run_cmd(["usermod", "-s", TUNNEL_SHELL, user])
        if shell_fix.returncode != 0:
            return False, f"usermod fallo: {shell_fix.stderr.strip()}"
    else:
        create = run_cmd(["useradd", "-m", "-s", TUNNEL_SHELL, user])
        if create.returncode != 0:
            return False, f"useradd fallo: {create.stderr.strip()}"

    pass_change = run_cmd(["chpasswd"], stdin=f"{user}:{password}")
    if pass_change.returncode != 0:
        return False, f"chpasswd fallo: {pass_change.stderr.strip()}"

    # Asegura cuenta desbloqueada (evita 'incorrect user name or password' en dropbear interno).
    unlock = run_cmd(["passwd", "-u", user])
    if unlock.returncode != 0 and "password unlocked" not in unlock.stdout.lower():
        return False, f"passwd -u fallo: {unlock.stderr.strip()}"
    return True, ""

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
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
                result.append({
                    "id": cid, "name": data.get("name", "sin-nombre"),
                    "expires_at": exp, "days_left": days_left(exp), "active": now_ts() <= exp
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
                "id": cid, "name": item.get("name", "sin-nombre"),
                "expires_at": exp, "days_left": days_left(exp), "active": now_ts() <= exp
            })
            return

        if path == "/ssh/users":
            db = load_ssh_db()
            result = []
            for user, data in sorted(db.items()):
                exp = int(data.get("expires_at", 0))
                result.append({
                    "user": user, "name": data.get("name", user),
                    "expires_at": exp, "days_left": days_left(exp), "active": now_ts() <= exp
                })
            json_resp(self, 200, {"users": result, "total": len(result)})
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
            db[cid] = {"name": name, "created_at": db.get(cid, {}).get("created_at", now), "expires_at": now + max(days, 0) * 86400}
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
            db[cid] = item
            save_db(db)
            exp = int(item.get("expires_at", 0))
            json_resp(self, 200, {"ok": True, "id": cid, "name": item.get("name"), "days_left": days_left(exp)})
            return

        if path == "/ssh/create":
            user = str(body.get("user", "")).strip()
            password = str(body.get("password", "")).strip()
            name = str(body.get("name", user)).strip() or user
            days = int(body.get("days", 30))
            if not user or not password:
                json_resp(self, 400, {"error": "falta user o password"})
                return
            if not USERNAME_RE.match(user):
                json_resp(self, 400, {"error": "usuario invalido (usa minusculas, numero, _ o -)"})
                return

            ok, reason = upsert_system_user(user, password)
            if not ok:
                json_resp(self, 500, {"error": reason})
                return

            db = load_ssh_db()
            now = now_ts()
            db[user] = {"name": name, "created_at": db.get(user, {}).get("created_at", now), "expires_at": now + max(days, 0) * 86400}
            save_ssh_db(db)
            json_resp(self, 200, {"ok": True, "user": user, "name": name, "days": days})
            return

        if path == "/ssh/delete":
            user = str(body.get("user", "")).strip()
            if not user:
                json_resp(self, 400, {"error": "falta user"})
                return
            db = load_ssh_db()
            if user not in db:
                json_resp(self, 404, {"error": "no encontrado"})
                return

            # Elimina procesos de sesión, borra usuario y home si existe.
            run_cmd(["pkill", "-KILL", "-u", user])
            if shutil.which("userdel"):
                run_cmd(["userdel", "-r", "-f", user])
            db.pop(user, None)
            save_ssh_db(db)
            json_resp(self, 200, {"ok": True})
            return

        if path == "/ssh/update":
            user = str(body.get("user", "")).strip()
            if not user:
                json_resp(self, 400, {"error": "falta user"})
                return
            db = load_ssh_db()
            item = db.get(user)
            if not item:
                json_resp(self, 404, {"error": "no encontrado"})
                return
            if "name" in body:
                item["name"] = str(body["name"]).strip() or user

            mode, value = normalize_days(body)
            base_exp = int(item.get("expires_at", now_ts()))
            base = base_exp if base_exp > now_ts() else now_ts()
            if mode == "add":
                item["expires_at"] = base + max(value, 0) * 86400
            elif mode == "set":
                item["expires_at"] = now_ts() + max(value, 0) * 86400

            db[user] = item
            save_ssh_db(db)
            exp = int(item.get("expires_at", 0))
            json_resp(self, 200, {"ok": True, "user": user, "days_left": days_left(exp)})
            return

        json_resp(self, 404, {"error": "ruta no encontrada"})

if __name__ == "__main__":
    print(f"panel api escuchando :{PORT}")
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
PYEOF
chmod +x /opt/btserver/panel.py

info "Escribiendo unit de systemd para panel..."
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
systemctl enable btpanel >/dev/null 2>&1 || true
systemctl restart btpanel

echo "================================================"
echo "  PANEL ACTUALIZADO"
echo "================================================"
echo "  URL PANEL:  http://${SERVER_IP}:${PANEL_PORT}"
echo "  TOKEN:      ${PANEL_TOKEN}"
echo "================================================"
