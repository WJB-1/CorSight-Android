package com.example.voicenavigation.util

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 径向几何工具 — 统一处理环形布局中的角度、距离、扇区索引计算。
 *
 * 设计为纯函数工具类，无 Android 依赖，可被任意环形 UI 复用：
 * - 环形菜单（RingMenuView）
 * - 环形仪表盘
 * - 环形进度条
 * - 转盘选择器
 * - 任何"围绕中心点分布元素"的 UI
 *
 * ## 坐标系约定
 *
 * 使用 **Canvas 标准坐标系**：
 * - 0° 在 **3 点钟方向**
 * - 顺时针为正
 * - 范围 [0°, 360°)
 *
 * 这与 Android Canvas.drawArc() 的坐标系一致，绘制和计算共用同一套，无需偏移。
 *
 * ## 用法
 *
 * ```kotlin
 * // 绘制第 i 个扇区
 * val drawAngle = RadialGeometry.sectorStartAngle(i, totalItems)
 *
 * // 触摸点映射到扇区索引
 * val touchedIndex = RadialGeometry.touchToSectorIndex(
 *     touchX, touchY, centerX, centerY, totalItems
 * )
 *
 * // 检查触摸点是否在某个环内
 * val inRing = RadialGeometry.isInRing(touchX, touchY, cx, cy, innerR, outerR)
 * ```
 */
object RadialGeometry {

    // ═══════════════════════════════════════════════════
    // 基础计算
    // ═══════════════════════════════════════════════════

    /**
     * 计算 (x, y) 相对于 (cx, cy) 的距离。
     */
    fun distance(x: Float, y: Float, cx: Float, cy: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 计算 (x, y) 相对于 (cx, cy) 的角度（度数）。
     *
     * 返回 [0, 360)，0° 在 3 点钟方向，顺时针递增。
     * 这与 Canvas.drawArc 的坐标系完全一致。
     */
    fun angleDeg(x: Float, y: Float, cx: Float, cy: Float): Float {
        val dx = (x - cx).toDouble()
        val dy = (y - cy).toDouble()
        val deg = Math.toDegrees(atan2(dy, dx)).toFloat()
        return normalizeAngle(deg)
    }

    /**
     * 将任意角度归一化到 [0, 360)。
     */
    fun normalizeAngle(deg: Float): Float {
        var a = deg % 360f
        if (a < 0) a += 360f
        return a
    }

    // ═══════════════════════════════════════════════════
    // 环形区域判断
    // ═══════════════════════════════════════════════════

    /**
     * 判断 (x, y) 是否在以 (cx, cy) 为圆心的圆环内。
     */
    fun isInRing(x: Float, y: Float, cx: Float, cy: Float, innerR: Float, outerR: Float): Boolean {
        val d = distance(x, y, cx, cy)
        return d in innerR..outerR
    }

    /**
     * 判断 (x, y) 是否在以 (cx, cy) 为圆心的圆内。
     */
    fun isInCircle(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Boolean {
        return distance(x, y, cx, cy) <= radius
    }

    // ═══════════════════════════════════════════════════
    // 扇区索引计算（绘制和触摸共用）
    // ═══════════════════════════════════════════════════

    /**
     * 计算第 [index] 个扇区的起始角度（Canvas 坐标系）。
     *
     * 用于绘制：`canvas.drawArc(rect, sectorStartAngle(i, n), sweepAngle(n), ...)`
     *
     * @param index 扇区索引 (0-based)
     * @param totalCount 扇区总数
     * @return 起始角度（度数，Canvas 坐标系）
     */
    fun sectorStartAngle(index: Int, totalCount: Int): Float {
        if (totalCount <= 0) return 0f
        return index * (360f / totalCount)
    }

    /**
     * 计算每个扇区的扫过角度。
     *
     * @param totalCount 扇区总数
     * @param gapDegrees 扇区间隔角度（度数），默认 0
     * @return 每个扇区的扫过角度
     */
    fun sectorSweepAngle(totalCount: Int, gapDegrees: Float = 0f): Float {
        if (totalCount <= 0) return 360f
        return (360f / totalCount) - gapDegrees
    }

    /**
     * 计算第 [index] 个扇区的中心角度。
     *
     * 用于在扇区中心绘制标签。
     */
    fun sectorCenterAngle(index: Int, totalCount: Int, gapDegrees: Float = 0f): Float {
        return sectorStartAngle(index, totalCount) + sectorSweepAngle(totalCount, gapDegrees) / 2f
    }

    // ═══════════════════════════════════════════════════
    // 触摸点 → 扇区索引（核心方法）
    // ═══════════════════════════════════════════════════

    /**
     * 将触摸坐标映射到扇区索引。
     *
     * 内部使用 [angleDeg] 计算触摸点角度，然后除以每扇区角度得到索引。
     * 与 [sectorStartAngle] 使用同一套坐标系，保证绘制和触摸永远对齐。
     *
     * @param touchX 触摸点 X
     * @param touchY 触摸点 Y
     * @param cx 中心点 X
     * @param cy 中心点 Y
     * @param totalCount 扇区总数
     * @return 扇区索引 (0-based)，如果 totalCount <= 0 返回 -1
     */
    fun touchToSectorIndex(touchX: Float, touchY: Float, cx: Float, cy: Float, totalCount: Int): Int {
        if (totalCount <= 0) return -1
        val angle = angleDeg(touchX, touchY, cx, cy)
        val anglePerSector = 360f / totalCount
        return ((angle / anglePerSector).toInt()) % totalCount
    }

    /**
     * 计算子扇区相对于父扇区的索引。
     *
     * 子扇区以父扇区中心为基准，均匀分布在父扇区两侧。
     *
     * @param touchX 触摸点 X
     * @param touchY 触摸点 Y
     * @param cx 中心点 X
     * @param cy 中心点 Y
     * @param parentIndex 父扇区索引
     * @param parentTotalCount 父扇区总数
     * @param childTotalCount 子扇区总数
     * @return 子扇区索引 (0-based)
     */
    fun touchToChildIndex(
        touchX: Float, touchY: Float,
        cx: Float, cy: Float,
        parentIndex: Int, parentTotalCount: Int,
        childTotalCount: Int
    ): Int {
        if (childTotalCount <= 0) return -1

        val touchAngle = angleDeg(touchX, touchY, cx, cy)
        val parentCenter = sectorCenterAngle(parentIndex, parentTotalCount)
        val childAnglePerItem = 360f / childTotalCount

        // 以父扇区中心为基准，子扇区向两侧展开
        // 子扇区 0 的起始角度 = parentCenter - childTotalAngle/2
        val childTotalAngle = childAnglePerItem * childTotalCount
        val childStartAngle = normalizeAngle(parentCenter - childTotalAngle / 2f)

        // 计算触摸点相对于子扇区起始的角度
        val relativeAngle = normalizeAngle(touchAngle - childStartAngle)
        return ((relativeAngle / childAnglePerItem).toInt()) % childTotalCount
    }

    // ═══════════════════════════════════════════════════
    // 子扇区绘制角度（与 touchToChildIndex 配套）
    // ═══════════════════════════════════════════════════

    /**
     * 计算子扇区的起始角度（用于绘制）。
     *
     * 子扇区以父扇区中心为基准均匀分布，与 [touchToChildIndex] 使用同一套计算逻辑。
     *
     * @param childIndex 子扇区索引
     * @param parentIndex 父扇区索引
     * @param parentTotalCount 父扇区总数
     * @param childTotalCount 子扇区总数
     * @param gapDegrees 扇区间隔
     * @return 子扇区起始角度
     */
    fun childSectorStartAngle(
        childIndex: Int,
        parentIndex: Int, parentTotalCount: Int,
        childTotalCount: Int,
        gapDegrees: Float = 0f
    ): Float {
        val parentCenter = sectorCenterAngle(parentIndex, parentTotalCount)
        val childAnglePerItem = 360f / childTotalCount
        val childTotalAngle = childAnglePerItem * childTotalCount
        val childStartAngle = normalizeAngle(parentCenter - childTotalAngle / 2f)
        return childStartAngle + childIndex * childAnglePerItem + gapDegrees / 2f
    }

    /**
     * 计算子扇区的中心角度（用于绘制标签）。
     */
    fun childSectorCenterAngle(
        childIndex: Int,
        parentIndex: Int, parentTotalCount: Int,
        childTotalCount: Int,
        gapDegrees: Float = 0f
    ): Float {
        return childSectorStartAngle(childIndex, parentIndex, parentTotalCount, childTotalCount, gapDegrees) +
                sectorSweepAngle(childTotalCount, gapDegrees) / 2f
    }

    // ═══════════════════════════════════════════════════
    // 极坐标 ↔ 直角坐标转换
    // ═══════════════════════════════════════════════════

    /**
     * 极坐标转直角坐标。
     *
     * @param centerX 中心 X
     * @param centerY 中心 Y
     * @param radius 半径
     * @param angleDeg 角度（度数，Canvas 坐标系）
     * @return Pair(x, y)
     */
    fun polarToCartesian(centerX: Float, centerY: Float, radius: Float, angleDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg.toDouble())
        val x = centerX + (radius * kotlin.math.cos(rad)).toFloat()
        val y = centerY + (radius * kotlin.math.sin(rad)).toFloat()
        return Pair(x, y)
    }

    // ═══════════════════════════════════════════════════
    // 边缘钳位（方案 A：平移钳位）
    // ═══════════════════════════════════════════════════

    /**
     * 将环形菜单的中心点钳位到屏幕范围内，保证整个菜单不超出屏幕。
     *
     * 当触摸点靠近屏幕边缘时，中心点向屏幕内部平移，
     * 使菜单外环刚好贴着屏幕边缘。
     *
     * @param touchX 触摸点 X
     * @param touchY 触摸点 Y
     * @param outerRadius 菜单最外层半径（innerRadius + ringWidth + gap）
     * @param screenW 屏幕宽度
     * @param screenH 屏幕高度
     * @param padding 边距（像素），默认 0
     * @return Pair(clampedX, clampedY)
     */
    fun clampCenter(
        touchX: Float, touchY: Float,
        outerRadius: Float,
        screenW: Float, screenH: Float,
        padding: Float = 0f
    ): Pair<Float, Float> {
        val minX = outerRadius + padding
        val minY = outerRadius + padding
        val maxX = screenW - outerRadius - padding
        val maxY = screenH - outerRadius - padding
        val cx = touchX.coerceIn(minX, maxOf(minX, maxX))
        val cy = touchY.coerceIn(minY, maxOf(minY, maxY))
        return Pair(cx, cy)
    }
}
