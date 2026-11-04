package app.museearset.android

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.Context
import android.os.SystemClock

/**
 * Headset mic routing, ported from the house agent's ScoRoute (field-proven there): when an
 * earset is chosen in the settings and it is among the available communication devices,
 * opening a talk session first switches to MODE_IN_COMMUNICATION and sets that earset as the
 * communication device, waits until the system confirms it (2.5 s at most), and only then
 * starts listening - that is what makes the phone's speech recognizer hear the earset's mic.
 * Any failure falls back to the phone mic, a call in progress skips the whole thing, and the
 * route is released when the session card closes. A prefs flag remembers that we hold the
 * route, so if the process dies mid-session the next start cleans up (cleanupOnStart).
 * Picking an earset in the settings is the on switch; "None" turns the feature off.
 */
object ScoRoute {
    private const val WAIT_MS = 2500L
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("earset", Context.MODE_PRIVATE)

    fun deviceAddr(ctx: Context) = prefs(ctx).getString("es_addr", "") ?: ""
    fun deviceName(ctx: Context) = prefs(ctx).getString("es_name", "") ?: ""
    fun setDevice(ctx: Context, addr: String, name: String) =
        prefs(ctx).edit().putString("es_addr", addr).putString("es_name", name).apply()
    fun enabled(ctx: Context) = deviceAddr(ctx).isNotEmpty()

    @Volatile private var held = false
    @Volatile private var runId = 0
    private var savedMode = AudioManager.MODE_NORMAL
    private var targetId = -1

    private fun describe(d: AudioDeviceInfo) = "${d.productName} [type ${d.type}, id ${d.id}]"
    private fun isHeadset(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    /**
     * Called from SessionActivity.onCreate instead of a plain listen(). True = the route
     * dance took over (asynchronous): [ready] runs when listening may start, on the opened
     * route or on the phone-mic fallback alike. False = feature not in play; listen now.
     */
    fun begin(ctx: Context, ready: () -> Unit): Boolean {
        if (!enabled(ctx)) return false
        val addr = deviceAddr(ctx)
        val app = ctx.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            ELog.log(app, "headset mic: a call owns the audio mode -> phone mic")
            return false
        }
        val comm = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
        val target = comm.firstOrNull { isHeadset(it) && it.address == addr }
        if (target == null) {
            ELog.log(app, "headset mic: chosen earset (${deviceName(ctx)}) not a communication device -> phone mic (candidates ${comm.map { describe(it) }})")
            return false
        }
        savedMode = am.mode
        targetId = target.id
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        val accepted = runCatching { am.setCommunicationDevice(target) }.getOrDefault(false)
        ELog.log(app, "headset mic: mode $savedMode -> IN_COMMUNICATION, set device ${describe(target)} -> $accepted")
        if (!accepted) {
            runCatching { am.mode = savedMode }
            return false
        }
        held = true
        prefs(app).edit().putBoolean("es_held", true).putInt("es_saved_mode", savedMode).apply()
        val my = ++runId
        Thread {
            val start = SystemClock.elapsedRealtime()
            var confirmed = false
            while (SystemClock.elapsedRealtime() - start < WAIT_MS) {
                if (my != runId) return@Thread // released meanwhile; onDestroy already restored
                if (runCatching { am.communicationDevice?.id == targetId }.getOrDefault(false)) { confirmed = true; break }
                Thread.sleep(100)
            }
            if (my != runId) return@Thread
            val waited = SystemClock.elapsedRealtime() - start
            if (confirmed) {
                ELog.log(app, "headset mic: route confirmed (${waited}ms) -> listening on the earset")
            } else {
                ELog.log(app, "headset mic: not confirmed in ${WAIT_MS}ms -> releasing, phone mic")
                release(app)
            }
            ready()
        }.start()
        return true
    }

    /** Undo the route: clear the device, and put the mode back only if it is still ours. */
    fun release(ctx: Context) {
        if (!held) return
        held = false
        runId++
        val app = ctx.applicationContext
        prefs(app).edit().remove("es_held").remove("es_saved_mode").apply()
        val am = app.getSystemService(AudioManager::class.java)
        runCatching { am.clearCommunicationDevice() }
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            runCatching { am.mode = savedMode }
            ELog.log(app, "headset mic: released (mode back to $savedMode)")
        } else {
            ELog.log(app, "headset mic: released (mode already ${am.mode}, left alone)")
        }
    }

    /**
     * Crash leftover: if the process died while holding the route, the prefs flag survives.
     * Called when the app starts (settings, assistant entry) while no session card is alive.
     */
    fun cleanupOnStart(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("es_held", false)) return
        val was = p.getInt("es_saved_mode", AudioManager.MODE_NORMAL)
        p.edit().remove("es_held").remove("es_saved_mode").apply()
        val app = ctx.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            runCatching { am.clearCommunicationDevice() }
            runCatching { am.mode = was }
            ELog.log(app, "headset mic: cleaned up a leftover route (mode back to $was)")
        } else {
            ELog.log(app, "headset mic: cleared a leftover flag only (mode is ${am.mode})")
        }
    }
}
