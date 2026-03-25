# HEV native library placeholder

No se versiona `libhev-socks5-tunnel.so` en este repositorio para evitar commitear binarios.

El workflow de CI (`.github/workflows/build.yml`) descarga automáticamente el archivo en esta ruta antes de compilar:

`app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so`

Para pruebas locales, descarga manualmente el `.so` a esta misma ruta.
