package app.museearset.android

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spoken answers, in the voice the settings chose - and only two kinds (the product's whole
 * settings promise): Google Cloud TTS with a selectable Chirp3-HD voice, or the phone's
 * built-in TTS engine. Ported from the house agent's Speech, minus its gates (car, lab
 * modes): in this app, an answer that arrives is meant to be spoken. Synthesis runs per
 * sentence into files that one player thread plays in order, so the first sentence starts
 * while the rest are still being made; a sentence the cloud fails on falls back to the
 * built-in engine for that sentence.
 */
object VoiceOut {
    const val MODE_CLOUD = "cloud"
    const val MODE_LOCAL = "local"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("earset", Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = prefs(ctx).getString("tts_mode", MODE_LOCAL) ?: MODE_LOCAL
    fun setMode(ctx: Context, m: String) = prefs(ctx).edit().putString("tts_mode", m).apply()

    /** The Cloud TTS API key: app-private storage only, never logged, never shown in full. */
    fun cloudKey(ctx: Context): String = prefs(ctx).getString("tts_api_key", "") ?: ""
    fun setCloudKey(ctx: Context, k: String) = prefs(ctx).edit().putString("tts_api_key", k.trim()).apply()

    /** Locales offered for the cloud voice (Chirp3-HD availability differs per locale). */
    val CLOUD_LOCALES = listOf("en-US", "ko-KR", "ja-JP", "de-DE", "fr-FR", "es-ES", "es-US",
        "it-IT", "pt-BR", "pt-PT", "nl-NL", "pl-PL", "ru-RU", "hi-IN", "id-ID", "vi-VN", "th-TH", "tr-TR")

    /** The Chirp3-HD voice names; a full voice id is "<locale>-Chirp3-HD-<name>". */
    val CLOUD_VOICES = listOf("Achird", "Algenib", "Algieba", "Alnilam", "Aoede", "Autonoe",
        "Callirrhoe", "Charon", "Despina", "Enceladus", "Erinome", "Fenrir", "Gacrux", "Iapetus",
        "Kore", "Laomedeia", "Leda", "Orus", "Pulcherrima", "Puck", "Rasalgethi", "Sadachbia",
        "Sadaltager", "Schedar", "Sulafat", "Umbriel", "Vindemiatrix", "Zephyr", "Zubenelgenubi")

    fun defaultCloudVoice(): String {
        val loc = Locale.getDefault().toLanguageTag()
        val use = if (loc in CLOUD_LOCALES) loc else "en-US"
        return "$use-Chirp3-HD-Despina"
    }

    fun cloudVoice(ctx: Context): String = prefs(ctx).getString("tts_cloud_voice", "")?.ifEmpty { null } ?: defaultCloudVoice()
    fun setCloudVoice(ctx: Context, v: String) = prefs(ctx).edit().putString("tts_cloud_voice", v).apply()

    /** The built-in engine voice the settings picked (an engine voice name), "" = engine default. */
    fun localVoice(ctx: Context): String = prefs(ctx).getString("tts_local_voice", "") ?: ""
    fun setLocalVoice(ctx: Context, name: String) = prefs(ctx).edit().putString("tts_local_voice", name).apply()

    const val KIND_LOCAL = 0
    const val KIND_CLOUD = 1

    /** The synthesis kind the settings resolve to (cloud chosen without a key is local). */
    fun kind(ctx: Context): Int =
        if (mode(ctx) == MODE_CLOUD && cloudKey(ctx).isNotEmpty()) KIND_CLOUD else KIND_LOCAL

    fun describe(ctx: Context): String = when (kind(ctx)) {
        KIND_CLOUD -> "Google Cloud · ${cloudVoice(ctx)}"
        else -> if (mode(ctx) == MODE_CLOUD) "Built-in (cloud chosen, but no API key yet)"
                else "Built-in" + localVoice(ctx).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    }

    // --- the built-in engine (kept alive once started; binding takes a moment) ---

    @Volatile private var tts: TextToSpeech? = null

    /** Never call on the main thread: the init callback arrives there, so it would wait forever. */
    @Synchronized
    fun engine(ctx: Context): TextToSpeech {
        tts?.let { return it }
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val t = TextToSpeech(ctx.applicationContext) { status = it; ready.countDown() }
        if (!ready.await(8, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) {
            t.shutdown()
            throw IllegalStateException("the phone's TTS engine did not start")
        }
        // Out the way navigation prompts go, so an earset treats it like guidance audio.
        t.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { Player.onUtteranceDone(id) }
            @Deprecated("old API") override fun onError(id: String?) { Player.onUtteranceDone(id) }
        })
        tts = t
        return t
    }

    fun engineOrNull(): TextToSpeech? = tts

    /** The engine's voices for the device locale's language. Off-main thread (starts the engine). */
    fun localeVoices(ctx: Context): List<Voice> {
        val lang = Locale.getDefault().language
        return (engine(ctx).voices ?: emptySet()).filter { it.locale.language == lang }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
    }

    // --- speaking ---

    fun speak(ctx: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val app = ctx.applicationContext
        Thread {
            runCatching {
                if (kind(app) == KIND_LOCAL) runCatching { engine(app) }
                Player.enqueue(app, sentences(clean), flush = true)
            }.onFailure { ELog.log(app, "speak failed: ${it.message}") }
        }.start()
    }

    fun stopAll(ctx: Context) {
        runCatching { tts?.stop() }
        Player.stop()
    }

    fun speaking(): Boolean = Player.playing

    /** Sentences, so the first one can play while the next is made. Tiny bits join the next; long ones split. */
    fun sentences(text: String): List<String> {
        val raw = Regex("(?<=[.!?。…~\\n])\\s*").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var carry = ""
        for (r in raw) {
            val s = if (carry.isEmpty()) r else "$carry $r"
            if (s.length < 8) { carry = s; continue }
            carry = ""
            var rest = s
            while (rest.length > 160) {
                val cut = rest.lastIndexOf(',', 160).takeIf { it > 40 } ?: rest.lastIndexOf(' ', 160).takeIf { it > 40 } ?: 160
                out += rest.substring(0, cut + 1).trim(); rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotEmpty()) out += rest
        }
        if (carry.isNotEmpty()) { if (out.isEmpty()) out += carry else out[out.lastIndex] = out.last() + " " + carry }
        return out
    }

    object Player {
        private class Job(val gen: Int, val text: String, val at: Long, val kind: Int)
        private class Clip(val gen: Int, val file: java.io.File, val at: Long, val first: Boolean)

        @Volatile private var gen = 0
        private val toMake = java.util.concurrent.LinkedBlockingQueue<Job>()
        private val toPlay = java.util.concurrent.LinkedBlockingQueue<Clip>()
        @Volatile private var player: android.media.MediaPlayer? = null
        @Volatile private var curEnd: CountDownLatch? = null
        @Volatile var playing = false; private set
        private var started = false
        private val waits = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
        private var n = 0

        @Synchronized
        fun enqueue(ctx: Context, pieces: List<String>, flush: Boolean) {
            start(ctx)
            if (flush) stop()
            val now = System.currentTimeMillis()
            val kind = VoiceOut.kind(ctx)
            pieces.forEach { toMake.put(Job(gen, it, now, kind)) }
        }

        fun stop() {
            gen++
            toMake.clear(); toPlay.clear()
            runCatching { player?.stop() }
            curEnd?.countDown()
        }

        fun onUtteranceDone(id: String?) { id?.let { waits.remove(it)?.countDown() } }

        private fun start(ctx: Context) {
            if (started) return
            started = true
            val dir = java.io.File(ctx.cacheDir, "say").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            Thread({ makeLoop(ctx, dir) }, "say-make").apply { isDaemon = true; start() }
            Thread({ playLoop(ctx) }, "say-play").apply { isDaemon = true; start() }
        }

        private fun makeLoop(ctx: Context, dir: java.io.File) {
            var lastGen = -1
            var lastFallbackGen = -1
            while (true) {
                val j = toMake.take()
                if (j.gen != gen) continue
                var clip: Clip? = null
                var attempted = false
                if (j.kind == KIND_CLOUD) {
                    val mp3 = CloudTts.synth(j.text, VoiceOut.cloudVoice(ctx), VoiceOut.cloudKey(ctx))
                    if (mp3 != null && j.gen == gen) {
                        val f = java.io.File(dir, "say-${n++}.mp3")
                        runCatching { f.writeBytes(mp3) }
                        if (f.length() > 0) clip = Clip(j.gen, f, j.at, j.gen != lastGen) else f.delete()
                    }
                    if (clip == null && lastFallbackGen != j.gen) {
                        lastFallbackGen = j.gen
                        ELog.log(ctx, "cloud synth failed -> this answer falls back to the built-in voice" +
                            (CloudTts.lastError?.let { " ($it)" } ?: ""))
                    }
                }
                val fb = if (clip == null)
                    (VoiceOut.engineOrNull() ?: runCatching { VoiceOut.engine(ctx) }.getOrNull()) else null
                if (clip == null && fb != null) {
                    val v = VoiceOut.localVoice(ctx).takeIf { it.isNotEmpty() }
                        ?.let { n -> fb.voices?.firstOrNull { it.name == n } }
                    if (v != null) runCatching { fb.voice = v }
                    val id = "say-${n++}"
                    val f = java.io.File(dir, "$id.wav")
                    val done = CountDownLatch(1).also { waits[id] = it }
                    if (fb.synthesizeToFile(j.text, Bundle(), f, id) != TextToSpeech.SUCCESS) { waits.remove(id); f.delete() }
                    else {
                        attempted = true
                        done.await(20, TimeUnit.SECONDS)
                        if (j.gen == gen && f.length() > 44) clip = Clip(j.gen, f, j.at, j.gen != lastGen) else f.delete()
                    }
                }
                if (clip != null) { toPlay.put(clip); lastGen = j.gen }
                else if (attempted) lastGen = j.gen
            }
        }

        private fun playLoop(ctx: Context) {
            while (true) {
                val c = toPlay.take()
                if (c.gen != gen) { c.file.delete(); continue }
                if (c.first) ELog.log(ctx, "answer: first sound %.1fs after the reply arrived".format(Locale.US, (System.currentTimeMillis() - c.at) / 1000.0))
                val end = CountDownLatch(1).also { curEnd = it }
                runCatching {
                    val mp = android.media.MediaPlayer()
                    player = mp
                    mp.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    mp.setDataSource(c.file.path)
                    mp.setOnCompletionListener { end.countDown() }
                    mp.setOnErrorListener { _, _, _ -> end.countDown(); true }
                    mp.prepare()
                    playing = true
                    mp.start()
                    end.await(60, TimeUnit.SECONDS)
                }.onFailure { ELog.log(ctx, "playback failed: ${it.message}") }
                playing = toPlay.isNotEmpty() || toMake.isNotEmpty()
                runCatching { player?.release() }; player = null
                c.file.delete()
            }
        }
    }
}

/**
 * Google Cloud TTS: one HTTPS call per sentence, MP3 out. Blocking - the player's maker
 * thread only. Null on any failure; the caller falls back to the phone's engine for that
 * sentence. The key arrives as an argument and is never logged.
 */
object CloudTts {
    @Volatile var lastError: String? = null

    fun synth(text: String, voice: String, key: String): ByteArray? {
        if (key.isEmpty()) return null
        return runCatching {
            // "ko-KR-Chirp3-HD-Despina" -> languageCode "ko-KR"
            val lang = voice.split("-").take(2).joinToString("-")
            val body = JSONObject()
                .put("input", JSONObject().put("text", text))
                .put("voice", JSONObject().put("languageCode", lang).put("name", voice))
                .put("audioConfig", JSONObject().put("audioEncoding", "MP3"))
            val conn = URL("https://texttospeech.googleapis.com/v1/text:synthesize")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Goog-Api-Key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                lastError = "http ${conn.responseCode}"
                return null
            }
            val resp = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            Base64.decode(JSONObject(resp).getString("audioContent"), Base64.DEFAULT)
        }.onFailure { lastError = "${it.javaClass.simpleName}: ${it.message?.take(80)}" }.getOrNull()
    }
}
