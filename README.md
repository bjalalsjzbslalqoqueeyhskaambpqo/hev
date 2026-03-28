# ADM VPS (producción, arm64, preconfigurada)

App Android de gestión para panel VPS, pensada para entregar al cliente final ya funcionando.

## Configuración preinyectada

Edita `PANEL_CONFIG.txt`:

```txt
<BASE_URL> <TOKEN>
```

Ejemplo:

```txt
http://TU_IP:8090 TU_TOKEN_GENERADO
```

Al compilar, se inyecta en `BuildConfig` y el usuario final abre la app ya configurada.

## Funciones del panel

- Búsqueda por nombre/ID.
- Filtros rápidos: todos / expira en 3 días / expira en 7 días.
- Orden por días (asc/desc).
- Sección “Por expirar” para seguimiento inmediato.
- CRUD básico de clientes (crear, sumar días, eliminar).
- Bloqueo de botones mientras hay requests para evitar doble toque/errores.
- Exportación local en JSON al almacenamiento/descargas.

## Firma y CI

- Release arm64 (`arm64-v8a`) con reducción de tamaño.
- CI genera keystore aleatorio automáticamente y firma release.
- Workflow empaqueta y sube artifact ZIP.

⚠️ Si el keystore es aleatorio por build, no podrás actualizar sobre APKs anteriores (firma distinta).

