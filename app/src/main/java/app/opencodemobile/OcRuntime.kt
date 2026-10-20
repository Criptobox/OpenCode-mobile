package app.opencodemobile

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.file.Files

/** Entorno Linux (Alpine + PRoot) donde corre OpenCode dentro del teléfono. */
class OcRuntime(private val ctx: Context) {
    private val root = File(ctx.filesDir, "rootfs")
    private val libDir = File(ctx.filesDir, "lib")
    private val tmpDir = File(ctx.filesDir, "tmp")
    private val marker = File(ctx.filesDir, "installed.v1")
    private val nativeDir = ctx.applicationInfo.nativeLibraryDir
    val logFile = File(ctx.filesDir, "server.log")
    val port = 4096

    fun isInstalled() = marker.exists()

    private fun env() = mapOf(
        "PROOT_LOADER" to "$nativeDir/libproot-loader.so",
        "PROOT_TMP_DIR" to tmpDir.path,
        "PROOT_NO_SECCOMP" to "1",
        "LD_LIBRARY_PATH" to "${libDir.path}:$nativeDir"
    )

    private fun prepareLibs() {
        libDir.mkdirs(); tmpDir.mkdirs()
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

    private fun run(script: String, log: (String) -> Unit): Int {
        val pb = ProcessBuilder(guestCmd(script)).redirectErrorStream(true)
        pb.environment().putAll(env())
        val p = pb.start()
        p.inputStream.bufferedReader().forEachLine(log)
        return p.waitFor()
    }

    private fun download(url: String, dest: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    fun install(log: (String) -> Unit) {
        prepareLibs()
        if (!File(root, "bin/busybox").exists()) {
            log("Descargando Alpine Linux…")
            val base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"
            val index = URL(base + "latest-releases.yaml").readText()
            val name = Regex("alpine-minirootfs-[0-9.]+-aarch64\\.tar\\.gz").find(index)?.value
                ?: error("No encontré el paquete de Alpine")
            val tgz = File(ctx.cacheDir, name)
            download(base + name, tgz)
            root.deleteRecursively(); root.mkdirs()
            log("Extrayendo…")
            val tar = ProcessBuilder("/system/bin/tar", "-xzf", tgz.path, "-C", root.path)
                .redirectErrorStream(true).start()
            val out = tar.inputStream.bufferedReader().readText()
            if (tar.waitFor() != 0) error("tar falló: $out")
            tgz.delete()
        }
        File(root, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        log("Instalando paquetes y OpenCode (unos minutos)…")
        val setup = "sed -i 's#https://#http://#' /etc/apk/repositories && " +
            "apk update && " +
            "apk add --no-cache bash git curl ripgrep ca-certificates libgcc libstdc++ nodejs npm && " +
            "npm install -g opencode-ai && mkdir -p /root/projects && opencode --version"
        val code = run(setup, log)
        if (code != 0) error("Falló la instalación (código $code)")
        marker.writeText("ok")
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
