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
import com.example.voicenavigation.menu.RingMenuItem
import com.example.voicenavigation.util.RadialGeometry

/**
 * 环形菜单自定义 View。
 *
 * 所有角度计算委托给 [RadialGeometry] 工具类，
 * 绘制和触摸共用同一套坐标系（Canvas 标准：0° 在 3 点钟，顺时针）。
 */
class RingMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== 尺寸参数 ====================
    private var innerRadius = 80f
    private var ringWidth = 140f
    private var subRingWidth = 120f
    private var gapAngle = 4f
    private var textSize = 26f
    private var centerTextSize = 22f
    private var gap = 10f
    private var subGap = 10f

    // ==================== 状态 ====================
    private var items: List<RingMenuItem> = emptyList()
    private var selectedIndex = -1
    private var selectedChildIndex = -1
    private var activeParentIndex = -1
    private var centerX = 0f
    private var centerY = 0f
    /** 是否由外部指定了中心点（setCenter），onSizeChanged 不再覆盖 */
    private var centerOverridden = false

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

    /** 兼容旧动画层通过 Int alpha 驱动 */
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

    /**
     * 重置中心点为屏幕中心。菜单关闭时调用。
     */
    fun resetCenter() {
        centerOverridden = false
        // 下次 onSizeChanged 时会重新计算为屏幕中心
    }

    /**
     * 设置菜单绘制中心点。用于菜单跟随触摸点显示。
     *
     * 调用后所有扇形绘制和角度计算都以此为中心。
     * 默认是屏幕中心（onSizeChanged 中设置）。
     */
    fun setCenter(x: Float, y: Float) {
        centerX = x
        centerY = y
        centerOverridden = true
    }

    /**
     * 获取菜单最外层半径（用于边缘钳位计算）。
     */
    fun getOuterRadius(): Float {
        return innerRadius + ringWidth + gap
    }

    // ==================== 外部驱动 API ====================

    /**
     * 外部手指位置更新。
     * 角度计算全部委托 [RadialGeometry]，保证与绘制完全一致。
     */
    fun updateFinger(x: Float, y: Float) {
        val distance = RadialGeometry.distance(x, y, centerX, centerY)

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
            if (RadialGeometry.isInRing(x, y, centerX, centerY, subInner, subOuter)) {
                val children = items[activeParentIndex].children ?: emptyList()
                if (children.isNotEmpty()) {
                    val idx = RadialGeometry.touchToChildIndex(
                        x, y, centerX, centerY,
                        activeParentIndex, items.size,
                        children.size
                    )
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
        if (RadialGeometry.isInRing(x, y, centerX, centerY, mainInner, mainOuter)) {
            val idx = RadialGeometry.touchToSectorIndex(x, y, centerX, centerY, items.size)
            if (idx != selectedIndex) {
                selectedIndex = idx
                selectedChildIndex = -1
                onItemSelected?.invoke(items[idx])
                activeParentIndex = if (items[idx].hasChildren) idx else -1
                invalidate()
            }
        }
    }

    fun confirmSelection() { handleUp() }
    fun cancelSelection() { resetSelection(); invalidate() }

    // ==================== 触摸处理 ====================

    var selfTouchEnabled = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selfTouchEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateFinger(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> { confirmSelection(); return true }
            MotionEvent.ACTION_CANCEL -> { cancelSelection(); return true }
        }
        return super.onTouchEvent(event)
    }

    // ==================== 选中执行 ====================

    private fun handleUp() {
        if (activeParentIndex >= 0 && selectedChildIndex >= 0) {
            val parent = items[activeParentIndex]
            parent.children?.getOrNull(selectedChildIndex)?.let { onItemExecuted?.invoke(it) }
            resetSelection(); invalidate()
            return
        }
        if (selectedIndex >= 0) {
            val item = items[selectedIndex]
            if (item.hasChildren) {
                activeParentIndex = selectedIndex
                invalidate()
            } else {
                onItemExecuted?.invoke(item)
                resetSelection(); invalidate()
            }
            return
        }
        onCenterClicked?.invoke()
    }

    private fun resetSelection() {
        selectedIndex = -1; selectedChildIndex = -1; activeParentIndex = -1
    }

    // ==================== 绘制 ====================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!centerOverridden) {
            centerX = w / 2f; centerY = h / 2f
        }
        val minDim = minOf(w, h)
        innerRadius = minDim * 0.08f
        ringWidth = minDim * 0.16f
        subRingWidth = minDim * 0.13f
        textSize = minDim * 0.028f
        centerTextSize = minDim * 0.024f
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

        // ── 主菜单环（绘制角度由 RadialGeometry 计算） ──
        items.forEachIndexed { index, item ->
            val startAngle = RadialGeometry.sectorStartAngle(index, items.size) + gapAngle / 2
            val sweepAngle = RadialGeometry.sectorSweepAngle(items.size, gapAngle)
            val isSelected = (index == selectedIndex && activeParentIndex != index)
            val expansion = if (isSelected) selectionExpansion else 0f
            drawSector(canvas, startAngle, sweepAngle,
                innerRadius + gap, innerRadius + gap + ringWidth + expansion,
                item.color, isSelected)
            val centerAngle = RadialGeometry.sectorCenterAngle(index, items.size, gapAngle)
            val (labelX, labelY) = RadialGeometry.polarToCartesian(
                centerX, centerY, innerRadius + gap + (ringWidth + expansion) / 2, centerAngle)
            drawLabel(canvas, item.label, labelX, labelY)
        }

        // ── 二级菜单环（绘制角度由 RadialGeometry 计算） ──
        if (activeParentIndex >= 0 && items[activeParentIndex].hasChildren) {
            val children = items[activeParentIndex].children!!
            val subScale = subMenuScale
            if (subScale > 0.01f) {
                canvas.save()
                canvas.scale(subScale, subScale, centerX, centerY)
                children.forEachIndexed { cIndex, child ->
                    val startAngle = RadialGeometry.childSectorStartAngle(
                        cIndex, activeParentIndex, items.size, children.size, gapAngle)
                    val sweepAngle = RadialGeometry.sectorSweepAngle(children.size, gapAngle)
                    val isSelected = (cIndex == selectedChildIndex)
                    val expansion = if (isSelected) selectionExpansion else 0f
                    val subInner = innerRadius + ringWidth + subGap
                    drawSector(canvas, startAngle, sweepAngle,
                        subInner, subInner + subRingWidth + expansion,
                        child.color, isSelected)
                    val centerAngle = RadialGeometry.childSectorCenterAngle(
                        cIndex, activeParentIndex, items.size, children.size, gapAngle)
                    val (labelX, labelY) = RadialGeometry.polarToCartesian(
                        centerX, centerY, subInner + (subRingWidth + expansion) / 2, centerAngle)
                    drawLabel(canvas, child.label, labelX, labelY)
                }
                canvas.restore()
            }
        }

        // 中心圆
        canvas.save()
        canvas.scale(centerButtonScale, centerButtonScale, centerX, centerY)
        canvas.drawCircle(centerX, centerY, innerRadius, paintCenter)
        val centerLabel = if (activeParentIndex >= 0) context.getString(R.string.menu_back)
            else context.getString(R.string.menu_close)
        canvas.drawText(centerLabel, centerX, centerY + paintCenterText.textSize / 3, paintCenterText)
        canvas.restore()

        canvas.restore()
    }

    private fun drawSector(
        canvas: Canvas, startAngle: Float, sweepAngle: Float,
        innerR: Float, outerR: Float, color: Int, isSelected: Boolean
    ) {
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

    private fun drawLabel(canvas: Canvas, label: String, x: Float, y: Float) {
        paintText.textSize = textSize
        canvas.drawText(label, x, y + paintText.textSize / 3, paintText)
    }

    private fun brighten(color: Int): Int {
        val boost = 1.0f + glowIntensity * 0.3f
        val r = ((color shr 16 and 0xFF) * boost).toInt().coerceAtMost(255)
        val g = ((color shr 8 and 0xFF) * boost).toInt().coerceAtMost(255)
        val b = ((color and 0xFF) * boost).toInt().coerceAtMost(255)
        return Color.argb(0xFF, r, g, b)
    }
}
