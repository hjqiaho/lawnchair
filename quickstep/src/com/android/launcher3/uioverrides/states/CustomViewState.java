/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.uioverrides.states;

import static com.android.launcher3.anim.Interpolators.DEACCEL;
import static com.android.launcher3.anim.Interpolators.DEACCEL_1_5;
import static com.android.launcher3.anim.Interpolators.DEACCEL_2;
import static com.android.launcher3.anim.Interpolators.DEACCEL_3;
import static com.android.launcher3.logging.StatsLogManager.LAUNCHER_STATE_CUSTOMVIEW;

import android.content.Context;
import android.graphics.Rect;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.customview.CustomViewContainerView;
import com.android.quickstep.views.RecentsView;

import app.lawnchair.util.LawnchairUtilsKt;

/**
 * Definition for CustomView state
 */
public class CustomViewState extends LauncherState {

    private static final int STATE_FLAGS = FLAG_WORKSPACE_INACCESSIBLE | FLAG_CLOSE_POPUPS;

    private static final PageAlphaProvider PAGE_ALPHA_PROVIDER = new PageAlphaProvider(DEACCEL_2) {
        @Override
        public float getPageAlpha(int pageIndex) {
            return 0;
        }
    };

    public CustomViewState(int id) {
        super(id, LAUNCHER_STATE_CUSTOMVIEW, STATE_FLAGS);
    }

    @Override
    public int getTransitionDuration(Context context) {
        return 320;
    }

    @Override
    public String getDescription(Launcher launcher) {
        CustomViewContainerView customView = launcher.getCustomView();
        return customView.getDescription();
    }

    @Override
    public float getVerticalProgress(Launcher launcher) {
        return 0f;
    }

    @Override
    public ScaleAndTranslation getWorkspaceScaleAndTranslation(Launcher launcher) {
        ScaleAndTranslation scaleAndTranslation = LauncherState.OVERVIEW
                .getWorkspaceScaleAndTranslation(launcher);
        scaleAndTranslation.scale = 1;
        scaleAndTranslation.translationY = 0;

        float parallaxFactor = 0.5f;
        scaleAndTranslation.translationX = OverviewState.getDefaultSwipeWidth(launcher) * parallaxFactor;
        return scaleAndTranslation;
    }

    @Override
    public ScaleAndTranslation getHotseatScaleAndTranslation(Launcher launcher) {
        ScaleAndTranslation scaleAndTranslation = super.getHotseatScaleAndTranslation(launcher);
        scaleAndTranslation.translationY = 0;

        float parallaxFactor = 0.5f;
        scaleAndTranslation.translationX = OverviewState.getDefaultSwipeWidth(launcher) * parallaxFactor;
        return scaleAndTranslation;
    }

    @Override
    public boolean isTaskbarStashed(Launcher launcher) {
        return true;
    }

    @Override
    protected float getDepthUnchecked(Context context) {
        // The scrim fades in at approximately 50% of the swipe gesture.
        // This means that the depth should be greater than 1, in order to fully zoom out.
        return 2f;
    }

    @Override
    public PageAlphaProvider getWorkspacePageAlphaProvider(Launcher launcher) {
        return PAGE_ALPHA_PROVIDER;
    }

    @Override
    public int getVisibleElements(Launcher launcher) {
        return CUSTOM_VIEW_CONTENT;
    }

    @Override
    public LauncherState getHistoryForState(LauncherState previousState) {
        return previousState == OVERVIEW ? OVERVIEW : NORMAL;
    }

    @Override
    public int getWorkspaceScrimColor(Launcher launcher) {
        return LawnchairUtilsKt.getCustomViewScrimColor(launcher);
    }
}
