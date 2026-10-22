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

## 0.4.1 — recuperación de interfaz
Parte de la base 0.3.2 con una pantalla de error de emergencia para fallos JavaScript.

## 0.5.0 — fluidez y estabilidad
**Interfaz (assets/ui/index.html)**
- Render por claves: ya no se reconstruye todo el chat cada 1,2 s (adiós parpadeo, saltos de scroll y bloques de código que se reiniciaban).
- Respuesta en vivo: se aplican los eventos `message.part.updated` / `message.part.delta` del servidor; la recarga completa queda como red de seguridad.
- Corregido: la respuesta se daba por terminada al instante porque se miraba el último mensaje del turno anterior.
- Corregido: el botón de eliminar sesión no hacía nada (WebView bloquea `confirm()`); ahora es un diálogo propio.
- Corregido: CSS duplicado que anulaba el rediseño 0.3.0.
- Corregido: Markdown rompía el código (`**` dentro de bloques). Nuevo Markdown con títulos, listas, enlaces, citas y botón Copiar.
- Permisos: se cargan los pendientes al abrir la sesión, solo se muestran los de la sesión actual y se añade "Siempre".
- Tarjetas de herramientas desplegables (entrada/salida) y bloque de razonamiento.
- Estado "generando" real (`/session/status`), reconexión de eventos, recarga al volver a la app, botón "ir al final".
- Enter en teclado móvil inserta salto de línea (se envía con el botón).
- Catálogo gratuito: caché de 24 h si no hay red (sigue fallando de forma segura pasado ese tiempo).

**Android**
- WebChromeClient: selector de archivos (adjuntos del panel Ajustes) y consola en logcat; depuración remota con chrome://inspect.
- Manejo de errores de carga y caída del renderer del WebView (antes: pantalla vacía o cierre de la app).
- Barras del sistema oscuras en la interfaz (antes: franja clara arriba con tema claro).
- `launchMode=singleTask` (tocar la notificación ya no abre una segunda instancia).
- Servidor vigilado: se relanza solo si se cae; al volver a la app se reinicia si Android lo mató.
- Espera de arranque por HTTP (no solo puerto abierto), hasta 3 min y con fallo rápido si el proceso muere.
- Descargas con timeouts, progreso limitado y verificación de integridad (sha256 de Alpine, sha512 de npm).

## 0.6.0 — servidor protegido y preguntas del agente
- **Contraseña del servidor local:** OpenCode arranca con `OPENCODE_SERVER_PASSWORD` (aleatoria, guardada en `files/server.key`, privada de la app). Antes cualquier otra app del teléfono podía conectarse a `127.0.0.1:4096` y ejecutar comandos. El WebView responde solo al desafío de `127.0.0.1` (`onReceivedHttpAuthRequest`).
  - Si en tu teléfono toda la app da error 401, pon `SECURE_SERVER = false` en `OcRuntime.kt` y vuelve a compilar (y avísame).
- **Preguntas del agente:** soporte de la herramienta `question` (`/question`, eventos `question.asked/replied/rejected`): opciones de una o varias respuestas, respuesta libre y "Omitir". Antes el agente quedaba esperando sin que se viera nada.
- Corregido: las tarjetas de permiso no se cerraban tras responder (bug de 0.5.0).

Código fuente sin compilar ni probar en dispositivo: compílalo con GitHub Actions como siempre.
