/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.launcher3.touch;

import static com.android.launcher3.LauncherState.CUSTOM_VIEW;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_CUSTOM_VIEW_FADE;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_SCRIM_FADE;

import android.view.MotionEvent;
import android.view.animation.Interpolator;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.states.StateAnimationConfig;

/**
 * TouchController to switch between NORMAL and CUSTOM_VIEW state.
 */
public class CustomViewSwipeController extends AbstractStateChangeTouchController {

    private static final float ALLAPPS_STAGGERED_FADE_THRESHOLD = 0.5f;

    public static final Interpolator ALLAPPS_STAGGERED_FADE_EARLY_RESPONDER =
            Interpolators.clampToProgress(LINEAR, 0, ALLAPPS_STAGGERED_FADE_THRESHOLD);
    public static final Interpolator ALLAPPS_STAGGERED_FADE_LATE_RESPONDER =
            Interpolators.clampToProgress(LINEAR, ALLAPPS_STAGGERED_FADE_THRESHOLD, 1f);

    public CustomViewSwipeController(Launcher l) {
        super(l, SingleAxisSwipeDetector.VERTICAL);
    }

    @Override
    protected boolean canInterceptTouch(MotionEvent ev) {
        if (mCurrentAnimation != null) {
            // If we are already animating from a previous state, we can intercept.
            return true;
        }
        if (AbstractFloatingView.getTopOpenView(mLauncher) != null) {
            return false;
        }
        if (!mLauncher.isInState(NORMAL) && !mLauncher.isInState(CUSTOM_VIEW)) {
            // Don't listen for the swipe gesture if we are already in some other state.
            return false;
        }
        if (mLauncher.isInState(CUSTOM_VIEW) && !mLauncher.getAppsView().shouldContainerScroll(ev)) {
            return false;
        }
        return true;
    }

    @Override
    protected LauncherState getTargetState(LauncherState fromState, boolean isDragTowardPositive) {
        if (fromState == NORMAL && isDragTowardPositive) {
            return CUSTOM_VIEW;
        } else if (fromState == CUSTOM_VIEW && !isDragTowardPositive) {
            return NORMAL;
        }
        return fromState;
    }

    @Override
    protected float initCurrentAnimation() {
        float range = mLauncher.getCustomViewController().getShiftRange();
        StateAnimationConfig config = getConfigForStates(mFromState, mToState);
        config.duration = (long) (2 * range);

        mCurrentAnimation = mLauncher.getStateManager()
                .createAnimationToNewWorkspace(mToState, config);
        float startVerticalShift = mFromState.getVerticalProgress(mLauncher) * range;
        float endVerticalShift = mToState.getVerticalProgress(mLauncher) * range;
        float totalShift = endVerticalShift - startVerticalShift;
        return 1 / totalShift;
    }

    @Override
    protected StateAnimationConfig getConfigForStates(LauncherState fromState,
            LauncherState toState) {
        StateAnimationConfig config = super.getConfigForStates(fromState, toState);
        if (fromState == NORMAL && toState == CUSTOM_VIEW) {
            applyNormalToCustomViewAnimConfig(config);
        } else if (fromState == CUSTOM_VIEW && toState == NORMAL) {
            applyCustomViewToNormalConfig(config);
        }
        return config;
    }

    /**
     * Applies Animation config values for transition from all apps to home
     */
    public static void applyCustomViewToNormalConfig(StateAnimationConfig config) {
        config.setInterpolator(ANIM_SCRIM_FADE, ALLAPPS_STAGGERED_FADE_LATE_RESPONDER);
        config.setInterpolator(ANIM_CUSTOM_VIEW_FADE, ALLAPPS_STAGGERED_FADE_EARLY_RESPONDER);
    }

    /**
     * Applies Animation config values for transition from home to all apps
     */
    public static void applyNormalToCustomViewAnimConfig(StateAnimationConfig config) {
        config.setInterpolator(ANIM_SCRIM_FADE, ALLAPPS_STAGGERED_FADE_EARLY_RESPONDER);
        config.setInterpolator(ANIM_CUSTOM_VIEW_FADE, ALLAPPS_STAGGERED_FADE_LATE_RESPONDER);
    }


}
