# BlackTunnel Panel APK (admin visual)

App Android para gestionar tu `panel.py` con mejor interfaz visual y flujo rápido de venta.

## Qué incluye

- Configuración cifrada localmente (`BASE_URL` + `TOKEN`) con `EncryptedSharedPreferences`.
- Token oculto por defecto (y toggle temporal para mostrar/ocultar).
- Feedback visual de estado:
  - Barra de carga.
  - Snackbar de notificaciones.
  - Botones con micro-animación.
- Acciones directas:
  - Comprobar conexión.
  - Listar clientes.
  - Crear/reemplazar cliente.
  - Agregar días.
  - Eliminar cliente.

## Archivo para “código de build”

Se añadió el archivo raíz:

- `SELLER_CODE.txt`

Tú editas ese archivo (por ejemplo con un código de versión comercial), haces push, y el workflow vuelve a compilar APK automáticamente.

Ese valor se inyecta al app en build time como `BuildConfig.SELLER_CODE` y se muestra en pantalla como referencia del build.

## Build automático al detectar cambios

Workflow: `.github/workflows/build-panel-apk.yml`

Se dispara en `push` a `main` cuando cambia cualquiera de estos paths:

- `SELLER_CODE.txt`
- `app/**`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `.github/workflows/build-panel-apk.yml`

Además puedes lanzarlo manualmente con `workflow_dispatch`.

El artifact generado es:

- `blacktunnel-panel-debug-apk`

