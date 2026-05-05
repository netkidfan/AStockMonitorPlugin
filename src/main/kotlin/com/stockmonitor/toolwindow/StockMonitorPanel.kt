package com.stockmonitor.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.stockmonitor.data.*
import com.stockmonitor.model.*
import com.stockmonitor.services.MonitorService
import com.stockmonitor.settings.StockMonitorConfigurable
import com.stockmonitor.settings.StockMonitorSettings
import java.awt.*
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

// ─────────────────────────────────────────────────────────────
//  侧边栏面板
// ─────────────────────────────────────────────────────────────

/** 视图模式 */
private enum class ViewMode { LIST, CHART }




class StockMonitorPanel(private val project: Project) {

    // ── 颜色主题 ─────────────────────────────────────────────
    private val BG         = JBColor(Color(15,  17,  23),  Color(15,  17,  23))
    private val BG2        = JBColor(Color(26,  29,  39),  Color(26,  29,  39))
    private val BG3        = JBColor(Color(37,  43,  59),  Color(37,  43,  59))
    private val FG         = JBColor(Color(226, 232, 240), Color(226, 232, 240))
    private val FG_DIM     = JBColor(Color(113, 128, 150), Color(113, 128, 150))
    private val FG_BRIGHT  = JBColor(Color(200, 215, 240), Color(200, 215, 240))
    private val COLOR_UP   = JBColor(Color(252, 96,  96),  Color(252, 96,  96))
    private val COLOR_DOWN = JBColor(Color(74,  222, 128), Color(74,  222, 128))

    private val sdf = SimpleDateFormat("HH:mm:ss")

    // ── 中文可显示字体 ────────────────────────────────────────
    private fun cnFont(style: Int = Font.PLAIN, size: Int = 13) =
        Font("Dialog", style, size)

    // ────────────────────────────────────────────────────────
    //  FancyButton — 带鼠标交互的自定义按钮
    // ────────────────────────────────────────────────────────

    private enum class BtnState { NORMAL, HOVER, PRESSED }

    inner class FancyButton(
        label: String,
        private var fgNormal: Color? = null,
        isPrimary: Boolean = false
    ) : JButton(label) {

        private var state = BtnState.NORMAL

        private fun setState(s: BtnState) {
            if (state == s) return
            state = s
            repaint()
        }

        init {
            isContentAreaFilled = false
            isOpaque            = false
            isFocusPainted      = false
            isBorderPainted     = false
            font                = cnFont(Font.PLAIN, if (isPrimary) 13 else 12)
            foreground          = fgNormal ?: FG_DIM
            cursor              = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val hPad = if (isPrimary) 14 else 8
            border              = JBUI.Borders.empty(5, hPad)

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent)  = setState(BtnState.HOVER)
                override fun mouseExited(e: java.awt.event.MouseEvent)   = setState(BtnState.NORMAL)
                override fun mousePressed(e: java.awt.event.MouseEvent)  = setState(BtnState.PRESSED)
                override fun mouseReleased(e: java.awt.event.MouseEvent) {
                    val pt = e.point
                    setState(if (pt.x in 0 until width && pt.y in 0 until height) BtnState.HOVER else BtnState.NORMAL)
                }
            })
        }

        fun setFgNormal(c: Color) {
            fgNormal = c
            refreshFg()
        }

        /** 高亮激活态（用于时间周期按钮） */
        fun setActive(active: Boolean) {
            foreground = if (active) FG_BRIGHT else (fgNormal ?: FG_DIM)
            repaint()
        }

        private fun refreshFg() {
            foreground = when (state) {
                BtnState.NORMAL  -> fgNormal ?: FG_DIM
                BtnState.HOVER   -> (fgNormal ?: FG_DIM).brighter(40)
                BtnState.PRESSED -> (fgNormal ?: FG_DIM).brighter(80)
            }
        }

        private fun Color.brighter(delta: Int): Color {
            fun clamp(v: Int) = minOf(255, v + delta)
            return Color(clamp(red), clamp(green), clamp(blue), alpha)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val bg = when (state) {
                BtnState.NORMAL  -> Color(37,  43,  59)
                BtnState.HOVER   -> Color(55,  64,  90)
                BtnState.PRESSED -> Color(72,  84, 118)
            }
            g2.color = bg
            g2.fillRoundRect(0, 0, width, height, 8, 8)

            if (state == BtnState.HOVER || state == BtnState.PRESSED) {
                g2.color = Color(100, 120, 180, 120)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
            }

            refreshFg()
            g2.dispose()
            super.paintComponent(g)
        }
    }

    // ── 模式切换图标按钮（无文字，纯图标）──
    private inner class IconButton(
        private val icon: String,
        private var tip: String = ""
    ) : JButton(icon) {

        var active: Boolean = false
            set(v) { field = v; repaint() }

        init {
            isContentAreaFilled = false
            isOpaque            = false
            isFocusPainted      = false
            isBorderPainted     = false
            font                = cnFont(Font.PLAIN, 13)
            foreground          = FG_DIM
            cursor              = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border              = JBUI.Borders.empty(4, 6)
            toolTipText         = tip

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    if (!active) foreground = FG_BRIGHT
                    repaint()
                }
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    foreground = if (active) FG_BRIGHT else FG_DIM
                    repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = if (active) Color(55, 64, 90) else Color(37, 43, 59)
            g2.color = bg
            g2.fillRoundRect(0, 0, width, height, 6, 6)
            if (active) {
                g2.color = Color(100, 120, 180, 100)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
            }
            foreground = if (active) FG_BRIGHT else FG_DIM
            g2.dispose()
            super.paintComponent(g)
        }
    }

    // ── 品种状态表格（列表模式）───────────────────────────────
    private val statusTableModel = object : DefaultTableModel(
        arrayOf<Any>("品种", "最新价", "5min MA", "15min MA", "30min MA", "数据源", "状态"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    private val statusTable = JBTable(statusTableModel).apply {
        background          = BG2
        foreground          = FG
        gridColor           = JBColor(Color(37, 43, 59), Color(37, 43, 59))
        rowHeight           = 26
        showVerticalLines   = true
        showHorizontalLines = true
        tableHeader.background = JBColor(Color(21, 24, 35), Color(21, 24, 35))
        tableHeader.foreground = JBColor(Color(147, 197, 253), Color(147, 197, 253))
        tableHeader.font       = cnFont(Font.BOLD, 12)
        setDefaultRenderer(Any::class.java, StatusCellRenderer())
    }

    // ── 信号日志文本区 ──────────────────────────────────────
    private val logArea = JBTextArea().apply {
        isEditable    = false
        background    = BG2
        foreground    = FG_DIM
        font          = Font("Monospaced", Font.PLAIN, 12)
        lineWrap      = true
        wrapStyleWord = true
        margin        = JBUI.insets(6)
    }

    // ── 状态栏 ──────────────────────────────────────────────
    private val statusLabel = JLabel("⏸ 未启动").apply {
        foreground = FG_DIM
        font       = cnFont(Font.PLAIN, 11)
        border     = JBUI.Borders.emptyLeft(8)
    }

    // ── 启动/停止按钮 ───────────────────────────────────────
    private val toggleBtn = FancyButton("▶ 启动监控", fgNormal = FG, isPrimary = true).apply {
        addActionListener { toggleMonitor() }
    }

    // ── 刷新按钮 ────────────────────────────────────────────
    private val refreshBtn = FancyButton("🔄 刷新").apply {
        addActionListener { onRefresh() }
    }

    // ── 设置按钮 ────────────────────────────────────────────
    private val settingsBtn = FancyButton("⚙ 设置").apply {
        addActionListener { onOpenSettings() }
    }

    // ── 模式切换图标 ────────────────────────────────────────
    private val listModeBtn  = IconButton("☰", "列表模式").apply { active = true }
    private val chartModeBtn = IconButton("📊", "图像模式")

    // ── 图像模式：品种图卡列表 ──────────────────────────────
    /** key = symbol.code, value = 对应的图卡组件 */
    private val symbolCards = mutableMapOf<String, SymbolChartCard>()

    /** key = symbol.code, value = 图卡 wrapper 面板（用于控制显示/隐藏） */
    private val cardWrappers = mutableMapOf<String, JPanel>()

    /** 图像模式滚动容器内的网格面板 */
    private var chartGridPanel: JPanel? = null

    /** 最大化时的全屏容器 */
    private var chartFullscreenPanel: JPanel? = null

    /** CardLayout 引用（用于切换视图） */
    private var chartCardLayout: CardLayout? = null

    /** viewContainer 引用（CardLayout.show 的目标） */
    private var chartViewContainer: JPanel? = null

    companion object {
        private const val VIEW_GRID  = "GRID"
        private const val VIEW_FULL  = "FULL"
    }

    /** 当前最大化的品种代码，null 表示未最大化 */
    private var maximizedCardCode: String? = null

    // ── 当前视图状态 ─────────────────────────────────────────
    private var currentMode: ViewMode = ViewMode.LIST
    private var lastStatuses: List<SymbolStatus> = emptyList()

    /** 主体内容区（类属性，供 switchMode 直接操作） */
    private val centerArea = JPanel(BorderLayout()).apply { background = BG }

    /** 已保存的品种列表（监控未启动时维持图卡内容） */
    private var savedSymbols: List<StockSymbol> = emptyList()

    // ─────────────────────────────────────────────────────
    fun getContent(): JComponent {
        val root = JPanel(BorderLayout()).apply { background = BG }

        syncToggleBtn()

        // 预加载品种列表（供图形模式未启动时使用）
        if (savedSymbols.isEmpty()) {
            val svc = MonitorService.getInstance()
            savedSymbols = svc?.parseSymbols(StockMonitorSettings.getInstance().symbolsRaw) ?: emptyList()
        }

        // ── 顶部工具栏 ──
        val toolbar = buildToolbar()

        // ── 主体区域（动态切换）──
        centerArea.add(buildListContent(), BorderLayout.CENTER)

        // ── 底部状态栏 ──
        val statusBar = JPanel(BorderLayout()).apply {
            background = JBColor(Color(12, 14, 22), Color(12, 14, 22))
            border     = JBUI.Borders.empty(3, 0)
            add(statusLabel, BorderLayout.WEST)
        }

        root.add(toolbar,    BorderLayout.NORTH)
        root.add(centerArea, BorderLayout.CENTER)
        root.add(statusBar,  BorderLayout.SOUTH)

        // 注册回调
        MonitorService.getInstance()?.setUiCallbacks(
            onStatusUpdate = { statuses ->
                lastStatuses = statuses
                SwingUtilities.invokeLater {
                    if (currentMode == ViewMode.LIST) refreshTable(statuses)
                    else refreshChartGrid(statuses.map { it.symbol })
                    if (statuses.isNotEmpty()) savedSymbols = statuses.map { it.symbol }
                }
            },
            onSignal = { signal ->
                SwingUtilities.invokeLater { appendSignalLog(signal) }
            },
            onStatusText = { text ->
                SwingUtilities.invokeLater { statusLabel.text = text }
            },
            onSettingsApplied = {
                // 设置变更后，从最新配置重新读取品种列表（保持顺序同步）
                val fresh = MonitorService.getInstance()
                    ?.parseSymbols(StockMonitorSettings.getInstance().symbolsRaw)
                    ?: emptyList()
                savedSymbols = fresh
                SwingUtilities.invokeLater {
                    if (currentMode == ViewMode.LIST) {
                        // 列表模式：按设置顺序刷新
                        val ordered = fresh.mapNotNull { sym ->
                            lastStatuses.find { it.symbol.code == sym.code }?.also { it.symbol = sym }
                        }
                        if (ordered.isNotEmpty()) {
                            refreshTable(ordered)
                        }
                    } else {
                        // 图形模式：按设置顺序重建网格
                        refreshChartGrid(fresh)
                    }
                }
            }
        )

        return root
    }

    // ─────────────────────────────────────────────────────
    //  顶部工具栏
    // ─────────────────────────────────────────────────────

    private fun buildToolbar(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            background = JBColor(Color(21, 24, 35), Color(21, 24, 35))

            add(JLabel("📈 A股均线监控").apply {
                foreground = FG
                font       = cnFont(Font.BOLD, 13)
            })
            add(Box.createHorizontalStrut(6))
            add(listModeBtn.apply {
                addActionListener { switchMode(ViewMode.LIST) }
            })
            add(chartModeBtn.apply {
                addActionListener { switchMode(ViewMode.CHART) }
            })
            add(Box.createHorizontalStrut(4))
            add(toggleBtn)
            add(refreshBtn)
            add(settingsBtn)
        }
    }

    // ─────────────────────────────────────────────────────
    //  模式切换
    // ─────────────────────────────────────────────────────

    private fun switchMode(mode: ViewMode) {
        if (mode == currentMode) return
        currentMode = mode

        listModeBtn.active  = mode == ViewMode.LIST
        chartModeBtn.active = mode == ViewMode.CHART

        // 切换模式时重置最大化状态
        maximizedCardCode = null
        // 清理全屏容器并切回网格视图
        chartFullscreenPanel?.removeAll()
        chartCardLayout?.show(chartViewContainer!!, VIEW_GRID)

        // 直接操作 centerArea（工具栏保持不动）
        centerArea.removeAll()
        when (mode) {
            ViewMode.LIST  -> centerArea.add(buildListContent(), BorderLayout.CENTER)
            ViewMode.CHART -> centerArea.add(buildChartContent(), BorderLayout.CENTER)
        }
        centerArea.revalidate()
        centerArea.repaint()

        when (mode) {
            ViewMode.CHART -> {
                // 优先从设置读取最新顺序，确保与设置界面一致
                val symbols = when {
                    savedSymbols.isNotEmpty() -> savedSymbols
                    lastStatuses.isNotEmpty() -> lastStatuses.map { it.symbol }
                    else -> MonitorService.getInstance()
                        ?.parseSymbols(StockMonitorSettings.getInstance().symbolsRaw)
                        ?: emptyList()
                }
                if (symbols.isNotEmpty()) refreshChartGrid(symbols)
            }
            ViewMode.LIST -> {
                if (lastStatuses.isNotEmpty()) {
                    refreshTable(lastStatuses)
                } else {
                    loadListFromCache()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────
    //  列表模式内容
    // ─────────────────────────────────────────────────────

    private fun buildListContent(): JComponent {
        val ruleBar = JLabel(
            "<html><span style='color:#718096;font-size:10px'>" +
            "🚀 5/15min突破  ⚠️ 15/30min跌破  📈 30min独立突破  📉 30min独立跌破  | MA60 · 连续2根确认" +
            "</span></html>"
        ).apply {
            background = JBColor(Color(19, 23, 32), Color(19, 23, 32))
            isOpaque   = true
            border     = JBUI.Borders.empty(4, 10)
        }

        val topScroll = JBScrollPane(statusTable).apply {
            background    = BG2
            border        = JBUI.Borders.empty()
            preferredSize = Dimension(-1, 260)
        }

        val logHeader = JPanel(BorderLayout()).apply {
            background = JBColor(Color(21, 24, 35), Color(21, 24, 35))
            border     = JBUI.Borders.empty(4, 8)
            add(JLabel("🔔 信号日志").apply {
                foreground = JBColor(Color(147, 197, 253), Color(147, 197, 253))
                font       = cnFont(Font.BOLD, 12)
            }, BorderLayout.WEST)
            add(FancyButton("清空", fgNormal = FG_DIM).apply {
                addActionListener { clearLog() }
            }, BorderLayout.EAST)
        }
        val logScroll = JBScrollPane(logArea).apply {
            background = BG2
            border     = JBUI.Borders.empty()
        }
        val logPanel = JPanel(BorderLayout()).apply {
            background = BG2
            add(logHeader, BorderLayout.NORTH)
            add(logScroll, BorderLayout.CENTER)
        }

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, topScroll, logPanel).apply {
            dividerSize  = 4
            resizeWeight = 0.55
            border       = JBUI.Borders.empty()
            background   = BG
        }

        return JPanel(BorderLayout()).apply {
            background = BG
            add(ruleBar, BorderLayout.NORTH)
            add(split,   BorderLayout.CENTER)
        }
    }

    // ─────────────────────────────────────────────────────
    //  图像模式内容
    // ─────────────────────────────────────────────────────

    private fun buildChartContent(): JComponent {
        // 3列网格
        val gridPanel = JPanel(GridLayout(0, 3, 6, 6)).apply {
            background = BG
            border     = JBUI.Borders.empty(6)
        }
        chartGridPanel = gridPanel

        // 全屏容器（BorderLayout.CENTER 自然填满视口）
        val fullscreenPanel = JPanel(BorderLayout(0, 0)).apply {
            background = BG
        }
        chartFullscreenPanel = fullscreenPanel

        // CardLayout 包装两个视图，通过 show() 切换
        val cardLayout = CardLayout(0, 0)
        val viewContainer = JPanel(cardLayout).apply {
            background = BG
            add(gridPanel,      VIEW_GRID)
            add(fullscreenPanel, VIEW_FULL)
        }
        chartCardLayout    = cardLayout
        chartViewContainer = viewContainer
        // 默认显示网格视图
        cardLayout.show(viewContainer, VIEW_GRID)

        // 滚动面板只持有 viewContainer
        val scrollPane = JBScrollPane(viewContainer).apply {
            background              = BG
            border                  = JBUI.Borders.empty()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 20
        }

        // 初次构建：优先从设置读取最新顺序
        val symbols = when {
            savedSymbols.isNotEmpty() -> savedSymbols
            lastStatuses.isNotEmpty() -> lastStatuses.map { it.symbol }
            else -> MonitorService.getInstance()
                ?.parseSymbols(StockMonitorSettings.getInstance().symbolsRaw)
                ?: emptyList()
        }
        for (sym in symbols) {
            val card = getOrCreateCard(sym)
            val wrapper = JPanel(BorderLayout()).apply {
                background = BG
                add(card.panel, BorderLayout.CENTER)
            }
            cardWrappers[sym.code] = wrapper
            gridPanel.add(wrapper)
            loadChartForCard(sym.code, sym.exchange, card.currentTf)
        }

        return scrollPane
    }

    /**
     * 获取或新建指定品种的图卡。
     * 首次切换到图像模式后，立即触发该品种当前周期数据加载。
     */
    private fun getOrCreateCard(sym: StockSymbol): SymbolChartCard {
        return symbolCards.getOrPut(sym.code) {
            SymbolChartCard(
                sym,
                onLoadRequest = { code, exchange, tf ->
                    loadChartForCard(code, exchange, tf)
                },
                onMaximizeToggle = { code ->
                    toggleCardMaximize(code)
                }
            )
        }
    }

    /**
     * 切换指定品种图卡的最大化/还原状态。
     * 最大化时该卡片填满整个滚动区域（不含工具栏），其他图卡隐藏；
     * 还原时恢复 3 列网格，全部图卡显示。
     */
    private fun toggleCardMaximize(code: String) {
        val grid       = chartGridPanel ?: return
        val fullscreen = chartFullscreenPanel ?: return
        val card       = symbolCards[code] ?: return
        val wrapper    = cardWrappers[code] ?: return
        val cl         = chartCardLayout ?: return
        val container  = chartViewContainer ?: return

        SwingUtilities.invokeLater {
            if (maximizedCardCode == code) {
                // ── 还原 ──
                maximizedCardCode = null
                card.restoreNormal()
                // 把图卡移回网格 wrapper（替换掉 gridPanel 中的旧引用）
                val gridIdx = (0 until grid.componentCount).firstOrNull {
                    grid.getComponent(it) == wrapper
                }
                if (gridIdx == null || grid.getComponent(gridIdx) != wrapper) {
                    // wrapper 还在 gridPanel 中（从未被移走）
                }
                // 从全屏容器移回网格（BorderLayout.CENTER 替换）
                fullscreen.removeAll()
                wrapper.removeAll()
                wrapper.add(card.panel, BorderLayout.CENTER)
                grid.add(wrapper)
                // 切换回网格视图
                cl.show(container, VIEW_GRID)
            } else {
                // ── 最大化 ──
                // 先还原旧的（如果之前有）
                if (maximizedCardCode != null && maximizedCardCode != code) {
                    val oldCard    = symbolCards[maximizedCardCode]
                    val oldWrapper = cardWrappers[maximizedCardCode]
                    oldCard?.restoreNormal()
                    oldWrapper?.let { ow ->
                        fullscreen.removeAll()
                        ow.removeAll()
                        ow.add(oldCard!!.panel, BorderLayout.CENTER)
                        grid.add(ow)
                    }
                }
                maximizedCardCode = code
                card.toggleMaximized()
                // 把图卡从网格移到全屏容器
                grid.remove(wrapper)
                wrapper.removeAll()
                wrapper.add(card.panel, BorderLayout.CENTER)
                fullscreen.removeAll()
                fullscreen.add(wrapper, BorderLayout.CENTER)
                // 切换到全屏视图
                cl.show(container, VIEW_FULL)
            }
        }
    }

    /**
     * 刷新图像模式网格（品种列表或顺序发生变化时调用）。
     * 按传入 symbols 的顺序重建网格，保持已有卡片状态。
     * 只在图像模式下有效。
     */
    private fun refreshChartGrid(symbols: List<StockSymbol>) {
        if (currentMode != ViewMode.CHART) return
        val grid = chartGridPanel ?: return
        SwingUtilities.invokeLater {
            // 清空网格（保留卡片数据结构以便复用）
            grid.removeAll()

            // 按 settings 顺序重建网格
            for (sym in symbols) {
                val card = getOrCreateCard(sym)
                val wrapper = cardWrappers.getOrPut(sym.code) {
                    JPanel(BorderLayout()).apply {
                        background = BG
                        add(card.panel, BorderLayout.CENTER)
                    }
                }
                // 确保 wrapper 内容与当前 card 同步（防止被全屏模式遗留状态污染）
                wrapper.removeAll()
                wrapper.add(card.panel, BorderLayout.CENTER)
                cardWrappers[sym.code] = wrapper
                grid.add(wrapper)

                // 仅对新增品种触发数据加载（已有数据的无需重复加载）
                val existing = symbolCards[sym.code]
                if (existing == null) {
                    loadChartForCard(sym.code, sym.exchange, card.currentTf)
                }
            }

            grid.revalidate()
            grid.repaint()
        }
    }

    /** 异步加载指定品种+周期的图表数据，结果设置到对应图卡的 renderer */
    private fun loadChartForCard(code: String, exchange: String, tf: ChartTimeframe) {
        val card = symbolCards[code] ?: return
        card.setLoading(true)

        ApplicationManager.getApplication().executeOnPooledThread {
            // 获取服务引用（可能为 null，此时直接报错退出）
            val svc = MonitorService.getInstance()
            if (svc == null) {
                SwingUtilities.invokeLater { card.setError("⚠ 服务未初始化，请先启动监控") }
                return@executeOnPooledThread
            }

            try {
                val (candles, ma30, ma60) = svc.getChartData(code, exchange, tf)
                val latestPrice = candles.lastOrNull()?.close
                SwingUtilities.invokeLater {
                    card.setData(candles, ma30, ma60, tf, latestPrice)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { card.setError(e.message ?: "加载失败") }
            }
        }
    }

    // ─────────────────────────────────────────────────────
    //  按钮文字与服务状态同步
    // ─────────────────────────────────────────────────────

    private fun syncToggleBtn() {
        val running = MonitorService.getInstance()?.isRunning() ?: false
        if (running) {
            toggleBtn.text = "⏸ 停止监控"
            toggleBtn.setFgNormal(Color(252, 165, 50))
            statusLabel.text = "● 监控运行中"
        } else {
            toggleBtn.text = "▶ 启动监控"
            toggleBtn.setFgNormal(FG)
            statusLabel.text = "⏸ 未启动"
        }
    }

    // ─────────────────────────────────────────────────────
    //  表格刷新
    // ─────────────────────────────────────────────────────

    private fun refreshTable(statuses: List<SymbolStatus>) {
        statusTableModel.rowCount = 0
        for (s in statuses) {
            val lastSig = s.lastSignal
            val sigText = lastSig?.let { "${it.type.icon} ${it.type.label}" } ?: "—"
            statusTableModel.addRow(arrayOf(
                "${s.symbol.name}  ${s.symbol.code}",
                s.latestPrice5?.let { "%.4f".format(it) } ?: "—",
                s.ma5?.let  { "%.4f".format(it) } ?: "—",
                s.ma15?.let { "%.4f".format(it) } ?: "—",
                s.ma30?.let { "%.4f".format(it) } ?: "—",
                s.dataSource,
                sigText
            ))
        }
    }

    /**
     * 监控未启动时，从 MonitorService 缓存或设置配置中拉取最近交易日数据，
     * 并填充列表模式表格。
     */
    private fun loadListFromCache() {
        val svc = MonitorService.getInstance() ?: return
        val symbols = when {
            lastStatuses.isNotEmpty() -> lastStatuses.map { it.symbol }
            savedSymbols.isNotEmpty() -> savedSymbols
            else -> svc.parseSymbols(StockMonitorSettings.getInstance().symbolsRaw).also { savedSymbols = it }
        }
        if (symbols.isEmpty()) {
            statusLabel.text = "⚠ 未配置任何品种，请先在设置中添加"
            return
        }

        statusLabel.text = "📡 正在加载最近交易日数据..."

        ApplicationManager.getApplication().executeOnPooledThread {
            val rows = symbols.mapNotNull { sym ->
                try {
                    val r5  = fetchKlineMulti(sym.code, sym.exchange, 5,  65)
                    val r15 = fetchKlineMulti(sym.code, sym.exchange, 15, 65)
                    val r30 = fetchKlineMulti(sym.code, sym.exchange, 30, 65)
                    val c5  = r5.closes; val c15 = r15.closes; val c30 = r30.closes
                    if (c5.size < 3) return@mapNotNull null
                    val period = StockMonitorSettings.getInstance().maPeriod
                    arrayOf(
                        "${sym.name}  ${sym.code}",
                        "%.4f".format(c5.last()),
                        "%.4f".format(com.stockmonitor.data.calcMa(c5, period)),
                        "%.4f".format(com.stockmonitor.data.calcMa(c15, period)),
                        "%.4f".format(com.stockmonitor.data.calcMa(c30, period)),
                        r5.sourceName,
                        "—（缓存）"
                    )
                } catch (_: Exception) { null }
            }

            SwingUtilities.invokeLater {
                if (rows.isNotEmpty()) {
                    statusTableModel.rowCount = 0
                    for (row in rows) statusTableModel.addRow(row)
                    statusLabel.text = "📋 已加载 ${symbols.size} 个品种（最近交易日）"
                } else {
                    statusLabel.text = "⚠ 加载失败，请启动监控重试"
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────
    //  日志追加
    // ─────────────────────────────────────────────────────

    private fun appendSignalLog(signal: Signal) {
        val ts   = sdf.format(Date(signal.timestamp))
        val icon = signal.type.icon
        val line = buildString {
            appendLine("${"─".repeat(48)}")
            appendLine("$icon [$ts]  ${signal.toNotificationTitle()}")
            appendLine("   ${signal.toNotificationContent().replace("\n", "\n   ")}")
        }
        logArea.append(line)
        logArea.caretPosition = logArea.document.length
    }

    private fun clearLog() { logArea.text = "" }

    // ─────────────────────────────────────────────────────
    //  启停监控
    // ─────────────────────────────────────────────────────

    private fun toggleMonitor() {
        val svc = MonitorService.getInstance() ?: run {
            statusLabel.text = "⚠ 服务未初始化，请重启 IDEA"
            return
        }
        if (svc.isRunning()) svc.stop() else svc.start()
        syncToggleBtn()
    }

    // ─────────────────────────────────────────────────────
    //  刷新
    // ─────────────────────────────────────────────────────

    private fun onRefresh() {
        val svc = MonitorService.getInstance()
        if (svc == null || !svc.isRunning()) {
            val result = JOptionPane.showConfirmDialog(
                null, "监控尚未启动，是否先启动监控？",
                "提示", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE
            )
            if (result == JOptionPane.YES_OPTION) {
                svc?.start() ?: return
                syncToggleBtn()
                statusLabel.text = "🔄 正在首次拉取数据..."
            }
            return
        }
        statusLabel.text = "🔄 手动刷新中..."
        svc.forceRefresh()
    }

    // ─────────────────────────────────────────────────────
    //  打开设置
    // ─────────────────────────────────────────────────────

    private fun onOpenSettings() {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, StockMonitorConfigurable::class.java)
        syncToggleBtn()
    }

    // ─────────────────────────────────────────────────────
    //  自定义单元格渲染器
    // ─────────────────────────────────────────────────────

    inner class StatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            background = if (isSelected) JBColor(Color(45, 55, 72), Color(45, 55, 72)) else BG2
            foreground = when {
                column == 6 && value != null -> {
                    val v = value.toString()
                    when {
                        "🚀" in v || "📈" in v -> COLOR_UP
                        "⚠️" in v || "📉" in v -> COLOR_DOWN
                        else -> FG_DIM
                    }
                }
                column == 0 -> FG
                else        -> FG_DIM
            }
            font   = if (column == 0) cnFont(Font.BOLD, 12) else Font("Monospaced", Font.PLAIN, 11)
            border = JBUI.Borders.empty(0, 6)
            return this
        }
    }
}
