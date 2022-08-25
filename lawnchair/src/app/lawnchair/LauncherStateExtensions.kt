package app.lawnchair

import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.anim.AnimatorListeners.forEndCallback
import com.android.launcher3.anim.Interpolators
import com.android.launcher3.customview.CustomViewTransitionController
import com.android.launcher3.states.StateAnimationConfig
import com.android.launcher3.touch.AllAppsSwipeController
import com.android.launcher3.touch.CustomViewSwipeController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun Launcher.animateToAllApps() {
    suspendCancellableCoroutine<Unit> { cont ->
        val duration = LauncherState.ALL_APPS.getTransitionDuration(this).toLong()
        val config = StateAnimationConfig()
        AllAppsSwipeController.applyNormalToAllAppsAnimConfig(config)
        config.duration = duration

        val animation = stateManager
            .createAnimationToNewWorkspace(LauncherState.ALL_APPS, config)
        val anim = animation.animationPlayer
        anim.setFloatValues(0f, 1f)
        anim.duration = duration
        anim.interpolator = Interpolators.DEACCEL
        anim.addListener(forEndCallback(Runnable { cont.resume(Unit) }))
        animation.dispatchOnStart()
        anim.start()
        cont.invokeOnCancellation { anim.cancel() }
    }
}
suspend fun Launcher.animateToCustomView() {
    suspendCancellableCoroutine<Unit> { cont ->
        val duration = LauncherState.CUSTOM_VIEW.getTransitionDuration(this).toLong()
        val config = StateAnimationConfig()
        CustomViewSwipeController.applyNormalToCustomViewAnimConfig(config)
        config.duration = duration

        val animation = stateManager
            .createAnimationToNewWorkspace(LauncherState.CUSTOM_VIEW, config)
        val anim = animation.animationPlayer
        anim.setFloatValues(0f, 1f)
        anim.duration = duration
        anim.interpolator = Interpolators.DEACCEL
        anim.addListener(forEndCallback(Runnable { cont.resume(Unit) }))
        animation.dispatchOnStart()
        anim.start()
        cont.invokeOnCancellation { anim.cancel() }
    }
}
