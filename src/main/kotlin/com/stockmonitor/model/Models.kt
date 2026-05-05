package com.stockmonitor.model

// ─────────────────────────────────────────────────────────────
//  数据模型
// ─────────────────────────────────────────────────────────────

/** 单个监控品种配置 */
data class StockSymbol(
    val code: String,       // 股票代码，如 "518880"
    val name: String,       // 显示名称，如 "黄金ETF华安"
    val exchange: String    // "sh" 或 "sz"
)

/** 信号类型 */
enum class SignalType(val label: String, val icon: String) {
    BREAK_UP    ("5/15min突破",   "🚀"),
    BREAK_DOWN  ("15/30min跌破",  "⚠️"),
    BREAK_UP_30 ("30min突破",     "📈"),
    BREAK_DOWN_30("30min跌破",    "📉")
}

/** 触发信号 */
data class Signal(
    val type: SignalType,
    val symbol: StockSymbol,
    val price: Double,
    val ma5: Double,
    val ma15: Double,
    val ma30: Double,
    val confirmedBars: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isUp() = type == SignalType.BREAK_UP || type == SignalType.BREAK_UP_30

    fun toNotificationTitle() = "【${type.label}】${symbol.name}（${symbol.code}）"

    fun toNotificationContent(): String = when (type) {
        SignalType.BREAK_UP ->
            "价格 ${"%.4f".format(price)} 连续 ${confirmedBars} 根K线站上\n" +
            "5min MA60=${"%,.4f".format(ma5)}  15min MA60=${"%,.4f".format(ma15)}"
        SignalType.BREAK_DOWN ->
            "价格 ${"%.4f".format(price)} 连续 ${confirmedBars} 根K线跌破\n" +
            "15min MA60=${"%,.4f".format(ma15)}  30min MA60=${"%,.4f".format(ma30)}"
        SignalType.BREAK_UP_30 ->
            "30min价格 ${"%.4f".format(price)} 连续 ${confirmedBars} 根K线站上\n" +
            "30min MA60=${"%,.4f".format(ma30)}"
        SignalType.BREAK_DOWN_30 ->
            "30min价格 ${"%.4f".format(price)} 连续 ${confirmedBars} 根K线跌破\n" +
            "30min MA60=${"%,.4f".format(ma30)}"
    }
}

/** 品种实时状态（用于 UI 展示） */
data class SymbolStatus(
    var symbol: StockSymbol,
    var latestPrice5: Double? = null,
    var ma5: Double? = null,
    var ma15: Double? = null,
    var ma30: Double? = null,
    var lastSignal: Signal? = null,
    var dataSource: String = "—",       // 上次成功的数据源名称
    var lastUpdateMs: Long = 0L,
    var error: String? = null
)

/** 单根 K 线（完整 OHLCV） */
data class Candle(
    val timestamp: Long,   // 时间戳（毫秒）
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
)

/** 图表时间周期枚举 */
enum class ChartTimeframe(
    val label: String,
    val icon: String,
    val emKlt: Int,     // 东方财富 klt 参数（0=日线，1=周线，2=月线，分钟级别=数字）
    val desc: String    // 描述
) {
    INTRADAY("分时", "📊", -1,  "日内分时"),
    M5     ("5分",  "5'",  5,   "5分钟线"),
    M15    ("15分", "15'", 15,  "15分钟线"),
    M30    ("30分", "30'", 30,  "30分钟线"),
    M60    ("60分", "60'", 60,  "60分钟线"),
    DAILY  ("日线", "D",   101, "日 K 线"),
    WEEKLY ("周线", "W",   102, "周 K 线"),
    MONTHLY("月线", "M",   103, "月 K 线")
}
