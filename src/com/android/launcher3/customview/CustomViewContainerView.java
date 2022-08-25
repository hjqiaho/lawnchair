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
 *
 * Modifications copyright 2021, Lawnchair
 */
package com.android.launcher3.customview;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.os.UserManager;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.DragSource;
import com.android.launcher3.DropTarget.DragObject;
import com.android.launcher3.Insettable;
import com.android.launcher3.InsettableFrameLayout;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;

import com.android.launcher3.allapps.AllAppsRecyclerView;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.keyboard.FocusedItemDecorator;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.util.ItemInfoMatcher;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.RecyclerViewFastScroller;
import com.android.launcher3.views.ScrimView;

import app.lawnchair.preferences.PreferenceManager;
import app.lawnchair.theme.color.ColorTokens;
import app.lawnchair.ui.StretchRecyclerViewContainer;
import com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip.OnActivePageChangedListener;

/**
 * The all apps view container.
 */
public class CustomViewContainerView extends StretchRecyclerViewContainer implements DragSource,
    Insettable, OnDeviceProfileChangeListener, OnActivePageChangedListener,
    ScrimView.ScrimDrawingController {

    private static final String BUNDLE_KEY_CURRENT_PAGE = "launcher.allapps.current_page";

    public static final float PULL_MULTIPLIER = .02f;
    public static final float FLING_VELOCITY_MULTIPLIER = 1200f;

    private final Paint mHeaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mInsets = new Rect();

    protected final BaseDraggingActivity mLauncher;
    protected final ItemInfoMatcher mPersonalMatcher = ItemInfoMatcher.ofUser(
            Process.myUserHandle());

    private final Paint mNavBarScrimPaint;
    private int mNavBarScrimHeight = 0;



    protected boolean mUsingTabs;
    private boolean mIsSearching;
    private boolean mHasWorkApps;

    protected RecyclerViewFastScroller mTouchHandler;
    protected final Point mFastScrollerOffset = new Point();

    private final boolean mScrimIsTranslucent;
    private final int mScrimColor;
    private final int mHeaderProtectionColor;
    private final float mHeaderThreshold;
    private ScrimView mScrimView;
    private int mHeaderColor;
    private int mTabsProtectionAlpha;
    private int mSearchVerticalOffset;

    public CustomViewContainerView(Context context) {
        this(context, null);
    }

    public CustomViewContainerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomViewContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mLauncher = BaseDraggingActivity.fromContext(context);

        float drawerOpacity = PreferenceManager.getInstance(context).getDrawerOpacity().get();
        mScrimIsTranslucent = drawerOpacity < 1f;

        mScrimColor = R.color.chip_scrim_start_color;
        mHeaderThreshold = getResources().getDimensionPixelSize(
                R.dimen.dynamic_grid_cell_border_spacing);
        mHeaderProtectionColor = ColorTokens.AllAppsHeaderProtectionColor.resolveColor(context);

        mSearchVerticalOffset = getResources().getDimensionPixelSize(R.dimen.all_apps_search_vertical_offset);

        mLauncher.addOnDeviceProfileChangeListener(this);

        mNavBarScrimPaint = new Paint();
        mNavBarScrimPaint.setColor(Themes.getAttrColor(context, R.attr.allAppsNavBarScrimColor));
    }


    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {

    }

    public LayoutInflater getLayoutInflater() {
        return LayoutInflater.from(getContext());
    }


    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // This is a focus listener that proxies focus from a view into the list view.  This is to
        // work around the search box from getting first focus and showing the cursor.

    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }



    @Override
    public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
        if (Utilities.ATLEAST_Q) {
            mNavBarScrimHeight = insets.getTappableElementInsets().bottom;
        } else {
            mNavBarScrimHeight = insets.getStableInsetBottom();
        }
        return super.dispatchApplyWindowInsets(insets);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        super.draw(canvas);

        if (mNavBarScrimHeight > 0) {
            canvas.drawRect(0, getHeight() - mNavBarScrimHeight, getWidth(), getHeight(),
                    mNavBarScrimPaint);
        }
    }


    // Used by tests only
    private boolean isDescendantViewVisible(int viewId) {
        final View view = findViewById(viewId);
        if (view == null) return false;

        if (!view.isShown()) return false;

        return view.getGlobalVisibleRect(new Rect());
    }

    /**
     * Returns whether the view itself will handle the touch event or not.
     */
    public boolean shouldContainerScroll(MotionEvent ev) {
        // IF the MotionEvent is inside the search box, and the container keeps on receiving
        // touch input, container should move down.

        return true;
    }

    /**
     * Adds an update listener to {@param animator} that adds springs to the animation.
     */
    public void addSpringFromFlingUpdateListener(ValueAnimator animator,
            float velocity /* release velocity */,
            float progress /* portion of the distance to travel*/) {
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animator) {
                float distance = (float) ((1 - progress) * getWidth()); // px
                float settleVelocity = Math.min(0, distance
                        / (CustomViewTransitionController.INTERP_COEFF * animator.getDuration())
                        + velocity);
                absorbSwipeUpVelocity(Math.max(1000, Math.abs(
                        Math.round(settleVelocity * FLING_VELOCITY_MULTIPLIER))));
            }
        });
    }

    public void onPull(float deltaDistance, float displacement) {
        absorbPullDeltaDistance(PULL_MULTIPLIER * deltaDistance, PULL_MULTIPLIER * displacement);
        // Current motion spec is to actually push and not pull
        // on this surface. However, until EdgeEffect.onPush (b/190612804) is
        // implemented at view level, we will simply pull
    }

    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        outRect.offset( (int) getTranslationX(),0);
    }

    @Override
    public void setTranslationX(float translationX) {
        super.setTranslationX(translationX);
        invalidateHeader();
    }

    public void setScrimView(ScrimView scrimView) {
        mScrimView = scrimView;
    }
    /**
     * redraws header protection
     */
    public void invalidateHeader() {
        if (mScrimView != null ) {
            mScrimView.invalidate();
        }
    }
    @Override
    public void clipChild(@NonNull Canvas canvas, @NonNull View child) {
        canvas.clipRect(
                child.getLeft(), child.getTop() + mSearchVerticalOffset,
                child.getRight(), child.getBottom());
    }

    @Override
    public void drawOnScrim(Canvas canvas) {
        mHeaderPaint.setColor(mHeaderColor);
        mHeaderPaint.setAlpha((int) (getAlpha() * Color.alpha(mHeaderColor)));
        if (mHeaderPaint.getColor() != mScrimColor && mHeaderPaint.getColor() != 0) {
            int bottom = (int) (getTranslationY());
            canvas.drawRect(0, 0, canvas.getWidth(), bottom, mHeaderPaint);
            int tabsHeight = 0;
            if (mTabsProtectionAlpha > 0 && tabsHeight != 0) {
                mHeaderPaint.setAlpha((int) (getAlpha() * mTabsProtectionAlpha));
                canvas.drawRect(0, bottom, canvas.getWidth(), bottom + tabsHeight, mHeaderPaint);
            }
        }
    }

    @Override
    public void onActivePageChanged(int currentActivePage) {
        reset(true /* animate */);

    }
    /**
     * Resets the state of AllApps.
     */
    public void reset(boolean animate) {

    }

    @Override
    public void onDropCompleted(View target, DragObject d, boolean success) {

    }
    public String getDescription() {
        @StringRes int descriptionRes;
        descriptionRes = R.string.cunstom_view_button_label;
        return getContext().getString(descriptionRes);
    }
    @Override
    public void setInsets(Rect insets) {
        mInsets.set(insets);
        DeviceProfile grid = mLauncher.getDeviceProfile();


        ViewGroup.MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
        mlp.leftMargin = insets.left;
        mlp.rightMargin = insets.right;
        setLayoutParams(mlp);

        if (grid.isVerticalBarLayout()) {
            setPadding(grid.workspacePadding.left, 0, grid.workspacePadding.right, 0);
        } else {
            setPadding(0, 0, 0, 0);
        }

        InsettableFrameLayout.dispatchInsets(this, insets);
    }
}
