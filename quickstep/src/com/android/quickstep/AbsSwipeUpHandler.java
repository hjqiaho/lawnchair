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
package com.android.quickstep;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.widget.Toast.LENGTH_SHORT;

import static com.android.launcher3.BaseActivity.INVISIBLE_BY_STATE_HANDLER;
import static com.android.launcher3.BaseActivity.STATE_HANDLER_INVISIBILITY_FLAGS;
import static com.android.launcher3.QuickstepAppTransitionManagerImpl.RECENTS_LAUNCH_DURATION;
import static com.android.launcher3.anim.Interpolators.ACCEL_DEACCEL;
import static com.android.launcher3.anim.Interpolators.DEACCEL;
import static com.android.launcher3.anim.Interpolators.OVERSHOOT_1_2;
import static com.android.launcher3.config.FeatureFlags.ENABLE_QUICKSTEP_LIVE_TILE;
import static com.android.launcher3.logging.StatsLogManager.LAUNCHER_STATE_BACKGROUND;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.IGNORE;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_HOME_GESTURE;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_OVERVIEW_GESTURE;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_QUICKSWITCH_LEFT;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_QUICKSWITCH_RIGHT;
import static com.android.launcher3.util.DisplayController.getSingleFrameMs;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.launcher3.util.SystemUiController.UI_STATE_OVERVIEW;
import static com.android.launcher3.util.VibratorWrapper.OVERVIEW_HAPTIC;
import static com.android.quickstep.GestureState.GestureEndTarget.HOME;
import static com.android.quickstep.GestureState.GestureEndTarget.LAST_TASK;
import static com.android.quickstep.GestureState.GestureEndTarget.NEW_TASK;
import static com.android.quickstep.GestureState.GestureEndTarget.RECENTS;
import static com.android.quickstep.GestureState.STATE_END_TARGET_ANIMATION_FINISHED;
import static com.android.quickstep.GestureState.STATE_END_TARGET_SET;
import static com.android.quickstep.GestureState.STATE_RECENTS_SCROLLING_FINISHED;
import static com.android.quickstep.MultiStateCallback.DEBUG_STATES;
import static com.android.quickstep.views.RecentsView.UPDATE_SYSUI_FLAGS_THRESHOLD;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.ACTIVITY_TYPE_HOME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnApplyWindowInsetsListener;
import android.view.ViewTreeObserver.OnDrawListener;
import android.view.WindowInsets;
import android.view.animation.Interpolator;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.logging.StatsLogManager;
import com.android.launcher3.logging.StatsLogManager.StatsLogger;
import com.android.launcher3.statemanager.StatefulActivity;
import com.android.launcher3.tracing.InputConsumerProto;
import com.android.launcher3.tracing.SwipeHandlerProto;
import com.android.launcher3.util.TraceHelper;
import com.android.launcher3.util.VibratorWrapper;
import com.android.launcher3.util.WindowBounds;
import com.android.quickstep.BaseActivityInterface.AnimationFactory;
import com.android.quickstep.GestureState.GestureEndTarget;
import com.android.quickstep.inputconsumers.OverviewInputConsumer;
import com.android.quickstep.util.ActiveGestureLog;
import com.android.quickstep.util.ActivityInitListener;
import com.android.quickstep.util.AnimatorControllerWithResistance;
import com.android.quickstep.util.InputConsumerProxy;
import com.android.quickstep.util.MotionPauseDetector;
import com.android.quickstep.util.MultiValueUpdateListener;
import com.android.quickstep.util.ProtoTracer;
import com.android.quickstep.util.RecentsOrientedState;
import com.android.quickstep.util.RectFSpringAnim;
import com.android.quickstep.util.SurfaceTransactionApplier;
import com.android.quickstep.util.SwipePipToHomeAnimator;
import com.android.quickstep.util.TransformParams;
import com.android.quickstep.views.LiveTileOverlay;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;
import com.android.systemui.shared.system.LatencyTrackerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;
import com.android.systemui.shared.system.SyncRtSurfaceTransactionApplierCompat.SurfaceParams;
import com.android.systemui.shared.system.TaskInfoCompat;
import com.android.systemui.shared.system.TaskStackChangeListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Handles the navigation gestures when Launcher is the default home activity.
 */
@TargetApi(Build.VERSION_CODES.R)
public abstract class AbsSwipeUpHandler<T extends StatefulActivity<?>, Q extends RecentsView>
        extends SwipeUpAnimationLogic implements OnApplyWindowInsetsListener,
        RecentsAnimationCallbacks.RecentsAnimationListener {
    private static final String TAG = "AbsSwipeUpHandler";

    private static final String[] STATE_NAMES = DEBUG_STATES ? new String[17] : null;

    protected final BaseActivityInterface<?, T> mActivityInterface;
    protected final InputConsumerProxy mInputConsumerProxy;
    protected final ActivityInitListener mActivityInitListener;
    // Callbacks to be made once the recents animation starts
    private final ArrayList<Runnable> mRecentsAnimationStartCallbacks = new ArrayList<>();
    protected RecentsAnimationController mRecentsAnimationController;
    protected RecentsAnimationTargets mRecentsAnimationTargets;
    protected T mActivity;
    protected Q mRecentsView;
    protected Runnable mGestureEndCallback;
    protected MultiStateCallback mStateCallback;
    protected boolean mCanceled;
    private boolean mRecentsViewScrollLinked = false;

    private static int getFlagForIndex(int index, String name) {
        if (DEBUG_STATES) {
            STATE_NAMES[index] = name;
        }
        return 1 << index;
    }

    // Launcher UI related states
    protected static final int STATE_LAUNCHER_PRESENT =
            getFlagForIndex(0, "STATE_LAUNCHER_PRESENT");
    protected static final int STATE_LAUNCHER_STARTED =
            getFlagForIndex(1, "STATE_LAUNCHER_STARTED");
    protected static final int STATE_LAUNCHER_DRAWN = getFlagForIndex(2, "STATE_LAUNCHER_DRAWN");

    // Internal initialization states
    private static final int STATE_APP_CONTROLLER_RECEIVED =
            getFlagForIndex(3, "STATE_APP_CONTROLLER_RECEIVED");

    // Interaction finish states
    private static final int STATE_SCALED_CONTROLLER_HOME =
            getFlagForIndex(4, "STATE_SCALED_CONTROLLER_HOME");
    private static final int STATE_SCALED_CONTROLLER_RECENTS =
            getFlagForIndex(5, "STATE_SCALED_CONTROLLER_RECENTS");

    protected static final int STATE_HANDLER_INVALIDATED =
            getFlagForIndex(6, "STATE_HANDLER_INVALIDATED");
    private static final int STATE_GESTURE_STARTED =
            getFlagForIndex(7, "STATE_GESTURE_STARTED");
    private static final int STATE_GESTURE_CANCELLED =
            getFlagForIndex(8, "STATE_GESTURE_CANCELLED");
    private static final int STATE_GESTURE_COMPLETED =
            getFlagForIndex(9, "STATE_GESTURE_COMPLETED");

    private static final int STATE_CAPTURE_SCREENSHOT =
            getFlagForIndex(10, "STATE_CAPTURE_SCREENSHOT");
    protected static final int STATE_SCREENSHOT_CAPTURED =
            getFlagForIndex(11, "STATE_SCREENSHOT_CAPTURED");
    private static final int STATE_SCREENSHOT_VIEW_SHOWN =
            getFlagForIndex(12, "STATE_SCREENSHOT_VIEW_SHOWN");

    private static final int STATE_RESUME_LAST_TASK =
            getFlagForIndex(13, "STATE_RESUME_LAST_TASK");
    private static final int STATE_START_NEW_TASK =
            getFlagForIndex(14, "STATE_START_NEW_TASK");
    private static final int STATE_CURRENT_TASK_FINISHED =
            getFlagForIndex(15, "STATE_CURRENT_TASK_FINISHED");
    private static final int STATE_FINISH_WITH_NO_END =
            getFlagForIndex(16, "STATE_FINISH_WITH_NO_END");

    private static final int LAUNCHER_UI_STATES =
            STATE_LAUNCHER_PRESENT | STATE_LAUNCHER_DRAWN | STATE_LAUNCHER_STARTED;

    public static final long MAX_SWIPE_DURATION = 350;
    public static final long MIN_OVERSHOOT_DURATION = 120;

    public static final float MIN_PROGRESS_FOR_OVERVIEW = 0.7f;
    private static final float SWIPE_DURATION_MULTIPLIER =
            Math.min(1 / MIN_PROGRESS_FOR_OVERVIEW, 1 / (1 - MIN_PROGRESS_FOR_OVERVIEW));
    private static final String SCREENSHOT_CAPTURED_EVT = "ScreenshotCaptured";

    public static final long RECENTS_ATTACH_DURATION = 300;

    /**
     * Used as the page index for logging when we return to the last task at the end of the gesture.
     */
    private static final int LOG_NO_OP_PAGE_INDEX = -1;

    protected final TaskAnimationManager mTaskAnimationManager;

    // Either RectFSpringAnim (if animating home) or ObjectAnimator (from mCurrentShift) otherwise
    private RunningWindowAnim mRunningWindowAnim;
    private boolean mIsMotionPaused;
    private boolean mHasMotionEverBeenPaused;

    private boolean mContinuingLastGesture;

    private ThumbnailData mTaskSnapshot;

    // Used to control launcher components throughout the swipe gesture.
    private AnimatorControllerWithResistance mLauncherTransitionController;

    private AnimationFactory mAnimationFactory = (t) -> { };

    private boolean mWasLauncherAlreadyVisible;

    private boolean mPassedOverviewThreshold;
    private boolean mGestureStarted;
    private boolean mLogDirectionUpOrLeft = true;
    private PointF mDownPos;
    private boolean mIsLikelyToStartNewTask;

    private final long mTouchTimeMs;
    private long mLauncherFrameDrawnTime;

    private final Runnable mOnDeferredActivityLaunch = this::onDeferredActivityLaunch;

    private static final long SWIPE_PIP_TO_HOME_DURATION = 425;
    private SwipePipToHomeAnimator mSwipePipToHomeAnimator;
    protected boolean mIsSwipingPipToHome;

    public AbsSwipeUpHandler(Context context, RecentsAnimationDeviceState deviceState,
            TaskAnimationManager taskAnimationManager, GestureState gestureState,
            long touchTimeMs, boolean continuingLastGesture,
            InputConsumerController inputConsumer) {
        super(context, deviceState, gestureState, new TransformParams());
        mActivityInterface = gestureState.getActivityInterface();
        mActivityInitListener = mActivityInterface.createActivityInitListener(this::onActivityInit);
        mInputConsumerProxy =
                new InputConsumerProxy(inputConsumer, this::createNewInputProxyHandler);
        mTaskAnimationManager = taskAnimationManager;
        mTouchTimeMs = touchTimeMs;
        mContinuingLastGesture = continuingLastGesture;

        initAfterSubclassConstructor();
        initStateCallbacks();
    }

    private void initStateCallbacks() {
        mStateCallback = new MultiStateCallback(STATE_NAMES);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_GESTURE_STARTED,
                this::onLauncherPresentAndGestureStarted);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_DRAWN | STATE_GESTURE_STARTED,
                this::initializeLauncherAnimationController);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_LAUNCHER_DRAWN,
                this::launcherFrameDrawn);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_LAUNCHER_STARTED
                        | STATE_GESTURE_CANCELLED,
                this::resetStateForAnimationCancel);

        mStateCallback.runOnceAtState(STATE_RESUME_LAST_TASK | STATE_APP_CONTROLLER_RECEIVED,
                this::resumeLastTask);
        mStateCallback.runOnceAtState(STATE_START_NEW_TASK | STATE_SCREENSHOT_CAPTURED,
                this::startNewTask);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_APP_CONTROLLER_RECEIVED
                        | STATE_LAUNCHER_DRAWN | STATE_CAPTURE_SCREENSHOT,
                this::switchToScreenshot);

        mStateCallback.runOnceAtState(STATE_SCREENSHOT_CAPTURED | STATE_GESTURE_COMPLETED
                        | STATE_SCALED_CONTROLLER_RECENTS,
                this::finishCurrentTransitionToRecents);

        mStateCallback.runOnceAtState(STATE_SCREENSHOT_CAPTURED | STATE_GESTURE_COMPLETED
                        | STATE_SCALED_CONTROLLER_HOME,
                this::finishCurrentTransitionToHome);
        mStateCallback.runOnceAtState(STATE_SCALED_CONTROLLER_HOME | STATE_CURRENT_TASK_FINISHED,
                this::reset);

        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_APP_CONTROLLER_RECEIVED
                        | STATE_LAUNCHER_DRAWN | STATE_SCALED_CONTROLLER_RECENTS
                        | STATE_CURRENT_TASK_FINISHED | STATE_GESTURE_COMPLETED
                        | STATE_GESTURE_STARTED,
                this::setupLauncherUiAfterSwipeUpToRecentsAnimation);

        mGestureState.runOnceAtState(STATE_END_TARGET_ANIMATION_FINISHED,
                this::continueComputingRecentsScrollIfNecessary);
        mGestureState.runOnceAtState(STATE_END_TARGET_ANIMATION_FINISHED
                        | STATE_RECENTS_SCROLLING_FINISHED,
                this::onSettledOnEndTarget);

        mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED, this::invalidateHandler);
        mStateCallback.runOnceAtState(STATE_LAUNCHER_PRESENT | STATE_HANDLER_INVALIDATED,
                this::invalidateHandlerWithLauncher);
        mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED | STATE_RESUME_LAST_TASK,
                this::notifyTransitionCancelled);
        mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED | STATE_FINISH_WITH_NO_END,
                this::notifyTransitionCancelled);

        if (!ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mStateCallback.addChangeListener(STATE_APP_CONTROLLER_RECEIVED | STATE_LAUNCHER_PRESENT
                            | STATE_SCREENSHOT_VIEW_SHOWN | STATE_CAPTURE_SCREENSHOT,
                    (b) -> mRecentsView.setRunningTaskHidden(!b));
        }
    }

    protected boolean onActivityInit(Boolean alreadyOnHome) {
        T createdActivity = mActivityInterface.getCreatedActivity();
        if (createdActivity != null) {
            initTransitionEndpoints(createdActivity.getDeviceProfile());
        }
        final T activity = mActivityInterface.getCreatedActivity();
        if (mActivity == activity) {
            return true;
        }

        if (mActivity != null) {
            // The launcher may have been recreated as a result of device rotation.
            int oldState = mStateCallback.getState() & ~LAUNCHER_UI_STATES;
            initStateCallbacks();
            mStateCallback.setState(oldState);
        }
        mWasLauncherAlreadyVisible = alreadyOnHome;
        mActivity = activity;
        // Override the visibility of the activity until the gesture actually starts and we swipe
        // up, or until we transition home and the home animation is composed
        if (alreadyOnHome) {
            mActivity.clearForceInvisibleFlag(STATE_HANDLER_INVISIBILITY_FLAGS);
        } else {
            mActivity.addForceInvisibleFlag(STATE_HANDLER_INVISIBILITY_FLAGS);
        }

        mRecentsView = activity.getOverviewPanel();
        mRecentsView.setOnPageTransitionEndCallback(null);
        addLiveTileOverlay();

        mStateCallback.setState(STATE_LAUNCHER_PRESENT);
        if (alreadyOnHome) {
            onLauncherStart();
        } else {
            activity.runOnceOnStart(this::onLauncherStart);
        }

        setupRecentsViewUi();
        linkRecentsViewScroll();

        return true;
    }

    /**
     * Return true if the window should be translated horizontally if the recents view scrolls
     */
    protected boolean moveWindowWithRecentsScroll() {
        return mGestureState.getEndTarget() != HOME;
    }

    private void onLauncherStart() {
        final T activity = mActivityInterface.getCreatedActivity();
        if (mActivity != activity) {
            return;
        }
        if (mStateCallback.hasStates(STATE_HANDLER_INVALIDATED)) {
            return;
        }
        mTaskViewSimulator.setOrientationState(mRecentsView.getPagedViewOrientedState());

        // If we've already ended the gesture and are going home, don't prepare recents UI,
        // as that will set the state as BACKGROUND_APP, overriding the animation to NORMAL.
        if (mGestureState.getEndTarget() != HOME) {
            Runnable initAnimFactory = () -> {
                mAnimationFactory = mActivityInterface.prepareRecentsUI(mDeviceState,
                        mWasLauncherAlreadyVisible, this::onAnimatorPlaybackControllerCreated);
                maybeUpdateRecentsAttachedState(false /* animate */);
            };
            if (mWasLauncherAlreadyVisible) {
                // Launcher is visible, but might be about to stop. Thus, if we prepare recents
                // now, it might get overridden by moveToRestState() in onStop(). To avoid this,
                // wait until the next gesture (and possibly launcher) starts.
                mStateCallback.runOnceAtState(STATE_GESTURE_STARTED, initAnimFactory);
            } else {
                initAnimFactory.run();
            }
        }
        AbstractFloatingView.closeAllOpenViewsExcept(activity, mWasLauncherAlreadyVisible,
                AbstractFloatingView.TYPE_LISTENER);

        if (mWasLauncherAlreadyVisible) {
            mStateCallback.setState(STATE_LAUNCHER_DRAWN);
        } else {
            Object traceToken = TraceHelper.INSTANCE.beginSection("WTS-init");
            View dragLayer = activity.getDragLayer();
            dragLayer.getViewTreeObserver().addOnDrawListener(new OnDrawListener() {
                boolean mHandled = false;

                @Override
                public void onDraw() {
                    if (mHandled) {
                        return;
                    }
                    mHandled = true;

                    TraceHelper.INSTANCE.endSection(traceToken);
                    dragLayer.post(() ->
                            dragLayer.getViewTreeObserver().removeOnDrawListener(this));
                    if (activity != mActivity) {
                        return;
                    }

                    mStateCallback.setState(STATE_LAUNCHER_DRAWN);
                }
            });
        }

        activity.getRootView().setOnApplyWindowInsetsListener(this);
        mStateCallback.setState(STATE_LAUNCHER_STARTED);
    }

    private void onLauncherPresentAndGestureStarted() {
        // Re-setup the recents UI when gesture starts, as the state could have been changed during
        // that time by a previous window transition.
        setupRecentsViewUi();

        // For the duration of the gesture, in cases where an activity is launched while the
        // activity is not yet resumed, finish the animation to ensure we get resumed
        mGestureState.getActivityInterface().setOnDeferredActivityLaunchCallback(
                mOnDeferredActivityLaunch);

        mGestureState.runOnceAtState(STATE_END_TARGET_SET,
                () -> {
                    mDeviceState.getRotationTouchHelper()
                            .onEndTargetCalculated(mGestureState.getEndTarget(),
                                    mActivityInterface);

                    mRecentsView.onGestureEndTargetCalculated(mGestureState.getEndTarget());
                });

        notifyGestureStartedAsync();
    }

    private void onDeferredActivityLaunch() {
        if (ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mActivityInterface.switchRunningTaskViewToScreenshot(
                    null, () -> {
                        mTaskAnimationManager.finishRunningRecentsAnimation(true /* toHome */);
                    });
        } else {
            mTaskAnimationManager.finishRunningRecentsAnimation(true /* toHome */);
        }
    }

    private void setupRecentsViewUi() {
        if (mContinuingLastGesture) {
            updateSysUiFlags(mCurrentShift.value);
            return;
        }
        notifyGestureAnimationStartToRecents();
    }

    protected void notifyGestureAnimationStartToRecents() {
        mRecentsView.onGestureAnimationStart(mGestureState.getRunningTask());
    }

    private void launcherFrameDrawn() {
        mLauncherFrameDrawnTime = SystemClock.uptimeMillis();
    }

    private void initializeLauncherAnimationController() {
        buildAnimationController();

        Object traceToken = TraceHelper.INSTANCE.beginSection("logToggleRecents",
                TraceHelper.FLAG_IGNORE_BINDERS);
        LatencyTrackerCompat.logToggleRecents(
                mContext, (int) (mLauncherFrameDrawnTime - mTouchTimeMs));
        TraceHelper.INSTANCE.endSection(traceToken);

        // This method is only called when STATE_GESTURE_STARTED is set, so we can enable the
        // high-res thumbnail loader here once we are sure that we will end up in an overview state
        RecentsModel.INSTANCE.get(mContext).getThumbnailCache()
                .getHighResLoadingState().setVisible(true);
    }

    public MotionPauseDetector.OnMotionPauseListener getMotionPauseListener() {
        return new MotionPauseDetector.OnMotionPauseListener() {
            @Override
            public void onMotionPauseDetected() {
                mHasMotionEverBeenPaused = true;
                maybeUpdateRecentsAttachedState();
                performHapticFeedback();
            }

            @Override
            public void onMotionPauseChanged(boolean isPaused) {
                mIsMotionPaused = isPaused;
            }
        };
    }

    public void maybeUpdateRecentsAttachedState() {
        maybeUpdateRecentsAttachedState(true /* animate */);
    }

    /**
     * Determines whether to show or hide RecentsView. The window is always
     * synchronized with its corresponding TaskView in RecentsView, so if
     * RecentsView is shown, it will appear to be attached to the window.
     *
     * Note this method has no effect unless the navigation mode is NO_BUTTON.
     */
    private void maybeUpdateRecentsAttachedState(boolean animate) {
        if (!mDeviceState.isFullyGesturalNavMode() || mRecentsView == null) {
            return;
        }
        RemoteAnimationTargetCompat runningTaskTarget = mRecentsAnimationTargets != null
                ? mRecentsAnimationTargets.findTask(mGestureState.getRunningTaskId())
                : null;
        final boolean recentsAttachedToAppWindow;
        if (mGestureState.getEndTarget() != null) {
            recentsAttachedToAppWindow = mGestureState.getEndTarget().recentsAttachedToAppWindow;
        } else if (mContinuingLastGesture
                && mRecentsView.getRunningTaskIndex() != mRecentsView.getNextPage()) {
            recentsAttachedToAppWindow = true;
        } else if (runningTaskTarget != null && isNotInRecents(runningTaskTarget)) {
            // The window is going away so make sure recents is always visible in this case.
            recentsAttachedToAppWindow = true;
        } else {
            recentsAttachedToAppWindow = mHasMotionEverBeenPaused || mIsLikelyToStartNewTask;
        }
        mAnimationFactory.setRecentsAttachedToAppWindow(recentsAttachedToAppWindow, animate);

        // Reapply window transform throughout the attach animation, as the animation affects how
        // much the window is bound by overscroll (vs moving freely).
        if (animate) {
            ValueAnimator reapplyWindowTransformAnim = ValueAnimator.ofFloat(0, 1);
            reapplyWindowTransformAnim.addUpdateListener(anim -> {
                if (mRunningWindowAnim == null) {
                    applyWindowTransform();
                }
            });
            reapplyWindowTransformAnim.setDuration(RECENTS_ATTACH_DURATION).start();
        } else {
            applyWindowTransform();
        }
    }

    public void setIsLikelyToStartNewTask(boolean isLikelyToStartNewTask) {
        setIsLikelyToStartNewTask(isLikelyToStartNewTask, true /* animate */);
    }

    private void setIsLikelyToStartNewTask(boolean isLikelyToStartNewTask, boolean animate) {
        if (mIsLikelyToStartNewTask != isLikelyToStartNewTask) {
            mIsLikelyToStartNewTask = isLikelyToStartNewTask;
            maybeUpdateRecentsAttachedState(animate);
        }
    }

    private void buildAnimationController() {
        if (!canCreateNewOrUpdateExistingLauncherTransitionController()) {
            return;
        }
        initTransitionEndpoints(mActivity.getDeviceProfile());
        mAnimationFactory.createActivityInterface(mTransitionDragLength);
    }

    /**
     * We don't want to change mLauncherTransitionController if mGestureState.getEndTarget() == HOME
     * (it has its own animation).
     * @return Whether we can create the launcher controller or update its progress.
     */
    private boolean canCreateNewOrUpdateExistingLauncherTransitionController() {
        return mGestureState.getEndTarget() != HOME;
    }

    @Override
    public WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
        WindowInsets result = view.onApplyWindowInsets(windowInsets);
        buildAnimationController();
        return result;
    }

    private void onAnimatorPlaybackControllerCreated(AnimatorControllerWithResistance anim) {
        mLauncherTransitionController = anim;
        mLauncherTransitionController.getNormalController().dispatchOnStart();
        updateLauncherTransitionProgress();
    }

    public Intent getLaunchIntent() {
        return mGestureState.getOverviewIntent();
    }

    /**
     * Called when the value of {@link #mCurrentShift} changes
     */
    @UiThread
    @Override
    public void updateFinalShift() {
        final boolean passed = mCurrentShift.value >= MIN_PROGRESS_FOR_OVERVIEW;
        if (passed != mPassedOverviewThreshold) {
            mPassedOverviewThreshold = passed;
            if (!mDeviceState.isFullyGesturalNavMode()) {
                performHapticFeedback();
            }
        }

        updateSysUiFlags(mCurrentShift.value);
        applyWindowTransform();

        updateLauncherTransitionProgress();
    }

    private void updateLauncherTransitionProgress() {
        if (mLauncherTransitionController == null
                || !canCreateNewOrUpdateExistingLauncherTransitionController()) {
            return;
        }
        mLauncherTransitionController.setProgress(mCurrentShift.value, mDragLengthFactor);
    }

    /**
     * @param windowProgress 0 == app, 1 == overview
     */
    private void updateSysUiFlags(float windowProgress) {
        if (mRecentsAnimationController != null && mRecentsView != null) {
            TaskView runningTask = mRecentsView.getRunningTaskView();
            TaskView centermostTask = mRecentsView.getTaskViewNearestToCenterOfScreen();
            int centermostTaskFlags = centermostTask == null ? 0
                    : centermostTask.getThumbnail().getSysUiStatusNavFlags();
            boolean swipeUpThresholdPassed = windowProgress > 1 - UPDATE_SYSUI_FLAGS_THRESHOLD;
            boolean quickswitchThresholdPassed = centermostTask != runningTask;

            // We will handle the sysui flags based on the centermost task view.
            mRecentsAnimationController.setUseLauncherSystemBarFlags(swipeUpThresholdPassed
                    ||  (quickswitchThresholdPassed && centermostTaskFlags != 0));
            mRecentsAnimationController.setSplitScreenMinimized(swipeUpThresholdPassed);

            int sysuiFlags = swipeUpThresholdPassed ? 0 : centermostTaskFlags;
            mActivity.getSystemUiController().updateUiState(UI_STATE_OVERVIEW, sysuiFlags);
        }
    }

    @Override
    public void onRecentsAnimationStart(RecentsAnimationController controller,
            RecentsAnimationTargets targets) {
        ActiveGestureLog.INSTANCE.addLog("startRecentsAnimationCallback", targets.apps.length);
        mRecentsAnimationController = controller;
        mRecentsAnimationTargets = targets;
        mTransformParams.setTargetSet(mRecentsAnimationTargets);
        RemoteAnimationTargetCompat runningTaskTarget = targets.findTask(
                mGestureState.getRunningTaskId());

        if (runningTaskTarget != null) {
            mTaskViewSimulator.setPreview(runningTaskTarget);
        }

        // Only initialize the device profile, if it has not been initialized before, as in some
        // configurations targets.homeContentInsets may not be correct.
        if (mActivity == null) {
            DeviceProfile dp = mTaskViewSimulator.getOrientationState().getLauncherDeviceProfile();
            if (targets.minimizedHomeBounds != null && runningTaskTarget != null) {
                Rect overviewStackBounds = mActivityInterface
                        .getOverviewWindowBounds(targets.minimizedHomeBounds, runningTaskTarget);
                dp = dp.getMultiWindowProfile(mContext,
                        new WindowBounds(overviewStackBounds, targets.homeContentInsets));
            } else {
                // If we are not in multi-window mode, home insets should be same as system insets.
                dp = dp.copy(mContext);
            }
            dp.updateInsets(targets.homeContentInsets);
            dp.updateIsSeascape(mContext);
            initTransitionEndpoints(dp);
            mTaskViewSimulator.getOrientationState().setMultiWindowMode(dp.isMultiWindowMode);
        }

        // Notify when the animation starts
        if (!mRecentsAnimationStartCallbacks.isEmpty()) {
            for (Runnable action : new ArrayList<>(mRecentsAnimationStartCallbacks)) {
                action.run();
            }
            mRecentsAnimationStartCallbacks.clear();
        }

        // Only add the callback to enable the input consumer after we actually have the controller
        mStateCallback.runOnceAtState(STATE_APP_CONTROLLER_RECEIVED | STATE_GESTURE_STARTED,
                mRecentsAnimationController::enableInputConsumer);
        mStateCallback.setStateOnUiThread(STATE_APP_CONTROLLER_RECEIVED);

        mPassedOverviewThreshold = false;
    }

    @Override
    public void onRecentsAnimationCanceled(ThumbnailData thumbnailData) {
        ActiveGestureLog.INSTANCE.addLog("cancelRecentsAnimation");
        mActivityInitListener.unregister();
        mStateCallback.setStateOnUiThread(STATE_GESTURE_CANCELLED | STATE_HANDLER_INVALIDATED);

        // Defer clearing the controller and the targets until after we've updated the state
        mRecentsAnimationController = null;
        mRecentsAnimationTargets = null;
        if (mRecentsView != null) {
            mRecentsView.setRecentsAnimationTargets(null, null);
        }
    }

    @UiThread
    public void onGestureStarted(boolean isLikelyToStartNewTask) {
        if (mRecentsView != null) {
            InteractionJankMonitorWrapper.begin(mRecentsView,
                    InteractionJankMonitorWrapper.CUJ_QUICK_SWITCH, 2000 /* ms timeout */);
            InteractionJankMonitorWrapper.begin(mRecentsView,
                    InteractionJankMonitorWrapper.CUJ_APP_CLOSE_TO_HOME);
        }
        notifyGestureStartedAsync();
        setIsLikelyToStartNewTask(isLikelyToStartNewTask, false /* animate */);
        mStateCallback.setStateOnUiThread(STATE_GESTURE_STARTED);
        mGestureStarted = true;
        mTaskViewSimulator.setDrawsBelowRecents(true);
    }

    /**
     * Notifies the launcher that the swipe gesture has started. This can be called multiple times.
     */
    @UiThread
    private void notifyGestureStartedAsync() {
        final T curActivity = mActivity;
        if (curActivity != null) {
            // Once the gesture starts, we can no longer transition home through the button, so
            // reset the force override of the activity visibility
            mActivity.clearForceInvisibleFlag(STATE_HANDLER_INVISIBILITY_FLAGS);
        }
    }

    /**
     * Called as a result on ACTION_CANCEL to return the UI to the start state.
     */
    @UiThread
    public void onGestureCancelled() {
        updateDisplacement(0);
        mStateCallback.setStateOnUiThread(STATE_GESTURE_COMPLETED);
        handleNormalGestureEnd(0, false, new PointF(), true /* isCancel */);
    }

    /**
     * @param endVelocity The velocity in the direction of the nav bar to the middle of the screen.
     * @param velocity The x and y components of the velocity when the gesture ends.
     * @param downPos The x and y value of where the gesture started.
     */
    @UiThread
    public void onGestureEnded(float endVelocity, PointF velocity, PointF downPos) {
        float flingThreshold = mContext.getResources()
                .getDimension(R.dimen.quickstep_fling_threshold_speed);
        boolean isFling = mGestureStarted && !mIsMotionPaused
                && Math.abs(endVelocity) > flingThreshold;
        mStateCallback.setStateOnUiThread(STATE_GESTURE_COMPLETED);
        boolean isVelocityVertical = Math.abs(velocity.y) > Math.abs(velocity.x);
        if (isVelocityVertical) {
            mLogDirectionUpOrLeft = velocity.y < 0;
        } else {
            mLogDirectionUpOrLeft = velocity.x < 0;
        }
        mDownPos = downPos;
        handleNormalGestureEnd(endVelocity, isFling, velocity, false /* isCancel */);
    }

    /**
     * Called to create a input proxy for the running task
     */
    @UiThread
    protected InputConsumer createNewInputProxyHandler() {
        endRunningWindowAnim(mGestureState.getEndTarget() == HOME /* cancel */);
        endLauncherTransitionController();

        StatefulActivity activity = mActivityInterface.getCreatedActivity();
        return activity == null ? InputConsumer.NO_OP
                : new OverviewInputConsumer(mGestureState, activity, null, true);
    }

    private void endRunningWindowAnim(boolean cancel) {
        if (mRunningWindowAnim != null) {
            if (cancel) {
                mRunningWindowAnim.cancel();
            } else {
                mRunningWindowAnim.end();
            }
        }
    }

    private void onSettledOnEndTarget() {
        // Fast-finish the attaching animation if it's still running.
        maybeUpdateRecentsAttachedState(false);
        final GestureEndTarget endTarget = mGestureState.getEndTarget();
        if (endTarget != NEW_TASK) {
            InteractionJankMonitorWrapper.cancel(
                    InteractionJankMonitorWrapper.CUJ_QUICK_SWITCH);
        }
        if (endTarget != HOME) {
            InteractionJankMonitorWrapper.cancel(
                    InteractionJankMonitorWrapper.CUJ_APP_CLOSE_TO_HOME);
        }
        switch (endTarget) {
            case HOME:
                mStateCallback.setState(STATE_SCALED_CONTROLLER_HOME | STATE_CAPTURE_SCREENSHOT);
                // Notify swipe-to-home (recents animation) is finished
                SystemUiProxy.INSTANCE.get(mContext).notifySwipeToHomeFinished();
                break;
            case RECENTS:
                mStateCallback.setState(STATE_SCALED_CONTROLLER_RECENTS | STATE_CAPTURE_SCREENSHOT
                        | STATE_SCREENSHOT_VIEW_SHOWN);
                break;
            case NEW_TASK:
                mStateCallback.setState(STATE_START_NEW_TASK | STATE_CAPTURE_SCREENSHOT);
                break;
            case LAST_TASK:
                mStateCallback.setState(STATE_RESUME_LAST_TASK);
                break;
        }
        ActiveGestureLog.INSTANCE.addLog("onSettledOnEndTarget " + endTarget);
    }

    /** @return Whether this was the task we were waiting to appear, and thus handled it. */
    protected boolean handleTaskAppeared(RemoteAnimationTargetCompat appearedTaskTarget) {
        if (mStateCallback.hasStates(STATE_HANDLER_INVALIDATED)) {
            return false;
        }
        if (mStateCallback.hasStates(STATE_START_NEW_TASK)
                && appearedTaskTarget.taskId == mGestureState.getLastStartedTaskId()) {
            reset();
            return true;
        }
        return false;
    }

    private GestureEndTarget calculateEndTarget(PointF velocity, float endVelocity, boolean isFling,
            boolean isCancel) {
        final GestureEndTarget endTarget;
        final boolean goingToNewTask;
        if (mRecentsView != null) {
            if (!hasTargets()) {
                // If there are no running tasks, then we can assume that this is a continuation of
                // the last gesture, but after the recents animation has finished
                goingToNewTask = true;
            } else {
                final int runningTaskIndex = mRecentsView.getRunningTaskIndex();
                final int taskToLaunch = mRecentsView.getNextPage();
                goingToNewTask = runningTaskIndex >= 0 && taskToLaunch != runningTaskIndex;
            }
        } else {
            goingToNewTask = false;
        }
        final boolean reachedOverviewThreshold = mCurrentShift.value >= MIN_PROGRESS_FOR_OVERVIEW;
        if (!isFling) {
            if (isCancel) {
                endTarget = LAST_TASK;
            } else if (mDeviceState.isFullyGesturalNavMode()) {
                if (mIsMotionPaused) {
                    endTarget = RECENTS;
                } else if (goingToNewTask) {
                    endTarget = NEW_TASK;
                } else {
                    endTarget = !reachedOverviewThreshold ? LAST_TASK : HOME;
                }
            } else {
                endTarget = reachedOverviewThreshold && mGestureStarted
                        ? RECENTS
                        : goingToNewTask
                                ? NEW_TASK
                                : LAST_TASK;
            }
        } else {
            // If swiping at a diagonal, base end target on the faster velocity.
            boolean isSwipeUp = endVelocity < 0;
            boolean willGoToNewTaskOnSwipeUp =
                    goingToNewTask && Math.abs(velocity.x) > Math.abs(endVelocity);

            if (mDeviceState.isFullyGesturalNavMode() && isSwipeUp && !willGoToNewTaskOnSwipeUp) {
                endTarget = HOME;
            } else if (mDeviceState.isFullyGesturalNavMode() && isSwipeUp) {
                // If swiping at a diagonal, base end target on the faster velocity.
                endTarget = NEW_TASK;
            } else if (isSwipeUp) {
                endTarget = !reachedOverviewThreshold && willGoToNewTaskOnSwipeUp
                        ? NEW_TASK : RECENTS;
            } else {
                endTarget = goingToNewTask ? NEW_TASK : LAST_TASK;
            }
        }

        if (mDeviceState.isOverviewDisabled() && (endTarget == RECENTS || endTarget == LAST_TASK)) {
            return LAST_TASK;
        }
        return endTarget;
    }

    @UiThread
    private void handleNormalGestureEnd(float endVelocity, boolean isFling, PointF velocity,
            boolean isCancel) {
        long duration = MAX_SWIPE_DURATION;
        float currentShift = mCurrentShift.value;
        final GestureEndTarget endTarget = calculateEndTarget(velocity, endVelocity,
                isFling, isCancel);
        float endShift = endTarget.isLauncher ? 1 : 0;
        final float startShift;
        Interpolator interpolator = DEACCEL;
        if (!isFling) {
            long expectedDuration = Math.abs(Math.round((endShift - currentShift)
                    * MAX_SWIPE_DURATION * SWIPE_DURATION_MULTIPLIER));
            duration = Math.min(MAX_SWIPE_DURATION, expectedDuration);
            startShift = currentShift;
            interpolator = endTarget == RECENTS ? OVERSHOOT_1_2 : DEACCEL;
        } else {
            startShift = Utilities.boundToRange(currentShift - velocity.y
                    * getSingleFrameMs(mContext) / mTransitionDragLength, 0, mDragLengthFactor);
            if (mTransitionDragLength > 0) {
                if (endTarget == RECENTS && !mDeviceState.isFullyGesturalNavMode()) {
                    Interpolators.OvershootParams overshoot = new Interpolators.OvershootParams(
                            startShift, endShift, endShift, endVelocity,
                            mTransitionDragLength, mContext);
                    endShift = overshoot.end;
                    interpolator = overshoot.interpolator;
                    duration = Utilities.boundToRange(overshoot.duration, MIN_OVERSHOOT_DURATION,
                            MAX_SWIPE_DURATION);
                } else {
                    float distanceToTravel = (endShift - currentShift) * mTransitionDragLength;

                    // we want the page's snap velocity to approximately match the velocity at
                    // which the user flings, so we scale the duration by a value near to the
                    // derivative of the scroll interpolator at zero, ie. 2.
                    long baseDuration = Math.round(Math.abs(distanceToTravel / velocity.y));
                    duration = Math.min(MAX_SWIPE_DURATION, 2 * baseDuration);

                    if (endTarget == RECENTS) {
                        interpolator = OVERSHOOT_1_2;
                    }
                }
            }
        }

        if (endTarget.isLauncher) {
            mInputConsumerProxy.enable();
        }
        if (endTarget == HOME) {
            duration = Math.max(MIN_OVERSHOOT_DURATION, duration);
        } else if (endTarget == RECENTS) {
            LiveTileOverlay.INSTANCE.startIconAnimation();
            if (mRecentsView != null) {
                int nearestPage = mRecentsView.getPageNearestToCenterOfScreen();
                if (mRecentsView.getNextPage() != nearestPage) {
                    // We shouldn't really scroll to the next page when swiping up to recents.
                    // Only allow settling on the next page if it's nearest to the center.
                    mRecentsView.snapToPage(nearestPage, Math.toIntExact(duration));
                }
                if (mRecentsView.getScroller().getDuration() > MAX_SWIPE_DURATION) {
                    mRecentsView.snapToPage(mRecentsView.getNextPage(), (int) MAX_SWIPE_DURATION);
                }
                duration = Math.max(duration, mRecentsView.getScroller().getDuration());
            }
        }

        // Let RecentsView handle the scrolling to the task, which we launch in startNewTask()
        // or resumeLastTask().
        if (mRecentsView != null) {
            mRecentsView.setOnPageTransitionEndCallback(
                    () -> mGestureState.setState(STATE_RECENTS_SCROLLING_FINISHED));
        } else {
            mGestureState.setState(STATE_RECENTS_SCROLLING_FINISHED);
        }

        animateToProgress(startShift, endShift, duration, interpolator, endTarget, velocity);
    }

    private void doLogGesture(GestureEndTarget endTarget, @Nullable TaskView targetTask) {
        StatsLogManager.EventEnum event;
        switch (endTarget) {
            case HOME:
                event = LAUNCHER_HOME_GESTURE;
                break;
            case RECENTS:
                event = LAUNCHER_OVERVIEW_GESTURE;
                break;
            case LAST_TASK:
            case NEW_TASK:
                event = mLogDirectionUpOrLeft ? LAUNCHER_QUICKSWITCH_LEFT
                        : LAUNCHER_QUICKSWITCH_RIGHT;
                break;
            default:
                event = IGNORE;
        }
        StatsLogger logger = StatsLogManager.newInstance(mContext).logger()
                .withSrcState(LAUNCHER_STATE_BACKGROUND)
                .withDstState(endTarget.containerType);
        if (targetTask != null) {
            logger.withItemInfo(targetTask.getItemInfo());
        }

        DeviceProfile dp = mDp;
        if (dp == null || mDownPos == null) {
            // We probably never received an animation controller, skip logging.
            return;
        }
        int pageIndex = endTarget == LAST_TASK
                ? LOG_NO_OP_PAGE_INDEX
                : mRecentsView.getNextPage();
        // TODO: set correct container using the pageIndex
        logger.log(event);
    }

    /** Animates to the given progress, where 0 is the current app and 1 is overview. */
    @UiThread
    private void animateToProgress(float start, float end, long duration, Interpolator interpolator,
            GestureEndTarget target, PointF velocityPxPerMs) {
        runOnRecentsAnimationStart(() -> animateToProgressInternal(start, end, duration,
                interpolator, target, velocityPxPerMs));
    }

    protected abstract HomeAnimationFactory createHomeAnimationFactory(long duration);

    private final TaskStackChangeListener mActivityRestartListener = new TaskStackChangeListener() {
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (task.taskId == mGestureState.getRunningTaskId()
                    && TaskInfoCompat.getActivityType(task) != ACTIVITY_TYPE_HOME) {
                // Since this is an edge case, just cancel and relaunch with default activity
                // options (since we don't know if there's an associated app icon to launch from)
                endRunningWindowAnim(true /* cancel */);
                ActivityManagerWrapper.getInstance().unregisterTaskStackListener(
                        mActivityRestartListener);
                ActivityManagerWrapper.getInstance().startActivityFromRecents(task.taskId, null);
            }
        }
    };

    @UiThread
    private void animateToProgressInternal(float start, float end, long duration,
            Interpolator interpolator, GestureEndTarget target, PointF velocityPxPerMs) {
        // Set the state, but don't notify until the animation completes
        mGestureState.setEndTarget(target, false /* isAtomic */);
        maybeUpdateRecentsAttachedState();

        // If we are transitioning to launcher, then listen for the activity to be restarted while
        // the transition is in progress
        if (mGestureState.getEndTarget().isLauncher) {
            ActivityManagerWrapper.getInstance().registerTaskStackListener(
                    mActivityRestartListener);
        }

        if (mGestureState.getEndTarget() == HOME) {
            mTaskViewSimulator.setDrawsBelowRecents(false);
            getOrientationHandler().adjustFloatingIconStartVelocity(velocityPxPerMs);
            final RemoteAnimationTargetCompat runningTaskTarget = mRecentsAnimationTargets != null
                    ? mRecentsAnimationTargets.findTask(mGestureState.getRunningTaskId())
                    : null;
            HomeAnimationFactory homeAnimFactory = createHomeAnimationFactory(duration);
            mIsSwipingPipToHome = homeAnimFactory.supportSwipePipToHome()
                    && runningTaskTarget != null
                    && runningTaskTarget.pictureInPictureParams != null
                    && TaskInfoCompat.isAutoEnterPipEnabled(
                            runningTaskTarget.pictureInPictureParams)
                    && TaskInfoCompat.getPipSourceRectHint(
                            runningTaskTarget.pictureInPictureParams) != null;
            if (mIsSwipingPipToHome) {
                mSwipePipToHomeAnimator = getSwipePipToHomeAnimator(
                        homeAnimFactory, runningTaskTarget, start);
                mSwipePipToHomeAnimator.setDuration(SWIPE_PIP_TO_HOME_DURATION);
                mSwipePipToHomeAnimator.setInterpolator(interpolator);
                mSwipePipToHomeAnimator.setFloatValues(0f, 1f);
                mSwipePipToHomeAnimator.start();
                mRunningWindowAnim = RunningWindowAnim.wrap(mSwipePipToHomeAnimator);
            } else {
                mSwipePipToHomeAnimator = null;
                RectFSpringAnim windowAnim = createWindowAnimationToHome(start, homeAnimFactory);
                windowAnim.addAnimatorListener(new AnimationSuccessListener() {
                    @Override
                    public void onAnimationSuccess(Animator animator) {
                        if (mRecentsAnimationController == null) {
                            // If the recents animation is interrupted, we still end the running
                            // animation (not canceled) so this is still called. In that case,
                            // we can skip doing any future work here for the current gesture.
                            return;
                        }
                        // Finalize the state and notify of the change
                        mGestureState.setState(STATE_END_TARGET_ANIMATION_FINISHED);
                    }
                });
                windowAnim.start(mContext, velocityPxPerMs);
                mRunningWindowAnim = RunningWindowAnim.wrap(windowAnim);
            }
            homeAnimFactory.playAtomicAnimation(velocityPxPerMs.y);
            mLauncherTransitionController = null;
        } else {
            ValueAnimator windowAnim = mCurrentShift.animateToValue(start, end);
            windowAnim.setDuration(duration).setInterpolator(interpolator);
            windowAnim.addUpdateListener(valueAnimator -> {
                computeRecentsScrollIfInvisible();
            });
            windowAnim.addListener(new AnimationSuccessListener() {
                @Override
                public void onAnimationSuccess(Animator animator) {
                    if (mRecentsAnimationController == null) {
                        // If the recents animation is interrupted, we still end the running
                        // animation (not canceled) so this is still called. In that case, we can
                        // skip doing any future work here for the current gesture.
                        return;
                    }
                    if (mRecentsView != null) {
                        int taskToLaunch = mRecentsView.getNextPage();
                        int runningTask = getLastAppearedTaskIndex();
                        boolean hasStartedNewTask = hasStartedNewTask();
                        if (target == NEW_TASK && taskToLaunch == runningTask
                                && !hasStartedNewTask) {
                            // We are about to launch the current running task, so use LAST_TASK
                            // state instead of NEW_TASK. This could happen, for example, if our
                            // scroll is aborted after we determined the target to be NEW_TASK.
                            mGestureState.setEndTarget(LAST_TASK);
                        } else if (target == LAST_TASK && hasStartedNewTask) {
                            // We are about to re-launch the previously running task, but we can't
                            // just finish the controller like we normally would because that would
                            // instead resume the last task that appeared, and not ensure that this
                            // task is restored to the top. To address this, re-launch the task as
                            // if it were a new task.
                            mGestureState.setEndTarget(NEW_TASK);
                        }
                    }
                    mGestureState.setState(STATE_END_TARGET_ANIMATION_FINISHED);
                }
            });
            windowAnim.start();
            mRunningWindowAnim = RunningWindowAnim.wrap(windowAnim);
        }
    }

    private SwipePipToHomeAnimator getSwipePipToHomeAnimator(HomeAnimationFactory homeAnimFactory,
            RemoteAnimationTargetCompat runningTaskTarget, float startProgress) {
        // Directly animate the app to PiP (picture-in-picture) mode
        final ActivityManager.RunningTaskInfo taskInfo = mGestureState.getRunningTask();
        final RecentsOrientedState orientationState = mTaskViewSimulator.getOrientationState();
        final int windowRotation = orientationState.getDisplayRotation();
        final int homeRotation = orientationState.getRecentsActivityRotation();
        final Rect destinationBounds = SystemUiProxy.INSTANCE.get(mContext)
                .startSwipePipToHome(taskInfo.topActivity,
                        TaskInfoCompat.getTopActivityInfo(taskInfo),
                        runningTaskTarget.pictureInPictureParams,
                        homeRotation,
                        mDp.hotseatBarSizePx);
        final Rect startBounds = new Rect();
        updateProgressForStartRect(new Matrix(), startProgress).round(startBounds);
        final SwipePipToHomeAnimator swipePipToHomeAnimator = new SwipePipToHomeAnimator(
                runningTaskTarget.taskId,
                taskInfo.topActivity,
                runningTaskTarget.leash.getSurfaceControl(),
                TaskInfoCompat.getPipSourceRectHint(runningTaskTarget.pictureInPictureParams),
                TaskInfoCompat.getWindowConfigurationBounds(taskInfo),
                startBounds,
                destinationBounds,
                mRecentsView);
        // We would assume home and app window always in the same rotation While homeRotation
        // is not ROTATION_0 (which implies the rotation is turned on in launcher settings).
        if (homeRotation == ROTATION_0
                && (windowRotation == ROTATION_90 || windowRotation == ROTATION_270)) {
            swipePipToHomeAnimator.setFromRotation(mTaskViewSimulator, windowRotation);
        }
        swipePipToHomeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                // Ensure Launcher ends in NORMAL state, we intentionally skip other callbacks
                // since they are not relevant in this swipe-pip-to-home case.
                homeAnimFactory.createActivityAnimationToHome().dispatchOnStart();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mRecentsAnimationController == null) {
                    // If the recents animation is interrupted, we still end the running
                    // animation (not canceled) so this is still called. In that case, we can
                    // skip doing any future work here for the current gesture.
                    return;
                }
                // Finalize the state and notify of the change
                mGestureState.setState(STATE_END_TARGET_ANIMATION_FINISHED);
            }
        });
        return swipePipToHomeAnimator;
    }

    private void computeRecentsScrollIfInvisible() {
        if (mRecentsView != null && mRecentsView.getVisibility() != View.VISIBLE) {
            // Views typically don't compute scroll when invisible as an optimization,
            // but in our case we need to since the window offset depends on the scroll.
            mRecentsView.computeScroll();
        }
    }

    private void continueComputingRecentsScrollIfNecessary() {
        if (!mGestureState.hasState(STATE_RECENTS_SCROLLING_FINISHED)
                && !mStateCallback.hasStates(STATE_HANDLER_INVALIDATED)
                && !mCanceled) {
            computeRecentsScrollIfInvisible();
            mRecentsView.postOnAnimation(this::continueComputingRecentsScrollIfNecessary);
        }
    }

    /**
     * Creates an animation that transforms the current app window into the home app.
     * @param startProgress The progress of {@link #mCurrentShift} to start the window from.
     * @param homeAnimationFactory The home animation factory.
     */
    @Override
    protected RectFSpringAnim createWindowAnimationToHome(float startProgress,
            HomeAnimationFactory homeAnimationFactory) {
        RectFSpringAnim anim =
                super.createWindowAnimationToHome(startProgress, homeAnimationFactory);
        anim.addOnUpdateListener((r, p) -> {
            updateSysUiFlags(Math.max(p, mCurrentShift.value));
        });
        anim.addAnimatorListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (mActivity != null) {
                    removeLiveTileOverlay();
                }
            }

            @Override
            public void onAnimationSuccess(Animator animator) {
                if (mRecentsView != null) {
                    mRecentsView.post(mRecentsView::resetTaskVisuals);
                }
                // Make sure recents is in its final state
                maybeUpdateRecentsAttachedState(false);
                mActivityInterface.onSwipeUpToHomeComplete(mDeviceState);
            }
        });
        if (mRecentsAnimationTargets != null) {
            mRecentsAnimationTargets.addReleaseCheck(anim);
        }
        return anim;
    }

    public void onConsumerAboutToBeSwitched() {
        if (mActivity != null) {
            // In the off chance that the gesture ends before Launcher is started, we should clear
            // the callback here so that it doesn't update with the wrong state
            mActivity.clearRunOnceOnStartCallback();
            resetLauncherListenersAndOverlays();
        }
        if (mGestureState.getEndTarget() != null && !mGestureState.isRunningAnimationToLauncher()) {
            cancelCurrentAnimation();
        } else {
            mStateCallback.setStateOnUiThread(STATE_FINISH_WITH_NO_END);
            reset();
        }
    }

    public boolean isCanceled() {
        return mCanceled;
    }

    @UiThread
    private void resumeLastTask() {
        mRecentsAnimationController.finish(false /* toRecents */, null);
        ActiveGestureLog.INSTANCE.addLog("finishRecentsAnimation", false);
        doLogGesture(LAST_TASK, null);
        reset();
    }

    @UiThread
    private void startNewTask() {
        TaskView taskToLaunch = mRecentsView == null ? null : mRecentsView.getNextPageTaskView();
        startNewTask(success -> {
            if (!success) {
                reset();
                // We couldn't launch the task, so take user to overview so they can
                // decide what to do instead of staying in this broken state.
                endLauncherTransitionController();
                updateSysUiFlags(1 /* windowProgress == overview */);
            }
            doLogGesture(NEW_TASK, taskToLaunch);
        });
    }

    /**
     * Called when we successfully startNewTask() on the task that was previously running. Normally
     * we call resumeLastTask() when returning to the previously running task, but this handles a
     * specific edge case: if we switch from A to B, and back to A before B appears, we need to
     * start A again to ensure it stays on top.
     */
    @androidx.annotation.CallSuper
    protected void onRestartPreviouslyAppearedTask() {
        // Finish the controller here, since we won't get onTaskAppeared() for a task that already
        // appeared.
        if (mRecentsAnimationController != null) {
            mRecentsAnimationController.finish(false, null);
        }
        reset();
    }

    private void reset() {
        mStateCallback.setStateOnUiThread(STATE_HANDLER_INVALIDATED);
    }

    /**
     * Cancels any running animation so that the active target can be overriden by a new swipe
     * handle (in case of quick switch).
     */
    private void cancelCurrentAnimation() {
        mCanceled = true;
        mCurrentShift.cancelAnimation();
    }

    private void invalidateHandler() {
        mInputConsumerProxy.destroy();
        endRunningWindowAnim(false /* cancel */);

        if (mGestureEndCallback != null) {
            mGestureEndCallback.run();
        }

        mActivityInitListener.unregister();
        ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mActivityRestartListener);
        mTaskSnapshot = null;
    }

    private void invalidateHandlerWithLauncher() {
        endLauncherTransitionController();

        mRecentsView.onGestureAnimationEnd();
        resetLauncherListenersAndOverlays();
    }

    private void endLauncherTransitionController() {
        if (mLauncherTransitionController != null) {
            // End the animation, but stay at the same visual progress.
            mLauncherTransitionController.getNormalController().dispatchSetInterpolator(
                    t -> Utilities.boundToRange(mCurrentShift.value, 0, 1));
            mLauncherTransitionController.getNormalController().getAnimationPlayer().end();
            mLauncherTransitionController = null;
        }
    }

    private void resetLauncherListenersAndOverlays() {
        // Reset the callback for deferred activity launches
        if (!ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mActivityInterface.setOnDeferredActivityLaunchCallback(null);
        }
        mActivity.getRootView().setOnApplyWindowInsetsListener(null);
        removeLiveTileOverlay();
    }

    private void notifyTransitionCancelled() {
        mAnimationFactory.onTransitionCancelled();
    }

    private void resetStateForAnimationCancel() {
        boolean wasVisible = mWasLauncherAlreadyVisible || mGestureStarted;
        mActivityInterface.onTransitionCancelled(wasVisible);

        // Leave the pending invisible flag, as it may be used by wallpaper open animation.
        mActivity.clearForceInvisibleFlag(INVISIBLE_BY_STATE_HANDLER);
    }

    protected void switchToScreenshot() {
        final int runningTaskId = mGestureState.getRunningTaskId();
        if (ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            if (mRecentsAnimationController != null) {
                mRecentsAnimationController.getController().setWillFinishToHome(true);
                // Update the screenshot of the task
                if (mTaskSnapshot == null) {
                    mTaskSnapshot = mRecentsAnimationController.screenshotTask(runningTaskId);
                }
                mRecentsView.updateThumbnail(runningTaskId, mTaskSnapshot, false /* refreshNow */);
            }
            mStateCallback.setStateOnUiThread(STATE_SCREENSHOT_CAPTURED);
        } else if (!hasTargets()) {
            // If there are no targets, then we don't need to capture anything
            mStateCallback.setStateOnUiThread(STATE_SCREENSHOT_CAPTURED);
        } else {
            boolean finishTransitionPosted = false;
            if (mRecentsAnimationController != null) {
                // Update the screenshot of the task
                if (mTaskSnapshot == null) {
                    UI_HELPER_EXECUTOR.execute(() -> {
                        if (mRecentsAnimationController == null) return;
                        final ThumbnailData taskSnapshot =
                                mRecentsAnimationController.screenshotTask(runningTaskId);
                        MAIN_EXECUTOR.execute(() -> {
                            mTaskSnapshot = taskSnapshot;
                            if (!updateThumbnail(runningTaskId)) {
                                setScreenshotCapturedState();
                            }
                        });
                    });
                    return;
                }
                finishTransitionPosted = updateThumbnail(runningTaskId);
            }
            if (!finishTransitionPosted) {
                setScreenshotCapturedState();
            }
        }
    }

    // Returns whether finish transition was posted.
    private boolean updateThumbnail(int runningTaskId) {
        boolean finishTransitionPosted = false;
        final TaskView taskView;
        if (mGestureState.getEndTarget() == HOME) {
            // Capture the screenshot before finishing the transition to home to ensure it's
            // taken in the correct orientation, but no need to update the thumbnail.
            taskView = null;
        } else {
            taskView = mRecentsView.updateThumbnail(runningTaskId, mTaskSnapshot);
        }
        if (taskView != null && !mCanceled) {
            // Defer finishing the animation until the next launcher frame with the
            // new thumbnail
            finishTransitionPosted = ViewUtils.postFrameDrawn(taskView,
                    () -> mStateCallback.setStateOnUiThread(STATE_SCREENSHOT_CAPTURED),
                    this::isCanceled);
        }
        return finishTransitionPosted;
    }

    private void setScreenshotCapturedState() {
        // If we haven't posted a draw callback, set the state immediately.
        Object traceToken = TraceHelper.INSTANCE.beginSection(SCREENSHOT_CAPTURED_EVT,
                TraceHelper.FLAG_CHECK_FOR_RACE_CONDITIONS);
        mStateCallback.setStateOnUiThread(STATE_SCREENSHOT_CAPTURED);
        TraceHelper.INSTANCE.endSection(traceToken);
    }

    private void finishCurrentTransitionToRecents() {
        if (ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED);
        } else if (!hasTargets() || mRecentsAnimationController == null) {
            // If there are no targets or the animation not started, then there is nothing to finish
            mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED);
        } else {
            mRecentsAnimationController.finish(true /* toRecents */,
                    () -> mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED));
        }
        ActiveGestureLog.INSTANCE.addLog("finishRecentsAnimation", true);
    }

    private void finishCurrentTransitionToHome() {
        if (!hasTargets() || mRecentsAnimationController == null) {
            // If there are no targets or the animation not started, then there is nothing to finish
            mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED);
        } else {
            maybeFinishSwipePipToHome();
            finishRecentsControllerToHome(
                    () -> mStateCallback.setStateOnUiThread(STATE_CURRENT_TASK_FINISHED));
        }
        ActiveGestureLog.INSTANCE.addLog("finishRecentsAnimation", true);
        doLogGesture(HOME, mRecentsView == null ? null : mRecentsView.getCurrentPageTaskView());
    }

    /**
     * Resets the {@link #mIsSwipingPipToHome} and notifies SysUI that transition is finished
     * if applicable. This should happen before {@link #finishRecentsControllerToHome(Runnable)}.
     */
    private void maybeFinishSwipePipToHome() {
        if (mIsSwipingPipToHome && mSwipePipToHomeAnimator != null) {
            SystemUiProxy.INSTANCE.get(mContext).stopSwipePipToHome(
                    mSwipePipToHomeAnimator.getComponentName(),
                    mSwipePipToHomeAnimator.getDestinationBounds());
            mRecentsAnimationController.setFinishTaskBounds(
                    mSwipePipToHomeAnimator.getTaskId(),
                    mSwipePipToHomeAnimator.getDestinationBounds());
            mIsSwipingPipToHome = false;
        }
    }

    protected abstract void finishRecentsControllerToHome(Runnable callback);

    private final TaskStackChangeListener mLiveTileRestartListener = new TaskStackChangeListener() {
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (mRecentsAnimationTargets.hasTask(task.taskId)) {
                launchOtherTaskInLiveTileMode(task.taskId, mRecentsAnimationTargets.apps);
            }
            ActivityManagerWrapper.getInstance().unregisterTaskStackListener(
                    mLiveTileRestartListener);
        }
    };

    private void setupLauncherUiAfterSwipeUpToRecentsAnimation() {
        endLauncherTransitionController();
        mActivityInterface.onSwipeUpToRecentsComplete();
        mRecentsView.onSwipeUpAnimationSuccess();
        if (ENABLE_QUICKSTEP_LIVE_TILE.get()) {
            mTaskAnimationManager.setLaunchOtherTaskInLiveTileModeHandler(
                    appearedTaskTarget -> {
                        RemoteAnimationTargetCompat[] apps = Arrays.copyOf(
                                mRecentsAnimationTargets.apps,
                                mRecentsAnimationTargets.apps.length + 1);
                        apps[apps.length - 1] = appearedTaskTarget;
                        launchOtherTaskInLiveTileMode(appearedTaskTarget.taskId, apps);
                    });
            ActivityManagerWrapper.getInstance().registerTaskStackListener(
                    mLiveTileRestartListener);
        }

        SystemUiProxy.INSTANCE.get(mContext).onOverviewShown(false, TAG);
        doLogGesture(RECENTS, mRecentsView.getCurrentPageTaskView());
        reset();
    }

    private void launchOtherTaskInLiveTileMode(int taskId, RemoteAnimationTargetCompat[] apps) {
        AnimatorSet anim = new AnimatorSet();
        TaskView taskView = mRecentsView.getTaskView(taskId);
        if (taskView == null || !mRecentsView.isTaskViewVisible(taskView)) {
            // TODO: Refine this animation.
            SurfaceTransactionApplier surfaceApplier =
                    new SurfaceTransactionApplier(mActivity.getDragLayer());
            ValueAnimator appAnimator = ValueAnimator.ofFloat(0, 1);
            appAnimator.setDuration(RECENTS_LAUNCH_DURATION);
            appAnimator.setInterpolator(ACCEL_DEACCEL);
            appAnimator.addUpdateListener(new MultiValueUpdateListener() {
                @Override
                public void onUpdate(float percent) {
                    SurfaceParams.Builder builder = new SurfaceParams.Builder(
                            apps[apps.length - 1].leash);
                    Matrix matrix = new Matrix();
                    matrix.postScale(percent, percent);
                    matrix.postTranslate(mDp.widthPx * (1 - percent) / 2,
                            mDp.heightPx * (1 - percent) / 2);
                    builder.withAlpha(percent).withMatrix(matrix);
                    surfaceApplier.scheduleApply(builder.build());
                }
            });
            anim.play(appAnimator);
        } else {
            TaskViewUtils.composeRecentsLaunchAnimator(
                    anim, taskView, apps,
                    mRecentsAnimationTargets.wallpapers, true /* launcherClosing */,
                    mActivity.getStateManager(), mRecentsView,
                    mActivityInterface.getDepthController());
        }
        anim.addListener(new AnimatorListenerAdapter(){

            @Override
            public void onAnimationEnd(Animator animator) {
                cleanUp(false);
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                cleanUp(true);
            }

            private void cleanUp(boolean canceled) {
                if (mRecentsAnimationController != null) {
                    mRecentsAnimationController.finish(false /* toRecents */,
                            null /* onFinishComplete */);
                    if (canceled) {
                        mRecentsAnimationController = null;
                    } else {
                        mActivityInterface.onLaunchTaskSuccess();
                    }
                    ActiveGestureLog.INSTANCE.addLog("finishRecentsAnimation", false);
                }
            }
        });
        anim.start();
    }

    private void addLiveTileOverlay() {
        if (LiveTileOverlay.INSTANCE.attach(mActivity.getRootView().getOverlay())) {
            mRecentsView.setLiveTileOverlayAttached(true);
        }
    }

    private void removeLiveTileOverlay() {
        LiveTileOverlay.INSTANCE.detach(mActivity.getRootView().getOverlay());
        mRecentsView.setLiveTileOverlayAttached(false);
    }

    private static boolean isNotInRecents(RemoteAnimationTargetCompat app) {
        return app.isNotInRecents
                || app.activityType == ACTIVITY_TYPE_HOME;
    }

    /**
     * To be called at the end of constructor of subclasses. This calls various methods which can
     * depend on proper class initialization.
     */
    protected void initAfterSubclassConstructor() {
        initTransitionEndpoints(
                mTaskViewSimulator.getOrientationState().getLauncherDeviceProfile());
    }

    protected void performHapticFeedback() {
        VibratorWrapper.INSTANCE.get(mContext).vibrate(OVERVIEW_HAPTIC);
    }

    public Consumer<MotionEvent> getRecentsViewDispatcher(float navbarRotation) {
        return mRecentsView != null ? mRecentsView.getEventDispatcher(navbarRotation) : null;
    }

    public void setGestureEndCallback(Runnable gestureEndCallback) {
        mGestureEndCallback = gestureEndCallback;
    }

    protected void linkRecentsViewScroll() {
        SurfaceTransactionApplier.create(mRecentsView, applier -> {
            mTransformParams.setSyncTransactionApplier(applier);
            runOnRecentsAnimationStart(() ->
                    mRecentsAnimationTargets.addReleaseCheck(applier));
        });

        mRecentsView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (moveWindowWithRecentsScroll()) {
                updateFinalShift();
            }
        });
        runOnRecentsAnimationStart(() ->
                mRecentsView.setRecentsAnimationTargets(mRecentsAnimationController,
                        mRecentsAnimationTargets));
        mRecentsViewScrollLinked = true;
    }

    protected void startNewTask(Consumer<Boolean> resultCallback) {
        // Launch the task user scrolled to (mRecentsView.getNextPage()).
        if (!mCanceled) {
            TaskView nextTask = mRecentsView.getNextPageTaskView();
            if (nextTask != null) {
                int taskId = nextTask.getTask().key.id;
                mGestureState.updateLastStartedTaskId(taskId);
                boolean hasTaskPreviouslyAppeared = mGestureState.getPreviouslyAppearedTaskIds()
                        .contains(taskId);
                nextTask.launchTask(false /* animate */, true /* freezeTaskList */,
                        success -> {
                            resultCallback.accept(success);
                            if (success) {
                                if (hasTaskPreviouslyAppeared) {
                                    onRestartPreviouslyAppearedTask();
                                }
                            } else {
                                mActivityInterface.onLaunchTaskFailed();
                                nextTask.notifyTaskLaunchFailed(TAG);
                                mRecentsAnimationController.finish(true /* toRecents */, null);
                            }
                        }, MAIN_EXECUTOR.getHandler());
            } else {
                mActivityInterface.onLaunchTaskFailed();
                Toast.makeText(mContext, R.string.activity_not_available, LENGTH_SHORT).show();
                mRecentsAnimationController.finish(true /* toRecents */, null);
            }
        }
        mCanceled = false;
    }

    /**
     * Runs the given {@param action} if the recents animation has already started, or queues it to
     * be run when it is next started.
     */
    protected void runOnRecentsAnimationStart(Runnable action) {
        if (mRecentsAnimationTargets == null) {
            mRecentsAnimationStartCallbacks.add(action);
        } else {
            action.run();
        }
    }

    /**
     * TODO can we remove this now that we don't finish the controller until onTaskAppeared()?
     * @return whether the recents animation has started and there are valid app targets.
     */
    protected boolean hasTargets() {
        return mRecentsAnimationTargets != null && mRecentsAnimationTargets.hasTargets();
    }

    @Override
    public void onRecentsAnimationFinished(RecentsAnimationController controller) {
        mRecentsAnimationController = null;
        mRecentsAnimationTargets = null;
        if (mRecentsView != null) {
            mRecentsView.setRecentsAnimationTargets(null, null);
        }
    }

    @Override
    public void onTaskAppeared(RemoteAnimationTargetCompat appearedTaskTarget) {
        if (mRecentsAnimationController != null) {
            if (handleTaskAppeared(appearedTaskTarget)) {
                mRecentsAnimationController.finish(false /* toRecents */,
                        null /* onFinishComplete */);
                mActivityInterface.onLaunchTaskSuccess();
                ActiveGestureLog.INSTANCE.addLog("finishRecentsAnimation", false);
            }
        }
    }

    /**
     * @return The index of the TaskView in RecentsView whose taskId matches the task that will
     * resume if we finish the controller.
     */
    protected int getLastAppearedTaskIndex() {
        return mGestureState.getLastAppearedTaskId() != -1
                ? mRecentsView.getTaskIndexForId(mGestureState.getLastAppearedTaskId())
                : mRecentsView.getRunningTaskIndex();
    }

    /**
     * @return Whether we are continuing a gesture that already landed on a new task,
     * but before that task appeared.
     */
    protected boolean hasStartedNewTask() {
        return mGestureState.getLastStartedTaskId() != -1;
    }

    /**
     * Registers a callback to run when the activity is ready.
     * @param intent The intent that will be used to start the activity if it doesn't exist already.
     */
    public void initWhenReady(Intent intent) {
        // Preload the plan
        RecentsModel.INSTANCE.get(mContext).getTasks(null);

        mActivityInitListener.register(intent);
    }

    /**
     * Applies the transform on the recents animation
     */
    protected void applyWindowTransform() {
        if (mWindowTransitionController != null) {
            mWindowTransitionController.setProgress(mCurrentShift.value, mDragLengthFactor);
        }
        if (mRecentsAnimationTargets != null) {
            if (mRecentsViewScrollLinked) {
                mTaskViewSimulator.setScroll(mRecentsView.getScrollOffset());
            }
            mTaskViewSimulator.apply(mTransformParams);
        }
        if (ENABLE_QUICKSTEP_LIVE_TILE.get() && mRecentsAnimationTargets != null) {
            LiveTileOverlay.INSTANCE.update(
                    mTaskViewSimulator.getCurrentRect(),
                    mTaskViewSimulator.getCurrentCornerRadius());
            LiveTileOverlay.INSTANCE.setRotation(
                    mRecentsView.getPagedViewOrientedState().getDisplayRotation());
        }
        ProtoTracer.INSTANCE.get(mContext).scheduleFrameUpdate();
    }

    /**
     * Used for winscope tracing, see launcher_trace.proto
     * @see com.android.systemui.shared.tracing.ProtoTraceable#writeToProto
     * @param inputConsumerProto The parent of this proto message.
     */
    public void writeToProto(InputConsumerProto.Builder inputConsumerProto) {
        SwipeHandlerProto.Builder swipeHandlerProto = SwipeHandlerProto.newBuilder();

        mGestureState.writeToProto(swipeHandlerProto);

        swipeHandlerProto.setIsRecentsAttachedToAppWindow(
                mAnimationFactory.isRecentsAttachedToAppWindow());
        swipeHandlerProto.setScrollOffset(mRecentsView == null
                ? 0
                : mRecentsView.getScrollOffset());
        swipeHandlerProto.setAppToOverviewProgress(mCurrentShift.value);

        inputConsumerProto.setSwipeHandler(swipeHandlerProto);
    }

    public interface Factory {

        AbsSwipeUpHandler<StatefulActivity<?>, RecentsView> newHandler(
                GestureState gestureState, long touchTimeMs, boolean continuingLastGesture);
    }
}
