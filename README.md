# OpenCode Mobile

APK que corre OpenCode dentro del teléfono (Alpine + PRoot) y lo muestra en un WebView.
Con los modelos gratis de OpenCode Zen no necesitas PC ni servidor.

## Compilar desde el celular
1. Crea un repositorio en GitHub.
2. Crea el archivo `.github/workflows/build.yml` (pega el contenido del que viene en el zip).
3. Sube `opencode-mobile.zip` a la raíz del repo (Add file > Upload files).
4. En la pestaña Actions espera que termine "Build APK".
5. Descarga `app-debug.apk` desde Releases (o desde el artifact) e instálalo.

## Primer arranque
Descarga Alpine y OpenCode (necesita internet, unos minutos). Después arranca en segundos.

## Notas
- targetSdk 28 a propósito, para poder ejecutar PRoot como Termux. Solo instalación manual.
- No afiliado a OpenCode ni a Anthropic.
