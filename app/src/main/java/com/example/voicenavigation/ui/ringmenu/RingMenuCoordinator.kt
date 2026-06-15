package com.example.voicenavigation.ui.ringmenu

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.example.voicenavigation.animation.RingMenuAnimations
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central coordinator for ring menu touch interaction and animation.
 *
 * Replaces [GestureVoiceLauncher] for all ring-menu-related touch handling.
 * Owns a clear state machine (IDLE -> PRESSING -> RING_MENU -> SELECTING -> EXECUTING -> IDLE)
 * and drives [RingMenuView] animatable properties via the existing animation system.
 *
 * Single entry point: [onTouchEvent]. Events are emitted via [events] SharedFlow.
 *
 * ## Usage
 * ```kotlin
 * // In Activity.onCreate:
 * coordinator = RingMenuCoordinator(this, ringMenuView, ringMenuContainer)
 *
 * // In Activity.dispatchTouchEvent:
 * override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
 *     coordinator.onTouchEvent(ev)
 *     return super.dispatchTouchEvent(ev)
 * }
 *
 * // Collect events:
 * lifecycleScope.launch {
 *     coordinator.events.collect { event -> handleInteractionEvent(event) }
 * }
 * ```
 *
 * ## Menu positioning modes
 * - **Center mode** (default): menu appears at screen center.
 * - **Touch point mode**: menu appears at the long-press touch point.
 *   Controlled by [menuPositionMode].
 */
class RingMenuCoordinator(
    private val context: Context,
    private val ringMenuView: RingMenuView,
    private val ringMenuContainer: FrameLayout
) {

    companion object {
        private const val TAG = "RingMenuCoordinator"
        private const val LONG_PRESS_DURATION_MS = 500L
        /** Squared distance threshold (in px) beyond which the finger is considered to have moved. */
        private const val MOVE_THRESHOLD_SQ = 50f * 50f
    }

    // ==================== State Machine ====================

    enum class State {
        /** No touch in progress. */
        IDLE,
        /** Finger is down, waiting for long-press threshold. */
        PRESSING,
        /** Long-press triggered, menu is showing (animating in). */
        RING_MENU,
        /** Menu is fully visible, finger is sliding over sectors. */
        SELECTING,
        /** Item was confirmed, executing (animating out). */
        EXECUTING
    }

    /** Where the menu center is placed. */
    enum class MenuPositionMode {
        /** Menu always centered on screen. */
        CENTER,
        /** Menu centered on the touch point. */
        TOUCH_POINT
    }

    // ==================== Public configuration ====================

    /** Current menu position mode. Default: [MenuPositionMode.CENTER]. */
    var menuPositionMode: MenuPositionMode = MenuPositionMode.CENTER

    /** Whether vibration feedback is enabled on long-press. */
    var hapticEnabled: Boolean = true

    // ==================== Event emission ====================

    private val _events = MutableSharedFlow<InteractionEvent>(extraBufferCapacity = 16)
    /** Collect this to receive interaction events. */
    val events: SharedFlow<InteractionEvent> = _events.asSharedFlow()

    // ==================== Internal state ====================

    private var state: State = State.IDLE
        private set(value) {
            if (field != value) {
                Log.d(TAG, "State: $field -> $value")
                field = value
            }
        }

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var vibrator: Vibrator? = null

    /** Touch-down coordinates (raw). */
    private var downX = 0f
    private var downY = 0f

    /** Whether the finger has moved past the threshold since ACTION_DOWN. */
    private var hasMoved = false

    /** Center coordinates for the ring menu (set once per show). */
    private var menuCenterX = 0f
    private var menuCenterY = 0f

    /** Currently tracked highlight (for emitting [InteractionEvent.ItemHighlighted]). */
    private var lastHighlightedItem: RingMenuItem? = null

    // Active animations (tracked for cancellation)
    private var showAnimatorSet: AnimatorSet? = null
    private var dismissAnimator: ValueAnimator? = null
    private var selectionExpandAnimator: ValueAnimator? = null
    private var glowAnimator: ValueAnimator? = null
    private var centerBreathingAnimator: ValueAnimator? = null

    init {
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        // Disable self-touch on the view so we drive it exclusively
        ringMenuView.selfTouchEnabled = false
    }

    // ==================== Public API ====================

    /**
     * Single entry point for all touch events.
     *
     * Call this from [android.app.Activity.dispatchTouchEvent].
     * Returns true if the event was consumed, false otherwise.
     *
     * @param event The motion event from dispatchTouchEvent.
     * @return true if consumed (caller should NOT pass to super), false to pass through.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return handleDown(event)
            MotionEvent.ACTION_MOVE -> return handleMove(event)
            MotionEvent.ACTION_UP -> return handleUp(event)
            MotionEvent.ACTION_CANCEL -> return handleCancel(event)
        }
        return false
    }

    /**
     * Programmatically dismiss the menu (e.g., on back press).
     * Emits [InteractionEvent.DismissMenu].
     */
    fun dismiss() {
        if (state == State.RING_MENU || state == State.SELECTING) {
            animateDismiss {
                emit(InteractionEvent.DismissMenu)
            }
        } else {
            resetToIdle()
        }
    }

    /**
     * Clean up all animations and state. Call from Activity.onDestroy.
     */
    fun destroy() {
        cancelAllAnimations()
        cancelLongPress()
        state = State.IDLE
    }

    /**
     * Returns whether the coordinator is currently showing or interacting with the menu.
     */
    fun isMenuActive(): Boolean = state == State.RING_MENU || state == State.SELECTING

    // ==================== Touch Handling ====================

    private fun handleDown(event: MotionEvent): Boolean {
        // If we are already executing or animating dismiss, ignore new touches
        if (state == State.EXECUTING) return false

        // If menu is already visible and user taps again, treat as new interaction
        if (state == State.RING_MENU || state == State.SELECTING) {
            // Let the touch pass through -- the menu will handle it
            return false
        }

        downX = event.x
        downY = event.y
        hasMoved = false
        lastHighlightedItem = null
        state = State.PRESSING
        scheduleLongPress()
        return false // Don't consume -- let other views process
    }

    private fun handleMove(event: MotionEvent): Boolean {
        if (state == State.IDLE || state == State.EXECUTING) return false

        val dx = event.x - downX
        val dy = event.y - downY
        val distSq = dx * dx + dy * dy

        if (distSq > MOVE_THRESHOLD_SQ) {
            hasMoved = true
        }

        when (state) {
            State.PRESSING -> {
                if (hasMoved) {
                    // Moved before long-press -- cancel
                    cancelLongPress()
                    state = State.IDLE
                    return false
                }
            }
            State.RING_MENU, State.SELECTING -> {
                // Feed finger position to the RingMenuView for sector highlighting
                ringMenuView.updateFinger(event.x, event.y)
                state = State.SELECTING
                emitHighlightEvent()
                return true
            }
            else -> {}
        }
        return false
    }

    private fun handleUp(event: MotionEvent): Boolean {
        when (state) {
            State.PRESSING -> {
                // Finger lifted before long-press -- normal tap, pass through
                cancelLongPress()
                state = State.IDLE
                return false
            }
            State.RING_MENU -> {
                // Long-press triggered but finger never moved -- voice assistant
                if (!hasMoved) {
                    animateDismiss {
                        emit(InteractionEvent.LaunchVoiceAssistant)
                    }
                } else {
                    // Moved then lifted without selecting -- dismiss
                    animateDismiss {
                        emit(InteractionEvent.Cancelled)
                    }
                }
                return true
            }
            State.SELECTING -> {
                // Confirm selection
                confirmCurrentSelection()
                return true
            }
            State.EXECUTING -> {
                return true
            }
            else -> {
                resetToIdle()
                return false
            }
        }
    }

    private fun handleCancel(event: MotionEvent): Boolean {
        if (state == State.RING_MENU || state == State.SELECTING) {
            animateDismiss {
                emit(InteractionEvent.Cancelled)
            }
            return true
        }
        cancelLongPress()
        state = State.IDLE
        return false
    }

    // ==================== Long Press ====================

    private fun scheduleLongPress() {
        cancelLongPress()
        longPressRunnable = Runnable {
            if (state == State.PRESSING) {
                onLongPressTriggered()
            }
        }
        handler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION_MS)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun onLongPressTriggered() {
        if (state != State.PRESSING) return

        state = State.RING_MENU

        // Haptic feedback
        if (hapticEnabled) {
            vibrator?.let { v ->
                if (v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(100)
                    }
                }
            }
        }

        // Determine menu center based on positioning mode
        when (menuPositionMode) {
            MenuPositionMode.CENTER -> {
                val containerW = ringMenuContainer.width.toFloat().takeIf { it > 0f }
                    ?: ringMenuContainer.context.resources.displayMetrics.widthPixels.toFloat()
                val containerH = ringMenuContainer.height.toFloat().takeIf { it > 0f }
                    ?: ringMenuContainer.context.resources.displayMetrics.heightPixels.toFloat()
                menuCenterX = containerW / 2f
                menuCenterY = containerH / 2f
            }
            MenuPositionMode.TOUCH_POINT -> {
                menuCenterX = downX
                menuCenterY = downY
            }
        }

        emit(InteractionEvent.ShowMenu(menuCenterX, menuCenterY))
        animateShow()
    }

    // ==================== Selection Confirmation ====================

    private fun confirmCurrentSelection() {
        val selectedIndex = ringMenuView.getSelectedIndex()
        val selectedChildIndex = ringMenuView.getSelectedChildIndex()
        val activeParentIndex = ringMenuView.getActiveParentIndex()
        val items = ringMenuView.getItems()

        // Delegate to the view's confirmSelection (which calls handleUp internally)
        ringMenuView.confirmSelection()

        // Determine what was selected for the event
        if (activeParentIndex >= 0 && selectedChildIndex >= 0) {
            // Sub-menu item selected
            val parent = items.getOrNull(activeParentIndex)
            val child = parent?.children?.getOrNull(selectedChildIndex)
            if (child != null && !child.hasChildren) {
                // Leaf item -- execute
                state = State.EXECUTING
                emit(InteractionEvent.ItemExecuted(child.command, child))
                animateDismiss {
                    resetToIdle()
                }
                return
            } else if (child != null && child.hasChildren) {
                // Nested sub-menu -- stay in SELECTING
                return
            }
        } else if (selectedIndex >= 0) {
            val item = items.getOrNull(selectedIndex)
            if (item != null) {
                if (item.hasChildren) {
                    // Tapped on a parent item -- sub-menu opens (handled by view)
                    // Stay in SELECTING state, animate sub-menu
                    animateSubMenuExpand()
                    return
                } else {
                    // Leaf item -- execute
                    state = State.EXECUTING
                    emit(InteractionEvent.ItemExecuted(item.command, item))
                    animateDismiss {
                        resetToIdle()
                    }
                    return
                }
            }
        }

        // Nothing selected -- center tap
        val activeParent = ringMenuView.getActiveParentIndex()
        if (activeParent >= 0) {
            // Sub-menu is active, center acts as "back"
            emit(InteractionEvent.SubMenuBack)
            // Stay in SELECTING state (view handles the back action)
        } else {
            emit(InteractionEvent.CenterTapped)
            animateDismiss {
                emit(InteractionEvent.DismissMenu)
            }
        }
    }

    // ==================== Animations ====================

    /**
     * Animate the ring menu appearing.
     * Drives: menuScale 0->1, overlayAlpha 0->0x80.
     * Starts center breathing and glow animations once the show completes.
     */
    private fun animateShow() {
        cancelAllAnimations()
        ringMenuContainer.visibility = View.VISIBLE

        // Use RingMenuAnimations.show for the core show animation
        val showAnim = RingMenuAnimations.show(ringMenuView, 350L)
        showAnim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (state == State.RING_MENU || state == State.SELECTING) {
                    // Start ambient animations
                    startAmbientAnimations()
                }
            }
        })
        showAnimatorSet = showAnim
    }

    /**
     * Animate the ring menu disappearing.
     * Drives: menuScale 1->0, overlayAlpha 0x80->0.
     */
    private fun animateDismiss(onComplete: (() -> Unit)? = null) {
        cancelAllAnimations()
        ringMenuView.cancelSelection()

        val dismissAnim = RingMenuAnimations.dismiss(ringMenuView, 200L) {
            ringMenuContainer.visibility = View.GONE
            onComplete?.invoke()
        }
        dismissAnimator = dismissAnim
    }

    /**
     * Start ambient animations (glow breathing + center button breathing).
     */
    private fun startAmbientAnimations() {
        // Glow breathing on selected sector
        glowAnimator = RingMenuAnimations.startGlowBreathing(ringMenuView, 800L)

        // Center button breathing
        centerBreathingAnimator = RingMenuAnimations.startCenterBreathing(ringMenuView, 1f, 1.08f, 1200L)
    }

    /**
     * Animate selection expansion when finger enters a new sector.
     * Drives: selectionExpansion 0->12.
     */
    private fun animateSelectionExpand() {
        selectionExpandAnimator?.cancel()
        selectionExpandAnimator = RingMenuAnimations.selectionExpand(ringMenuView, 0f, 12f, 150L)
    }

    /**
     * Animate sub-menu expanding.
     * Drives: subMenuScale 0->1.
     */
    private fun animateSubMenuExpand() {
        RingMenuAnimations.subMenuExpand(ringMenuView, 250L)
    }

    /**
     * Animate sub-menu collapsing.
     * Drives: subMenuScale current->0.
     */
    private fun animateSubMenuCollapse() {
        RingMenuAnimations.subMenuCollapse(ringMenuView, 150L)
    }

    /**
     * Cancel all tracked animations.
     */
    private fun cancelAllAnimations() {
        showAnimatorSet?.let {
            it.removeAllListeners()
            it.cancel()
        }
        showAnimatorSet = null

        dismissAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        dismissAnimator = null

        selectionExpandAnimator?.cancel()
        selectionExpandAnimator = null

        glowAnimator?.cancel()
        glowAnimator = null

        centerBreathingAnimator?.cancel()
        centerBreathingAnimator = null
    }

    // ==================== Highlight Tracking ====================

    /**
     * Check if the finger is over a new item and emit [InteractionEvent.ItemHighlighted].
     */
    private fun emitHighlightEvent() {
        val selectedIndex = ringMenuView.getSelectedIndex()
        val selectedChildIndex = ringMenuView.getSelectedChildIndex()
        val activeParentIndex = ringMenuView.getActiveParentIndex()
        val items = ringMenuView.getItems()

        val currentItem: RingMenuItem? = when {
            activeParentIndex >= 0 && selectedChildIndex >= 0 -> {
                items.getOrNull(activeParentIndex)?.children?.getOrNull(selectedChildIndex)
            }
            selectedIndex >= 0 -> items.getOrNull(selectedIndex)
            else -> null
        }

        if (currentItem != null && currentItem != lastHighlightedItem) {
            lastHighlightedItem = currentItem
            animateSelectionExpand()
            emit(InteractionEvent.ItemHighlighted(currentItem))
        } else if (currentItem == null && lastHighlightedItem != null) {
            lastHighlightedItem = null
            emit(InteractionEvent.HighlightCleared)
        }
    }

    // ==================== Helpers ====================

    private fun emit(event: InteractionEvent) {
        _events.tryEmit(event)
    }

    private fun resetToIdle() {
        cancelLongPress()
        cancelAllAnimations()
        lastHighlightedItem = null
        state = State.IDLE
    }
}
