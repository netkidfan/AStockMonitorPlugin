package com.stockmonitor.toolwindow

import com.stockmonitor.model.Candle
import com.stockmonitor.model.ChartTimeframe
import java.awt.*
import java.awt.geom.Path2D
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

// ─────────────────────────────────────────────────────────────
//  K 线图表渲染面板
// ─────────────────────────────────────────────────────────────

/**
 * 自绘 K 线 + MA30/MA60 均线图表，带右侧价格轴与均线价格标注。
 * 全在 EDT 线程绘制，保证线程安全。
 */
class ChartRenderer : JPanel() {

    // ── 配色（深色主题）───────────────────────────────
    private val BG         = Color(15,  17,  23)
    private val GRID_COLOR = Color(37,  43,  59)
    private val TEXT_COLOR = Color(113, 128, 150)

    // 上涨红（中国惯例）/ 下跌绿
    private val UP_COLOR   = Color(252, 96,  96)
    private val DN_COLOR   = Color(74,  222, 128)

    private val MA30_COLOR = Color(252, 165, 50)   // 橙色
    private val MA60_COLOR = Color(79,  195, 247)  // 青色
    private val PRICE_TAG_BG = Color(50, 60, 85)

    // ── 边距 ─────────────────────────────────────────
    private val MARGIN_LEFT   = 4
    private val MARGIN_RIGHT  = 80   // 右侧价格轴区域
    private val MARGIN_TOP    = 8
    private val MARGIN_BOTTOM = 24   // 底部时间刻度高度

    // ── 数据 ─────────────────────────────────────────
    private var candles:   List<Candle>   = emptyList()
    private var ma30:      List<Double?>  = emptyList()
    private var ma60:      List<Double?>  = emptyList()
    private var timeframe: ChartTimeframe = ChartTimeframe.DAILY

    // 价格区间
    private var priceMin   = 0.0
    private var priceMax   = 1.0
    private var priceRange = 1.0

    // ── 字体 ─────────────────────────────────────────
    private val labelFont = Font("Dialog",    Font.PLAIN, 9)
    private val monoFont  = Font("Monospaced", Font.PLAIN, 10)
    private val tagFont   = Font("Monospaced", Font.BOLD,  9)

    // ─────────────────────────────────────────────
    //  数据更新入口（线程安全）
    // ─────────────────────────────────────────────

    fun setData(c: List<Candle>, m30: List<Double?>, m60: List<Double?>, tf: ChartTimeframe) {
        candles   = c
        ma30      = m30
        ma60      = m60
        timeframe = tf
        computePriceRange()
        SwingUtilities.invokeLater { repaint() }
    }

    fun clear() {
        candles = emptyList()
        ma30    = emptyList()
        ma60    = emptyList()
        SwingUtilities.invokeLater { repaint() }
    }

    private fun computePriceRange() {
        if (candles.isEmpty()) { priceMin = 0.0; priceMax = 1.0; priceRange = 1.0; return }

        val candleVals = candles.flatMap { listOf(it.high, it.low) }.filter { it > 0 }
        val maVals = (ma30.filterNotNull() + ma60.filterNotNull())
        val all = candleVals + maVals

        if (all.isEmpty()) { priceMin = 0.0; priceMax = 1.0; priceRange = 1.0; return }

        priceMin   = all.minOrNull() ?: 0.0
        priceMax   = all.maxOrNull() ?: 1.0
        priceRange = priceMax - priceMin
        if (priceRange < 0.0001) priceRange = 0.0001

        // 上下各留 6% 边距（给 MA 标签留空间）
        val pad = priceRange * 0.06
        priceMin -= pad
        priceMax += pad
        priceRange = priceMax - priceMin
    }

    // ─────────────────────────────────────────────
    //  坐标换算
    // ─────────────────────────────────────────────

    private fun priceToY(price: Double, chartH: Int): Int =
        (chartH * (priceMax - price) / priceRange).toInt().coerceIn(0, chartH - 1)

    private fun indexToX(idx: Int, chartW: Int, total: Int): Int {
        if (total <= 1) return MARGIN_LEFT + chartW / 2
        return MARGIN_LEFT + idx * chartW / (total - 1)
    }

    // ─────────────────────────────────────────────
    //  绘制入口
    // ─────────────────────────────────────────────

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY)

        val W = width
        val H = height

        // 背景
        g2.color = BG
        g2.fillRect(0, 0, W, H)

        if (W <= MARGIN_LEFT + MARGIN_RIGHT || H <= MARGIN_TOP + MARGIN_BOTTOM) {
            g2.dispose(); return
        }

        val chartX = MARGIN_LEFT
        val chartW = W - MARGIN_LEFT - MARGIN_RIGHT
        val chartY = MARGIN_TOP
        val chartH = H - MARGIN_TOP - MARGIN_BOTTOM

        if (candles.isEmpty()) {
            drawEmpty(g2, W, H); g2.dispose(); return
        }

        // 绘制各层
        drawGrid(g2, chartX, chartY, chartW, chartH)
        drawCandles(g2, chartX, chartY, chartW, chartH)
        drawMaLine(g2, chartX, chartY, chartW, chartH, ma30, MA30_COLOR)
        drawMaLine(g2, chartX, chartY, chartW, chartH, ma60, MA60_COLOR)
        drawPriceAxis(g2, chartX, chartY, chartW, chartH)
        drawTimeAxis(g2, chartX, chartY, chartW, chartH)
        drawMaPriceTags(g2, chartX, chartY, chartW, chartH, W)
        drawLatestPriceTag(g2, chartX, chartY, chartW, chartH, W)

        g2.dispose()
    }

    // ─────────────────────────────────────────────
    //  空状态
    // ─────────────────────────────────────────────

    private fun drawEmpty(g: Graphics2D, W: Int, H: Int) {
        g.color = TEXT_COLOR
        g.font  = Font("Dialog", Font.PLAIN, 12)
        val msg = "暂无数据"
        val fm  = g.fontMetrics
        g.drawString(msg, (W - fm.stringWidth(msg)) / 2, H / 2)
    }

    // ─────────────────────────────────────────────
    //  网格
    // ─────────────────────────────────────────────

    private fun drawGrid(g: Graphics2D, cx: Int, cy: Int, cw: Int, ch: Int) {
        g.color  = GRID_COLOR
        g.stroke = BasicStroke(0.5f)
        // 水平线 4 条
        for (i in 0..4) {
            val y = cy + i * ch / 4
            g.drawLine(cx, y, cx + cw, y)
        }
        // 垂直线（最多 6 根）
        val n    = candles.size
        val step = maxOf(1, n / 6)
        for (i in 0 until n step step) {
            val x = indexToX(i, cw, n)
            g.drawLine(x, cy, x, cy + ch)
        }
        g.stroke = BasicStroke(1.0f)
    }

    // ─────────────────────────────────────────────
    //  K 线蜡烛
    // ─────────────────────────────────────────────

    private fun drawCandles(g: Graphics2D, cx: Int, cy: Int, cw: Int, ch: Int) {
        val n      = candles.size
        if (n == 0) return

        // 蜡烛宽度根据根数自适应
        val candleW = when {
            n <= 30  -> maxOf(4, (cw.toDouble() / n * 0.7).toInt())
            n <= 80  -> maxOf(2, (cw.toDouble() / n * 0.65).toInt())
            else     -> maxOf(1, (cw.toDouble() / n * 0.6).toInt())
        }

        for (i in candles.indices) {
            val c     = candles[i]
            val up    = c.close >= c.open
            val color = if (up) UP_COLOR else DN_COLOR
            g.color   = color
            g.stroke  = BasicStroke(1.0f)

            val centerX = indexToX(i, cw, n)
            val highY   = cy + priceToY(c.high,  ch)
            val lowY    = cy + priceToY(c.low,   ch)
            val openY   = cy + priceToY(c.open,  ch)
            val closeY  = cy + priceToY(c.close, ch)

            // 影线
            g.drawLine(centerX, highY, centerX, lowY)

            // 实体
            val bodyTop = minOf(openY, closeY)
            val bodyH   = maxOf(1, kotlin.math.abs(closeY - openY))
            val bodyX   = centerX - candleW / 2

            if (up) {
                // 涨：填充（空心也可，这里用实心）
                g.fillRect(bodyX, bodyTop, candleW, bodyH)
            } else {
                // 跌：实心
                g.fillRect(bodyX, bodyTop, candleW, bodyH)
            }
        }
        g.stroke = BasicStroke(1.0f)
    }

    // ─────────────────────────────────────────────
    //  MA 均线
    // ─────────────────────────────────────────────

    private fun drawMaLine(g: Graphics2D, cx: Int, cy: Int, cw: Int, ch: Int,
                            series: List<Double?>, color: Color) {
        val n = series.size
        if (n < 2) return

        g.color  = color
        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        val path = Path2D.Double()
        var started = false

        for (idx in series.indices) {
            val v = series[idx] ?: continue
            val x = indexToX(idx, cw, n).toDouble()
            val y = (cy + priceToY(v, ch)).toDouble()
            if (!started) { path.moveTo(x, y); started = true }
            else          { path.lineTo(x, y) }
        }
        if (started) g.draw(path)
        g.stroke = BasicStroke(1.0f)
    }

    // ─────────────────────────────────────────────
    //  右侧价格轴
    // ─────────────────────────────────────────────

    private fun drawPriceAxis(g: Graphics2D, cx: Int, cy: Int, cw: Int, ch: Int) {
        g.color = TEXT_COLOR
        g.font  = monoFont
        val fm    = g.fontMetrics
        val axisX = cx + cw + 5

        for (i in 0..4) {
            val price    = priceMax - i * priceRange / 4
            val y        = cy + i * ch / 4
            val priceStr = formatPrice(price)
            val strH     = fm.ascent
            g.drawString(priceStr, axisX, y + strH / 2)
        }
    }

    // ─────────────────────────────────────────────
    //  底部时间轴
    // ─────────────────────────────────────────────

    private fun drawTimeAxis(g: Graphics2D, cx: Int, cy: Int, cw: Int, ch: Int) {
        val n = candles.size
        if (n == 0) return
        g.color = TEXT_COLOR
        g.font  = labelFont

        val fmt = when (timeframe) {
            ChartTimeframe.INTRADAY                                -> SimpleDateFormat("HH:mm",   Locale.CHINA)
            ChartTimeframe.M5, ChartTimeframe.M15,
            ChartTimeframe.M30, ChartTimeframe.M60               -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            ChartTimeframe.DAILY                                   -> SimpleDateFormat("MM-dd",   Locale.CHINA)
            ChartTimeframe.WEEKLY, ChartTimeframe.MONTHLY         -> SimpleDateFormat("yyyy-MM", Locale.CHINA)
        }

        // 首、1/4、中、3/4、末 五个时间点
        val steps = setOf(0, n / 4, n / 2, n * 3 / 4, n - 1).sorted()
        val fm    = g.fontMetrics
        for (s in steps) {
            val c       = candles.getOrNull(s) ?: continue
            val timeStr = fmt.format(Date(c.timestamp))
            val x       = indexToX(s, cw, n)
            val strW    = fm.stringWidth(timeStr)
            val bx      = (x - strW / 2).coerceIn(cx, cx + cw - strW)
            g.drawString(timeStr, bx, cy + ch + 16)
        }
    }

    // ─────────────────────────────────────────────
    //  MA 均线价格标签（右侧轴，带彩色背景）
    // ─────────────────────────────────────────────

    private fun drawMaPriceTags(g: Graphics2D, cx: Int, cy: Int, cw: Int, ch: Int, W: Int) {
        val last30 = ma30.lastOrNull() ?: return
        val last60 = ma60.lastOrNull()

        val tagW  = MARGIN_RIGHT - 2
        val tagH  = 14
        val tagX  = cx + cw + 1

        // MA30 标签
        val y30 = cy + priceToY(last30, ch)
        drawPriceTagLine(g, cx, y30, cw, MA30_COLOR)
        drawPriceTag(g, tagX, y30 - tagH / 2, tagW, tagH, MA30_COLOR, formatPrice(last30))

        // MA60 标签
        if (last60 != null) {
            val y60 = cy + priceToY(last60, ch)
            // 避免标签重叠：y60 距 y30 太近时下移
            val adjY60 = if (kotlin.math.abs(y60 - y30) < tagH + 2) y30 + tagH + 3 else y60
            drawPriceTagLine(g, cx, y60, cw, MA60_COLOR)
            drawPriceTag(g, tagX, adjY60 - tagH / 2, tagW, tagH, MA60_COLOR, formatPrice(last60))
        }
    }

    /** 在图表区右端画一条横向虚线，指向对应价格 */
    private fun drawPriceTagLine(g: Graphics2D, cx: Int, y: Int, cw: Int, color: Color) {
        g.color  = Color(color.red, color.green, color.blue, 80)
        g.stroke = BasicStroke(0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            1f, floatArrayOf(4f, 3f), 0f)
        g.drawLine(cx, y, cx + cw, y)
        g.stroke = BasicStroke(1.0f)
    }

    /** 在右侧轴区域画带色块的价格标签 */
    private fun drawPriceTag(g: Graphics2D, x: Int, y: Int, w: Int, h: Int,
                              color: Color, text: String) {
        // 色块背景（半透明）
        g.color = Color(color.red, color.green, color.blue, 200)
        g.fillRoundRect(x, y, w, h, 4, 4)

        // 文字
        g.color = Color(15, 17, 23)   // 深色文字保证对比度
        g.font  = tagFont
        val fm  = g.fontMetrics
        val tx  = x + (w - fm.stringWidth(text)) / 2
        val ty  = y + fm.ascent + (h - fm.height) / 2
        g.drawString(text, tx, ty)
    }

    // ─────────────────────────────────────────────
    //  最新价格标签（右侧轴，白色背景）
    // ─────────────────────────────────────────────

    private fun drawLatestPriceTag(g: Graphics2D, cx: Int, cy: Int, cw: Int, ch: Int, W: Int) {
        val latestCandle = candles.lastOrNull() ?: return
        val price = latestCandle.close
        val y     = cy + priceToY(price, ch)

        val tagW  = MARGIN_RIGHT - 2
        val tagH  = 14
        val tagX  = cx + cw + 1

        // 价格横线
        val up    = latestCandle.close >= latestCandle.open
        val lineC = if (up) UP_COLOR else DN_COLOR
        g.color   = Color(lineC.red, lineC.green, lineC.blue, 160)
        g.stroke  = BasicStroke(0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            1f, floatArrayOf(3f, 2f), 0f)
        g.drawLine(cx, y, cx + cw, y)
        g.stroke  = BasicStroke(1.0f)

        // 价格标签（深底白字，区别于 MA 标签）
        g.color = if (up) UP_COLOR else DN_COLOR
        g.fillRoundRect(tagX, y - tagH / 2, tagW, tagH, 4, 4)
        g.color = Color(15, 17, 23)
        g.font  = tagFont
        val text = formatPrice(price)
        val fm   = g.fontMetrics
        val tx   = tagX + (tagW - fm.stringWidth(text)) / 2
        val ty   = y - tagH / 2 + fm.ascent + (tagH - fm.height) / 2
        g.drawString(text, tx, ty)
    }

    // ─────────────────────────────────────────────
    //  工具方法
    // ─────────────────────────────────────────────

    /** 根据价格范围自动选择小数位数 */
    private fun formatPrice(price: Double): String {
        return when {
            price >= 1000 -> "%.2f".format(price)
            price >= 10   -> "%.3f".format(price)
            else          -> "%.4f".format(price)
        }
    }
}
