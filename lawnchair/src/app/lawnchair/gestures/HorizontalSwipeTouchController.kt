package app.lawnchair.gestures

import android.graphics.PointF
import android.view.MotionEvent
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.touch.BothAxesSwipeDetector
import com.android.launcher3.util.TouchController
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

class HorizontalSwipeTouchController(
    private val launcher: LawnchairLauncher,
    private val gestureController: GestureController
) : TouchController, BothAxesSwipeDetector.Listener {

    private val prefs = PreferenceManager2.getInstance(launcher)
    private val detector = BothAxesSwipeDetector(launcher, this)

    private var overrideSwipeRight = false
    private var overrideSwipeLeft = false

    private var noIntercept = false
    private var currentMillis = 0L
    private var currentVelocitx = 0f
    private var currentDisplacement = 0f

    private var triggered = false

    init {
        launcher.lifecycleScope.launch {
            prefs.swipeRightGestureHandler.get()
                .onEach { overrideSwipeRight = it != prefs.swipeRightGestureHandler.defaultValue }
                .launchIn(this)
            prefs.swipeLeftGestureHandler.get()
                .onEach { overrideSwipeLeft = it != prefs.swipeLeftGestureHandler.defaultValue }
                .launchIn(this)
        }
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            noIntercept = !canInterceptTouch(ev)
            if (noIntercept) {
                return false
            }
            detector.setDetectableScrollConditions(getSwipeDirection(), false)
        }
        if (noIntercept) {
            return false
        }
        onControllerTouchEvent(ev)
        return detector.isDraggingOrSettling
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
        return detector.onTouchEvent(ev)
    }

    private fun canInterceptTouch(ev: MotionEvent): Boolean {
        if ((ev.edgeFlags and Utilities.EDGE_NAV_BAR) != 0) {
            return false
        }
        //不是第一页和最后一页不拦截
        if (launcher.workspace.currentPage != 0 && launcher.workspace.currentPage != launcher.workspace.pageCount-1) {
            return false
        }
        return AbstractFloatingView.getTopOpenView(launcher) == null &&
            launcher.isInState(LauncherState.NORMAL)
    }

    override fun onDragStart(start: Boolean) {
        triggered = false
    }

    override fun onDrag(displacement: PointF, motionEvent: MotionEvent): Boolean {
        if (triggered) return true
        val velocitx = computeVelocitx(displacement.x - currentDisplacement, motionEvent.eventTime)
        if (velocitx.absoluteValue > TRIGGER_VELOCITX) {
            triggered = true
            if (velocitx < 0) {
                //左滑 加载最后一屏
                gestureController.onSwipeLeft()
            } else {
                //右滑 加载负一屏
                gestureController.onSwipeRight()
            }
        }
        return true
    }

    override fun onDragEnd(velocity: PointF) {
        detector.finishedScrolling()
    }

    private fun getSwipeDirection(): Int {
        var directions = 0
        if (overrideSwipeRight) {
            //第一页拦截
            if (launcher.workspace.currentPage == 0) {
                directions = directions or BothAxesSwipeDetector.DIRECTION_RIGHT
            }
        }
        if (overrideSwipeLeft) {
            //最后一屏拦截
            if (launcher.workspace.currentPage == launcher.workspace.pageCount-1) {
                directions = directions or BothAxesSwipeDetector.DIRECTION_LEFT
            }
        }
        return directions
    }

    private fun computeVelocitx(delta: Float, millis: Long): Float {
        val previousMillis = currentMillis
        currentMillis = millis

        val deltaTimeMillis = (currentMillis - previousMillis).toFloat()
        val velocitx = if (deltaTimeMillis > 0) delta / deltaTimeMillis else 0f
        currentVelocitx = if (currentVelocitx.absoluteValue < 0.001f) {
            velocitx
        } else {
            val alpha = computeDampeningFactor(deltaTimeMillis)
            Utilities.mapRange(alpha, currentVelocitx, velocitx)
        }
        return currentVelocitx
    }

    /**
     * Returns a time-dependent dampening factor using delta time.
     */
    private fun computeDampeningFactor(deltaTime: Float): Float {
        return deltaTime / (SCROLL_VELOCITX_DAMPENING_RC + deltaTime)
    }

    companion object {
        private const val SCROLL_VELOCITX_DAMPENING_RC = 1000f / (2f * Math.PI.toFloat() * 10f)
        private const val TRIGGER_VELOCITX = 2.25f
    }
}
