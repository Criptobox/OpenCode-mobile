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
