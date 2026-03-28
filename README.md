# BlackTunnel Panel APK (producción, arm64)

App Android para gestionar tu `panel.py` con interfaz visual rápida para administración.

## Dónde poner la URL y el token (IMPORTANTE)

Dentro de la app, en la **primera tarjeta superior**:

1. Campo **Base URL** → aquí pegas la URL del panel.
2. Campo **Token** → aquí pegas el token del panel.
3. Pulsa **Guardar**.
4. Pulsa **Comprobar** para validar conexión.

Ejemplo:

- Base URL: `http://TU_IP:8090`
- Token: `<TU_TOKEN_GENERADO>`

> Recomendación de seguridad: no hardcodear el token en el código fuente ni en Git.

## Qué incluye

- Configuración cifrada localmente (`BASE_URL` + `TOKEN`) con `EncryptedSharedPreferences`.
- Token oculto por defecto (toggle temporal para mostrar/ocultar).
- Feedback visual:
  - Barra de carga.
  - Snackbar de notificaciones.
  - Botones con micro-animación.
- Acciones directas:
  - Comprobar conexión.
  - Listar clientes.
  - Crear/reemplazar cliente.
  - Agregar días.
  - Eliminar cliente.

## Archivo para disparar build automáticamente

Archivo raíz:

- `SELLER_CODE.txt`

Cuando cambias ese archivo y haces push a `main`, se dispara compilación automática.
Ese valor se inyecta como `BuildConfig.SELLER_CODE` y se muestra en pantalla.

## Producción optimizada (64 bits)

Se configura build para **solo arm64-v8a**, con reducción de tamaño en release:

- `minifyEnabled = true`
- `shrinkResources = true`
- `splits abi` solo `arm64-v8a`

## CI automático

Workflow: `.github/workflows/build-panel-apk.yml`

Trigger por cambios en:

- `SELLER_CODE.txt`
- `app/**`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `.github/workflows/build-panel-apk.yml`

Build de CI:

- `gradle :app:assembleRelease`
- Empaquetado zip con compresión máxima (`zip -9`)
- Artifact: `blacktunnel-panel-arm64-release`

