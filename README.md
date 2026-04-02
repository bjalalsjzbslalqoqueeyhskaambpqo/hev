# ADM VPS (final)

Aplicación Android de gestión preconfigurada para usuarios finales.

## Sin configuración manual del usuario

- La app toma `BASE_URL` y `TOKEN` desde `PANEL_CONFIG.txt` en build.
- El usuario final abre y usa el gestor directamente (crear/listar/gestionar).

## Interfaz

- Pestaña **Crear**: bloque de gestión **VLESS** (crear/sumar días/eliminar) y bloque de gestión **SSH** (crear/actualizar/eliminar/listar).
- Pestaña **Listar**: buscar, filtrar, ordenar y menú de acciones por cliente.

## Red y permisos

- HTTP claro habilitado para panel sin TLS (`usesCleartextTraffic` + `network_security_config`).
- Permiso de Internet activo.

## CI

- Build release arm64 firmado en CI.

## Script servidor

- Se agregó `scripts/install_btserver.sh` con actualización de `panel.py` para gestión SSH real a nivel sistema (crear/actualizar/eliminar/listar), compatibilidad con `days`/`set_days` en `/ssh/update` y corrección de shell/desbloqueo para autenticación interna por dropbear. 
