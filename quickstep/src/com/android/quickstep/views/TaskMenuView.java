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

package com.android.quickstep.views;

import static com.android.app.animation.Interpolators.EMPHASIZED;
import static com.android.launcher3.Flags.enableOverviewIconMenu;
import static com.android.launcher3.testing.shared.TestProtocol.TEST_TAPL_OVERVIEW_ACTIONS_MENU_FAILURE;
import static com.android.launcher3.testing.shared.TestProtocol.testLogD;
import static com.android.launcher3.util.MultiPropertyFactory.MULTI_PROPERTY_VALUE;
import static com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_BOTTOM_OR_RIGHT;
import static android.view.Surface.ROTATION_0;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static com.android.quickstep.views.TaskThumbnailView.DIM_ALPHA;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.app.animation.Interpolators;
import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.R;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.RoundedRectRevealOutlineProvider;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.views.BaseDragLayer;
import com.android.quickstep.TaskOverlayFactory;
import com.android.quickstep.TaskUtils;
import com.android.quickstep.orientation.RecentsPagedOrientationHandler;
import com.android.quickstep.util.RecentsOrientedState;
import com.android.quickstep.util.TaskCornerRadius;
import com.android.quickstep.views.TaskView.TaskIdAttributeContainer;

/**
 * Contains options for a recent task when long-pressing its icon.
 */
public class TaskMenuView extends AbstractFloatingView {

    private static final Rect sTempRect = new Rect();

    private static final int REVEAL_OPEN_DURATION = enableOverviewIconMenu() ? 417 : 150;
    private static final int REVEAL_CLOSE_DURATION = enableOverviewIconMenu() ? 333 : 100;

    private BaseDraggingActivity mActivity;
    private TextView mTaskName;
    @Nullable
    private AnimatorSet mOpenCloseAnimator;
    @Nullable
    private ValueAnimator mRevealAnimator;
    @Nullable private Runnable mOnClosingStartCallback;
    private TaskView mTaskView;
    private TaskIdAttributeContainer mTaskContainer;
    private LinearLayout mOptionLayout;
    private float mMenuTranslationYBeforeOpen;
    private float mMenuTranslationXBeforeOpen;
    private ScrollView mOptionScrollView;

    public TaskMenuView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskMenuView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mActivity = BaseDraggingActivity.fromContext(context);
        setClipToOutline(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTaskName = findViewById(R.id.task_name);
        mOptionLayout = findViewById(R.id.menu_option_layout);
        mOptionScrollView = findViewById(R.id.menu_options_scroll_view);
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            BaseDragLayer dl = mActivity.getDragLayer();
            if (!dl.isEventOverView(this, ev)) {
                // TODO: log this once we have a new container type for it?
                close(true);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void handleClose(boolean animate) {
        if (animate || enableOverviewIconMenu()) {
            animateClose();
        } else {
            closeComplete();
        }
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_TASK_MENU) != 0;
    }

    @Override
    public ViewOutlineProvider getOutlineProvider() {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        TaskCornerRadius.get(view.getContext()));
            }
        };
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxMenuHeight = calculateMaxHeight();
        if (MeasureSpec.getSize(heightMeasureSpec) > maxMenuHeight) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxMenuHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void onRotationChanged() {
        if (mOpenCloseAnimator != null && mOpenCloseAnimator.isRunning()) {
            mOpenCloseAnimator.end();
        }
        if (mIsOpen) {
            mOptionLayout.removeAllViews();
            if (enableOverviewIconMenu() || !populateAndLayoutMenu()) {
                close(false);
            }
        }
    }

    public static boolean showForTask(TaskIdAttributeContainer taskContainer,
            @Nullable Runnable onClosingStartCallback) {
        BaseDraggingActivity activity = BaseDraggingActivity.fromContext(
                taskContainer.getTaskView().getContext());
        final TaskMenuView taskMenuView = (TaskMenuView) activity.getLayoutInflater().inflate(
                        R.layout.task_menu, activity.getDragLayer(), false);
        taskMenuView.setOnClosingStartCallback(onClosingStartCallback);
        return taskMenuView.populateAndShowForTask(taskContainer);
    }

    public static boolean showForTask(TaskIdAttributeContainer taskContainer) {
        return showForTask(taskContainer, null);
    }

    private boolean populateAndShowForTask(TaskIdAttributeContainer taskContainer) {
        if (isAttachedToWindow()) {
            return false;
        }
        mActivity.getDragLayer().addView(this);
        mTaskView = taskContainer.getTaskView();
        mTaskContainer = taskContainer;
        if (!populateAndLayoutMenu()) {
            return false;
        }
        post(this::animateOpen);
        return true;
    }

    /** @return true if successfully able to populate task view menu, false otherwise */
    private boolean populateAndLayoutMenu() {
        if (mTaskContainer.getTask().icon == null) {
            // Icon may not be loaded
            return false;
        }
        addMenuOptions(mTaskContainer);
        orientAroundTaskView(mTaskContainer);
        return true;
    }

    private void addMenuOptions(TaskIdAttributeContainer taskContainer) {
        if (enableOverviewIconMenu()) {
            removeView(mTaskName);
        } else {
            mTaskName.setText(TaskUtils.getTitle(getContext(), taskContainer.getTask()));
            mTaskName.setOnClickListener(v -> close(true));
        }
        TaskOverlayFactory.getEnabledShortcuts(mTaskView, taskContainer)
                .forEach(this::addMenuOption);
    }

    private void addMenuOption(SystemShortcut menuOption) {
        LinearLayout menuOptionView = (LinearLayout) mActivity.getLayoutInflater().inflate(
                R.layout.task_view_menu_option, this, false);
        if (enableOverviewIconMenu()) {
            ((GradientDrawable) menuOptionView.getBackground()).setCornerRadius(0);
        }
        menuOption.setIconAndLabelFor(
                menuOptionView.findViewById(R.id.icon), menuOptionView.findViewById(R.id.text));
        LayoutParams lp = (LayoutParams) menuOptionView.getLayoutParams();
        mTaskView.getPagedOrientationHandler().setLayoutParamsForTaskMenuOptionItem(lp,
                menuOptionView, mActivity.getDeviceProfile());
        // Set an onClick listener on each menu option. The onClick method is responsible for
        // ending LiveTile mode on the thumbnail if needed.
        menuOptionView.setOnClickListener(menuOption::onClick);
        mOptionLayout.addView(menuOptionView);
    }

    private void orientAroundTaskView(TaskIdAttributeContainer taskContainer) {
        RecentsView recentsView = mActivity.getOverviewPanel();
        RecentsPagedOrientationHandler orientationHandler =
                recentsView.getPagedOrientationHandler();
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        // Get Position
        DeviceProfile deviceProfile = mActivity.getDeviceProfile();
        mActivity.getDragLayer().getDescendantRectRelativeToSelf(
                enableOverviewIconMenu()
                        ? getIconView().findViewById(R.id.icon_view_menu_anchor)
                        : taskContainer.getThumbnailView(),
                sTempRect);
        Rect insets = mActivity.getDragLayer().getInsets();
        BaseDragLayer.LayoutParams params = (BaseDragLayer.LayoutParams) getLayoutParams();
        params.width = orientationHandler.getTaskMenuWidth(taskContainer.getThumbnailView(),
                deviceProfile, taskContainer.getStagePosition());
        // Gravity set to Left instead of Start as sTempRect.left measures Left distance not Start
        params.gravity = Gravity.LEFT;
        setLayoutParams(params);
        setScaleX(mTaskView.getScaleX());
        setScaleY(mTaskView.getScaleY());

        // Set task menu scroll view height
        ViewGroup.LayoutParams layoutParams = mOptionScrollView.getLayoutParams();
        RecentsOrientedState orientedState = recentsView.getPagedViewOrientedState();
        boolean isInLandscape = orientedState.getTouchRotation() != ROTATION_0;
        if (isInLandscape) {
            layoutParams.height = getContext().getResources().getDimensionPixelSize(R.dimen.task_menu_option_height_landscape);
        } else {
            layoutParams.height = LayoutParams.WRAP_CONTENT;
        }
        mOptionScrollView.setLayoutParams(layoutParams);

        // Set divider spacing
        ShapeDrawable divider = new ShapeDrawable(new RectShape());
        divider.getPaint().setColor(getResources().getColor(android.R.color.transparent));
        int dividerSpacing = (int) getResources().getDimension(R.dimen.task_menu_spacing);
        mOptionLayout.setShowDividers(
                enableOverviewIconMenu() ? SHOW_DIVIDER_NONE : SHOW_DIVIDER_MIDDLE);

        orientationHandler.setTaskOptionsMenuLayoutOrientation(
                deviceProfile, mOptionLayout, dividerSpacing, divider);
        float thumbnailAlignedX = sTempRect.left - insets.left;
        float thumbnailAlignedY = sTempRect.top - insets.top;

        // Changing pivot to make computations easier
        // NOTE: Changing the pivots means the rotated view gets rotated about the new pivots set,
        // which would render the X and Y position set here incorrect
        setPivotX(0);
        setPivotY(0);
        setRotation(orientationHandler.getDegreesRotated());

        if (enableOverviewIconMenu()) {
            setTranslationX(thumbnailAlignedX);
            setTranslationY(thumbnailAlignedY);
        } else {
            // Margin that insets the menuView inside the taskView
            float taskInsetMargin = getResources().getDimension(R.dimen.task_card_margin);
            setTranslationX(orientationHandler.getTaskMenuX(thumbnailAlignedX,
                    mTaskContainer.getThumbnailView(), deviceProfile, taskInsetMargin,
                    getIconView()));
            setTranslationY(orientationHandler.getTaskMenuY(
                    thumbnailAlignedY, mTaskContainer.getThumbnailView(),
                    mTaskContainer.getStagePosition(), this, taskInsetMargin,
                    getIconView()));
        }
    }

    private void animateOpen() {
        mMenuTranslationYBeforeOpen = getTranslationY();
        mMenuTranslationXBeforeOpen = getTranslationX();
        animateOpenOrClosed(false);
        mIsOpen = true;
    }

    private View getIconView() {
        return mTaskContainer.getIconView().asView();
    }

    private void animateClose() {
        animateOpenOrClosed(true);
    }

    private void animateOpenOrClosed(boolean closing) {
        if (mOpenCloseAnimator != null && mOpenCloseAnimator.isRunning()) {
            mOpenCloseAnimator.cancel();
        }
        mOpenCloseAnimator = new AnimatorSet();
        // If we're opening, we just start from the beginning as a new `TaskMenuView` is created
        // each time we do the open animation so there will never be a partial value here.
        float revealAnimationStartProgress = 0f;
        if (closing && mRevealAnimator != null) {
            revealAnimationStartProgress = 1f - mRevealAnimator.getAnimatedFraction();
        }
        mRevealAnimator = createOpenCloseOutlineProvider()
                .createRevealAnimator(this, closing, revealAnimationStartProgress);
        mRevealAnimator.setInterpolator(enableOverviewIconMenu() ? Interpolators.EMPHASIZED
                : Interpolators.DECELERATE);

        if (enableOverviewIconMenu()) {
            IconAppChipView iconAppChip = (IconAppChipView) mTaskContainer.getIconView().asView();

            float additionalTranslationY = 0;
            if (((RecentsView) mActivity.getOverviewPanel()).isOnGridBottomRow(mTaskView)) {
                // Animate menu up for enough room to display full menu when task on bottom row.
                float menuBottom = getHeight() + mMenuTranslationYBeforeOpen;
                float taskBottom = mTaskView.getHeight() + mTaskView.getPersistentTranslationY();
                float taskbarTop = mActivity.getDeviceProfile().heightPx
                        - mActivity.getDeviceProfile().getOverviewActionsClaimedSpaceBelow();
                float midpoint = (taskBottom + taskbarTop) / 2f;
                additionalTranslationY = -Math.max(menuBottom - midpoint, 0);
            }
            ObjectAnimator translationYAnim = ObjectAnimator.ofFloat(this, TRANSLATION_Y,
                    closing ? mMenuTranslationYBeforeOpen
                            : mMenuTranslationYBeforeOpen + additionalTranslationY);
            translationYAnim.setInterpolator(EMPHASIZED);

            ObjectAnimator menuTranslationYAnim = ObjectAnimator.ofFloat(
                    iconAppChip.getMenuTranslationY(),
                    MULTI_PROPERTY_VALUE, closing ? 0 : additionalTranslationY);
            menuTranslationYAnim.setInterpolator(EMPHASIZED);

            float additionalTranslationX = 0;
            if (mActivity.getDeviceProfile().isLandscape
                    && mTaskContainer.getStagePosition() == STAGE_POSITION_BOTTOM_OR_RIGHT) {
                // Animate menu and icon when split task would display off the side of the screen.
                additionalTranslationX = Math.max(
                        getTranslationX() + getWidth() - (mActivity.getDeviceProfile().widthPx
                                - getResources().getDimensionPixelSize(
                                R.dimen.task_menu_edge_padding) * 2), 0);
            }

            ObjectAnimator translationXAnim = ObjectAnimator.ofFloat(this, TRANSLATION_X,
                    closing ? mMenuTranslationXBeforeOpen
                            : mMenuTranslationXBeforeOpen - additionalTranslationX);
            translationXAnim.setInterpolator(EMPHASIZED);

            ObjectAnimator menuTranslationXAnim = ObjectAnimator.ofFloat(
                    iconAppChip.getMenuTranslationX(),
                    MULTI_PROPERTY_VALUE, closing ? 0 : -additionalTranslationX);
            menuTranslationXAnim.setInterpolator(EMPHASIZED);

            mOpenCloseAnimator.playTogether(translationYAnim, translationXAnim,
                    menuTranslationXAnim, menuTranslationYAnim);
        }

        mOpenCloseAnimator.playTogether(mRevealAnimator,
                ObjectAnimator.ofFloat(
                        mTaskContainer.getThumbnailView(), DIM_ALPHA,
                        closing ? 0 : TaskView.MAX_PAGE_SCRIM_ALPHA),
                ObjectAnimator.ofFloat(this, ALPHA, closing ? 0 : 1));
        mOpenCloseAnimator.addListener(new AnimationSuccessListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                testLogD(TEST_TAPL_OVERVIEW_ACTIONS_MENU_FAILURE,
                        "TaskMenuView.java.animateOpenOrClosed: onAnimationStart");
                setVisibility(VISIBLE);
                if (closing && mOnClosingStartCallback != null) {
                    mOnClosingStartCallback.run();
                }
            }

            @Override
            public void onAnimationSuccess(Animator animator) {
                testLogD(TEST_TAPL_OVERVIEW_ACTIONS_MENU_FAILURE,
                        "TaskMenuView.java.animateOpenOrClosed: onAnimationSuccess");
                if (closing) {
                    closeComplete();
                }
            }
        });
        mOpenCloseAnimator.setDuration(closing ? REVEAL_CLOSE_DURATION: REVEAL_OPEN_DURATION);
        mOpenCloseAnimator.start();
    }

    private void closeComplete() {
        mIsOpen = false;
        mActivity.getDragLayer().removeView(this);
        mRevealAnimator = null;
    }

    private RoundedRectRevealOutlineProvider createOpenCloseOutlineProvider() {
        float radius = TaskCornerRadius.get(mContext);
        Rect fromRect = new Rect(
                enableOverviewIconMenu() && isLayoutRtl() ? getWidth() : 0,
                0,
                enableOverviewIconMenu() && !isLayoutRtl() ? 0 : getWidth(),
                0);
        Rect toRect = new Rect(0, 0, getWidth(), getHeight());
        return new RoundedRectRevealOutlineProvider(radius, radius, fromRect, toRect);
    }

    /**
     * Calculates max height based on how much space we have available.
     * If not enough space then the view will scroll. The maximum menu size will sit inside the task
     * with a margin on the top and bottom.
     */
    private int calculateMaxHeight() {
        float taskInsetMargin = getResources().getDimension(R.dimen.task_card_margin);
        return mTaskView.getPagedOrientationHandler().getTaskMenuHeight(taskInsetMargin,
                mActivity.getDeviceProfile(), getTranslationX(), getTranslationY());
    }

    private void setOnClosingStartCallback(Runnable onClosingStartCallback) {
        mOnClosingStartCallback = onClosingStartCallback;
    }
}
