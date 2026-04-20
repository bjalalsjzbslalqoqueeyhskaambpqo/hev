# ADM VPS (final)

Aplicación Android de gestión preconfigurada para usuarios finales.

## Sin configuración manual del usuario

- La app toma `BASE_URL` y `TOKEN` desde `PANEL_CONFIG.txt` en build.
- También puedes personalizar `APPLICATION_ID` y `APP_NAME` desde `APP_IDENTITY.txt` para instalar varias variantes (por ejemplo, una por máquina/servidor).
- El usuario final abre y usa el gestor directamente (crear/listar/gestionar).

## Interfaz

- Pestaña **Crear**: crear cliente, sumar días, eliminar.
- Pestaña **Listar**: buscar, filtrar, ordenar y menú de acciones por cliente.

## Red y permisos

- HTTP claro habilitado para panel sin TLS (`usesCleartextTraffic` + `network_security_config`).
- Permiso de Internet activo.

## CI

- Build release arm64 firmado en CI.
