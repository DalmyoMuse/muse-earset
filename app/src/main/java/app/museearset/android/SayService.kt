package app.museearset.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Hosts the spoken-answer listener: the gadget-side `earset-say` command POSTs Muse's reply
 * here (127.0.0.1:47822/say), and this app speaks it in the voice the settings chose, through
 * whatever output is current (the earset, when connected). A foreground service, because
 * answers arrive at any time; the persistent notification is Android's price for that.
 * Loopback only - nothing here is reachable from the network.
 */
class SayService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Muse Earset", NotificationManager.IMPORTANCE_MIN))
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Muse Earset")
            .setContentText("Ready - Muse's answers will be spoken here.")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .build()
        startForeground(1, note)
        running = true
        Thread({ serve() }, "earset-say-server").apply { isDaemon = true; start() }
        ELog.log(this, "say service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun serve() {
        runCatching {
            ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")).use { server ->
                while (running) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    Thread { handle(sock) }.apply { isDaemon = true; start() }
                }
            }
        }.onFailure { ELog.log(this, "say server failed: ${it.message}") }
    }

    private fun handle(sock: java.net.Socket) {
        sock.use {
            it.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
            val head = reader.readLine() ?: return
            val parts = head.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1) ?: ""
            var length = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            val body = if (length > 0) {
                val buf = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""
            val (code, obj) = route(method, path, body)
            val data = obj.toString().toByteArray(Charsets.UTF_8)
            val out = it.getOutputStream()
            out.write("HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.write(data)
            out.flush()
        }
    }

    private fun route(method: String, path: String, body: String): Pair<Int, JSONObject> {
        val app = applicationContext
        return when {
            method == "GET" && path == "/state" ->
                200 to JSONObject().put("ok", true).put("speaking", VoiceOut.speaking())
            method == "POST" && path == "/say" -> {
                val text = runCatching { JSONObject(body).optString("text") }.getOrDefault("").trim()
                if (text.isEmpty()) 400 to JSONObject().put("ok", false).put("why", "empty text")
                else {
                    ELog.log(app, "answer arrived (${text.length} chars) -> speaking")
                    VoiceOut.speak(app, text.take(MAX_SAY_CHARS))
                    200 to JSONObject().put("ok", true)
                }
            }
            method == "POST" && path == "/stop" -> {
                VoiceOut.stopAll(app)
                200 to JSONObject().put("ok", true)
            }
            else -> 404 to JSONObject().put("ok", false).put("why", "not found")
        }
    }

    companion object {
        const val PORT = 47822
        const val MAX_SAY_CHARS = 1200
        private const val CHANNEL = "earset"

        fun ensure(ctx: Context) {
            runCatching {
                ctx.applicationContext.startForegroundService(Intent(ctx.applicationContext, SayService::class.java))
            }.onFailure { ELog.log(ctx, "say service start failed: ${it.message}") }
        }
    }
}

/** Starts the say service after a reboot, so answers keep working without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) SayService.ensure(ctx)
    }
}
