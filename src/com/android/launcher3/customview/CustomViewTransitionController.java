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
package com.android.launcher3.customview;

import static com.android.launcher3.LauncherState.CUSTOM_VIEW;
import static com.android.launcher3.LauncherState.CUSTOM_VIEW_CONTENT;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.anim.Interpolators.DEACCEL_1_7;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.launcher3.anim.PropertySetter.NO_ANIM_PROPERTY_SETTER;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_CUSTOM_VIEW_FADE;
import static com.android.launcher3.states.StateAnimationConfig.ANIM_VERTICAL_PROGRESS;
import static com.android.launcher3.util.SystemUiController.UI_CUSTOM_VIEW;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ObjectAnimator;
import android.util.FloatProperty;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimatorListeners;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.anim.PropertySetter;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.statemanager.StateManager.StateHandler;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.views.ScrimView;

/**
 * Handles Custom view transition.
 * 1) Slides Custom view using direct manipulation
 * 2) When finger is released, animate to either top or bottom accordingly.
 * <p/>
 * Algorithm:
 * If release velocity > THRES1, snap according to the direction of movement.
 * If release velocity < THRES1, snap according to either top or bottom depending on whether it's
 * closer to top or closer to the page indicator.
 */
public class CustomViewTransitionController
        implements StateHandler<LauncherState>, OnDeviceProfileChangeListener {
    // This constant should match the second derivative of the animator interpolator.
    public static final float INTERP_COEFF = 1.7f;

    public static final FloatProperty<CustomViewTransitionController> CUSTOM_VIEW_PROGRESS =
            new FloatProperty<CustomViewTransitionController>("customViewProgress") {

                @Override
                public Float get(CustomViewTransitionController controller) {
                    return controller.mProgress;
                }

                @Override
                public void setValue(CustomViewTransitionController controller, float progress) {
                    controller.setProgress(progress);
                }
            };

    private CustomViewContainerView mCustomView;

    private final Launcher mLauncher;
    private boolean mIsVerticalLayout;

    // Animation in this class is controlled by a single variable {@link mProgress}.
    // Visually, it represents top y coordinate of the Custom view container if multiplied with
    // {@link mShiftRange}.

    // When {@link mProgress} is 0, Custom view container is pulled up.
    // When {@link mProgress} is 1, Custom view container is pulled down.
    private float mShiftRange;      // changes depending on the orientation
    private float mProgress;        // [0, 1], mShiftRange * mProgress = shiftCurrent

    private float mScrollRangeDelta = 0;
    private ScrimView mScrimView;

    public CustomViewTransitionController(Launcher l) {
        mLauncher = l;
        mShiftRange =  - mLauncher.getDeviceProfile().widthPx;
        mProgress = 1f;

        mIsVerticalLayout = mLauncher.getDeviceProfile().isVerticalBarLayout();
        mLauncher.addOnDeviceProfileChangeListener(this);
    }

    public float getShiftRange() {
        return  - mShiftRange;
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        mIsVerticalLayout = dp.isVerticalBarLayout();
        setScrollRangeDelta(mScrollRangeDelta);

        if (mIsVerticalLayout) {
            mLauncher.getHotseat().setTranslationY(0);
            mLauncher.getWorkspace().getPageIndicator().setTranslationY(0);
        }
    }

    /**
     * Note this method should not be called outside this class. This is public because it is used
     * in xml-based animations which also handle updating the appropriate UI.
     *
     * @param progress value between 0 and 1, 0 shows Custom view and 1 shows workspace
     * @see #setState(LauncherState)
     * @see #setStateWithAnimation(LauncherState, StateAnimationConfig, PendingAnimation)
     */
    public void setProgress(float progress) {
        mProgress = progress;
        mCustomView.setTranslationX(mProgress * mShiftRange);
    }

    public float getProgress() {
        return mProgress;
    }

    /**
     * Sets the vertical transition progress to {@param state} and updates all the dependent UI
     * accordingly.
     */
    @Override
    public void setState(LauncherState state) {
        setProgress(state.getVerticalProgress(mLauncher));
        setAlphas(state, new StateAnimationConfig(), NO_ANIM_PROPERTY_SETTER);
        onProgressAnimationEnd();
    }

    /**
     * Creates an animation which updates the vertical transition progress and updates all the
     * dependent UI using various animation events
     */
    @Override
    public void setStateWithAnimation(LauncherState toState,
            StateAnimationConfig config, PendingAnimation builder) {
        float targetProgress = toState.getVerticalProgress(mLauncher);
        if (Float.compare(mProgress, targetProgress) == 0) {
            setAlphas(toState, config, builder);
            // Fail fast
            onProgressAnimationEnd();
            return;
        }

        // need to decide depending on the release velocity
        Interpolator interpolator = (config.userControlled ? LINEAR : DEACCEL_1_7);

        Animator anim = createSpringAnimation(mProgress, targetProgress);
        anim.setInterpolator(config.getInterpolator(ANIM_VERTICAL_PROGRESS, interpolator));
        anim.addListener(getProgressAnimatorListener());
        builder.add(anim);

        setAlphas(toState, config, builder);

        if (CUSTOM_VIEW.equals(toState) && mLauncher.isInState(NORMAL)) {
            mLauncher.getAppsView().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
    }

    public Animator createSpringAnimation(float... progressValues) {
        return ObjectAnimator.ofFloat(this, CUSTOM_VIEW_PROGRESS, progressValues);
    }

    /**
     * Updates the property for the provided state
     */
    public void setAlphas(LauncherState state, StateAnimationConfig config, PropertySetter setter) {
        int visibleElements = state.getVisibleElements(mLauncher);
        boolean hasAllAppsContent = (visibleElements & CUSTOM_VIEW_CONTENT) != 0;

        Interpolator customViewFade = config.getInterpolator(ANIM_CUSTOM_VIEW_FADE, LINEAR);
        setter.setViewAlpha(mCustomView, hasAllAppsContent ? 1 : 0, customViewFade);

        boolean shouldProtectHeader =
            CUSTOM_VIEW == state || mLauncher.getStateManager().getState() == CUSTOM_VIEW;
        mScrimView.setDrawingController(shouldProtectHeader ? mCustomView : null);
    }

    public AnimatorListener getProgressAnimatorListener() {
        return AnimatorListeners.forSuccessCallback(this::onProgressAnimationEnd);
    }

    /**
     * see Launcher#setupViews
     */
    public void setupViews(ScrimView scrimView, CustomViewContainerView customView) {
        mScrimView = scrimView;
        mCustomView = customView;
        if (FeatureFlags.ENABLE_DEVICE_SEARCH.get() && Utilities.ATLEAST_R) {
            mLauncher.getSystemUiController().updateUiState(UI_CUSTOM_VIEW,
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        mCustomView.setScrimView(scrimView);
    }

    /**
     * Updates the total scroll range but does not update the UI.
     */
    public void setScrollRangeDelta(float delta) {
        mScrollRangeDelta = delta;
        mShiftRange =  - mLauncher.getDeviceProfile().widthPx + mScrollRangeDelta;
    }

    /**
     * Set the final view states based on the progress.
     * TODO: This logic should go in {@link LauncherState}
     */
    private void onProgressAnimationEnd() {
        if (FeatureFlags.ENABLE_DEVICE_SEARCH.get()) return;
        if (Float.compare(mProgress, 1f) == 0) {
            mCustomView.reset(false /* animate */);
        }
    }
}
