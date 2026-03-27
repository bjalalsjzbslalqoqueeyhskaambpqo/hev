# BlackTunnel Panel APK (simple admin)

App Android súper simple para administrar tu `panel.py`:

- Guarda `BASE_URL` y `TOKEN` localmente (cifrado con `EncryptedSharedPreferences`).
- No imprime ni expone el token en respuestas.
- Menú admin básico con feedback visual:
  - Comprobar conexión.
  - Listar clientes.
  - Crear/Reemplazar cliente.
  - Agregar días.
  - Eliminar cliente.

## Uso rápido

1. Instala tu servidor y copia estos datos del script:
   - `BASE_URL = http://IP:8090`
   - `TOKEN = <token_largo>`
2. Abre la app.
3. Pega URL + token.
4. Pulsa **Guardar configuración** y luego **Comprobar conexión**.

## Endpoints usados

La app consume exactamente estos endpoints del panel:

- `GET /clients`
- `POST /client/create`
- `POST /client/update`
- `POST /client/delete`

Todos llevan header:

- `X-Token: <token>`

## Build APK en GitHub Actions

Se agregó workflow `.github/workflows/build-panel-apk.yml`:

- Trigger: push a `main` o manual `workflow_dispatch`.
- Comando build: `gradle :app:assembleDebug`
- Artifact: `blacktunnel-panel-debug-apk`

