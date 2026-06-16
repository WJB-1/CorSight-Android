package com.example.voicenavigation.collection.ui.gridmode

import android.content.Context
import android.graphics.Color
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.voicenavigation.R
import com.example.voicenavigation.animation.DirectionBarAnimations
import com.example.voicenavigation.collection.data.PhotoRecord
import com.example.voicenavigation.collection.ui.base.BaseCaptureFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * 八方向采集模式。
 *
 * 相机内电池式方向状态栏，未对准时禁用快门。
 * 继承 [BaseCaptureFragment] 获得相机、传感器、手势、生命周期管理。
 */
@AndroidEntryPoint
class GridCaptureFragment : BaseCaptureFragment() {

    companion object {
        private const val ALIGN_TOLERANCE = 12f

        private const val STATE_DONE = 0
        private const val STATE_ACTIVE = 1
        private const val STATE_MISALIGNED = 2
        private const val STATE_PENDING = 3
    }

    private val viewModel: GridCaptureViewModel by viewModels()

    private lateinit var directionBar: LinearLayout
    private val directionCells = mutableMapOf<String, Pair<View, TextView>>()
    private var isAligned = false

    override val layoutResId: Int = R.layout.fragment_grid_capture

    override fun onPreviewViewReady(view: View) {
        directionBar = view.findViewById(R.id.directionBar)
        setShutterEnabled(false)
        buildDirectionBar()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.capturedDirections.collectLatest { updateDirectionBar() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentTarget.collectLatest { updateDirectionBar() }
        }
    }

    // 八方向模式：需对齐才允许拍照
    override fun canTakePhoto(): Boolean = isAligned

    override fun onPhotoTaken(file: File, bearing: Float, fov: Float, zoom: Float, isWide: Boolean) {
        val target = viewModel.currentTarget.value ?: return
        val photo = PhotoRecord(
            filePath = file.absolutePath,
            bearing = bearing,
            fov = fov,
            direction = target,
            label = target
        )
        viewModel.addPhoto(photo)
        Toast.makeText(requireContext(), "$target 已采集", Toast.LENGTH_SHORT).show()

        if (viewModel.isComplete) {
            saveTask()
        }
    }

    // 航向更新时检查对齐
    override fun onCompassUpdated(heading: Float) {
        checkAlignment()
    }

    override fun onDebugOverlayText(): String {
        return "Target: ${viewModel.currentTarget.value ?: "-"}"
    }

    // ===== 电池式方向状态栏 =====

    private fun buildDirectionBar() {
        directionBar.removeAllViews()
        directionCells.clear()

        val dp = resources.displayMetrics.density
        val cellWidth = (36 * dp).toInt()
        val cellHeight = (28 * dp).toInt()
        val margin = (2 * dp).toInt()

        GridCaptureViewModel.DIRECTIONS.forEach { dir ->
            val tv = TextView(requireContext()).apply {
                text = dir
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#888888"))
            }

            val cell = FrameLayout(requireContext()).apply {
                setBackgroundResource(R.drawable.bg_direction_cell_pending)
                layoutParams = LinearLayout.LayoutParams(cellWidth, cellHeight).apply {
                    setMargins(margin, 0, margin, 0)
                }
                addView(tv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                ))
            }

            directionCells[dir] = Pair(cell, tv)
            directionBar.addView(cell)
        }
    }

    private fun updateDirectionBar() {
        val captured = viewModel.capturedDirections.value
        val target = viewModel.currentTarget.value

        for ((dir, pair) in directionCells) {
            val (cell, tv) = pair
            val state = when {
                dir in captured -> STATE_DONE
                dir == target && isAligned -> STATE_ACTIVE
                dir == target -> STATE_MISALIGNED
                else -> STATE_PENDING
            }

            val bgRes = when (state) {
                STATE_DONE -> R.drawable.bg_direction_cell_done
                STATE_ACTIVE -> R.drawable.bg_direction_cell_active
                STATE_MISALIGNED -> R.drawable.bg_direction_cell_misaligned
                else -> R.drawable.bg_direction_cell_pending
            }
            cell.setBackgroundResource(bgRes)

            val textColor = when (state) {
                STATE_DONE -> Color.WHITE
                STATE_ACTIVE -> Color.BLACK
                STATE_MISALIGNED -> Color.WHITE
                else -> Color.parseColor("#888888")
            }
            tv.setTextColor(textColor)

            if (state == STATE_MISALIGNED) {
                DirectionBarAnimations.startPulsing(cell)
            } else {
                DirectionBarAnimations.stopPulsing(cell)
                cell.alpha = 1f
            }
        }
    }

    // ===== 对齐检测 =====

    private fun checkAlignment() {
        val target = viewModel.currentTarget.value ?: return
        val targetAngle = GridCaptureViewModel.DIRECTION_ANGLES[target] ?: return

        var diff = abs(currentBearing - targetAngle)
        if (diff > 180) diff = 360 - diff

        val wasAligned = isAligned
        isAligned = diff <= ALIGN_TOLERANCE

        if (isAligned != wasAligned) {
            updateDirectionBar()
            setShutterEnabled(isAligned)
            if (isAligned) {
                val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    // ===== 保存任务 =====

    private fun saveTask() {
        showSaveDialog(
            title = "八方向采集完成",
            photoCount = viewModel.photoCount,
            onSave = { desc -> viewModel.saveTask(currentLat, currentLng, desc) },
            onDiscard = {
                viewModel.photos.value.forEach { File(it.filePath).delete() }
                // 清空方向进度
                isAligned = false
                viewModel.capturedDirections.value.toSet().forEach { /* ViewModel 会清 */ }
            }
        )
    }
}
