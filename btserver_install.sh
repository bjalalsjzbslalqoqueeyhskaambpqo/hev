#!/bin/bash
set -euo pipefail

BT_DIR=/opt/btserver
BT_PY="$BT_DIR/btserver.py"
BTCTL=/usr/local/bin/btctl
XRAY_CFG=/usr/local/etc/xray/config.json
SERVICE_FILE=/etc/systemd/system/btserver.service

default_xray_config() {
cat > "$XRAY_CFG" << 'XRAYEOF'
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "port": 10809,
      "listen": "127.0.0.1",
      "protocol": "vless",
      "settings": {
        "clients": [],
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
XRAYEOF
}

write_btserver_py() {
cat > "$BT_PY" << 'PYEOF'
#!/usr/bin/env python3
import json
import socket
import subprocess
import threading
import uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path

PORT = 80
XRAY_ADDR = ("127.0.0.1", 10809)
DB_PATH = Path("/opt/btserver/clients.json")
XRAY_CONFIG = Path("/usr/local/etc/xray/config.json")

lock = threading.Lock()
active_by_uuid = {}


def now_utc():
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_iso(raw: str):
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except Exception:
        return None


def load_db():
    if not DB_PATH.exists():
        return {"clients": {}}
    try:
        data = json.loads(DB_PATH.read_text())
        clients = data.get("clients") if isinstance(data, dict) else None
        if not isinstance(clients, dict):
            return {"clients": {}}
        return {"clients": clients}
    except Exception:
        return {"clients": {}}


def save_db(db):
    DB_PATH.write_text(json.dumps(db, indent=2, sort_keys=True) + "\n")


def status_for(client):
    if client is None:
        return "NOT_ADDED", None, None
    expires = parse_iso(client.get("expires_at", ""))
    if expires is None:
        return "EXPIRED", client.get("created_at"), client.get("expires_at")
    if now_utc() > expires:
        return "EXPIRED", client.get("created_at"), client.get("expires_at")
    return "VALID", client.get("created_at"), client.get("expires_at")


def days_left(expires_at):
    expires = parse_iso(expires_at or "")
    if not expires:
        return "0"
    delta = expires - now_utc()
    return str(max(0, int(delta.total_seconds() // 86400)))


def sync_xray_clients(db):
    config = json.loads(XRAY_CONFIG.read_text())
    clients = []
    for client_uuid, payload in db.get("clients", {}).items():
        status, _, _ = status_for(payload)
        if status == "VALID":
            clients.append({"id": client_uuid})

    config["inbounds"][0]["settings"]["clients"] = clients
    XRAY_CONFIG.write_text(json.dumps(config, indent=2) + "\n")
    subprocess.run(["systemctl", "restart", "xray"], check=True)


def create_client(days: int):
    db = load_db()
    client_uuid = str(uuid.uuid4())
    created = now_utc()
    expires = created + timedelta(days=max(1, days))
    db["clients"][client_uuid] = {
        "created_at": iso(created),
        "days": max(1, days),
        "expires_at": iso(expires),
    }
    save_db(db)
    sync_xray_clients(db)
    print(f"UUID: {client_uuid}")
    print(f"CREATED_AT: {iso(created)}")
    print(f"EXPIRES_AT: {iso(expires)}")


def extend_client(client_uuid: str, days: int):
    db = load_db()
    if client_uuid not in db.get("clients", {}):
        raise SystemExit("UUID no existe")
    created = now_utc()
    expires = created + timedelta(days=max(1, days))
    db["clients"][client_uuid]["created_at"] = iso(created)
    db["clients"][client_uuid]["days"] = max(1, days)
    db["clients"][client_uuid]["expires_at"] = iso(expires)
    save_db(db)
    sync_xray_clients(db)
    print(f"UUID: {client_uuid}")
    print(f"CREATED_AT: {iso(created)}")
    print(f"EXPIRES_AT: {iso(expires)}")


def list_clients():
    db = load_db()
    print("UUID | STATUS | CREATED_AT | EXPIRES_AT | DAYS_LEFT")
    print("-" * 88)
    for client_uuid, payload in sorted(db.get("clients", {}).items()):
        status, created_at, expires_at = status_for(payload)
        print(f"{client_uuid} | {status} | {created_at or '-'} | {expires_at or '-'} | {days_left(expires_at)}")


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


def close_existing(uuid_value: str):
    with lock:
        old = active_by_uuid.get(uuid_value)
        if old:
            try:
                old.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                old.close()
            except Exception:
                pass
        active_by_uuid[uuid_value] = None


def activate(uuid_value: str, sock: socket.socket):
    with lock:
        old = active_by_uuid.get(uuid_value)
        if old and old is not sock:
            try:
                old.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                old.close()
            except Exception:
                pass
        active_by_uuid[uuid_value] = sock


def deactivate(uuid_value: str, sock: socket.socket):
    with lock:
        if active_by_uuid.get(uuid_value) is sock:
            active_by_uuid.pop(uuid_value, None)


def parse_headers(raw: bytes):
    action = ""
    uuid_value = ""
    for line in raw.decode(errors="replace").splitlines():
        if line.lower().startswith("action:"):
            action = line.split(":", 1)[1].strip().lower()
        if line.lower().startswith("x-uuid:"):
            uuid_value = line.split(":", 1)[1].strip()
    return action, uuid_value


def headers_for(uuid_value: str):
    db = load_db()
    client = db.get("clients", {}).get(uuid_value)
    status, created_at, expires_at = status_for(client)
    return {
        "X-Status": status,
        "X-Name": uuid_value or "-",
        "X-Expire": expires_at or "-",
        "X-Days-Left": days_left(expires_at),
        "X-Premium": "1" if status == "VALID" else "0",
        "X-Created-At": created_at or "-",
    }, status


def send_101(sock: socket.socket, headers: dict):
    payload = [
        b"HTTP/1.1 101 Switching Protocols",
        b"Upgrade: websocket",
        b"Connection: Upgrade",
    ]
    for k, v in headers.items():
        payload.append(f"{k}: {v}".encode())
    sock.sendall(b"\r\n".join(payload) + b"\r\n\r\n")


def handle_tunnel(client: socket.socket, uuid_value: str):
    upstream = None
    try:
        upstream = socket.create_connection(XRAY_ADDR, 5)
        activate(uuid_value, client)
        t1 = threading.Thread(target=pipe, args=(client, upstream), daemon=True)
        t2 = threading.Thread(target=pipe, args=(upstream, client), daemon=True)
        t1.start()
        t2.start()
        t1.join()
        t2.join()
    except Exception:
        pass
    finally:
        deactivate(uuid_value, client)
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

        action, uuid_value = parse_headers(raw)
        headers, status = headers_for(uuid_value)

        if action in ("tunnel", "tunnel-fast"):
            send_101(sock, headers)
            if status != "VALID":
                sock.close()
                return
            close_existing(uuid_value)
            sock.settimeout(None)
            handle_tunnel(sock, uuid_value)
        elif action == "auth":
            send_101(sock, headers)
            sock.close()
        else:
            sock.close()
    except Exception:
        try:
            sock.close()
        except Exception:
            pass


def run_server():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(512)
    print(f"escuchando :{PORT}")
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd")

    c_create = sub.add_parser("create")
    c_create.add_argument("--days", type=int, default=30)

    c_extend = sub.add_parser("extend")
    c_extend.add_argument("uuid")
    c_extend.add_argument("--days", type=int, default=30)

    sub.add_parser("list")
    sub.add_parser("serve")

    args = parser.parse_args()

    if args.cmd == "create":
        create_client(args.days)
    elif args.cmd == "extend":
        extend_client(args.uuid, args.days)
    elif args.cmd == "list":
        list_clients()
    else:
        run_server()
PYEOF
chmod +x "$BT_PY"
}

write_btctl() {
cat > "$BTCTL" << 'CTLEOF'
#!/bin/bash
set -euo pipefail

PY=/opt/btserver/btserver.py

show_menu() {
  echo "==== BT Server Menu ===="
  echo "1) Crear UUID"
  echo "2) Renovar UUID"
  echo "3) Listar UUIDs"
  echo "4) Salir"
  read -rp "Opción: " opt

  case "$opt" in
    1)
      read -rp "Días (default 30): " days
      days=${days:-30}
      python3 "$PY" create --days "$days"
      ;;
    2)
      read -rp "UUID: " uuid
      read -rp "Días nuevos (default 30): " days
      days=${days:-30}
      python3 "$PY" extend "$uuid" --days "$days"
      ;;
    3)
      python3 "$PY" list
      ;;
    *)
      exit 0
      ;;
  esac
}

if [[ $# -gt 0 ]]; then
  exec python3 "$PY" "$@"
else
  show_menu
fi
CTLEOF
chmod +x "$BTCTL"
}

write_service() {
cat > "$SERVICE_FILE" << 'SVCEOF'
[Unit]
Description=BlackTunnel Server
After=network.target xray.service

[Service]
ExecStart=/usr/bin/python3 /opt/btserver/btserver.py serve
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
SVCEOF
}

is_installed() {
  [[ -x "$BTCTL" && -f "$BT_PY" && -f "$SERVICE_FILE" ]]
}

install_or_update() {
  mkdir -p "$BT_DIR"

  # Solo en instalación/actualización explícita
  bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install

  default_xray_config
  write_btserver_py
  write_btctl
  write_service

  if [[ ! -f "$BT_DIR/clients.json" ]]; then
    echo '{"clients":{}}' > "$BT_DIR/clients.json"
  fi

  systemctl daemon-reload
  systemctl enable xray btserver
  systemctl restart xray btserver

  echo "✅ Instalación/actualización completada"
  echo "Comando de gestión: btctl"
}

create_uuid() {
  read -rp "Días (default 30): " days
  days=${days:-30}
  "$BTCTL" create --days "$days"
}

renew_uuid() {
  read -rp "UUID: " uuid
  read -rp "Días nuevos (default 30): " days
  days=${days:-30}
  "$BTCTL" extend "$uuid" --days "$days"
}

list_uuids() {
  "$BTCTL" list
}

restart_services() {
  systemctl restart xray btserver
  systemctl status btserver --no-pager | sed -n '1,15p'
}

menu() {
  while true; do
    echo
    echo "==== BT Server Control ===="
    echo "1) Instalar / actualizar"
    echo "2) Crear UUID"
    echo "3) Renovar UUID"
    echo "4) Listar UUIDs"
    echo "5) Reiniciar servicios"
    echo "6) Salir"
    read -rp "Opción: " opt

    case "$opt" in
      1)
        install_or_update
        ;;
      2)
        if ! is_installed; then echo "⚠️ Primero ejecuta opción 1"; else create_uuid; fi
        ;;
      3)
        if ! is_installed; then echo "⚠️ Primero ejecuta opción 1"; else renew_uuid; fi
        ;;
      4)
        if ! is_installed; then echo "⚠️ Primero ejecuta opción 1"; else list_uuids; fi
        ;;
      5)
        if ! is_installed; then echo "⚠️ Primero ejecuta opción 1"; else restart_services; fi
        ;;
      6)
        exit 0
        ;;
      *)
        echo "Opción inválida"
        ;;
    esac
  done
}

# Modo CLI directo: ./btserver_install.sh install|create|extend|list|menu
case "${1:-menu}" in
  install)
    install_or_update
    ;;
  create)
    if ! is_installed; then echo "⚠️ Primero instala con: $0 install"; exit 1; fi
    shift
    "$BTCTL" create "$@"
    ;;
  extend)
    if ! is_installed; then echo "⚠️ Primero instala con: $0 install"; exit 1; fi
    shift
    "$BTCTL" extend "$@"
    ;;
  list)
    if ! is_installed; then echo "⚠️ Primero instala con: $0 install"; exit 1; fi
    "$BTCTL" list
    ;;
  menu)
    menu
    ;;
  *)
    echo "Uso: $0 [menu|install|create|extend|list]"
    exit 1
    ;;
esac
