# ADM VPS (final)

Aplicación Android de gestión (preconfigurable) con interfaz por secciones:

- Pestaña **Crear**: alta/edición rápida.
- Pestaña **Listar**: buscador, filtros, orden, menú de acciones por cliente.

## Permisos y red

- Se habilitó tráfico HTTP claro para paneles sin TLS (`network_security_config` + `usesCleartextTraffic`).
- Permisos de almacenamiento para exportar/importar JSON en Android compatible.

## Configuración preinyectada

Edita `PANEL_CONFIG.txt`:

```txt
<BASE_URL> <TOKEN>
```

La app arranca con esos datos inyectados en build.

## Funciones

- Buscar por ID/nombre.
- Filtros: todos / vencen en 3 días / vencen en 7 días.
- Orden por días.
- Menú por cliente: editar, aumentar días, eliminar.
- Importar / exportar JSON de clientes.

## CI

- Firma release en CI con keystore generado automáticamente.
- Build release arm64 y artifact ZIP.
