package app.museearset.android

import android.app.Activity
import android.os.Bundle

/**
 * The assistant entry (VOICE_COMMAND / VOICE_SEARCH_HANDS_FREE): choosing Muse Earset as the
 * device assistant - or an earset gesture that fires the assistant intent - lands here, and a
 * talk session starts. Never displays anything itself.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        ELog.log(app, "assistant entry")
        ScoRoute.cleanupOnStart(app)
        SayService.ensure(app)
        Earset.press(app, "assistant")
        finish()
    }
}
