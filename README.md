# BlackTunnel Panel APK (producción, arm64, preconfigurada)

Esta versión está pensada para **entregar al cliente ya lista**, sin que el usuario final tenga que escribir URL/token.

## Archivo que debes editar antes de compilar

Edita este archivo en la raíz del repo:

- `PANEL_CONFIG.txt`

Formato (una sola línea):

```txt
<BASE_URL> <TOKEN>
```

Ejemplo:

```txt
http://TU_IP:8090 TU_TOKEN_GENERADO
```

> Al compilar, esos valores se inyectan automáticamente en la app (BuildConfig), y la app arranca ya configurada.

## Qué pasa en la app

- Si `PANEL_CONFIG.txt` tiene URL+token válidos:
  - La app queda preconfigurada.
  - El usuario final **no necesita configurar nada**.
  - Los controles de guardado/mostrar token se ocultan.
- Si `PANEL_CONFIG.txt` está vacío o inválido:
  - La app queda en modo editable manual.

## Archivo de código comercial (opcional)

- `SELLER_CODE.txt`

Se inyecta como `BuildConfig.SELLER_CODE` para identificar builds por vendedor/lote.

## Producción optimizada (64 bits)

- Solo `arm64-v8a`
- `minifyEnabled = true`
- `shrinkResources = true`
- Split ABI sin universal APK

## CI automático

Workflow: `.github/workflows/build-panel-apk.yml`

Se dispara en `push` a `main` cuando cambian:

- `PANEL_CONFIG.txt`
- `SELLER_CODE.txt`
- `app/**`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `.github/workflows/build-panel-apk.yml`

Pipeline:

1. `gradle :app:assembleRelease`
2. empaqueta en zip (`zip -9`)
3. sube artifact `blacktunnel-panel-arm64-release`

