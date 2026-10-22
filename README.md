# OpenCode Mobile

APK que corre OpenCode dentro del teléfono (Alpine + PRoot) y lo muestra en un WebView.
Con los modelos gratis de OpenCode Zen no necesitas PC ni servidor.

## Compilar desde el celular
1. Crea un repositorio PRIVADO en GitHub (el zip incluye la llave de firma).
2. Crea `.github/workflows/build.yml` (pega el contenido del que viene en el zip).
3. Sube `opencode-mobile.zip` a la raíz del repo con el navegador (modo escritorio).
4. En Actions espera que termine "Build APK" y baja el APK desde Releases.

## Actualizar sin perder nada
- La llave de firma es fija (app/debug.keystore): cada APK nuevo se instala ENCIMA del anterior
  y conserva el entorno ya descargado. No desinstales la app.
- La instalación se hace por etapas con marcas: si se corta, continúa donde quedó.
- Las descargas se reanudan solas si se cae la conexión.

## Notas
- targetSdk 28 a propósito, para ejecutar PRoot como Termux. Solo instalación manual.
- No afiliado a OpenCode ni a Anthropic.


## Cambios de la versión 0.2.0
- El apartado Modelos consulta el catálogo oficial de OpenCode Zen (`https://opencode.ai/zen/v1/models`).
- Solo muestra modelos del proveedor OpenCode Zen presentes en el catálogo y con coste de entrada, salida, lectura de caché y escritura de caché explícitamente igual a `0`.
- Si el catálogo oficial no responde, faltan precios o el modelo no coincide, la app falla de forma segura y no lo etiqueta como gratuito.
- Añadido botón para actualizar manualmente el catálogo y fecha de la última comprobación.
- Los precios y la disponibilidad pueden cambiar; que un modelo sea gratuito no garantiza que el servicio esté siempre operativo ni que no tenga límites de uso.

**Nota de compilación:** esta entrega contiene el código fuente actualizado. La compilación Android debe ejecutarse en GitHub Actions siguiendo los pasos anteriores; no se incluye un APK compilado ni se afirma que haya sido instalado en un dispositivo.


## OpenCode Mobile 0.3.0 — rediseño visual

Esta versión adapta la interfaz móvil a un tema oscuro con tarjetas elevadas, acentos violeta/azul, tarjetas de acceso rápido, navegación inferior y una pantalla de proyectos basada en las sesiones reales del motor OpenCode. La pantalla de modelos conserva la verificación de catálogo y coste de la versión 0.2.0.

La carpeta incluye el código fuente; no incluye una APK compilada.


## OpenCode Mobile 0.4.0 — chat y modelos
- Evita llamadas simultáneas a la consulta de mensajes para reducir tráfico y condiciones de carrera durante el chat.
- Actualiza el estado del chat durante la generación sin forzar un redibujado en cada tick.
- Mide localmente el tiempo hasta el primer texto recibido por modelo y muestra última/ media en el selector. Esto mide el rendimiento observado desde esta APK, no la velocidad interna del proveedor.
- Añade búsqueda de modelos gratuitos verificados y favoritos persistidos localmente.
- Conserva el filtro estricto: solo modelos presentes en el catálogo oficial y con todos los costes requeridos explícitamente en cero.

**Limitación:** el tiempo hasta el primer texto solo se mide cuando OpenCode expone texto en el endpoint de mensajes; no garantiza streaming token a token. Los repositorios y archivos dependen de las rutas que soporte el servidor OpenCode de esta instalación y no se han añadido botones que finjan esa funcionalidad. Esta entrega es código fuente, no una APK compilada ni probada en un teléfono.
