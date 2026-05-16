package com.stockmonitor.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.stockmonitor.data.SignalDetector
import com.stockmonitor.data.fetchKlineMulti
import com.stockmonitor.data.fetchKlineFull
import com.stockmonitor.data.fetchIntradayTencent
import com.stockmonitor.model.Candle
import com.stockmonitor.model.ChartTimeframe
import com.stockmonitor.model.Signal
import com.stockmonitor.model.SignalType
import com.stockmonitor.model.StockSymbol
import com.stockmonitor.model.SymbolStatus
import com.stockmonitor.settings.StockMonitorSettings
import java.time.LocalTime
import java.util.concurrent.*

// ─────────────────────────────────────────────────────────────
//  后台监控服务
// ─────────────────────────────────────────────────────────────

@Service(Service.Level.APP)
class MonitorService {

    // ── UI 回调 ──────────────────────────────────
    private var onStatusUpdate: ((List<SymbolStatus>) -> Unit)? = null
    private var onSignal:       ((Signal) -> Unit)?             = null
    private var onStatusText:   ((String) -> Unit)?             = null
    /** 设置被应用时回调，用于通知面板刷新品种顺序 */
    private var onSettingsApplied: (() -> Unit)? = null

    // ── 内部状态 ─────────────────────────────────
    private var scheduler: ScheduledExecutorService? = null
    private var running = false

    private val detectors   = mutableMapOf<String, SignalDetector>()
    private val statusMap   = mutableMapOf<String, SymbolStatus>()
    private val signalHistory = ArrayDeque<Signal>(200)

    // 图表数据缓存: symbolCode -> (ChartTimeframe.emKlt -> candles)
    private val chartCache = mutableMapOf<String, MutableMap<Int, List<Candle>>>()

    // ─────────────────────────────────────────────
    fun setUiCallbacks(
        onStatusUpdate: (List<SymbolStatus>) -> Unit,
        onSignal:       (Signal) -> Unit,
        onStatusText:   (String) -> Unit,
        onSettingsApplied: (() -> Unit)? = null
    ) {
        this.onStatusUpdate = onStatusUpdate
        this.onSignal       = onSignal
        this.onStatusText   = onStatusText
        this.onSettingsApplied = onSettingsApplied
    }

    // ─────────────────────────────────────────────
    fun isRunning() = running

    fun start() {
        if (running) return
        running = true
        val interval = StockMonitorSettings.getInstance().pollInterval.toLong()
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "StockMonitor-Worker").also { it.isDaemon = true }
        }
        // 立即执行一次，然后每 interval 秒执行
        scheduler!!.scheduleWithFixedDelay(::runCycle, 0, interval, TimeUnit.SECONDS)
        onStatusText?.invoke("● 监控运行中")
    }

    fun stop() {
        running = false
        scheduler?.shutdownNow()
        scheduler = null
        onStatusText?.invoke("⏸ 已停止")
    }

    fun restart() {
        stop()
        rebuildDetectors()
        onSettingsApplied?.invoke()
        start()
    }

    fun forceRefresh() {
        if (!running) return
        ApplicationManager.getApplication().executeOnPooledThread { runCycle() }
    }

    // ─────────────────────────────────────────────
    //  核心轮询逻辑
    // ─────────────────────────────────────────────

    private fun runCycle() {
        val s = StockMonitorSettings.getInstance()

        // 交易时段过滤
        if (s.tradingHoursOnly && !isTradingHours()) {
            onStatusText?.invoke("😴 非交易时段，等待中...")
            return
        }

        val symbols = parseSymbols(s.symbolsRaw)
        if (symbols.isEmpty()) {
            onStatusText?.invoke("⚠ 未配置任何品种")
            return
        }

        onStatusText?.invoke("🔄 正在拉取行情数据...")

        // 确保 detectors 与最新品种同步
        syncDetectors(symbols, s.maPeriod, s.confirmBars)

        val updatedStatuses = mutableListOf<SymbolStatus>()

        for (sym in symbols) {
            val logLines = StringBuilder()
            try {
                val r5  = fetchKlineMulti(sym.code, sym.exchange, 5,  65) { logLines.append(it) }
                val r15 = fetchKlineMulti(sym.code, sym.exchange, 15, 65) { logLines.append(it) }
                val r30 = fetchKlineMulti(sym.code, sym.exchange, 30, 65) { logLines.append(it) }

                val c5  = r5.closes
                val c15 = r15.closes
                val c30 = r30.closes

                val status = statusMap.getOrPut(sym.code) { SymbolStatus(sym) }
                status.symbol      = sym
                status.dataSource  = r5.sourceName
                status.lastUpdateMs = System.currentTimeMillis()
                status.error       = null

                if (c5.size >= 3) {
                    status.latestPrice5 = c5.last()
                    status.ma5  = com.stockmonitor.data.calcMa(c5,  s.maPeriod)
                    status.ma15 = com.stockmonitor.data.calcMa(c15, s.maPeriod)
                    status.ma30 = com.stockmonitor.data.calcMa(c30, s.maPeriod)

                    val signals = detectors[sym.code]?.check(c5, c15, c30) ?: emptyList()
                    for (sig in signals) {
                        status.lastSignal = sig
                        signalHistory.addLast(sig)
                        if (signalHistory.size > 200) signalHistory.removeFirst()

                        onSignal?.invoke(sig)
                        if (s.notifyIdea)     sendIdeaNotification(sig)
                        if (s.notifyDingtalk) sendDingtalkAsync(sig, s)
                    }
                } else {
                    status.error = "数据不足"
                }

                updatedStatuses += status

            } catch (e: Exception) {
                val status = statusMap.getOrPut(sym.code) { SymbolStatus(sym) }
                status.error = e.message
                updatedStatuses += status
            }
        }

        onStatusUpdate?.invoke(updatedStatuses)
        val interval = s.pollInterval
        val intervalStr = if (interval >= 60) "${interval / 60}分钟" else "${interval}秒"
        onStatusText?.invoke("✅ 监控中，下次更新: ${intervalStr}后")
    }

    // ─────────────────────────────────────────────
    //  IDEA 通知
    // ─────────────────────────────────────────────

    private fun sendIdeaNotification(signal: Signal) {
        val type = if (signal.isUp()) NotificationType.INFORMATION else NotificationType.WARNING
        try {
            val group = NotificationGroupManager.getInstance()
                .getNotificationGroup("A Stock Monitor Signals")
            group.createNotification(
                signal.toNotificationTitle(),
                signal.toNotificationContent().replace("\n", "<br>"),
                type
            ).notify(null)   // null = 全局通知，不绑定特定 project
        } catch (e: Exception) {
            // 降级：使用 balloon
            try {
                com.intellij.notification.Notifications.Bus.notify(
                    com.intellij.notification.Notification(
                        "A Stock Monitor Signals",
                        signal.toNotificationTitle(),
                        signal.toNotificationContent(),
                        type
                    )
                )
            } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────
    //  钉钉异步推送
    // ─────────────────────────────────────────────

    private fun sendDingtalkAsync(signal: Signal, s: StockMonitorSettings) {
        ApplicationManager.getApplication().executeOnPooledThread {
            DingtalkSender.send(
                webhook  = s.dingtalkWebhook,
                secret   = s.dingtalkSecret,
                keyword  = s.dingtalkKeyword,
                title    = signal.toNotificationTitle(),
                content  = buildDingtalkContent(signal)
            )
        }
    }

    private fun buildDingtalkContent(sig: Signal): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            .format(java.util.Date(sig.timestamp))
        return buildString {
            appendLine("**品种：** ${sig.symbol.name}（${sig.symbol.code}）\n")
            appendLine("**信号：** ${sig.type.icon} ${sig.type.label}\n")
            appendLine("**时间：** $ts\n")
            appendLine("**价格：** ${"%.4f".format(sig.price)}\n")
            when (sig.type) {
                SignalType.BREAK_UP -> {
                    appendLine("**5min MA60：** ${"%.4f".format(sig.ma5)}\n")
                    appendLine("**15min MA60：** ${"%.4f".format(sig.ma15)}\n")
                }
                SignalType.BREAK_DOWN -> {
                    appendLine("**15min MA60：** ${"%.4f".format(sig.ma15)}\n")
                    appendLine("**30min MA60：** ${"%.4f".format(sig.ma30)}\n")
                }
                SignalType.BREAK_UP_30, SignalType.BREAK_DOWN_30 ->
                    appendLine("**30min MA60：** ${"%.4f".format(sig.ma30)}\n")
            }
            appendLine("**连续确认：** ${sig.confirmedBars} 根K线\n")
            append("> ⚠️ 仅供参考，不构成投资建议")
        }
    }

    // ─────────────────────────────────────────────
    //  辅助工具
    // ─────────────────────────────────────────────

    private fun isTradingHours(): Boolean {
        val now = LocalTime.now()
        val wd  = java.time.DayOfWeek.from(java.time.LocalDate.now())
        if (wd == java.time.DayOfWeek.SATURDAY || wd == java.time.DayOfWeek.SUNDAY) return false
        val amOpen  = LocalTime.of(9, 30)
        val amClose = LocalTime.of(11, 30)
        val pmOpen  = LocalTime.of(13, 0)
        val pmClose = LocalTime.of(15, 0)
        return now in amOpen..amClose || now in pmOpen..pmClose
    }

    private operator fun ClosedRange<LocalTime>.contains(v: LocalTime) =
        v >= start && v <= endInclusive

    fun parseSymbols(raw: String): List<StockSymbol> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size >= 3)
                StockSymbol(
                    code     = parts[0].trim(),
                    name     = parts[1].trim(),
                    exchange = parts[2].trim().lowercase()
                )
            else null
        }

    private fun syncDetectors(symbols: List<StockSymbol>, maPeriod: Int, confirmBars: Int) {
        for (sym in symbols) {
            detectors.getOrPut(sym.code) {
                SignalDetector(sym, maPeriod, confirmBars)
            }
        }
        // 移除已删除品种的 detector
        val codes = symbols.map { it.code }.toSet()
        detectors.keys.retainAll(codes)
        statusMap.keys.retainAll(codes)
    }

    private fun rebuildDetectors() {
        detectors.clear()
        statusMap.clear()
    }

    /** 获取最近一次已缓存的品种状态（监控停止后仍可调用） */
    fun getLastCachedStatuses(): List<SymbolStatus> = statusMap.values.toList()

    // ─────────────────────────────────────────────
    //  图表数据查询（异步获取，最新数据从接口拉取）
    // ─────────────────────────────────────────────

    /**
     * 获取指定品种 + 时间周期的 K 线数据。
     * 优先从缓存返回，同时异步刷新最新数据更新缓存。
     * 返回: Pair(candles, ma30Series, ma60Series)
     */
    fun getChartData(symbolCode: String, exchange: String, tf: ChartTimeframe): Triple<List<Candle>, List<Double?>, List<Double?>> {
        val klt    = tf.emKlt
        val candles = if (tf == ChartTimeframe.INTRADAY) {
            fetchIntradayTencent(symbolCode, exchange)
        } else {
            fetchKlineFull(symbolCode, exchange, klt, 150)
        }

        // 更新缓存
        chartCache.getOrPut(symbolCode) { mutableMapOf() }[klt] = candles

        val ma30 = com.stockmonitor.data.calcMaSeriesCandles(candles, 30)
        val ma60 = com.stockmonitor.data.calcMaSeriesCandles(candles, 60)
        return Triple(candles, ma30, ma60)
    }

    companion object {
        fun getInstance(): MonitorService? = try {
            ApplicationManager.getApplication()
                .getService(MonitorService::class.java)
        } catch (_: Exception) {
            // IDE 尚未完全初始化时可能抛异常，兼容处理
            null
        }
    }
}
