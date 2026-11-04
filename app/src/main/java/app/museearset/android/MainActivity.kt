package app.museearset.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The settings screen - and the whole settings promise of this app: two choices.
 * 1) Which earset is yours (its mic becomes the talk mic, by Bluetooth address).
 * 2) Which voice answers (Google Cloud TTS with a selectable voice, or the built-in TTS).
 * Everything else on this screen is status, a test button, and the log.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var earsetNow: TextView
    private lateinit var voiceNow: TextView
    private lateinit var logView: TextView
    private lateinit var keyField: EditText
    private lateinit var cloudBox: LinearLayout
    private lateinit var localBox: LinearLayout

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setPadding(0, dp(22), 0, dp(6))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setPadding(0, dp(2), 0, dp(6))
        setTextColor(0xFF666666.toInt())
    }

    private fun value(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, dp(2), 0, dp(6)) }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScoRoute.cleanupOnStart(applicationContext)
        SayService.ensure(applicationContext)

        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        c.addView(TextView(this).apply {
            text = "Muse Earset"; textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        c.addView(note("Talk to Muse through your earset. This phone must already be a Muse Gadget (the bridge app + Termux setup); this app is its ears and voice."))
        status = value(""); c.addView(status)
        c.addView(button("Grant permissions (mic · nearby devices)") { grantPermissions() })

        c.addView(header("1 · Your earset"))
        c.addView(note("Pick the earset you talk into. While a talk session runs, its microphone is used (Bluetooth SCO); leave it unset to use the phone's mic."))
        earsetNow = value(""); c.addView(earsetNow)
        c.addView(button("Choose earset") { pickEarset() })

        c.addView(header("2 · The answering voice"))
        c.addView(note("Google Cloud needs your own API key (stored only in this app) and offers the Chirp3-HD voices. The built-in voice is the phone's own TTS engine. If the cloud fails, that sentence falls back to the built-in voice."))
        voiceNow = value(""); c.addView(voiceNow)
        c.addView(button("Choose voice kind") { pickVoiceKind() })

        cloudBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cloudBox.addView(note("Google Cloud TTS API key (fill in only to set or change it)"))
        keyField = EditText(this).apply {
            hint = "AIza…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        cloudBox.addView(keyField)
        cloudBox.addView(button("Save API key") { saveKey() })
        cloudBox.addView(button("Voice language") { pickCloudLocale() })
        cloudBox.addView(button("Voice (Chirp3-HD)") { pickCloudVoice() })
        c.addView(cloudBox)

        localBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        localBox.addView(button("Built-in voice") { pickLocalVoice() })
        c.addView(localBox)

        c.addView(button("Test the voice") { testVoice() })

        c.addView(header("Log"))
        logView = note(""); c.addView(logView)

        setContentView(ScrollView(this).apply { addView(c) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val mic = Earset.micOk(this)
        val bt = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        status.text = "Mic permission: ${if (mic) "granted" else "NOT granted"} · Nearby devices: ${if (bt) "granted" else "NOT granted"}"
        val addr = ScoRoute.deviceAddr(this)
        earsetNow.text = if (addr.isEmpty()) "Earset: not chosen (phone mic)"
            else "Earset: ${ScoRoute.deviceName(this)}"
        voiceNow.text = "Voice: ${VoiceOut.describe(this)}"
        val isCloud = VoiceOut.mode(this) == VoiceOut.MODE_CLOUD
        cloudBox.visibility = if (isCloud) View.VISIBLE else View.GONE
        localBox.visibility = if (isCloud) View.GONE else View.VISIBLE
        logView.text = ELog.tail(this).ifEmpty { "(nothing yet)" }
    }

    private fun grantPermissions() {
        val need = mutableListOf<String>()
        if (!Earset.micOk(this)) need += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.BLUETOOTH_CONNECT
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) { status.text = "All permissions already granted."; return }
        requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    @SuppressLint("MissingPermission")
    private fun bonded(): List<Pair<String, String>> {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val ad = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            ad.bondedDevices.map { (it.address ?: "") to (it.name ?: it.address ?: "") }
                .filter { it.first.isNotEmpty() }.sortedBy { it.second }
        }.getOrDefault(emptyList())
    }

    private fun pickEarset() {
        val devs = bonded()
        if (devs.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Earset")
                .setMessage("No paired Bluetooth devices found. Pair your earset in the phone's Bluetooth settings first (and grant the nearby-devices permission).")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = (listOf("None (use the phone mic)") + devs.map { it.second }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Your earset")
            .setItems(labels) { _, i ->
                if (i == 0) ScoRoute.setDevice(this, "", "")
                else ScoRoute.setDevice(this, devs[i - 1].first, devs[i - 1].second)
                ELog.log(this, "earset chosen: ${labels[i]}")
                refresh()
            }.show()
    }

    private fun pickVoiceKind() {
        val kinds = arrayOf("Google Cloud TTS (Chirp3-HD voices)", "Built-in TTS (the phone's engine)")
        AlertDialog.Builder(this).setTitle("Voice kind")
            .setItems(kinds) { _, i ->
                VoiceOut.setMode(this, if (i == 0) VoiceOut.MODE_CLOUD else VoiceOut.MODE_LOCAL)
                refresh()
            }.show()
    }

    private fun saveKey() {
        val v = keyField.text.toString().trim()
        if (v.isEmpty()) { status.text = "Paste the API key first."; return }
        VoiceOut.setCloudKey(this, v)
        keyField.setText("")
        ELog.log(this, "cloud key saved (…${v.takeLast(4)})")
        refresh()
    }

    private fun pickCloudLocale() {
        val cur = VoiceOut.cloudVoice(this)
        val name = cur.removePrefix(cur.split("-").take(2).joinToString("-") + "-Chirp3-HD-")
        AlertDialog.Builder(this).setTitle("Voice language")
            .setItems(VoiceOut.CLOUD_LOCALES.toTypedArray()) { _, i ->
                VoiceOut.setCloudVoice(this, "${VoiceOut.CLOUD_LOCALES[i]}-Chirp3-HD-$name")
                refresh()
            }.show()
    }

    private fun pickCloudVoice() {
        val cur = VoiceOut.cloudVoice(this)
        val locale = cur.split("-").take(2).joinToString("-")
        AlertDialog.Builder(this).setTitle("Chirp3-HD voice")
            .setItems(VoiceOut.CLOUD_VOICES.toTypedArray()) { _, i ->
                VoiceOut.setCloudVoice(this, "$locale-Chirp3-HD-${VoiceOut.CLOUD_VOICES[i]}")
                refresh()
            }.show()
    }

    private fun pickLocalVoice() {
        status.text = "Reading the built-in voices…"
        Thread {
            val voices = runCatching { VoiceOut.localeVoices(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (voices.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("Built-in voice")
                        .setMessage("The phone's TTS engine offered no voices for your language.")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val labels = (listOf("Engine default") + voices.map { it.name }).toTypedArray()
                AlertDialog.Builder(this).setTitle("Built-in voice")
                    .setItems(labels) { _, i ->
                        VoiceOut.setLocalVoice(this, if (i == 0) "" else voices[i - 1].name)
                        refresh()
                    }.show()
                refresh()
            }
        }.start()
    }

    private fun testVoice() {
        ELog.log(this, "voice test (${VoiceOut.describe(this)})")
        VoiceOut.speak(this, "Hello, this is Muse on your earset. The voice is set.")
    }
}
