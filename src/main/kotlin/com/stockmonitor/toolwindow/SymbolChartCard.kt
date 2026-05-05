package com.stockmonitor.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.stockmonitor.model.Candle
import com.stockmonitor.model.ChartTimeframe
import com.stockmonitor.model.StockSymbol
import java.awt.*
import java.util.Calendar
import javax.swing.*

// ─────────────────────────────────────────────────────────────
//  单品种图卡（标题栏 + 周期按钮 + K线图 + 状态行）
// ─────────────────────────────────────────────────────────────

/**
 * 每个品种对应一张独立的图卡。
 *
 * @param symbol       品种信息
 * @param onLoadRequest  请求加载数据的回调：(code, exchange, tf) -> Unit
 * @param onMaximizeToggle  最大化/最小化切换回调：(code) -> Unit
 */
class SymbolChartCard(
    val symbol: StockSymbol,
    private val onLoadRequest: (code: String, exchange: String, tf: ChartTimeframe) -> Unit,
    private val onMaximizeToggle: (code: String) -> Unit
) {

    // ── 配色 ─────────────────────────────────────────────
    private val BG        = Color(15,  17,  23)
    private val BG2       = Color(26,  29,  39)
    private val BG3       = Color(37,  43,  59)
    private val FG        = Color(226, 232, 240)
    private val FG_DIM    = Color(113, 128, 150)
    private val FG_BRIGHT = Color(200, 215, 240)
    private val BORDER_C  = Color(55,  64,  90)
    private val ACCENT    = Color(147, 197, 253)

    // ── 时间周期定义（按用户要求的顺序）────────────────────
    private val TIMEFRAMES = listOf(
        ChartTimeframe.INTRADAY,
        ChartTimeframe.DAILY,
        ChartTimeframe.WEEKLY,
        ChartTimeframe.MONTHLY,
        ChartTimeframe.M5,
        ChartTimeframe.M15,
        ChartTimeframe.M30,
        ChartTimeframe.M60
    )

    // ── 状态 ─────────────────────────────────────────────
    var currentTf: ChartTimeframe = defaultTimeframe()
        private set

    /** 是否已最大化 */
    var isMaximized: Boolean = false
        private set

    // ── 组件 ─────────────────────────────────────────────
    private val renderer    = ChartRenderer()
    private val statusLabel = JLabel("加载中...").apply {
        foreground = FG_DIM
        font       = Font("Dialog", Font.PLAIN, 10)
        border     = JBUI.Borders.empty(2, 6)
    }

    /** 周期按钮组，label -> JButton */
    private val tfButtonMap = mutableMapOf<ChartTimeframe, JButton>()

    /** 标题标签（双击最大化/还原），需在 toggleMaximized/restoreNormal 中动态更新 tooltip */
    private lateinit var titleLabel: JLabel

    /** 对外暴露的主面板 */
    val panel: JPanel = buildCard()

    // ─────────────────────────────────────────────────────
    //  构建图卡
    // ─────────────────────────────────────────────────────

    private fun buildCard(): JPanel {
        val card = JPanel(BorderLayout(0, 0)).apply {
            background = BG2
            border     = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_C, 1),
                JBUI.Borders.empty(0)
            )
        }

        // ── 标题栏（双击切换最大化/还原）──
        titleLabel = JLabel("${symbol.name}  ${symbol.code}").apply {
            foreground = FG
            font       = Font("Dialog", Font.BOLD, 12)
            border     = JBUI.Borders.empty(5, 8, 5, 8)
            cursor     = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (isMaximized) "双击还原" else "双击最大化"
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) {
                        onMaximizeToggle(symbol.code)
                    }
                }
            })
        }
        // titleBar 即 titleLabel 本身，不需要额外容器
        val titleBar = titleLabel

        // ── 周期按钮栏 ──
        val tfBar = JPanel(FlowLayout(FlowLayout.LEFT, 3, 3)).apply {
            background = BG2
            border     = JBUI.Borders.emptyLeft(4)
        }
        for (tf in TIMEFRAMES) {
            val btn = buildTfButton(tf)
            tfButtonMap[tf] = btn
            tfBar.add(btn)
        }
        // 默认激活"日"
        highlightTfButton(currentTf)

        val topPanel = JPanel(BorderLayout()).apply {
            background = BG2
            add(titleBar, BorderLayout.NORTH)
            add(tfBar,    BorderLayout.CENTER)
        }

        // ── 图表区 ──
        renderer.preferredSize = Dimension(0, 220)

        // ── 状态行 ──
        val statusBar = JPanel(BorderLayout()).apply {
            background = Color(12, 14, 22)
            border     = JBUI.Borders.empty(1, 0)
            add(statusLabel, BorderLayout.WEST)
        }

        card.add(topPanel,  BorderLayout.NORTH)
        card.add(renderer,  BorderLayout.CENTER)
        card.add(statusBar, BorderLayout.SOUTH)

        // 首次加载
        triggerLoad(currentTf)

        return card
    }

    // ─────────────────────────────────────────────────────
    //  周期按钮
    // ─────────────────────────────────────────────────────

    private fun buildTfButton(tf: ChartTimeframe): JButton {
        val label = when (tf) {
            ChartTimeframe.INTRADAY -> "分时"
            ChartTimeframe.DAILY    -> "日"
            ChartTimeframe.WEEKLY   -> "周"
            ChartTimeframe.MONTHLY  -> "月"
            ChartTimeframe.M5       -> "5"
            ChartTimeframe.M15      -> "15"
            ChartTimeframe.M30      -> "30"
            ChartTimeframe.M60      -> "60"
        }
        return object : JButton(label) {
            var hovered = false

            init {
                isContentAreaFilled = false
                isOpaque            = false
                isFocusPainted      = false
                isBorderPainted     = false
                font                = Font("Dialog", Font.PLAIN, 11)
                foreground          = FG_DIM
                cursor              = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border              = JBUI.Borders.empty(3, 6)
                preferredSize       = if (label.length > 2) Dimension(38, 22) else Dimension(30, 22)

                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent) { hovered = true; repaint() }
                    override fun mouseExited(e: java.awt.event.MouseEvent)  { hovered = false; repaint() }
                })
                addActionListener { onTfClick(tf) }
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val isActive = currentTf == tf
                val bg = when {
                    isActive -> Color(65, 80, 120)
                    hovered  -> Color(45, 55, 75)
                    else     -> BG3
                }
                g2.color = bg
                g2.fillRoundRect(0, 0, width, height, 6, 6)
                if (isActive) {
                    g2.color = Color(100, 140, 220, 160)
                    g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
                }
                foreground = if (isActive) ACCENT else if (hovered) FG_BRIGHT else FG_DIM
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    private fun onTfClick(tf: ChartTimeframe) {
        if (currentTf == tf) return
        currentTf = tf
        // 重绘所有按钮（更新激活态）
        for ((_, btn) in tfButtonMap) btn.repaint()
        triggerLoad(tf)
    }

    private fun highlightTfButton(tf: ChartTimeframe) {
        for ((_, btn) in tfButtonMap) btn.repaint()
    }

    private fun triggerLoad(tf: ChartTimeframe) {
        statusLabel.text = "📡 加载中..."
        onLoadRequest(symbol.code, symbol.exchange, tf)
    }

    // ─────────────────────────────────────────────────────
    //  数据更新（由外部在 EDT 调用）
    // ─────────────────────────────────────────────────────

    fun setData(
        candles: List<Candle>,
        ma30: List<Double?>,
        ma60: List<Double?>,
        tf: ChartTimeframe,
        latestPrice: Double?
    ) {
        renderer.setData(candles, ma30, ma60, tf)
        val priceStr = latestPrice?.let { "%.4f".format(it) } ?: "—"
        val ma30str  = ma30.lastOrNull()?.let { "%.4f".format(it) } ?: "—"
        val ma60str  = ma60.lastOrNull()?.let { "%.4f".format(it) } ?: "—"
        val countStr = if (candles.isEmpty()) "无数据" else "${candles.size}根K线"
        statusLabel.text = "$countStr  价格:$priceStr  MA30:$ma30str  MA60:$ma60str"
        statusLabel.foreground = FG_DIM
    }

    fun setLoading(loading: Boolean) {
        if (loading) {
            statusLabel.text = "📡 加载中..."
        }
    }

    fun setError(msg: String) {
        statusLabel.text = "⚠ $msg"
        statusLabel.foreground = Color(252, 96, 96)
        renderer.clear()
    }

    /** 切换最大化状态，返回新的 isMaximized 值 */
    fun toggleMaximized(): Boolean {
        isMaximized = !isMaximized
        // 图表高度：最大化时 400px，正常时 220px
        renderer.preferredSize = Dimension(
            if (isMaximized) Int.MAX_VALUE else 0,
            if (isMaximized) 400 else 220
        )
        // 更新 tooltip 提示
        titleLabel.toolTipText = if (isMaximized) "双击还原" else "双击最大化"
        panel.revalidate()
        panel.repaint()
        return isMaximized
    }

    /** 还原到正常大小 */
    fun restoreNormal() {
        if (!isMaximized) return
        isMaximized = false
        renderer.preferredSize = Dimension(0, 220)
        titleLabel.toolTipText = "双击最大化"
        panel.revalidate()
        panel.repaint()
    }

    companion object {
        /**
         * 根据当前时间判断默认显示的周期：
         * - A股交易时段（工作日 09:15–11:35 或 12:55–15:05）→ 分时图
         * - 其他时段 → 日线图
         */
        fun defaultTimeframe(): ChartTimeframe {
            val cal = Calendar.getInstance()
            val dow  = cal.get(Calendar.DAY_OF_WEEK)
            // 周末直接返回日线
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return ChartTimeframe.DAILY

            val hh  = cal.get(Calendar.HOUR_OF_DAY)
            val mm  = cal.get(Calendar.MINUTE)
            val min = hh * 60 + mm   // 当前分钟数（从0点算起）

            // 上午盘：09:15 ~ 11:35（早进5分钟避免边界）
            val amOpen  = 9 * 60 + 15
            val amClose = 11 * 60 + 35
            // 下午盘：12:55 ~ 15:05
            val pmOpen  = 12 * 60 + 55
            val pmClose = 15 * 60 + 5

            return if (min in amOpen..amClose || min in pmOpen..pmClose) {
                ChartTimeframe.INTRADAY
            } else {
                ChartTimeframe.DAILY
            }
        }
    }
}
