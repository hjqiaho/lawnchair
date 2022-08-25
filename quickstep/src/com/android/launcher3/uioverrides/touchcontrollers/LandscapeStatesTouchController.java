/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.launcher3.AbstractFloatingView.TYPE_ACCESSIBLE;
import static com.android.launcher3.AbstractFloatingView.TYPE_COMPOSE_VIEW;
import static com.android.launcher3.AbstractFloatingView.getTopOpenViewWithType;
import static com.android.launcher3.LauncherState.CUSTOM_VIEW;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.anim.Interpolators.ACCEL;
import static com.android.launcher3.anim.Interpolators.ACCEL_0_5;
import static com.android.launcher3.anim.Interpolators.DEACCEL;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_CUSTOM_VIEW_FADE;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_SCRIM_FADE;

import android.view.MotionEvent;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.customview.CustomViewTransitionController;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.touch.AbstractStateChangeTouchController;
import com.android.launcher3.touch.SingleAxisSwipeDetector;
import com.android.launcher3.uioverrides.states.OverviewState;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.util.LayoutUtils;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;

/**
 * Touch controller for handling various state transitions in portrait UI.
 */
public class LandscapeStatesTouchController extends AbstractStateChangeTouchController {

    private static final String TAG = "LandscapeStatesTouchCtrl";

    /**
     * The progress at which all apps content will be fully visible.
     */
    public static final float ALL_APPS_CONTENT_FADE_MAX_CLAMPING_THRESHOLD = 0.8f;

    /**
     * Minimum clamping progress for fading in all apps content
     */
    public static final float ALL_APPS_CONTENT_FADE_MIN_CLAMPING_THRESHOLD = 0.5f;

    /**
     * Minimum clamping progress for fading in all apps scrim
     */
    public static final float ALL_APPS_SCRIM_VISIBLE_THRESHOLD = .1f;

    /**
     * Maximum clamping progress for opaque all apps scrim
     */
    public static final float ALL_APPS_SCRIM_OPAQUE_THRESHOLD = .5f;


    public LandscapeStatesTouchController(Launcher l) {
        super(l, SingleAxisSwipeDetector.HORIZONTAL);
    }

    @Override
    protected boolean canInterceptTouch(MotionEvent ev) {
        // If we are swiping to all apps instead of overview, allow it from anywhere.
        boolean interceptAnywhere = mLauncher.isInState(NORMAL);
        if (mCurrentAnimation != null) {
            CustomViewTransitionController allAppsController = mLauncher.getCustomViewController();
            if (ev.getX() >= allAppsController.getShiftRange() * allAppsController.getProgress()
                    || interceptAnywhere) {
                // If we are already animating from a previous state, we can intercept as long as
                // the touch is below the current all apps progress (to allow for double swipe).
                return true;
            }
            // Otherwise, don't intercept so they can scroll recents, dismiss a task, etc.
            return false;
        }
        if (mLauncher.isInState(CUSTOM_VIEW)) {
            // In all-apps only listen if the container cannot scroll itself
            if (!mLauncher.getCustomView().shouldContainerScroll(ev)) {
                return false;
            }
            return true;
        } else {
            // For non-normal states, only listen if the event originated below the hotseat height
            if (!interceptAnywhere && !isTouchOverHotseat(mLauncher, ev)) {
                return false;
            }
        }
        if (getTopOpenViewWithType(mLauncher, TYPE_ACCESSIBLE | TYPE_COMPOSE_VIEW) != null) {
            return false;
        }
        //不是第一页不拦截
        if (mLauncher.getWorkspace().getCurrentPage() != 0) {
            return false;
        }
        return true;
    }

    @Override
    protected LauncherState getTargetState(LauncherState fromState, boolean isDragTowardPositive) {
        if (fromState == CUSTOM_VIEW && !isDragTowardPositive) {
            return NORMAL;
        } else if (fromState == OVERVIEW) {
            return isDragTowardPositive ? OVERVIEW : NORMAL;
        } else if (fromState == NORMAL && isDragTowardPositive) {
            return CUSTOM_VIEW;
        }
        return fromState;
    }

    private StateAnimationConfig getNormalToCustomViewAnimation() {
        StateAnimationConfig builder = new StateAnimationConfig();
//        builder.setInterpolator(ANIM_CUSTOM_VIEW_FADE, Interpolators.clampToProgress(ACCEL_0_5,
//            ALL_APPS_CONTENT_FADE_MIN_CLAMPING_THRESHOLD,
//            ALL_APPS_CONTENT_FADE_MAX_CLAMPING_THRESHOLD));
//        builder.setInterpolator(ANIM_SCRIM_FADE, Interpolators.clampToProgress(ACCEL_0_5,
//            ALL_APPS_SCRIM_VISIBLE_THRESHOLD,
//            ALL_APPS_SCRIM_OPAQUE_THRESHOLD));
        return builder;
    }

    private StateAnimationConfig getCustomViewToNormalAnimation() {
        StateAnimationConfig builder = new StateAnimationConfig();
//        builder.setInterpolator(ANIM_CUSTOM_VIEW_FADE, Interpolators.clampToProgress(DEACCEL,
//            1 - ALL_APPS_CONTENT_FADE_MAX_CLAMPING_THRESHOLD,
//            1 - ALL_APPS_CONTENT_FADE_MIN_CLAMPING_THRESHOLD));
//        builder.setInterpolator(ANIM_SCRIM_FADE, Interpolators.clampToProgress(DEACCEL,
//            1 - ALL_APPS_SCRIM_OPAQUE_THRESHOLD,
//            1 - ALL_APPS_SCRIM_VISIBLE_THRESHOLD));
        return builder;
    }
    @Override
    protected StateAnimationConfig getConfigForStates(
            LauncherState fromState, LauncherState toState) {
        final StateAnimationConfig config;
        if (fromState == NORMAL && toState == CUSTOM_VIEW) {
            config = getNormalToCustomViewAnimation();
        }else if (fromState == CUSTOM_VIEW && toState == NORMAL) {
            config = getCustomViewToNormalAnimation();
        }else {
            config = new StateAnimationConfig();
        }
        return config;
    }

    @Override
    protected float initCurrentAnimation() {
        float range = mLauncher.getCustomViewController().getShiftRange();
        long maxAccuracy = (long) (2 * range);

        float startVerticalShift = mFromState.getVerticalProgress(mLauncher) * range;
        float endVerticalShift = mToState.getVerticalProgress(mLauncher) * range;

        float totalShift = endVerticalShift - startVerticalShift;

        final StateAnimationConfig config = totalShift == 0 ? new StateAnimationConfig()
                : getConfigForStates(mFromState, mToState);
        config.duration = maxAccuracy;

        if (mCurrentAnimation != null) {
            mCurrentAnimation.getTarget().removeListener(mClearStateOnCancelListener);
            mCurrentAnimation.dispatchOnCancel();
        }

        mGoingBetweenStates = true;
        mCurrentAnimation = mLauncher.getStateManager()
            .createAnimationToNewWorkspace(mToState, config);
        mCurrentAnimation.getTarget().addListener(mClearStateOnCancelListener);

        if (totalShift == 0) {
            totalShift = Math.signum(mFromState.ordinal - mToState.ordinal)
                    * OverviewState.getDefaultSwipeWidth(mLauncher);
        }
        return  - (1 / totalShift);
    }

    @Override
    protected void onSwipeInteractionCompleted(LauncherState targetState) {
        super.onSwipeInteractionCompleted(targetState);
        if (mStartState == NORMAL && targetState == OVERVIEW) {
            SystemUiProxy.INSTANCE.get(mLauncher).onOverviewShown(true, TAG);
        }
    }

    /**
     * Whether the motion event is over the hotseat.
     *
     * @param launcher the launcher activity
     * @param ev       the event to check
     * @return true if the event is over the hotseat
     */
    static boolean isTouchOverHotseat(Launcher launcher, MotionEvent ev) {
        return (ev.getY() >= getHotseatTop(launcher));
    }

    public static int getHotseatTop(Launcher launcher) {
        DeviceProfile dp = launcher.getDeviceProfile();
        int hotseatHeight = dp.hotseatBarSizePx + dp.getInsets().bottom;
        return launcher.getDragLayer().getHeight() - hotseatHeight;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                InteractionJankMonitorWrapper.begin(
                        mLauncher.getRootView(), InteractionJankMonitorWrapper.CUJ_OPEN_ALL_APPS);
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                InteractionJankMonitorWrapper.cancel(
                        InteractionJankMonitorWrapper.CUJ_OPEN_ALL_APPS);
                break;
        }
        return super.onControllerInterceptTouchEvent(ev);

    }

    @Override
    protected void onReinitToState(LauncherState newToState) {
        super.onReinitToState(newToState);
        if ( newToState != CUSTOM_VIEW) {
            InteractionJankMonitorWrapper.cancel(InteractionJankMonitorWrapper.CUJ_OPEN_ALL_APPS);
        }
    }

    @Override
    protected void onReachedFinalState(LauncherState toState) {
        super.onReinitToState(toState);
        if (toState == CUSTOM_VIEW) {
            InteractionJankMonitorWrapper.end(InteractionJankMonitorWrapper.CUJ_OPEN_ALL_APPS);
        }
    }

    @Override
    protected void clearState() {
        super.clearState();
        InteractionJankMonitorWrapper.cancel(InteractionJankMonitorWrapper.CUJ_OPEN_ALL_APPS);
    }
}
