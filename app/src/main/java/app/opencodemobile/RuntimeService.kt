package app.opencodemobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class RuntimeService : Service() {
    companion object { @Volatile var proc: Process? = null }
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rt = OcRuntime(this)
        val ready = rt.isInstalled()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rt", "OpenCode", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "rt")
            .setContentTitle("OpenCode activo")
            .setContentText(if (ready) "Servidor local en 127.0.0.1:4096" else "Instalando entorno…")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        if (lock == null) {
            lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:runtime").apply { acquire() }
        }
        val p = proc
        if (ready && (p == null || !p.isAlive)) proc = rt.startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        proc?.destroy(); proc = null
        lock?.release(); lock = null
        super.onDestroy()
    }
}
