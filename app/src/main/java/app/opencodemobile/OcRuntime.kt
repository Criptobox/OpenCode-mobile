package app.opencodemobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

/** Entorno Linux (Alpine + PRoot) donde corre OpenCode. Todo se guarda en filesDir y sobrevive a las actualizaciones. */
class OcRuntime(private val ctx: Context) {
    private val root = File(ctx.filesDir, "rootfs")
    private val libDir = File(ctx.filesDir, "lib")
    private val tmpDir = File(ctx.filesDir, "tmp")
    private val dl = File(ctx.filesDir, "downloads")
    private val stageRootfs = File(ctx.filesDir, "stage_rootfs")
    private val stagePkgs = File(ctx.filesDir, "stage_pkgs")
    private val stageBin = File(ctx.filesDir, "stage_opencode")
    private val nativeDir = ctx.applicationInfo.nativeLibraryDir
    val logFile = File(ctx.filesDir, "server.log")
    val port = 4096

    companion object {
        /** Protege el servidor local con contraseña (HTTP Basic). Si ves 401 en toda la app, pon false y recompila. */
        const val SECURE_SERVER = true
        const val SERVER_USER = "opencode"
    }

    /** Contraseña aleatoria, generada una vez y guardada en el almacenamiento privado de la app. */
    @Synchronized
    fun serverPassword(): String {
        val f = File(ctx.filesDir, "server.key")
        try {
            if (f.exists()) f.readText().trim().takeIf { it.length >= 32 }?.let { return it }
        } catch (_: Exception) { }
        val b = ByteArray(24)
        java.security.SecureRandom().nextBytes(b)
        val p = hex(b)
        f.writeText(p)
        return p
    }

    fun isInstalled() = stageRootfs.exists() && stagePkgs.exists() && stageBin.exists()

    private fun env() = mapOf(
        "PROOT_LOADER" to "$nativeDir/libproot-loader.so",
        "PROOT_TMP_DIR" to tmpDir.path,
        "PROOT_NO_SECCOMP" to "1",
        "LD_LIBRARY_PATH" to "${libDir.path}:$nativeDir"
    )

    private fun prepareLibs() {
        libDir.mkdirs(); tmpDir.mkdirs(); dl.mkdirs()
        ctx.assets.open("proot_libs.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { l ->
                val parts = l.split(" ")
                val link = File(libDir, parts[0])
                Files.deleteIfExists(link.toPath())
                Files.createSymbolicLink(link.toPath(), File(nativeDir, parts[1]).toPath())
            }
        }
    }

    private fun guestCmd(script: String, extraEnv: List<String> = emptyList()): List<String> {
        val args = mutableListOf(
            "$nativeDir/libproot.so", "-0", "--link2symlink", "--kill-on-exit",
            "-r", root.path, "-b", "/dev", "-b", "/proc", "-b", "/sys"
        )
        // Android's shared storage is exposed only when the OS grants access. The app's private
        // runtime remains usable if the shared-storage path is absent.
        val shared = File("/storage/emulated/0")
        if (shared.exists() && shared.canRead()) { args += listOf("-b", "${shared.path}:/sdcard") }
        args += listOf("-w", "/root", "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        return args + extraEnv + listOf("/bin/sh", "-c", script)
    }

    private fun run(script: String, onLine: (String) -> Unit): Int {
        val pb = ProcessBuilder(guestCmd(script)).redirectErrorStream(true)
        pb.environment().putAll(env())
        val p = pb.start()
        p.inputStream.bufferedReader().forEachLine(onLine)
        return p.waitFor()
    }

    private fun <T> retry(times: Int = 8, f: () -> T): T {
        var last: Exception? = null
        repeat(times) {
            try { return f() } catch (e: Exception) { last = e; Thread.sleep(2000) }
        }
        throw last!!
    }

    /** GET de texto con timeouts (antes podía quedarse colgado indefinidamente sin red). */
    private fun httpText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "OpenCode-Mobile")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} en $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun digest(f: File, algo: String): ByteArray {
        val md = MessageDigest.getInstance(algo)
        f.inputStream().use { s ->
            val b = ByteArray(64 * 1024)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest()
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun sha256Check(expected: String): (File) -> Boolean = { f -> hex(digest(f, "SHA-256")) == expected }
    private fun sha1Check(expected: String): (File) -> Boolean = { f -> hex(digest(f, "SHA-1")) == expected }
    private fun sha512Check(expectedB64: String): (File) -> Boolean =
        { f -> java.util.Base64.getEncoder().encodeToString(digest(f, "SHA-512")) == expectedB64 }

    private fun finish(part: File, dest: File, verify: ((File) -> Boolean)?) {
        if (verify != null && !verify(part)) {
            part.delete()
            throw IOException("La verificación de integridad falló; se descargará de nuevo")
        }
        if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
    }

    /** Descarga con reintentos, reanudación y verificación de integridad opcional. */
    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit, verify: ((File) -> Boolean)? = null) {
        if (dest.exists()) return
        val part = File(dest.path + ".part")
        var attempt = 0
        var lastTick = 0L
        while (true) {
            try {
                val have = if (part.exists()) part.length() else 0L
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 20000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code == 416) { finish(part, dest, verify); return }
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                val resumed = code == 206
                val start = if (resumed) have else 0L
                val len = c.contentLengthLong
                val total = if (len > 0) start + len else 0L
                var done = start
                FileOutputStream(part, resumed).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 200) { lastTick = now; onProgress(done, total) }
                        }
                    }
                }
                onProgress(done, total)
                if (len > 0 && done != total) throw IOException("Descarga incompleta")
                finish(part, dest, verify)
                return
            } catch (e: IOException) {
                attempt++
                if (attempt > 15) throw e
                Thread.sleep(2000L * minOf(attempt, 5))
            }
        }
    }

    private fun mb(b: Long) = "%.1f".format(b / 1048576.0)
    private fun scale(from: Int, span: Int, d: Long, t: Long) = from + if (t > 0) (span * d / t).toInt() else 0

    private fun untar(tgz: File, into: File) {
        into.mkdirs()
        val tar = ProcessBuilder("/system/bin/tar", "-xzf", tgz.path, "-C", into.path).redirectErrorStream(true).start()
        val out = tar.inputStream.bufferedReader().readText()
        if (tar.waitFor() != 0) error("tar falló: $out")
    }

    /** onProgress recibe permilles (0..1000) y un texto. Cada etapa deja una marca: si algo falla, se reanuda desde ahí. */
    fun install(onProgress: (Int, String) -> Unit, log: (String) -> Unit) {
        prepareLibs()

        if (!stageRootfs.exists()) {
            onProgress(0, "Descargando Alpine Linux…")
            val base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"
            val index = retry { httpText(base + "latest-releases.yaml") }
            val name = Regex("alpine-minirootfs-[0-9.]+-aarch64\\.tar\\.gz").find(index)?.value
                ?: error("No encontré el paquete de Alpine")
            // sha256 publicado por Alpine para ese archivo (se busca solo dentro de su bloque YAML)
            val at = index.indexOf("file: $name")
            val blockEnd = index.indexOf("\n-", at).let { if (it < 0) index.length else it }
            val sha = if (at >= 0) Regex("sha256: ([0-9a-f]{64})").find(index.substring(at, blockEnd))?.groupValues?.get(1) else null
            val tgz = File(dl, name)
            download(base + name, tgz, { d, t -> onProgress(scale(0, 40, d, t), "Descargando Alpine · ${mb(d)} / ${mb(t)} MB") },
                sha?.let { sha256Check(it) })
            onProgress(40, "Extrayendo Alpine…")
            root.deleteRecursively()
            untar(tgz, root)
            tgz.delete()
            stageRootfs.writeText("ok")
        }
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")

        if (!stagePkgs.exists()) {
            onProgress(50, "Instalando paquetes base…")
            val script = "sed -i 's#https://#http://#' /etc/apk/repositories && " +
                "mkdir -p /var/cache/apk && ln -sfn /var/cache/apk /etc/apk/cache && " +
                "apk update && apk add bash git curl ripgrep ca-certificates libgcc libstdc++ && " +
                "rm -rf /var/cache/apk/* && mkdir -p /root/projects"
            val step = Regex("""\((\d+)/(\d+)\)""")
            var code = -1
            for (i in 1..5) {
                code = run(script) { line ->
                    log(line)
                    step.find(line)?.let { m ->
                        val n = m.groupValues[1].toInt(); val t = m.groupValues[2].toInt()
                        if (t > 0) onProgress(50 + 200 * n / t, "Instalando paquetes ($n/$t)")
                    }
                }
                if (code == 0) break
                log("Reintentando ($i/5)…")
                Thread.sleep(3000)
            }
            if (code != 0) error("Falló la instalación de paquetes (código $code)")
            stagePkgs.writeText("ok")
        }

        if (!stageBin.exists()) {
            onProgress(250, "Buscando la última versión de OpenCode…")
            val meta = JSONObject(retry { httpText("https://registry.npmjs.org/opencode-linux-arm64-musl/latest") })
            val version = meta.getString("version")
            val dist = meta.getJSONObject("dist")
            val tarball = dist.getString("tarball")
            val integrity = dist.optString("integrity")
            val shasum = dist.optString("shasum")
            val verify: ((File) -> Boolean)? = when {
                integrity.startsWith("sha512-") -> sha512Check(integrity.removePrefix("sha512-"))
                shasum.length == 40 -> sha1Check(shasum)
                else -> null
            }
            val tgz = File(dl, "opencode-$version.tgz")
            download(tarball, tgz, { d, t -> onProgress(scale(250, 700, d, t), "Descargando OpenCode $version · ${mb(d)} / ${mb(t)} MB") }, verify)
            onProgress(950, "Instalando OpenCode…")
            val tmp = File(tmpDir, "oc")
            tmp.deleteRecursively()
            untar(tgz, tmp)
            val bin = File(tmp, "package/bin/opencode").takeIf { it.exists() }
                ?: tmp.walkTopDown().firstOrNull { it.isFile && it.name == "opencode" && it.length() > 1_000_000 }
                ?: error("No encontré el binario de OpenCode")
            val target = File(root, "usr/local/bin/opencode")
            target.parentFile!!.mkdirs()
            target.delete()
            if (!bin.renameTo(target)) bin.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            tmp.deleteRecursively()
            tgz.delete()
            val code = run("opencode --version", log)
            if (code != 0) error("OpenCode no arrancó en este teléfono (código $code)")
            File(ctx.filesDir, "opencode.version").writeText(version)
            stageBin.writeText("ok")
        }
        onProgress(1000, "Listo")
    }

    /** Actualiza solo el binario de OpenCode; conserva rootfs, proyectos, ajustes y sesiones. */
    fun updateOpenCode(onProgress: (String) -> Unit = {}) {
        prepareLibs()
        onProgress("Consultando versión oficial…")
        val meta = JSONObject(retry { httpText("https://registry.npmjs.org/opencode-linux-arm64-musl/latest") })
        val version = meta.getString("version")
        if (version == installedVersion()) { onProgress("OpenCode ya está actualizado ($version)"); return }
        val dist = meta.getJSONObject("dist")
        val tarball = dist.getString("tarball")
        val integrity = dist.optString("integrity")
        val shasum = dist.optString("shasum")
        val verify: ((File) -> Boolean)? = when {
            integrity.startsWith("sha512-") -> sha512Check(integrity.removePrefix("sha512-"))
            shasum.length == 40 -> sha1Check(shasum)
            else -> null
        }
        val tgz = File(dl, "opencode-update-$version.tgz")
        tgz.delete()
        onProgress("Descargando OpenCode $version…")
        download(tarball, tgz, { d, t -> if (t > 0) onProgress("Descargando $version · ${mb(d)} / ${mb(t)} MB") }, verify)
        val tmp = File(tmpDir, "oc-update-$version")
        tmp.deleteRecursively()
        onProgress("Verificando y preparando actualización…")
        untar(tgz, tmp)
        val candidate = File(tmp, "package/bin/opencode").takeIf { it.exists() }
            ?: tmp.walkTopDown().firstOrNull { it.isFile && it.name == "opencode" && it.length() > 1_000_000 }
            ?: error("No se encontró el binario en el paquete oficial")
        val target = File(root, "usr/local/bin/opencode")
        val staged = File(target.parentFile, "opencode.new")
        target.parentFile?.mkdirs()
        candidate.copyTo(staged, overwrite = true)
        staged.setExecutable(true, false)
        val check = ProcessBuilder(guestCmd("/usr/local/bin/opencode --version")).redirectErrorStream(true).apply { environment().putAll(env()) }.start()
        val output = check.inputStream.bufferedReader().use { it.readText() }.trim()
        if (check.waitFor() != 0 || output.isBlank()) { staged.delete(); error("La versión descargada no supera la prueba de arranque") }
        val backup = File(target.parentFile, "opencode.previous")
        backup.delete(); if (target.exists() && !target.renameTo(backup)) error("No se pudo respaldar el binario actual")
        if (!staged.renameTo(target)) { if (backup.exists()) backup.renameTo(target); error("No se pudo activar la actualización") }
        target.setExecutable(true, false)
        File(ctx.filesDir, "opencode.version").writeText(version)
        stageBin.writeText("ok")
        backup.delete(); tgz.delete(); tmp.deleteRecursively()
        onProgress("Actualizado correctamente a $version")
    }

    fun installedVersion(): String = try { File(ctx.filesDir, "opencode.version").readText().trim() } catch (e: Exception) { "" }

    private fun serverEnv(): List<String> {
        val e = mutableListOf("SHELL=/bin/bash", "OPENCODE_DISABLE_AUTOUPDATE=true")
        if (SECURE_SERVER) { e += "OPENCODE_SERVER_USERNAME=$SERVER_USER"; e += "OPENCODE_SERVER_PASSWORD=${serverPassword()}" }
        return e
    }

    fun startServer(): Process {
        prepareLibs()
        val cmd = "mkdir -p /root/projects && cd /root/projects && exec opencode web --hostname 127.0.0.1 --port $port"
        val pb = ProcessBuilder(guestCmd(cmd, serverEnv()))
            .redirectErrorStream(true).redirectOutput(logFile)
        pb.environment().putAll(env())
        return pb.start()
    }

    /** Listo = el servidor HTTP contesta (no basta con que el puerto esté abierto). */
    fun serverReady(): Boolean = try {
        val c = URL("http://127.0.0.1:$port/session").openConnection() as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 2000
        try { c.responseCode in 100..599 } finally { c.disconnect() }
    } catch (e: Exception) { false }

    fun tailLog(n: Int): String = try {
        if (logFile.exists()) logFile.readLines().takeLast(n).joinToString("\n") else ""
    } catch (e: Exception) { "" }
}
