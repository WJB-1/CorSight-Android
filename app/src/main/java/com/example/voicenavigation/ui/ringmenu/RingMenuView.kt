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
 * 手指长按屏幕后在按住位置弹出，手指不抬起直接滑向某个扇形区域，
 * 松手即执行对应功能。支持二级子菜单（滑到有子菜单的项自动展开）。
 */
class RingMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ==================== 可配置参数 ====================
    private var innerRadius = 80f
    private var ringWidth = 140f
    private var subRingWidth = 120f
    private var gapAngle = 4f
    private var textSize = 26f
    private var centerTextSize = 22f

    // ==================== 状态 ====================
    private var items: List<RingMenuItem> = emptyList()
    private var selectedIndex = -1
    private var selectedChildIndex = -1
    private var activeParentIndex = -1
    private var centerX = 0f
    private var centerY = 0f

    // ==================== 绘制工具 ====================
    private val paintSector = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintSectorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFFFFFFF.toInt()
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD; textSize = this@RingMenuView.textSize
    }
    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt(); style = Paint.Style.FILL
    }
    private val paintCenterText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD; textSize = this@RingMenuView.centerTextSize
    }
    private val paintOverlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80000000.toInt(); style = Paint.Style.FILL
    }

    // ==================== 回调 ====================
    var onItemSelected: ((RingMenuItem) -> Unit)? = null
    var onItemExecuted: ((RingMenuItem) -> Unit)? = null
    var onCenterClicked: (() -> Unit)? = null

    // ==================== 数据驱动 API ====================

    fun setMenuItems(newItems: List<RingMenuItem>) {
        items = newItems
        selectedIndex = -1
        activeParentIndex = -1
        selectedChildIndex = -1
        invalidate()
    }

    // ==================== 触摸处理 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                handleMove(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handleUp()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetSelection()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleMove(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val distance = sqrt(dx * dx + dy * dy)
        val angle = normalizeAngle(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())

        // 中心区域
        if (distance < innerRadius) {
            if (selectedIndex != -1 || selectedChildIndex != -1) {
                resetSelection()
                invalidate()
            }
            return
        }

        // 二级菜单环
        if (activeParentIndex >= 0 && items.getOrNull(activeParentIndex)?.hasChildren == true) {
            val subInner = innerRadius + ringWidth + 10f
            val subOuter = subInner + subRingWidth
            if (distance in subInner..subOuter) {
                val children = items[activeParentIndex].children ?: emptyList()
                if (children.isNotEmpty()) {
                    val childAnglePerItem = 360f / children.size
                    val parentStartAngle = activeParentIndex * (360f / items.size)
                    val relativeAngle = normalizeAngle(angle - parentStartAngle)
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
        val mainInner = innerRadius + 10f
        val mainOuter = mainInner + ringWidth
        if (distance in mainInner..mainOuter) {
            val anglePerItem = 360f / items.size
            val idx = ((angle + anglePerItem / 2) / anglePerItem).toInt() % items.size
            if (idx != selectedIndex) {
                selectedIndex = idx
                selectedChildIndex = -1
                onItemSelected?.invoke(items[idx])
                if (items[idx].hasChildren) {
                    activeParentIndex = idx
                } else {
                    activeParentIndex = -1
                }
                invalidate()
            }
        }
    }

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

        // 点了中心：关闭
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
        // 根据屏幕尺寸动态调整半径
        val minDim = min(w, h)
        innerRadius = minDim * 0.08f
        ringWidth = minDim * 0.16f
        subRingWidth = minDim * 0.13f
        textSize = minDim * 0.028f
        centerTextSize = minDim * 0.024f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        // 半透明遮罩
        canvas.drawColor(0x80000000.toInt())

        val anglePerItem = 360f / items.size

        // 绘制主菜单环
        items.forEachIndexed { index, item ->
            val startAngle = index * anglePerItem + gapAngle / 2
            val sweepAngle = anglePerItem - gapAngle
            val isSelected = (index == selectedIndex && activeParentIndex != index)
            drawSector(canvas, startAngle, sweepAngle,
                innerRadius + 10f, innerRadius + 10f + ringWidth,
                item.color, isSelected)
            drawLabel(canvas, item.label, startAngle + sweepAngle / 2,
                innerRadius + 10f + ringWidth / 2)
        }

        // 绘制二级菜单环
        if (activeParentIndex >= 0 && items[activeParentIndex].hasChildren) {
            val children = items[activeParentIndex].children!!
            val childAnglePerItem = 360f / children.size
            val parentStartAngle = activeParentIndex * anglePerItem
            children.forEachIndexed { cIndex, child ->
                val startAngle = parentStartAngle + cIndex * childAnglePerItem + gapAngle / 2
                val sweepAngle = childAnglePerItem - gapAngle
                val isSelected = (cIndex == selectedChildIndex)
                val subInner = innerRadius + ringWidth + 10f
                drawSector(canvas, startAngle, sweepAngle,
                    subInner, subInner + subRingWidth,
                    child.color, isSelected)
                drawLabel(canvas, child.label, startAngle + sweepAngle / 2,
                    subInner + subRingWidth / 2)
            }
        }

        // 绘制中心圆
        canvas.drawCircle(centerX, centerY, innerRadius, paintCenter)
        val centerLabel = if (activeParentIndex >= 0) context.getString(R.string.menu_back) else context.getString(R.string.menu_close)
        canvas.drawText(centerLabel, centerX,
            centerY + paintCenterText.textSize / 3, paintCenterText)
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
        canvas.drawPath(path, paintSectorStroke)
    }

    private fun drawLabel(canvas: Canvas, label: String, angle: Float, radius: Float) {
        val rad = Math.toRadians(angle.toDouble())
        val x = centerX + (radius * cos(rad)).toFloat()
        val y = centerY + (radius * sin(rad)).toFloat()
        paintText.textSize = textSize
        canvas.drawText(label, x, y + paintText.textSize / 3, paintText)
    }

    private fun brighten(color: Int): Int {
        val r = ((color shr 16 and 0xFF) * 1.3f).toInt().coerceAtMost(255)
        val g = ((color shr 8 and 0xFF) * 1.3f).toInt().coerceAtMost(255)
        val b = ((color and 0xFF) * 1.3f).toInt().coerceAtMost(255)
        return Color.argb(255, r, g, b)
    }
}
