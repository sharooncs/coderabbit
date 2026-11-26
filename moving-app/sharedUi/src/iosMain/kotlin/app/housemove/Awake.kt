package app.housemove

import platform.UIKit.UIApplication

/**
 * The screen's auto-lock (UIKit's idleTimerDisabled), held off while anything needs the screen on: this iPhone's sync
 * code on show, a sync, joining, the live camera (PhoneHooks.keepAwake). Each hold is let go once; when the last one is,
 * the setting goes back to what it was before the first. Main thread only.
 */
internal object Awake {
    private var holds = 0
    private var before = false

    fun hold(on: Boolean) {
        val app = UIApplication.sharedApplication
        if (on) {
            if (holds++ == 0) {
                before = app.idleTimerDisabled
                app.idleTimerDisabled = true
            }
        } else if (holds > 0 && --holds == 0) {
            app.idleTimerDisabled = before
        }
    }
}
