package com.stockmonitor.data

import com.stockmonitor.model.Signal
import com.stockmonitor.model.SignalType
import com.stockmonitor.model.StockSymbol

// ─────────────────────────────────────────────────────────────
//  均线计算
// ─────────────────────────────────────────────────────────────

fun calcMa(closes: List<Double>, period: Int): Double? {
    if (closes.size < period) return null
    return closes.takeLast(period).average()
}

// ─────────────────────────────────────────────────────────────
//  信号检测器（每个品种独立一个实例，维护连续计数状态）
// ─────────────────────────────────────────────────────────────

class SignalDetector(
    private val symbol: StockSymbol,
    private val maPeriod: Int,
    private val confirmBars: Int
) {
    // 连续计数
    private var upCount      = 0
    private var downCount    = 0
    private var up30Count    = 0
    private var down30Count  = 0

    // 上次触发状态（防重复）
    private var lastUpSignal    = false
    private var lastDownSignal  = false
    private var lastUp30Signal  = false
    private var lastDown30Signal = false

    /**
     * 传入三周期收盘价列表（时间升序），返回本次触发的信号列表
     */
    fun check(
        closes5: List<Double>,
        closes15: List<Double>,
        closes30: List<Double>
    ): List<Signal> {
        val signals = mutableListOf<Signal>()

        val ma5  = calcMa(closes5,  maPeriod) ?: return signals
        val ma15 = calcMa(closes15, maPeriod) ?: return signals
        val ma30 = calcMa(closes30, maPeriod) ?: return signals

        val p5  = closes5.lastOrNull()  ?: return signals
        val p15 = closes15.lastOrNull()
        val p30 = closes30.lastOrNull()

        // ── 5/15min 联合突破 ──────────────────────────
        val above5  = p5 > ma5
        val above15 = p15 != null && p15 > ma15
        if (above5 && above15) { upCount++;   downCount = 0 } else upCount = 0

        // ── 15/30min 联合跌破 ──────────────────────────
        val below15 = p15 != null && p15 < ma15
        val below30 = p30 != null && p30 < ma30
        if (below15 && below30) { downCount++; upCount = 0 } else downCount = 0

        // ── 30min 独立突破 ────────────────────────────
        val above30 = p30 != null && p30 > ma30
        if (above30) { up30Count++;   down30Count = 0 } else up30Count = 0

        // ── 30min 独立跌破 ────────────────────────────
        val below30only = p30 != null && p30 < ma30
        if (below30only) down30Count++ else down30Count = 0

        // ── 触发判断 ──────────────────────────────────
        fun makeSignal(type: SignalType, price: Double) = Signal(
            type = type, symbol = symbol, price = price,
            ma5 = ma5, ma15 = ma15, ma30 = ma30,
            confirmedBars = when (type) {
                SignalType.BREAK_UP      -> upCount
                SignalType.BREAK_DOWN    -> downCount
                SignalType.BREAK_UP_30   -> up30Count
                SignalType.BREAK_DOWN_30 -> down30Count
            }
        )

        if (upCount >= confirmBars && !lastUpSignal) {
            lastUpSignal = true; lastDownSignal = false
            signals += makeSignal(SignalType.BREAK_UP, p5)
        } else if (upCount < confirmBars) lastUpSignal = false

        if (downCount >= confirmBars && !lastDownSignal) {
            lastDownSignal = true; lastUpSignal = false
            signals += makeSignal(SignalType.BREAK_DOWN, p15 ?: p5)
        } else if (downCount < confirmBars) lastDownSignal = false

        if (up30Count >= confirmBars && !lastUp30Signal) {
            lastUp30Signal = true; lastDown30Signal = false
            p30?.let { signals += makeSignal(SignalType.BREAK_UP_30, it) }
        } else if (up30Count < confirmBars) lastUp30Signal = false

        if (down30Count >= confirmBars && !lastDown30Signal) {
            lastDown30Signal = true; lastUp30Signal = false
            p30?.let { signals += makeSignal(SignalType.BREAK_DOWN_30, it) }
        } else if (down30Count < confirmBars) lastDown30Signal = false

        return signals
    }

    fun reset() {
        upCount = 0; downCount = 0; up30Count = 0; down30Count = 0
        lastUpSignal = false; lastDownSignal = false
        lastUp30Signal = false; lastDown30Signal = false
    }
}
