package app.museearset.android

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny log: logcat plus a small file in the app's private storage, so a field test can be
 * read back from the settings screen. Recognized text is never written here, only lengths,
 * the same rule the house apps use: a log must not become a transcript.
 */
object ELog {
    private const val MAX_BYTES = 128 * 1024

    @Synchronized
    fun log(ctx: Context, msg: String) {
        Log.i("MuseEarset", msg)
        runCatching {
            val f = File(ctx.applicationContext.filesDir, "earset-log.txt")
            if (f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(tail)
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$ts $msg\n")
        }
    }

    fun tail(ctx: Context, lines: Int = 14): String = runCatching {
        File(ctx.applicationContext.filesDir, "earset-log.txt")
            .readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")
}
