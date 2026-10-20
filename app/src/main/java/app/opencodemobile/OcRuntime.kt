package app.opencodemobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.file.Files

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

    private fun guestCmd(script: String) = listOf(
        "$nativeDir/libproot.so", "-0", "--link2symlink", "--kill-on-exit",
        "-r", root.path, "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root",
        "/usr/bin/env", "-i", "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "/bin/sh", "-c", script
    )

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

    /** Descarga con reintentos y reanudación: si se corta la conexión, continúa donde iba. */
    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        if (dest.exists()) return
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            try {
                val have = if (part.exists()) part.length() else 0L
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 20000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code == 416) { part.renameTo(dest); return }
                val resumed = code == 206
                val start = if (resumed) have else 0L
                val total = start + c.contentLengthLong
                var done = start
                FileOutputStream(part, resumed).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (c.contentLengthLong > 0 && done != total) throw IOException("Descarga incompleta")
                part.renameTo(dest)
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
            val index = retry { URL(base + "latest-releases.yaml").readText() }
            val name = Regex("alpine-minirootfs-[0-9.]+-aarch64\\.tar\\.gz").find(index)?.value
                ?: error("No encontré el paquete de Alpine")
            val tgz = File(dl, name)
            download(base + name, tgz) { d, t -> onProgress(scale(0, 40, d, t), "Descargando Alpine · ${mb(d)} / ${mb(t)} MB") }
            onProgress(40, "Extrayendo Alpine…")
            root.deleteRecursively()
            untar(tgz, root)
            tgz.delete()
            stageRootfs.writeText("ok")
        }
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
                        onProgress(50 + 200 * n / t, "Instalando paquetes ($n/$t)")
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
            val meta = JSONObject(retry { URL("https://registry.npmjs.org/opencode-linux-arm64-musl/latest").readText() })
            val version = meta.getString("version")
            val tarball = meta.getJSONObject("dist").getString("tarball")
            val tgz = File(dl, "opencode-$version.tgz")
            download(tarball, tgz) { d, t -> onProgress(scale(250, 700, d, t), "Descargando OpenCode $version · ${mb(d)} / ${mb(t)} MB") }
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

    fun startServer(): Process {
        prepareLibs()
        val cmd = "mkdir -p /root/projects && cd /root/projects && exec opencode web --hostname 127.0.0.1 --port $port"
        val pb = ProcessBuilder(guestCmd(cmd)).redirectErrorStream(true).redirectOutput(logFile)
        pb.environment().putAll(env())
        return pb.start()
    }

    fun serverUp(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400); true }
    } catch (e: Exception) { false }
}
