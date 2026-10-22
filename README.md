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


## 0.6.1 — corrección de conversaciones largas

Se corrigió el contenedor flex del chat: el panel de mensajes puede reducirse dentro de la pantalla, pero cada burbuja, herramienta y bloque de razonamiento conserva su altura natural (`flex-shrink: 0`). Esto evita que, al acumularse muchos mensajes/herramientas, el navegador comprima verticalmente los elementos y recorte el texto.


## OpenCode Mobile 0.7.0 — mejoras experimentales

- Barra del chat con selector de modelo gratuito, modo Plan/Build, `/undo` y `/compact`.
- Ventana de renderizado limitada a los últimos elementos para reducir trabajo del WebView en conversaciones largas.
- Esqueletos visuales mientras carga el historial.
- Resaltado verde/rojo de líneas de salida que parecen un diff dentro de las herramientas.

**Importante:** estos cambios son código fuente experimental. Las rutas `/session/{id}/undo` y `/session/{id}/summarize`, el campo `agent` y el selector de modelo deben verificarse contra la versión de OpenCode que se instala en el dispositivo. El renderizado limitado mejora el rendimiento, pero en esta versión no implementa paginación hacia mensajes antiguos. El resaltado de diff depende de que la herramienta entregue texto en formato diff; todavía no existe una reversión por archivo. La actualización del binario, la acción Detener en notificaciones y el montaje seguro de `/sdcard` no se han implementado en esta entrega y requieren cambios adicionales en el servicio Android/PRoot y pruebas en dispositivo.


## OpenCode Mobile 0.8.0 — mantenimiento y acceso al almacenamiento
- La notificación persistente del servidor incorpora la acción «Detener servidor». Esto detiene el proceso de OpenCode sin borrar el entorno instalado. Al volver a abrir la app, el servicio puede iniciarse de nuevo.
- PRoot intenta montar el almacenamiento compartido de Android como `/sdcard` si la ruta del sistema es accesible. El acceso efectivo depende de los permisos y restricciones de almacenamiento del dispositivo; esta entrega no garantiza acceso universal a todas las carpetas.
- Esta versión no implementa todavía el actualizador integrado del binario, la notificación de fin de tarea con cancelación de esa tarea, ni la reversión individual de diffs. Se dejan como siguientes pasos para no presentarlos como funciones terminadas.
- Validación de esta entrega: revisión estática y empaquetado del código fuente. No se ha compilado ni probado en un dispositivo Android.

## 0.8.1 — mantenimiento, notificaciones y diffs

- Añade en Ajustes un actualizador del binario OpenCode basado en el registro oficial de npm para `opencode-linux-arm64-musl`. Descarga el paquete, verifica `dist.integrity`/`shasum` cuando está publicado, prueba el binario antes de sustituir el anterior y conserva proyectos/sesiones en el rootfs.
- Añade aviso Android cuando la sesión activa pasa de generación a inactiva. El botón de la notificación permite detener el servidor.
- Añade un visor básico de diffs por sesión con líneas añadidas/eliminadas coloreadas. Para revertir cambios se conserva `/undo` (revierte el último mensaje de la sesión); la reversión granular por archivo no se incluye aún.
- Se mantiene el montaje de almacenamiento compartido `/storage/emulated/0` como `/sdcard` dentro de PRoot cuando Android permite leerlo.

Validación de esta entrega: comprobación de sintaxis JavaScript y estructura ZIP. No se dispone de Gradle instalado ni se ha compilado/probado el APK en un dispositivo; el actualizador y las notificaciones requieren validación real en Android antes de considerarse verificados.


## 0.8.4 — estabilidad del chat
- Conserva la posición de lectura mientras se actualizan mensajes en streaming.
- Ajusta la altura útil de WebView al abrir/cerrar el teclado.
- Mantiene el cuadro de escritura dentro de la zona visible y limita su crecimiento.
- La cancelación muestra un error claro si el servidor no confirma el aborto.
- Entrega de código fuente; no se declara compilada ni probada en un teléfono.


## 0.8.5 — Diagnóstico de conexión
- Añadido un diagnóstico de solo lectura en Ajustes para comprobar el motor OpenCode, sesiones, estado y catálogo oficial de modelos.
- Muestra el resultado individual, tiempo de respuesta, estado de red y conexión de eventos en vivo; permite repetir la prueba.
- Versión del proyecto actualizada a 0.8.5.


## 0.8.6 — Dictado por voz
- Añade botón de micrófono en el compositor del chat.
- Usa el reconocedor de voz nativo de Android en español y solicita el permiso de micrófono al primer uso.
- Inserta el texto reconocido en el cuadro de mensaje; no envía automáticamente.
- Incluye estado de escucha, cancelación y mensajes de error para falta de permisos o servicio de voz.
- El dictado depende de que el dispositivo tenga un servicio de reconocimiento de voz instalado y, en algunos dispositivos, conexión a internet.


## 0.8.7 — actualizaciones de la APK desde la aplicación
- Nuevo apartado en Ajustes para configurar el repositorio GitHub que publica la APK (`usuario/repositorio`).
- Comprueba el último release al abrir la app y después como máximo cada seis horas cuando se vuelve a abrir.
- El actualizador consulta automáticamente `Criptobox/OpenCode-mobile` al iniciar y cada 6 horas mientras se usa la app. Si hay un APK en un release `build-N` más reciente que el `versionCode` instalado, muestra una notificación. En Ajustes basta pulsar **Actualizar ahora**: descarga la APK con barra de progreso y abre el instalador de Android. Android todavía requiere confirmar la instalación; la APK nueva debe estar firmada con la misma clave que la instalada.
- Permite descargar la APK y abrir el instalador de Android desde la propia aplicación; Android puede pedir autorización para instalar aplicaciones de esta fuente. La instalación requiere confirmación del usuario.
- La app conserva el enlace del release y no intenta instalar silenciosamente. No se puede actualizar una APK instalada con una firma distinta.
- Requiere que el release de GitHub sea accesible públicamente sin iniciar sesión. Si el repositorio es privado, la comprobación pública no funcionará sin una solución autenticada segura.
- Validación de esta entrega: revisión estática y comprobación de sintaxis JavaScript/ZIP. No se ha compilado ni probado en un teléfono Android.


### 0.8.8 — actualización con progreso
- Pantalla simplificada: botón «Actualizar ahora» busca una versión superior y, si existe, la descarga automáticamente.
- Barra de progreso y texto de estado durante la descarga.
- Descarga a archivo temporal y renombrado solo al terminar; rechaza archivos pequeños/incompletos.
- Android sigue mostrando su confirmación del instalador; no se puede instalar silenciosamente con una APK normal.
- Requiere que el repositorio oficial de releases se configure una vez al preparar la app; el usuario final no debe tener que escribirlo cuando ese dato esté fijado por el desarrollador.
