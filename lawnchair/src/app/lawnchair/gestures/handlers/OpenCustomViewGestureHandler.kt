package app.lawnchair.gestures.handlers

import android.content.Context
import app.lawnchair.LawnchairLauncher
import app.lawnchair.animateToCustomView
import app.lawnchair.gestures.GestureHandler

open class OpenCustomViewGestureHandler(context: Context) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: LawnchairLauncher) {
        launcher.animateToCustomView()
    }
}
