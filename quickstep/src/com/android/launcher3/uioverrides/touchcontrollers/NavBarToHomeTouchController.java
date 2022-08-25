/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.uioverrides.touchcontrollers;

import static com.android.launcher3.AbstractFloatingView.TYPE_ALL;
import static com.android.launcher3.AbstractFloatingView.TYPE_ALL_APPS_EDU;
import static com.android.launcher3.LauncherAnimUtils.SUCCESS_TRANSITION_PROGRESS;
import static com.android.launcher3.LauncherAnimUtils.newCancelListener;
import static com.android.launcher3.LauncherState.ALL_APPS;
import static com.android.launcher3.LauncherState.CUSTOM_VIEW;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.allapps.AllAppsTransitionController.ALL_APPS_PROGRESS;
import static com.android.launcher3.anim.AnimatorListeners.forSuccessCallback;
import static com.android.launcher3.anim.Interpolators.DEACCEL_3;
import static com.android.launcher3.config.FeatureFlags.ENABLE_ALL_APPS_EDU;
import static com.android.launcher3.config.FeatureFlags.ENABLE_QUICKSTEP_LIVE_TILE;
import static com.android.launcher3.customview.CustomViewTransitionController.CUSTOM_VIEW_PROGRESS;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_HOME_GESTURE;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_RECENTS;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.animation.Interpolator;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.AllAppsTransitionController;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.compat.AccessibilityManagerCompat;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.customview.CustomViewTransitionController;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.touch.SingleAxisSwipeDetector;
import com.android.launcher3.util.TouchController;
import com.android.quickstep.TaskUtils;
import com.android.quickstep.util.AnimatorControllerWithResistance;
import com.android.quickstep.util.AssistantUtilities;
import com.android.quickstep.util.OverviewToHomeAnim;
import com.android.quickstep.views.RecentsView;

/**
 * Handles swiping up on the nav bar to go home from launcher, e.g. overview or all apps.
 */
public class NavBarToHomeTouchController implements TouchController,
        SingleAxisSwipeDetector.Listener {

    private static final Interpolator PULLBACK_INTERPOLATOR = DEACCEL_3;
    // The min amount of overview scrim we keep during the transition.
    private static final float OVERVIEW_TO_HOME_SCRIM_MULTIPLIER = 0.5f;

    private final Launcher mLauncher;
    private final SingleAxisSwipeDetector mSwipeDetector;
    private final float mPullbackDistance;

    private boolean mNoIntercept;
    private LauncherState mStartState;
    private LauncherState mEndState = NORMAL;
    private AnimatorPlaybackController mCurrentAnimation;

    public NavBarToHomeTouchController(Launcher launcher) {
        mLauncher = launcher;
        mSwipeDetector = new SingleAxisSwipeDetector(mLauncher, this,
                SingleAxisSwipeDetector.VERTICAL);
        mPullbackDistance = mLauncher.getResources().getDimension(R.dimen.home_pullback_distance);
    }

    @Override
    public final boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mStartState = mLauncher.getStateManager().getState();
            mNoIntercept = !canInterceptTouch(ev);
            if (mNoIntercept) {
                return false;
            }
            mSwipeDetector.setDetectableScrollConditions(SingleAxisSwipeDetector.DIRECTION_POSITIVE,
                    false /* ignoreSlop */);
        }

        if (mNoIntercept) {
            return false;
        }

        onControllerTouchEvent(ev);
        return mSwipeDetector.isDraggingOrSettling();
    }

    private boolean canInterceptTouch(MotionEvent ev) {
        boolean cameFromNavBar = (ev.getEdgeFlags() & Utilities.EDGE_NAV_BAR) != 0;
        if (!cameFromNavBar) {
            return false;
        }
        if (mStartState.overviewUi || mStartState == ALL_APPS) {
            return true;
        }
        int typeToClose = ENABLE_ALL_APPS_EDU.get() ? TYPE_ALL & ~TYPE_ALL_APPS_EDU : TYPE_ALL;
        if (AbstractFloatingView.getTopOpenViewWithType(mLauncher, typeToClose) != null) {
            return true;
        }
        if (FeatureFlags.ASSISTANT_GIVES_LAUNCHER_FOCUS.get()
                && AssistantUtilities.isExcludedAssistantRunning()) {
            return true;
        }
        return false;
    }

    @Override
    public final boolean onControllerTouchEvent(MotionEvent ev) {
        return mSwipeDetector.onTouchEvent(ev);
    }

    private float getShiftRange() {
        return mLauncher.getDeviceProfile().heightPx;
    }

    @Override
    public void onDragStart(boolean start, float startDisplacement) {
        initCurrentAnimation();
    }

    private void initCurrentAnimation() {
        long accuracy = (long) (getShiftRange() * 2);
        final PendingAnimation builder = new PendingAnimation(accuracy);
        if (mStartState.overviewUi) {
            RecentsView recentsView = mLauncher.getOverviewPanel();
            AnimatorControllerWithResistance.createRecentsResistanceFromOverviewAnim(mLauncher,
                    builder);

            if (ENABLE_QUICKSTEP_LIVE_TILE.get()) {
                builder.addOnFrameCallback(recentsView::redrawLiveTile);
            }

            AbstractFloatingView.closeOpenContainer(mLauncher, AbstractFloatingView.TYPE_TASK_MENU);
        } else if (mStartState == ALL_APPS) {
            AllAppsTransitionController allAppsController = mLauncher.getAllAppsController();
            builder.setFloat(allAppsController, ALL_APPS_PROGRESS,
                    -mPullbackDistance / allAppsController.getShiftRange(), PULLBACK_INTERPOLATOR);

            // Slightly fade out all apps content to further distinguish from scrolling.
            StateAnimationConfig config = new StateAnimationConfig();
            config.duration = accuracy;
            config.setInterpolator(StateAnimationConfig.ANIM_ALL_APPS_FADE, Interpolators
                    .mapToProgress(PULLBACK_INTERPOLATOR, 0, 0.5f));

            allAppsController.setAlphas(mEndState, config, builder);
        } else if (mStartState == CUSTOM_VIEW) {
            CustomViewTransitionController customViewController = mLauncher.getCustomViewController();
            builder.setFloat(customViewController, CUSTOM_VIEW_PROGRESS,
                -mPullbackDistance / customViewController.getShiftRange(), PULLBACK_INTERPOLATOR);

            // Slightly fade out all apps content to further distinguish from scrolling.
            StateAnimationConfig config = new StateAnimationConfig();
            config.duration = accuracy;
            config.setInterpolator(StateAnimationConfig.ANIM_CUSTOM_VIEW_FADE, Interpolators
                .mapToProgress(PULLBACK_INTERPOLATOR, 0, 0.5f));

            customViewController.setAlphas(mEndState, config, builder);
        }
        AbstractFloatingView topView = AbstractFloatingView.getTopOpenView(mLauncher);
        if (topView != null) {
            topView.addHintCloseAnim(mPullbackDistance, PULLBACK_INTERPOLATOR, builder);
        }
        mCurrentAnimation = builder.createPlaybackController();
        mCurrentAnimation.getTarget().addListener(newCancelListener(this::clearState));
    }

    private void clearState() {
        mCurrentAnimation = null;
        mSwipeDetector.finishedScrolling();
        mSwipeDetector.setDetectableScrollConditions(0, false);
    }

    @Override
    public boolean onDrag(float displacement) {
        // Only allow swipe up.
        displacement = Math.min(0, displacement);
        float progress = Utilities.getProgress(displacement, 0, getShiftRange());
        mCurrentAnimation.setPlayFraction(progress);
        return true;
    }

    @Override
    public void onDragEnd(float velocity) {
        boolean fling = mSwipeDetector.isFling(velocity);
        float progress = mCurrentAnimation.getProgressFraction();
        float interpolatedProgress = PULLBACK_INTERPOLATOR.getInterpolation(progress);
        boolean success = interpolatedProgress >= SUCCESS_TRANSITION_PROGRESS
                || (velocity < 0 && fling);
        if (success) {
            if (ENABLE_QUICKSTEP_LIVE_TILE.get()) {
                RecentsView recentsView = mLauncher.getOverviewPanel();
                recentsView.switchToScreenshot(null,
                        () -> recentsView.finishRecentsAnimation(true /* toRecents */, null));
            }
            if (mStartState.overviewUi) {
                new OverviewToHomeAnim(mLauncher, () -> onSwipeInteractionCompleted(mEndState))
                        .animateWithVelocity(velocity);
            } else {
                mLauncher.getStateManager().goToState(mEndState, true,
                        forSuccessCallback(() -> onSwipeInteractionCompleted(mEndState)));
            }
            if (mStartState != mEndState) {
                logHomeGesture();
            }
            AbstractFloatingView topOpenView = AbstractFloatingView.getTopOpenView(mLauncher);
            if (topOpenView != null) {
                AbstractFloatingView.closeAllOpenViews(mLauncher);
                // TODO: add to WW log
            }
            TaskUtils.closeSystemWindowsAsync(CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);
        } else {
            // Quickly return to the state we came from (we didn't move far).
            ValueAnimator anim = mCurrentAnimation.getAnimationPlayer();
            anim.setFloatValues(progress, 0);
            anim.addListener(forSuccessCallback(() -> onSwipeInteractionCompleted(mStartState)));
            anim.setDuration(80).start();
        }
    }

    private void onSwipeInteractionCompleted(LauncherState targetState) {
        clearState();
        mLauncher.getStateManager().goToState(targetState, false /* animated */);
        AccessibilityManagerCompat.sendStateEventToTest(mLauncher, targetState.ordinal);
    }

    private void logHomeGesture() {
        mLauncher.getStatsLogManager().logger()
                .withSrcState(mStartState.statsLogOrdinal)
                .withDstState(mEndState.statsLogOrdinal)
                .log(LAUNCHER_HOME_GESTURE);
    }
}
