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
        return "UNKNOWN", 0, ""
    expires_at = int(current.get("expires_at", 0))
    name = str(current.get("name", "")).strip() or "sin-nombre"
    if now > expires_at:
        return "EXPIRED", 0, name
    days_left = max(0, (expires_at - now + 86399) // 86400)
    return "VALID", days_left, name


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
        header_complete_at = None
        while True:
            try:
                c = sock.recv(4096)
                if not c:
                    return
                raw += c
                if len(raw) > 65536:
                    return
                if b"\r\n\r\n" in raw and header_complete_at is None:
                    header_complete_at = time.time()
                    sock.settimeout(0.2)
                if header_complete_at is not None and (time.time() - header_complete_at) > 0.12:
                    break
            except socket.timeout:
                if b"\r\n\r\n" in raw:
                    break
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
        auth_state, days_left, name = ensure_client(db, client_id)
        if auth_state != "VALID":
            reject(sock, "403 Forbidden", auth_state)
            return

        if action in ("tunnel", "tunnel-fast"):
            sock.sendall(
                b"HTTP/1.1 101 Switching Protocols\r\n"
                b"Upgrade: websocket\r\n"
                b"Connection: Upgrade\r\n"
                b"X-Auth-State: VALID\r\n"
                + f"X-Name: {name}\r\n".encode()
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
                + f"X-Name: {name}\r\n".encode()
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
    print("  manage_client.py add <client_id> <days> [name]")
    print("  manage_client.py name <client_id> <name>")
    print("  manage_client.py rebind <name> <new_client_id>")
    print("  manage_client.py list")


def add_days(client_id: str, days: int, name: str = ""):
    db = load_db()
    now = int(time.time())
    current = db.get(client_id, {})
    expires_at = int(current.get("expires_at", now))
    base = expires_at if expires_at > now else now
    current_name = str(current.get("name", "")).strip()
    target_name = name.strip() if name.strip() else (current_name or "sin-nombre")
    db[client_id] = {"expires_at": base + days * 86400, "name": target_name}
    save_db(db)
    print(f"OK client_id={client_id} name={target_name} days+={days} expires_at={db[client_id]['expires_at']}")


def set_name(client_id: str, name: str):
    db = load_db()
    current = db.get(client_id)
    if not current:
        print("ERROR client_id no existe")
        raise SystemExit(1)
    current["name"] = name.strip() or "sin-nombre"
    db[client_id] = current
    save_db(db)
    print(f"OK client_id={client_id} name={db[client_id]['name']}")


def rebind_by_name(name: str, new_client_id: str):
    db = load_db()
    target_name = name.strip()
    if not target_name:
        print("ERROR nombre vacío")
        raise SystemExit(1)
    source_id = None
    source_data = None
    for cid, data in db.items():
        if str(data.get("name", "")).strip().lower() == target_name.lower():
            source_id = cid
            source_data = data
            break
    if source_data is None:
        print("ERROR nombre no encontrado")
        raise SystemExit(1)

    db.pop(source_id, None)
    db[new_client_id] = {
        "expires_at": int(source_data.get("expires_at", int(time.time()))),
        "name": str(source_data.get("name", "")).strip() or target_name,
    }
    save_db(db)
    print(f"OK rebind name={target_name} old_id={source_id} new_id={new_client_id}")


def list_clients():
    db = load_db()
    now = int(time.time())
    for client_id, data in sorted(db.items()):
        exp = int(data.get("expires_at", 0))
        days_left = max(0, (exp - now + 86399) // 86400)
        name = str(data.get("name", "")).strip() or "sin-nombre"
        print(f"{client_id} name={name} days_left={days_left} expires_at={exp}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print_usage()
        raise SystemExit(1)
    cmd = sys.argv[1].lower()
    if cmd == "add" and len(sys.argv) in (4, 5):
        add_days(sys.argv[2].strip(), int(sys.argv[3]), sys.argv[4] if len(sys.argv) == 5 else "")
    elif cmd == "name" and len(sys.argv) == 4:
        set_name(sys.argv[2].strip(), sys.argv[3])
    elif cmd == "rebind" and len(sys.argv) == 4:
        rebind_by_name(sys.argv[2], sys.argv[3].strip())
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


def add_days(client_id: str, days: int, name: str = ""):
    db = load_db()
    now = int(time.time())
    current = db.get(client_id, {})
    expires_at = int(current.get("expires_at", now))
    base = expires_at if expires_at > now else now
    current_name = str(current.get("name", "")).strip()
    target_name = name.strip() if name.strip() else (current_name or "sin-nombre")
    db[client_id] = {"expires_at": base + max(days, 0) * 86400, "name": target_name}
    save_db(db)


def list_rows():
    db = load_db()
    now = int(time.time())
    rows = []
    for client_id, data in sorted(db.items()):
        exp = int(data.get("expires_at", 0))
        days_left = max(0, (exp - now + 86399) // 86400)
        name = str(data.get("name", "")).strip() or "sin-nombre"
        rows.append((client_id, name, days_left, exp))
    return rows


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        rows = list_rows()
        table = "".join(
            f"<tr><td>{html.escape(cid)}</td><td>{html.escape(name)}</td><td>{days}</td><td>{exp}</td></tr>"
            for cid, name, days, exp in rows
        )
        body = f"""
        <html><body style="font-family:sans-serif;max-width:760px;margin:24px auto;">
        <h2>BT Server Panel</h2>
        <form method="POST" action="/add">
            <label>ID cliente:</label><br/>
            <input name="client_id" style="width:100%;padding:8px"/><br/><br/>
            <label>Días a agregar:</label><br/>
            <input name="days" value="30" type="number" min="0" style="width:100%;padding:8px"/><br/><br/>
            <label>Nombre:</label><br/>
            <input name="name" placeholder="Juan" style="width:100%;padding:8px"/><br/><br/>
            <button type="submit">Guardar</button>
        </form>
        <h3>Clientes</h3>
        <table border="1" cellpadding="6" cellspacing="0">
            <tr><th>Client ID</th><th>Nombre</th><th>Días restantes</th><th>Expires At (unix)</th></tr>
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
        name = data.get("name", [""])[0].strip()
        try:
            days = int(days_raw)
        except Exception:
            days = 0
        if client_id:
            add_days(client_id, days, name)
        self.send_response(303)
        self.send_header("Location", "/")
        self.end_headers()


if __name__ == "__main__":
    print(f"panel escuchando en :{PORT}")
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
PYEOF
chmod +x /opt/btserver/panel.py

cat > /opt/btserver/menu.sh << 'SH'
#!/bin/bash
set -euo pipefail

show_menu() {
  echo "==============================="
  echo "   BlackTunnel Admin Menu"
  echo "==============================="
  echo "1) Agregar días / crear client_id"
  echo "2) Editar nombre de client_id"
  echo "3) Reasignar por nombre (nuevo client_id)"
  echo "4) Listar client_id"
  echo "5) Ver estado servicios"
  echo "6) Abrir panel web (URL)"
  echo "0) Salir"
  echo -n "Opción: "
}

while true; do
  show_menu
  read -r opt
  case "${opt}" in
    1)
      echo -n "Client ID: "
      read -r client_id
      echo -n "Días a agregar: "
      read -r days
      echo -n "Nombre (opcional): "
      read -r name
      /usr/bin/python3 /opt/btserver/manage_client.py add "${client_id}" "${days}" "${name}"
      ;;
    2)
      echo -n "Client ID: "
      read -r client_id
      echo -n "Nuevo nombre: "
      read -r name
      /usr/bin/python3 /opt/btserver/manage_client.py name "${client_id}" "${name}"
      ;;
    3)
      echo -n "Nombre actual: "
      read -r name
      echo -n "Nuevo Client ID: "
      read -r client_id
      /usr/bin/python3 /opt/btserver/manage_client.py rebind "${name}" "${client_id}"
      ;;
    4)
      /usr/bin/python3 /opt/btserver/manage_client.py list
      ;;
    5)
      systemctl --no-pager status xray btserver btpanel || true
      ;;
    6)
      ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
      if [[ -z "${ip:-}" ]]; then ip="TU_IP_SERVIDOR"; fi
      echo "Panel web: http://${ip}:8090"
      ;;
    0)
      exit 0
      ;;
    *)
      echo "Opción inválida"
      ;;
  esac
  echo ""
done
SH
chmod +x /opt/btserver/menu.sh

cat > /usr/local/bin/btpanel << 'SH'
#!/bin/bash
set -euo pipefail

cmd="${1:-menu}"
case "$cmd" in
  menu)
    exec /opt/btserver/menu.sh
    ;;
  add)
    shift
    exec /usr/bin/python3 /opt/btserver/manage_client.py add "$@"
    ;;
  name)
    shift
    exec /usr/bin/python3 /opt/btserver/manage_client.py name "$@"
    ;;
  rebind)
    shift
    exec /usr/bin/python3 /opt/btserver/manage_client.py rebind "$@"
    ;;
  list)
    exec /usr/bin/python3 /opt/btserver/manage_client.py list
    ;;
  web)
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    if [[ -z "${ip:-}" ]]; then ip="TU_IP_SERVIDOR"; fi
    echo "http://${ip}:8090"
    ;;
  restart)
    exec systemctl restart btserver btpanel
    ;;
  status)
    exec systemctl --no-pager status xray btserver btpanel
    ;;
  *)
    echo "Uso:"
    echo "  btpanel menu           # menú interactivo"
    echo "  btpanel add <id> <d> [nombre] # agregar días/crear"
    echo "  btpanel name <id> <nombre>    # editar nombre"
    echo "  btpanel rebind <nombre> <id>  # mover cuenta a nuevo id"
    echo "  btpanel list           # listar IDs"
    echo "  btpanel web            # mostrar URL panel web"
    echo "  btpanel restart        # reiniciar servicios"
    echo "  btpanel status         # estado servicios"
    exit 1
    ;;
esac
SH
chmod +x /usr/local/bin/btpanel

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

echo ""
echo "Listo. Puedes gestionar con el comando global: btpanel"
echo "Ejemplos:"
echo "  btpanel menu"
echo "  btpanel add <client_id> <dias> <nombre>"
echo "  btpanel name <client_id> <nombre>"
echo "  btpanel rebind <nombre> <nuevo_client_id>"
echo "  btpanel list"
echo "  btpanel web"
