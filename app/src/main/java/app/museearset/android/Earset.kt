package app.museearset.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The talk session's core: press to listen, press again to send. The recognized text goes to
 * the gadget-side bridge on this same phone (termux/earset_bridge.py, 127.0.0.1:47823), which
 * hands it to the paired Muse through the stock gadget SDK socket. Ported from the house
 * agent's Talk object, minus everything household (buttons, keys, car logic).
 */
object Earset {
    const val BRIDGE_PORT = 47823
    /** How long after "ready" the start beep sounds at the earliest (ms). */
    const val BEEP_DELAY_MS = 400L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("earset", Context.MODE_PRIVATE)

    /**
     * Setup (not one of the two everyday settings): where earset_bridge.py listens.
     * "127.0.0.1" when the app and the gadget host are the same phone - the product's
     * default. A LAN address when another device hosts the bridge (a split test setup).
     */
    fun bridgeHost(ctx: Context): String = prefs(ctx).getString("bridge_host", "")?.ifEmpty { null } ?: "127.0.0.1"
    fun setBridgeHost(ctx: Context, h: String) = prefs(ctx).edit().putString("bridge_host", h.trim()).apply()
    fun bridgeUrl(ctx: Context) = "http://${bridgeHost(ctx)}:$BRIDGE_PORT/ask"
    fun micOk(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val ui = Handler(Looper.getMainLooper())
    @Volatile var listening: SessionActivity? = null

    /** Main thread. 1st press: start listening. While listening: stop and send what was heard. */
    fun press(ctx: Context, from: String) {
        ELog.log(ctx, "pressed: $from")
        listening?.let { it.finishSpeaking(); return }
        if (!micOk(ctx)) { ELog.log(ctx, "pressed: $from -> no mic permission"); return }
        quiet(ctx, true)
        ctx.startActivity(Intent(ctx, SessionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    /**
     * Blocking; retries for about 20 s (network switches, the phone waking up).
     * Returns "" on success, or why it failed.
     */
    fun send(ctx: Context, text: String): String {
        val url = bridgeUrl(ctx)
        var why = ""
        for (wait in listOf(0L, 2000L, 5000L, 10000L)) {
            if (wait > 0) Thread.sleep(wait)
            why = post(url, text)
            if (why.isEmpty()) return ""
        }
        return why
    }

    private fun post(url: String, text: String): String = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
        val body = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val r = runCatching { JSONObject(body) }.getOrNull()
        when {
            r?.optBoolean("ok") == true -> ""
            r != null -> r.optString("why").ifEmpty { "bridge refused" }
            else -> "bridge http ${conn.responseCode}"
        }
    }.getOrElse { "bridge unreachable: ${it.javaClass.simpleName}" }

    // The system's speech service plays its own start/end sounds on the notification stream.
    // Two sounds before speaking is confusing, so that stream is muted while listening and
    // only our beep is left. The flag survives a crash; the next press restores the sound.
    fun quiet(ctx: Context, on: Boolean) {
        val p = prefs(ctx)
        if (on == p.getBoolean("muted_noti", false)) return
        val am = ctx.getSystemService(AudioManager::class.java)
        if (on && am.isStreamMute(AudioManager.STREAM_NOTIFICATION)) return // already silent: never unmute it later
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION,
                if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            p.edit().putBoolean("muted_noti", on).apply()
        }.onFailure { ELog.log(ctx, "notification mute ${if (on) "on" else "off"} failed: ${it.message}") }
    }

    fun quietLater(ctx: Context) {
        val app = ctx.applicationContext
        ui.postDelayed({ if (listening == null) quiet(app, false) }, 700)
    }

    // Our own audio focus for the whole talk: the recognizer takes transient-exclusive focus
    // for every session, and with the session restarts music would pause/resume every few
    // seconds. Holding focus underneath keeps it paused once, until the card closes.
    private val focusAttrs = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var focusReq: android.media.AudioFocusRequest? = null

    fun holdFocus(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (on) {
            if (focusReq != null) return
            val r = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(focusAttrs).setOnAudioFocusChangeListener { }.build()
            if (am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusReq = r
        } else focusReq?.let { am.abandonAudioFocusRequest(it); focusReq = null }
    }

    // Short PCM tones played like navigation prompts, which the system does not duck or mute
    // for focus (a plain media-stream beep disappears under the recognizer's exclusive focus).
    private val beepAttrs = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    private fun tone(vararg notes: Pair<Double, Int>): ShortArray {
        val rate = 44100
        val out = ArrayList<Short>()
        for ((hz, ms) in notes) {
            val n = rate * ms / 1000
            val edge = rate * 8 / 1000 // 8 ms fade in/out, no clicks
            for (i in 0 until n) {
                val env = minOf(1.0, i / edge.toDouble(), (n - i) / edge.toDouble())
                out += (Math.sin(2 * Math.PI * hz * i / rate) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    private val sounds by lazy {
        mapOf("start" to tone(880.0 to 140), "sent" to tone(660.0 to 90, 990.0 to 130), "fail" to tone(440.0 to 160, 330.0 to 220))
    }

    fun beep(kind: String) {
        val pcm = sounds[kind] ?: sounds["fail"]!!
        runCatching {
            val t = android.media.AudioTrack.Builder().setAudioAttributes(beepAttrs)
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(44100)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            t.write(pcm, 0, pcm.size)
            t.play()
            ui.postDelayed({ runCatching { t.release() } }, pcm.size * 1000L / 44100 + 300)
        }
    }
}
