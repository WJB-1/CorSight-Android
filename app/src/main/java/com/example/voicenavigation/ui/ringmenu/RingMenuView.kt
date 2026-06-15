package com.example.voicenavigation.ui.ringmenu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.voicenavigation.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 环形菜单自定义 View。
 *
 * 职责：绘制扇形菜单 + 处理触摸选择 + 执行命令。
 * 角度坐标系：12 点钟方向 = 0°，顺时针递增（与绘制对齐）。
 *
 * 外部可通过 [updateFinger] / [confirmSelection] / [cancelSelection]
 * 驱动选中状态（供 Coordinator 模式使用）。
 */
class RingMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== 尺寸参数（onSizeChanged 中按屏幕比例计算） ====================
    private var innerRadius = 80f
    private var ringWidth = 140f
    private var subRingWidth = 120f
    private var gapAngle = 4f
    private var textSize = 26f
    private var centerTextSize = 22f
    private var gap = 10f        // 内环与主环间距（按屏幕比例）
    private var subGap = 10f     // 主环与子环间距

    // ==================== 状态 ====================
    private var items: List<RingMenuItem> = emptyList()
    private var selectedIndex = -1
    private var selectedChildIndex = -1
    private var activeParentIndex = -1
    private var centerX = 0f
    private var centerY = 0f

    // ==================== 可动画化属性 ====================
    var menuScale: Float = 1f
        set(value) { field = value; invalidate() }
    var overlayAlpha: Int = 0x80
        set(value) { field = value; invalidate() }
    var selectionExpansion: Float = 0f
        set(value) { field = value; invalidate() }
    var subMenuScale: Float = 1f
        set(value) { field = value; invalidate() }
    var centerButtonScale: Float = 1f
        set(value) { field = value; invalidate() }
    var glowIntensity: Float = 0f
        set(value) { field = value; invalidate() }

    /**
     * 兼容属性：旧动画层通过 glowAlpha (Int 0-255) 驱动，
     * 内部映射到 glowIntensity (Float 0-1)。
     */
    var glowAlpha: Int
        get() = (glowIntensity * 255).toInt()
        set(value) { glowIntensity = value.coerceIn(0, 255) / 255f }

    // ==================== 绘制工具 ====================
    private val paintSector = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFFFFFFF.toInt()
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); style = Paint.Style.FILL
    }
    private val paintCenterText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintOverlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80000000.toInt(); style = Paint.Style.FILL
    }

    // ==================== 回调 ====================
    var onItemSelected: ((RingMenuItem) -> Unit)? = null
    var onItemExecuted: ((RingMenuItem) -> Unit)? = null
    var onCenterClicked: (() -> Unit)? = null

    // ==================== 状态查询 ====================
    fun getSelectedIndex(): Int = selectedIndex
    fun getSelectedChildIndex(): Int = selectedChildIndex
    fun getActiveParentIndex(): Int = activeParentIndex
    fun getItems(): List<RingMenuItem> = items

    // ==================== 数据 API ====================

    fun setMenuItems(newItems: List<RingMenuItem>) {
        items = newItems
        resetSelection()
        invalidate()
    }

    // ==================== 外部驱动 API（供 Coordinator 使用） ====================

    /**
     * 外部手指位置更新。由 Coordinator 或 onTouchEvent 调用。
     * 统一使用 12 点钟为 0° 的坐标系。
     */
    fun updateFinger(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val distance = sqrt(dx * dx + dy * dy)

        // ── 统一角度计算：12 点钟 = 0°，顺时针 ──
        // atan2 的 0° 在 3 点钟方向，需要 +90° 对齐到 12 点钟
        val rawAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val angle = normalizeAngle(rawAngle + 90f)

        // 中心区域：取消高亮
        if (distance < innerRadius) {
            if (selectedIndex != -1 || selectedChildIndex != -1) {
                resetSelection()
                invalidate()
            }
            return
        }

        // 二级菜单环
        if (activeParentIndex >= 0 && items.getOrNull(activeParentIndex)?.hasChildren == true) {
            val subInner = innerRadius + ringWidth + subGap
            val subOuter = subInner + subRingWidth
            if (distance in subInner..subOuter) {
                val children = items[activeParentIndex].children ?: emptyList()
                if (children.isNotEmpty()) {
                    // ── B6 修复：子菜单以父扇区中心为基准展开 ──
                    val parentAnglePerItem = 360f / items.size
                    val parentCenter = activeParentIndex * parentAnglePerItem + parentAnglePerItem / 2
                    val childAnglePerItem = 360f / children.size
                    val childTotalAngle = childAnglePerItem * children.size
                    val childStartAngle = normalizeAngle(parentCenter - childTotalAngle / 2)
                    val relativeAngle = normalizeAngle(angle - childStartAngle)
                    val idx = ((relativeAngle + childAnglePerItem / 2) / childAnglePerItem).toInt() % children.size
                    if (idx != selectedChildIndex) {
                        selectedChildIndex = idx
                        selectedIndex = -1
                        onItemSelected?.invoke(children[idx])
                        invalidate()
                    }
                }
                return
            }
        }

        // 主菜单环
        val mainInner = innerRadius + gap
        val mainOuter = mainInner + ringWidth
        if (distance in mainInner..mainOuter) {
            val anglePerItem = 360f / items.size
            val idx = ((angle + anglePerItem / 2) / anglePerItem).toInt() % items.size
            if (idx != selectedIndex) {
                selectedIndex = idx
                selectedChildIndex = -1
                onItemSelected?.invoke(items[idx])
                activeParentIndex = if (items[idx].hasChildren) idx else -1
                invalidate()
            }
        }
    }

    /**
     * 外部确认选择（手指抬起）。供 Coordinator 调用。
     */
    fun confirmSelection() {
        handleUp()
    }

    /**
     * 外部取消选择。供 Coordinator 调用。
     */
    fun cancelSelection() {
        resetSelection()
        invalidate()
    }

    // ==================== 触摸处理（自身也处理，Coordinator 模式下可禁用） ====================

    /** 是否由自身处理触摸事件。设为 false 时由 Coordinator 驱动。 */
    var selfTouchEnabled = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selfTouchEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateFinger(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                confirmSelection()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelSelection()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ==================== 选中执行 ====================

    private fun handleUp() {
        // 二级菜单执行
        if (activeParentIndex >= 0 && selectedChildIndex >= 0) {
            val parent = items[activeParentIndex]
            parent.children?.getOrNull(selectedChildIndex)?.let { onItemExecuted?.invoke(it) }
            resetSelection()
            invalidate()
            return
        }

        // 主菜单执行
        if (selectedIndex >= 0) {
            val item = items[selectedIndex]
            if (item.hasChildren) {
                // 有子菜单：展开，不执行
                activeParentIndex = selectedIndex
                invalidate()
            } else {
                onItemExecuted?.invoke(item)
                resetSelection()
                invalidate()
            }
            return
        }

        // 无选中：点击中心 → 关闭
        onCenterClicked?.invoke()
    }

    private fun resetSelection() {
        selectedIndex = -1
        selectedChildIndex = -1
        activeParentIndex = -1
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle
        while (a < 0) a += 360f
        while (a >= 360f) a -= 360f
        return a
    }

    // ==================== 绘制 ====================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        val minDim = min(w, h)
        innerRadius = minDim * 0.08f
        ringWidth = minDim * 0.16f
        subRingWidth = minDim * 0.13f
        textSize = minDim * 0.028f
        centerTextSize = minDim * 0.024f
        // ── B8 修复：间距按屏幕比例 ──
        gap = minDim * 0.012f
        subGap = minDim * 0.012f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val scale = menuScale
        if (scale <= 0.01f) return
        canvas.save()
        canvas.scale(scale, scale, centerX, centerY)

        // 遮罩
        paintOverlay.alpha = overlayAlpha
        canvas.drawPaint(paintOverlay)

        val anglePerItem = 360f / items.size

        // 绘制主菜单环
        items.forEachIndexed { index, item ->
            val startAngle = index * anglePerItem + gapAngle / 2
            val sweepAngle = anglePerItem - gapAngle
            val isSelected = (index == selectedIndex && activeParentIndex != index)
            val expansion = if (isSelected) selectionExpansion else 0f
            drawSector(canvas, startAngle, sweepAngle,
                innerRadius + gap, innerRadius + gap + ringWidth + expansion,
                item.color, isSelected)
            drawLabel(canvas, item.label, startAngle + sweepAngle / 2,
                innerRadius + gap + (ringWidth + expansion) / 2)
        }

        // 绘制二级菜单环
        if (activeParentIndex >= 0 && items[activeParentIndex].hasChildren) {
            val children = items[activeParentIndex].children!!
            val childAnglePerItem = 360f / children.size
            // ── B6 修复：子菜单以父扇区中心为基准 ──
            val parentCenter = activeParentIndex * anglePerItem + anglePerItem / 2
            val childTotalAngle = childAnglePerItem * children.size
            val childStartAngle = parentCenter - childTotalAngle / 2

            val subScale = subMenuScale
            if (subScale > 0.01f) {
                canvas.save()
                canvas.scale(subScale, subScale, centerX, centerY)
                children.forEachIndexed { cIndex, child ->
                    val startAngle = childStartAngle + cIndex * childAnglePerItem + gapAngle / 2
                    val sweepAngle = childAnglePerItem - gapAngle
                    val isSelected = (cIndex == selectedChildIndex)
                    val expansion = if (isSelected) selectionExpansion else 0f
                    val subInner = innerRadius + ringWidth + subGap
                    drawSector(canvas, startAngle, sweepAngle,
                        subInner, subInner + subRingWidth + expansion,
                        child.color, isSelected)
                    drawLabel(canvas, child.label, startAngle + sweepAngle / 2,
                        subInner + (subRingWidth + expansion) / 2)
                }
                canvas.restore()
            }
        }

        // 中心圆
        val cScale = centerButtonScale
        canvas.save()
        canvas.scale(cScale, cScale, centerX, centerY)
        canvas.drawCircle(centerX, centerY, innerRadius, paintCenter)
        val centerLabel = if (activeParentIndex >= 0) context.getString(R.string.menu_back)
            else context.getString(R.string.menu_close)
        canvas.drawText(centerLabel, centerX,
            centerY + paintCenterText.textSize / 3, paintCenterText)
        canvas.restore()

        canvas.restore()
    }

    private fun drawSector(
        canvas: Canvas, startAngle: Float, sweepAngle: Float,
        innerR: Float, outerR: Float, color: Int, isSelected: Boolean
    ) {
        // ── B5 修复：glowIntensity 控制 RGB 提亮，而非 alpha ──
        paintSector.color = if (isSelected) brighten(color) else color
        val path = Path()
        val rectInner = RectF(centerX - innerR, centerY - innerR, centerX + innerR, centerY + innerR)
        val rectOuter = RectF(centerX - outerR, centerY - outerR, centerX + outerR, centerY + outerR)
        path.arcTo(rectOuter, startAngle, sweepAngle)
        path.arcTo(rectInner, startAngle + sweepAngle, -sweepAngle)
        path.close()
        canvas.drawPath(path, paintSector)
        canvas.drawPath(path, paintStroke)
    }

    private fun drawLabel(canvas: Canvas, label: String, angle: Float, radius: Float) {
        val rad = Math.toRadians(angle.toDouble())
        val x = centerX + (radius * cos(rad)).toFloat()
        val y = centerY + (radius * sin(rad)).toFloat()
        paintText.textSize = textSize
        canvas.drawText(label, x, y + paintText.textSize / 3, paintText)
    }

    /**
     * B5 修复：用 glowIntensity 控制提亮程度（0=原色，1=最大提亮）。
     */
    private fun brighten(color: Int): Int {
        val boost = 1.0f + glowIntensity * 0.3f  // glowIntensity 0..1 → boost 1.0..1.3
        val r = ((color shr 16 and 0xFF) * boost).toInt().coerceAtMost(255)
        val g = ((color shr 8 and 0xFF) * boost).toInt().coerceAtMost(255)
        val b = ((color and 0xFF) * boost).toInt().coerceAtMost(255)
        return Color.argb(0xFF, r, g, b)
    }
}
