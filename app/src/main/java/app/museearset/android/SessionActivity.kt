package app.museearset.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * The small card at the bottom while listening; touches pass through to the screen behind.
 * A visible window keeps the microphone allowed, and it shows over the lock screen.
 * Ported from the house agent's TalkActivity, including its hard-won session behaviour:
 * the system recognizer ends sessions on its own at short pauses, so one press is many
 * sessions - each finished piece is kept and listening restarts, until the press repeats,
 * 3 s of quiet after the last words (auto-send), 12 s of nothing at all (give up), or a
 * 90 s cap. The start beep waits for the mic level to actually move, because "ready" arrives
 * well before the recognizer really hears.
 */
class SessionActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private lateinit var label: TextView
    private var heard = ""
    private var done = false
    private val parts = mutableListOf<String>()
    private var stopping = false
    private var restarts = 0
    private var lastError = 0
    private fun collected() = (parts + listOfNotNull(heard.takeIf { it.isNotBlank() })).joinToString(" ").trim()
    private val autoSend = Runnable { if (!done && !stopping) { stopping = true; deliver(collected()) } }
    private val stopFallback = Runnable { deliver(collected()) }
    private val started = SystemClock.elapsedRealtime()
    private var readyAt = 0L
    private var beepAt = 0L
    private var speechAt = 0L
    private var levelAt = 0L
    private var firstRms: Float? = null

    private fun timings(): String {
        fun s(ms: Long) = String.format(Locale.US, "%.1f", ms / 1000.0)
        if (readyAt == 0L) return "never ready"
        return "ready ${s(readyAt - started)}s" + (if (levelAt > 0) " · sound +${s(levelAt - readyAt)}" else " · no sound") +
            (if (beepAt > 0) " · beep +${s(beepAt - readyAt)}" else "") +
            (if (speechAt > 0) " · speech +${s(speechAt - readyAt)}" else "") +
            (if (restarts > 0) " · restarts $restarts" else "") +
            (if (lastError != 0) " · error $lastError" else "")
    }

    private companion object {
        const val AUTO_SEND_MS = 3000L
        const val GIVE_UP_MS = 12000L
        const val MAX_MS = 90000L
        val QUIET_ERRORS = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)

    private fun sessionEnded(piece: String) {
        ELog.log(this, "session ended (piece ${piece.length} chars, restarts $restarts, ${(SystemClock.elapsedRealtime() - started) / 1000}s in)")
        if (piece.isNotBlank()) parts += piece
        heard = ""
        if (done) return
        if (stopping) { deliver(collected()); return }
        val now = SystemClock.elapsedRealtime()
        if (parts.isEmpty() && now - started > GIVE_UP_MS) { fail("I couldn't hear you"); return }
        if (now - started > MAX_MS) { deliver(collected()); return }
        if (parts.isNotEmpty()) {
            show("🎤 ${collected()} …")
            ui.removeCallbacks(autoSend); ui.postDelayed(autoSend, AUTO_SEND_MS)
        }
        restarts++
        ui.postDelayed({
            if (done || stopping) return@postDelayed
            runCatching { rec?.cancel(); rec?.startListening(intent()) }
                .onFailure { fail("couldn't restart listening: ${it.message}") }
        }, 100)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setDimAmount(0f)
        label = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE) }
        showBottomCard(label)
        Earset.listening = this
        Earset.holdFocus(applicationContext, true)
        show("⏳ Getting ready… (speak after the beep)")
        val routeTookOver = ScoRoute.begin(this) { ui.post { if (!done && !isFinishing) listen() } }
        if (!routeTookOver) listen()
    }

    private fun show(t: String) { label.text = t }

    private fun startBeep() {
        if (done || beepAt > 0) return
        beepAt = SystemClock.elapsedRealtime()
        Earset.beep("start")
        if (heard.isEmpty()) show("🎤 Speak now (press again to send)")
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fail("speech recognition isn't available on this phone"); return }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (readyAt > 0) return // a restarted session: no second beep
                readyAt = SystemClock.elapsedRealtime()
                show("⏳ Getting ready… (speak after the beep)")
                ui.postDelayed({ startBeep() }, 4000)
            }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    heard = it; ui.removeCallbacks(autoSend); show("🎤 ${collected()}")
                }
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                sessionEnded(t.ifEmpty { heard })
            }
            override fun onError(error: Int) {
                lastError = error
                ELog.log(this@SessionActivity, "recognition error $error" + if (error in QUIET_ERRORS) " (quiet end)" else " (fatal)")
                if (error in QUIET_ERRORS) { sessionEnded(heard); return }
                if (collected().isNotBlank()) { deliver(collected()); return }
                fail(when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "recognition server unreachable"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no mic permission"
                    SpeechRecognizer.ERROR_AUDIO -> "the mic is busy (another app?)"
                    else -> "recognition error $error"
                })
            }
            override fun onBeginningOfSpeech() {
                if (speechAt == 0L) speechAt = SystemClock.elapsedRealtime()
                ui.removeCallbacks(autoSend)
            }
            override fun onRmsChanged(rmsdB: Float) {
                if (readyAt == 0L || beepAt > 0) return
                if (firstRms == null) { firstRms = rmsdB; return }
                if (rmsdB != firstRms && levelAt == 0L) {
                    levelAt = SystemClock.elapsedRealtime()
                    val wait = Earset.BEEP_DELAY_MS - (levelAt - readyAt)
                    if (wait > 0) ui.postDelayed({ startBeep() }, wait) else startBeep()
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (stopping) show("⏳ ${collected().ifEmpty { "Listening…" }}") }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent())
        ELog.log(this, "session started")
    }

    /** The 2nd press: stop listening; the recognizer then reports what it has (or we send what we have after 3 s). */
    fun finishSpeaking() {
        ELog.log(this, "send pressed (${collected().length} chars collected, restarts $restarts)")
        if (done || stopping) return
        if (SystemClock.elapsedRealtime() - started < 800) return // the same gesture bouncing
        stopping = true
        ui.removeCallbacks(autoSend)
        show("⏳ ${collected().ifEmpty { "Listening…" }}")
        rec?.stopListening()
        ui.postDelayed(stopFallback, 3000)
    }

    private fun deliver(text: String) {
        if (done) return
        ui.removeCallbacks(autoSend); ui.removeCallbacks(stopFallback)
        if (text.isBlank()) { fail("I couldn't hear you"); return }
        done = true
        rec?.cancel()
        show("📨 $text")
        val app = applicationContext
        Thread {
            val why = Earset.send(app, text)
            ui.post {
                if (why.isEmpty()) {
                    ELog.log(app, "sent (${text.length} chars, ${(SystemClock.elapsedRealtime() - started) / 1000}s, restarts $restarts, ${timings()})")
                    Earset.beep("sent"); show("✓ Sent: $text")
                } else {
                    ELog.log(app, "send failed: $why")
                    Earset.beep("fail"); show("✗ Not sent ($why)")
                }
                ui.postDelayed({ finish() }, if (why.isEmpty()) 1200 else 3000)
            }
        }.start()
    }

    private fun fail(why: String) {
        if (done) return
        done = true
        ui.removeCallbacks(autoSend); ui.removeCallbacks(stopFallback)
        rec?.cancel()
        ELog.log(this, "failed: $why (${timings()})")
        Earset.beep("fail"); show("✗ $why")
        ui.postDelayed({ finish() }, 1800)
    }

    override fun onDestroy() {
        ELog.log(this, "card closed (restarts $restarts)")
        ScoRoute.release(this)
        if (Earset.listening === this) Earset.listening = null
        rec?.destroy(); rec = null
        Earset.holdFocus(applicationContext, false)
        Earset.quietLater(this)
        super.onDestroy()
    }
}

/**
 * The small bottom card, lifted above the system bars by their insets (on an edge-to-edge
 * window it would otherwise sit under the navigation bar, unreadable).
 */
fun Activity.showBottomCard(label: TextView) {
    val card = LinearLayout(this).apply {
        setPadding(48, 36, 48, 36)
        background = GradientDrawable().apply { setColor(0xFF210D04.toInt()); cornerRadius = 36f }
        addView(label)
    }
    val gap = (12 * resources.displayMetrics.density).toInt()
    setContentView(android.widget.FrameLayout(this).apply {
        addView(card)
        setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(bars.left + gap, 0, bars.right + gap, bars.bottom + gap)
            ins
        }
    })
}
