#!/bin/bash
set -euo pipefail

mkdir -p /opt/btserver

bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install

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


def handle_tunnel(client, initial_upstream_data=b""):
    upstream = None
    try:
        upstream = socket.create_connection(XRAY_ADDR, 5)
        if initial_upstream_data:
            upstream.sendall(initial_upstream_data)
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
                    sock.settimeout(0.3)
                if header_complete_at and (time.time() - header_complete_at) > 0.2:
                    break
            except socket.timeout:
                if b"\r\n\r\n" in raw:
                    break
                return

        header_breaks = []
        start = 0
        while True:
            idx = raw.find(b"\r\n\r\n", start)
            if idx < 0:
                break
            header_breaks.append(idx)
            start = idx + 4

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
                tail_start = 0
                if len(header_breaks) >= 2:
                    tail_start = header_breaks[1] + 4
                elif len(header_breaks) == 1:
                    tail_start = header_breaks[0] + 4
                initial_upstream_data = raw[tail_start:] if tail_start < len(raw) else b""
                handle_tunnel(sock, initial_upstream_data)
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


def now_ts():
    return int(time.time())


def days_left(expires_at: int) -> int:
    return max(0, (int(expires_at) - now_ts() + 86399) // 86400)


def create_client(client_id: str, name: str, days: int):
    db = load_db()
    now = now_ts()
    db[client_id] = {
        "name": name.strip() or "sin-nombre",
        "created_at": db.get(client_id, {}).get("created_at", now),
        "expires_at": now + max(days, 0) * 86400,
    }
    save_db(db)


def update_client(client_id: str, name: str = None, add_days: int = None, sub_days: int = None, set_days: int = None, new_id: str = None):
    db = load_db()
    item = db.get(client_id)
    if not item:
        return False, "client_id no existe"

    base_exp = int(item.get("expires_at", now_ts()))
    base = base_exp if base_exp > now_ts() else now_ts()

    if name is not None:
        item["name"] = name.strip() or "sin-nombre"
    if add_days is not None:
        item["expires_at"] = base + max(add_days, 0) * 86400
    if sub_days is not None:
        item["expires_at"] = max(0, base_exp - max(sub_days, 0) * 86400)
    if set_days is not None:
        item["expires_at"] = now_ts() + max(set_days, 0) * 86400

    if new_id and new_id != client_id:
        db.pop(client_id, None)
        db[new_id] = item
    else:
        db[client_id] = item

    save_db(db)
    return True, "ok"


def delete_client(client_id: str):
    db = load_db()
    if client_id in db:
        db.pop(client_id, None)
        save_db(db)
        return True
    return False


def render_rows():
    db = load_db()
    rows = []
    for cid, data in sorted(db.items()):
        name = str(data.get("name", "")).strip() or "sin-nombre"
        exp = int(data.get("expires_at", 0))
        rows.append((cid, name, days_left(exp), exp))
    return rows


class Handler(BaseHTTPRequestHandler):
    def _redirect(self):
        self.send_response(303)
        self.send_header("Location", "/")
        self.end_headers()

    def do_GET(self):
        rows = render_rows()
        table = ""
        for cid, name, dleft, exp in rows:
            table += f"""
            <tr>
              <td>{html.escape(cid)}</td>
              <td>{html.escape(name)}</td>
              <td>{dleft}</td>
              <td>{exp}</td>
              <td>
                <form method='POST' action='/update' style='display:inline;'>
                  <input type='hidden' name='client_id' value='{html.escape(cid)}'/>
                  <input name='name' placeholder='nombre' style='width:120px'/>
                  <button type='submit' name='action' value='set_name'>Editar</button>
                </form>
                <form method='POST' action='/update' style='display:inline;'>
                  <input type='hidden' name='client_id' value='{html.escape(cid)}'/>
                  <input name='new_id' placeholder='nuevo id' style='width:140px'/>
                  <button type='submit' name='action' value='rebind_id'>Cambiar ID</button>
                </form>
                <form method='POST' action='/update' style='display:inline;'>
                  <input type='hidden' name='client_id' value='{html.escape(cid)}'/>
                  <input name='days' value='1' type='number' min='0' style='width:70px'/>
                  <button type='submit' name='action' value='add_days'>+Días</button>
                </form>
                <form method='POST' action='/update' style='display:inline;'>
                  <input type='hidden' name='client_id' value='{html.escape(cid)}'/>
                  <input name='days' value='1' type='number' min='0' style='width:70px'/>
                  <button type='submit' name='action' value='sub_days'>-Días</button>
                </form>
                <form method='POST' action='/update' style='display:inline;'>
                  <input type='hidden' name='client_id' value='{html.escape(cid)}'/>
                  <input name='days' value='{dleft}' type='number' min='0' style='width:70px'/>
                  <button type='submit' name='action' value='set_days'>Set Días</button>
                </form>
                <form method='POST' action='/delete' style='display:inline;' onsubmit="return confirm('Eliminar {html.escape(cid)}?')">
                  <input type='hidden' name='client_id' value='{html.escape(cid)}'/>
                  <button type='submit'>Eliminar</button>
                </form>
              </td>
            </tr>
            """

        body = f"""
        <html><body style='font-family:sans-serif;max-width:1200px;margin:20px auto;'>
          <h2>BT Panel (Crear / Editar / Eliminar / Actualizar)</h2>
          <form method='POST' action='/create' style='padding:10px;border:1px solid #ddd;margin-bottom:14px;'>
            <h3>Crear usuario</h3>
            <input name='client_id' placeholder='client_id' style='width:340px;padding:8px' required />
            <input name='name' placeholder='nombre' style='width:220px;padding:8px' />
            <input name='days' type='number' min='0' value='30' style='width:90px;padding:8px' />
            <button type='submit'>Crear</button>
          </form>

          <table border='1' cellpadding='6' cellspacing='0' style='width:100%;'>
            <tr><th>Client ID</th><th>Nombre</th><th>Días restantes</th><th>Expires At</th><th>Acciones</th></tr>
            {table}
          </table>
        </body></html>
        """
        payload = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode()
        data = urllib.parse.parse_qs(raw)
        client_id = data.get("client_id", [""])[0].strip()
        name = data.get("name", [""])[0].strip()
        days_raw = data.get("days", ["0"])[0].strip()
        new_id = data.get("new_id", [""])[0].strip()
        action = data.get("action", [""])[0].strip()

        try:
            days = int(days_raw)
        except Exception:
            days = 0

        if self.path == "/create":
            if client_id:
                create_client(client_id, name, days)
            self._redirect()
            return

        if self.path == "/delete":
            if client_id:
                delete_client(client_id)
            self._redirect()
            return

        if self.path == "/update":
            if client_id:
                if action == "set_name":
                    update_client(client_id, name=name)
                elif action == "rebind_id":
                    if new_id:
                        update_client(client_id, new_id=new_id)
                elif action == "add_days":
                    update_client(client_id, add_days=days)
                elif action == "sub_days":
                    update_client(client_id, sub_days=days)
                elif action == "set_days":
                    update_client(client_id, set_days=days)
            self._redirect()
            return

        self.send_response(404)
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
systemctl enable xray btserver btpanel
systemctl restart xray btserver btpanel
systemctl status xray --no-pager
systemctl status btserver --no-pager
systemctl status btpanel --no-pager

echo ""
echo "Listo. Panel: http://<IP_SERVIDOR>:8090"
echo "Desde el panel puedes: crear, editar, eliminar, sumar/restar/setear días y cambiar ID."
