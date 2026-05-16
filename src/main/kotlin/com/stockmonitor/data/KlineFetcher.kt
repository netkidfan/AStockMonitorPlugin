package com.stockmonitor.data

import com.google.gson.JsonParser
import com.google.gson.JsonElement
import com.stockmonitor.model.Candle
import com.stockmonitor.settings.StockMonitorSettings
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────
//  多平台 K 线数据获取
//  优先级: 东方财富 → 新浪财经 → 腾讯财经
// ─────────────────────────────────────────────────────────────

private const val TIMEOUT_MS = 10_000

/** klt → 新浪 scale */
private val SINA_SCALE = mapOf(5 to 5, 15 to 15, 30 to 30)

/** klt → 腾讯 period 字符串 */
private val TX_PERIOD = mapOf(5 to "m5", 15 to "m15", 30 to "m30")

/** 每个 symbol_klt 上次成功的平台索引（0/1/2） */
private val providerIndex = mutableMapOf<String, Int>()

// ─────────────────────────────────────────────────────────────
//  HTTP 工具
// ─────────────────────────────────────────────────────────────

private fun httpGet(urlStr: String, referer: String): String {
    val conn = URL(urlStr).openConnection() as HttpURLConnection
    conn.connectTimeout = TIMEOUT_MS
    conn.readTimeout    = TIMEOUT_MS
    conn.setRequestProperty("User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
    conn.setRequestProperty("Referer", referer)
    conn.connect()
    return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        .also { conn.disconnect() }
}

/**
 * 解析 JSON，容忍轻微格式问题（MalformedJsonException）。
 * 当腾讯接口返回不标准 JSON 时 gracefully fallback 到空数据。
 */
private fun parseJsonLenient(raw: String): JsonElement? {
    if (raw.isBlank()) return null
    return try {
        JsonParser.parseString(raw)
    } catch (e: com.google.gson.stream.MalformedJsonException) {
        null
    } catch (e: Exception) {
        null
    }
}

// ─────────────────────────────────────────────────────────────
//  代码格式转换
// ─────────────────────────────────────────────────────────────

/** 东方财富 secid：0=深圳(SZ)，1=上海(SH) */
fun emCode(symbol: String, exchange: String): String {
    val market = when (exchange.lowercase()) {
        "sz", "sza", "shenzhen" -> "0"  // 深圳
        "sh", "sha", "shanghai" -> "1"  // 上海
        else                     -> "0"  // 未知默认深市（兼容旧配置）
    }
    return "$market.$symbol"
}

/** 新浪/腾讯代码格式  sz159934 / sh518880 */
fun sinaCode(symbol: String, exchange: String) = "$exchange$symbol"

// ─────────────────────────────────────────────────────────────
//  平台1: 东方财富
// ─────────────────────────────────────────────────────────────

private fun fetchEastMoney(symbol: String, exchange: String, klt: Int, count: Int): List<Double> {
    val secid = emCode(symbol, exchange)
    val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
        "?secid=$secid&klt=$klt&fqt=1&lmt=$count&end=20500101&iscca=1" +
        "&fields1=f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13" +
        "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61" +
        "&ut=fa5fd1943c7b386f172d6893dbfba10b"

    val raw = httpGet(url, "https://quote.eastmoney.com/")
    val klines = JsonParser.parseString(raw)
        .asJsonObject["data"]?.asJsonObject
        ?.get("klines")?.asJsonArray
        ?: return emptyList()

    return klines.mapNotNull { el ->
        val parts = el.asString.split(",")
        // 格式: 时间,开,收,高,低,成交量,...  index 2 = close
        parts.getOrNull(2)?.toDoubleOrNull()
    }
}

// ─────────────────────────────────────────────────────────────
//  平台2: 新浪财经
// ─────────────────────────────────────────────────────────────

private fun fetchSina(symbol: String, exchange: String, klt: Int, count: Int): List<Double> {
    val scale = SINA_SCALE[klt] ?: return emptyList()
    val code  = sinaCode(symbol, exchange)
    val url = "https://quotes.sina.cn/cn/api/json_v2.php/" +
        "CN_MarketDataService.getKLineData" +
        "?symbol=$code&scale=$scale&ma=no&datalen=$count"

    val raw  = httpGet(url, "https://finance.sina.com.cn/")
    val arr  = JsonParser.parseString(raw).asJsonArray
    return arr.mapNotNull { el ->
        el.asJsonObject["close"]?.asString?.toDoubleOrNull()
    }
}

// ─────────────────────────────────────────────────────────────
//  平台3: 腾讯财经
// ─────────────────────────────────────────────────────────────

private fun fetchTencent(symbol: String, exchange: String, klt: Int, count: Int): List<Double> {
    val period = TX_PERIOD[klt] ?: return emptyList()
    val code   = sinaCode(symbol, exchange)
    val url    = "http://ifzq.gtimg.cn/appstock/app/kline/mkline" +
        "?param=$code,$period,,$count&_var=kline_$period&r=${System.currentTimeMillis()}"

    var raw = httpGet(url, "https://gu.qq.com/")
    // 去掉 "kline_m5=" 前缀
    if ("=" in raw) raw = raw.substringAfter("=").trim()

    val root   = JsonParser.parseString(raw).asJsonObject
    val kdata  = root["data"]?.asJsonObject
        ?.get(code)?.asJsonObject
        ?.get(period)?.asJsonArray
        ?: return emptyList()

    return kdata.mapNotNull { el ->
        // 格式: [时间, open, close, high, low, vol]
        val arr = el.asJsonArray
        arr.takeIf { it.size() > 2 }?.get(2)?.asString?.toDoubleOrNull()
    }
}

// ─────────────────────────────────────────────────────────────
//  多平台轮询入口
// ─────────────────────────────────────────────────────────────

data class FetchResult(
    val closes: List<Double>,
    val sourceName: String
)

private val PROVIDERS: List<Pair<String, (String, String, Int, Int) -> List<Double>>> = listOf(
    "东方财富" to ::fetchEastMoney,
    "新浪财经" to ::fetchSina,
    "腾讯财经" to ::fetchTencent
)

/**
 * 多平台轮询获取分钟K线收盘价（时间升序）。
 * @param onLog  可选日志回调，用于记录切换/失败信息
 */
fun fetchKlineMulti(
    symbol: String,
    exchange: String,
    klt: Int,
    count: Int = 65,
    onLog: ((String) -> Unit)? = null
): FetchResult {
    val key   = "${symbol}_$klt"
    val start = providerIndex.getOrDefault(key, 0)
    val n     = PROVIDERS.size

    for (offset in 0 until n) {
        val idx          = (start + offset) % n
        val (name, func) = PROVIDERS[idx]
        try {
            val closes = func(symbol, exchange, klt, count)
            if (closes.size >= 3) {
                if (offset != 0) onLog?.invoke("  ⚡ [$symbol ${klt}min] 切换至 [$name] 成功\n")
                providerIndex[key] = idx
                return FetchResult(closes, name)
            }
        } catch (e: Exception) {
            onLog?.invoke("  ⚠ [$name] $symbol ${klt}min 失败: ${e.message}\n")
        }
    }
    onLog?.invoke("  ✗ [$symbol ${klt}min] 三平台全部失败\n")
    return FetchResult(emptyList(), "—")
}

// ─────────────────────────────────────────────────────────────
//  完整 OHLCV K 线获取（供图表使用）
// ─────────────────────────────────────────────────────────────

/** 解析东方财富 K 线响应，返回 OHLCV 列表（时间升序） */
private fun parseEastMoneyKlines(raw: String): List<Candle> {
    val arr = try {
        JsonParser.parseString(raw)
            .asJsonObject["data"]?.asJsonObject
            ?.get("klines")?.asJsonArray
    } catch (_: Exception) { null } ?: return emptyList()

    val sdf        = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    val dateOnlySdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    return arr.mapNotNull { el ->
        val parts = el.asString.split(",")
        if (parts.size < 6) return@mapNotNull null
        try {
            val time = try {
                sdf.parse(parts[0])?.time ?: 0L
            } catch (_: Exception) {
                try { dateOnlySdf.parse(parts[0])?.time ?: 0L }
                catch (_: Exception) { 0L }
            }
            Candle(
                timestamp = time,
                open   = parts[1].toDoubleOrNull() ?: 0.0,
                high  = parts[3].toDoubleOrNull() ?: 0.0,
                low   = parts[4].toDoubleOrNull() ?: 0.0,
                close = parts[2].toDoubleOrNull() ?: 0.0,
                volume = parts[5].toDoubleOrNull() ?: 0.0
            )
        } catch (_: Exception) { null }
    }
}

/**
 * 东方财富获取分钟/日/周/月 K 线完整 OHLCV。
 * klt: 5/15/30/60 → 分钟线；101 → 日线；102 → 周线；103 → 月线
 */
private fun fetchEastMoneyFull(symbol: String, exchange: String, klt: Int, count: Int): List<Candle> {
    val secid = emCode(symbol, exchange)
    // 注意：日/周/月线（klt>=101）不传 iscca，否则部分品种返回空；ut token 必须携带
    val baseParams = "?secid=$secid&klt=$klt&fqt=1&lmt=$count&end=20500101" +
            "&fields1=f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13" +
            "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61" +
            "&ut=fa5fd1943c7b386f172d6893dbfba10b"
    val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get$baseParams"

    return try {
        val result = parseEastMoneyKlines(httpGet(url, "https://quote.eastmoney.com/"))
        result
    } catch (_: Exception) { emptyList() }
}

/**
 * 新浪财经获取日/周/月线 + 分钟线完整 OHLCV（备用，当东方财富失败时使用）。
 * klt: 101 → 日线(scale=240); 102 → 周线(scale=1200); 103 → 月线(scale=4800)
 * 分钟线: 5→5, 15→15, 30→30, 60→60
 * 实测字段名（日线/分钟线一致）: day, open, high, low, close, volume
 */
private fun fetchSinaKlineFull(symbol: String, exchange: String, klt: Int, count: Int): List<Candle> {
    val scale = when (klt) {
        101  -> 240    // 新浪用 scale=240 表示日线
        102  -> 1200   // 周线
        103  -> 4800   // 月线
        5    -> 5
        15   -> 15
        30   -> 30
        60   -> 60
        else -> return emptyList()
    }
    val code = sinaCode(symbol, exchange)
    val url = "https://quotes.sina.cn/cn/api/json_v2.php/" +
            "CN_MarketDataService.getKLineData" +
            "?symbol=$code&scale=$scale&ma=no&datalen=$count"
    return try {
        val raw = httpGet(url, "https://finance.sina.com.cn/")
        val arr = JsonParser.parseString(raw).asJsonArray
        // 实测：新浪日线和分钟线字段名完全相同：day, open, high, low, close, volume
        val sdf     = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        arr.mapNotNull { el ->
            try {
                val obj = el.asJsonObject
                val timeStr = obj["day"]?.asString ?: return@mapNotNull null
                val ts = try { sdf.parse(timeStr)?.time ?: 0L }
                         catch (_: Exception) { dateSdf.parse(timeStr)?.time ?: 0L }
                if (ts == 0L) return@mapNotNull null
                Candle(
                    timestamp = ts,
                    open   = obj["open"]?.asString?.toDoubleOrNull() ?: 0.0,
                    high   = obj["high"]?.asString?.toDoubleOrNull() ?: 0.0,
                    low    = obj["low"]?.asString?.toDoubleOrNull() ?: 0.0,
                    close  = obj["close"]?.asString?.toDoubleOrNull() ?: 0.0,
                    volume = obj["volume"]?.asString?.toDoubleOrNull() ?: 0.0
                )
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }
}

/**
 * 腾讯财经获取分钟线 OHLCV（备用，仅支持 5/15/30/60 分钟线）。
 * 注意：腾讯日/周/月线接口（gu.qq.com/getkline）已需要 cookie 验证，不可直接使用。
 */
private fun fetchTencentKlineFull(symbol: String, exchange: String, klt: Int, count: Int): List<Candle> {
    // 腾讯只支持分钟线；日/周/月线交给新浪处理
    val period = when (klt) {
        5    -> "m5"
        15   -> "m15"
        30   -> "m30"
        60   -> "m60"
        else -> return emptyList()   // 日线/周线/月线不走腾讯
    }
    val code = sinaCode(symbol, exchange)
    val url  = "http://ifzq.gtimg.cn/appstock/app/kline/mkline" +
               "?param=$code,$period,,$count&r=${System.currentTimeMillis()}"
    return try {
        var raw = httpGet(url, "https://gu.qq.com/")
        if ("=" in raw) raw = raw.substringAfter("=").trim()
        val root    = JsonParser.parseString(raw).asJsonObject
        val dataObj = root["data"]?.asJsonObject?.get(code)?.asJsonObject ?: return emptyList()
        val arr     = dataObj.get(period)?.asJsonArray ?: return emptyList()
        val sdf     = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        arr.mapNotNull { el ->
            try {
                val a = el.asJsonArray
                if (a.size() < 6) return@mapNotNull null
                val ts = try { sdf.parse(a[0].asString)?.time ?: 0L } catch (_: Exception) { 0L }
                if (ts == 0L) return@mapNotNull null
                Candle(
                    timestamp = ts,
                    open   = a[1].asString.toDoubleOrNull() ?: 0.0,
                    close  = a[2].asString.toDoubleOrNull() ?: 0.0,
                    high   = a[3].asString.toDoubleOrNull() ?: 0.0,
                    low    = a[4].asString.toDoubleOrNull() ?: 0.0,
                    volume = a[5].asString.toDoubleOrNull() ?: 0.0
                )
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }
}

/**
 * 腾讯财经获取最近交易日分时数据（1分钟一根）。
 * 通过获取上一个交易日的 5 分钟 K 线并均分重组为分钟级数据。
 * 若当日为交易日且有数据则返回今日数据；否则返回最近一个有数据的交易日数据。
 */
fun fetchIntradayTencent(symbol: String, exchange: String): List<Candle> {
    val code = sinaCode(symbol, exchange)

    // 先拿 day 接口找到最近一个有数据的交易日日期
    val dayUrl = "https://gu.qq.com/app/gp/kline/getkline?_var=kline_dayqfq&param=$code,day,,,10,,&r=${System.currentTimeMillis()}"
    var raw = try { httpGet(dayUrl, "https://gu.qq.com/") } catch (_: Exception) { "" }
    if ("=" in raw) raw = raw.substringAfter("=").trim()
    val dayRoot = parseJsonLenient(raw)?.asJsonObject?.get("data")?.asJsonObject ?: return emptyList()
    val dayArr  = dayRoot.get(code)?.asJsonObject?.get("day")?.asJsonArray ?: return emptyList()
    if (dayArr.size() == 0) return emptyList()

    // 找最后一个有成交量/价格的交易日
    val lastDayEntry = (0 until dayArr.size())
        .mapNotNull { dayArr[it].asJsonArray.takeIf { e -> e.size() >= 3 } }
        .lastOrNull() ?: return emptyList()
    val rawDate = lastDayEntry[0].asString
    // 构造交易日的 yyyy-MM-dd 字符串（跨年时 rawDate 含完整年份）
    val todayStr  = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    val yearPref  = if (rawDate.length == 10) "" else todayStr.substring(0, 4) + "-"
    val targetDay = "$yearPref$rawDate"  // 如 "2026-04-03"

    // 再拿 5 分钟接口获取该交易日的分钟数据
    val m5Url = "http://ifzq.gtimg.cn/appstock/app/kline/mkline?param=$code,m5,,100,&r=${System.currentTimeMillis()}"
    var rawM5 = try { httpGet(m5Url, "https://gu.qq.com/") } catch (_: Exception) { "" }
    if ("=" in rawM5) rawM5 = rawM5.substringAfter("=").trim()
    val m5Root  = parseJsonLenient(rawM5)?.asJsonObject?.get("data")?.asJsonObject ?: return emptyList()
    val m5Arr   = m5Root.get(code)?.asJsonObject?.get("m5")?.asJsonArray ?: return emptyList()
    if (m5Arr.size() == 0) return emptyList()

    val sdf        = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    val daySdf     = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    val dayTs      = try { daySdf.parse(targetDay)?.time ?: 0L } catch (_: Exception) { 0L }
    val dayCandles = mutableListOf<Candle>()

    // 遍历所有 5 分钟 K 线，找出属于目标交易日的数据
    val amEndMs   = dayTs + 11 * 3600_000 + 30 * 60_000        // 11:30 ms
    val pmStartMs  = dayTs + 13 * 3600_000                      // 13:00 ms

    for (el in m5Arr) {
        val arr = el.asJsonArray
        if (arr.size() < 6) continue
        val tsStr = arr[0].asString  // "yyyy-MM-dd HH:mm"
        val tsMs  = try { sdf.parse(tsStr)?.time ?: 0L } catch (_: Exception) { 0L }
        if (tsMs == 0L || tsMs < dayTs || tsMs >= pmStartMs + 14 * 3600_000) continue

        // 跳过 11:30–13:00 的午间休市空白
        if (tsMs in amEndMs until pmStartMs) continue

        val open   = arr[1].asString.toDoubleOrNull() ?: continue
        val close  = arr[2].asString.toDoubleOrNull() ?: continue
        val high   = arr[3].asString.toDoubleOrNull() ?: continue
        val low    = arr[4].asString.toDoubleOrNull() ?: continue
        val vol    = arr[5].asString.toDoubleOrNull() ?: 0.0

        // 把一根 5min K 线均分为 5 根 1min K 线（价格相同，仅时间不同）
        val hh     = (tsMs - dayTs) / 3600_000
        val mmBase = ((tsMs - dayTs) % 3600_000) / 60_000
        for (m in 0..4) {
            val totalMin = (hh * 60 + mmBase + m).toInt()
            val tStr = "%s %02d:%02d".format(targetDay, totalMin / 60, totalMin % 60)
            val tMs  = try { sdf.parse(tStr)?.time ?: 0L } catch (_: Exception) { 0L }
            if (tMs > 0) {
                dayCandles.add(Candle(tMs, open, high, low, close, vol / 5.0))
            }
        }
    }

    return dayCandles
}

/**
 * Yahoo Finance 获取月 K 线 OHLCV（免费备用，主要用于月线 fallback）。
 * 支持上交所（SS）和深交所（SZ）品种。
 */
private fun fetchYahooFull(symbol: String, exchange: String, klt: Int, count: Int): List<Candle> {
    if (klt != 103) return emptyList()  // Yahoo 主要用于月线

    val suffix = exchange.uppercase()
    val yahooSymbol = "$symbol.$suffix"  // e.g. 518880.SH → 518880.SS; 159934.SZ → 159934.SZ
    val cal = java.util.Calendar.getInstance()
    // 往前往后推足够月份
    cal.add(java.util.Calendar.MONTH, -(count * 31 / 30 + 24))
    val period1 = cal.timeInMillis / 1000
    val period2 = System.currentTimeMillis() / 1000

    val url = "https://query1.finance.yahoo.com/v8/finance/chart/$yahooSymbol" +
            "?period1=$period1&period2=$period2&interval=1mo"
    return try {
        val raw = httpGet(url, "https://finance.yahoo.com/")
        val root = JsonParser.parseString(raw).asJsonObject
        val result = root["chart"]?.asJsonObject?.get("result")?.asJsonArray
            ?: return emptyList()
        if (result.size() == 0) return emptyList()
        val timestamps = result[0].asJsonObject["timestamp"]?.asJsonArray ?: return emptyList()
        val quotes     = result[0].asJsonObject["indicators"]?.asJsonObject
            ?.get("quote")?.asJsonArray?.get(0)?.asJsonObject ?: return emptyList()
        val opens  = quotes["open"]?.asJsonArray  ?: return emptyList()
        val highs  = quotes["high"]?.asJsonArray  ?: return emptyList()
        val lows   = quotes["low"]?.asJsonArray   ?: return emptyList()
        val closes = quotes["close"]?.asJsonArray ?: return emptyList()

        val size = minOf(timestamps.size(), opens.size(), highs.size(), lows.size(), closes.size())
        val list = mutableListOf<Candle>()
        for (i in 0 until size) {
            val ts = timestamps[i].asLong * 1000
            val open   = opens[i].takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.run { if (isNumber) asDouble else 0.0 }  ?: 0.0
            val high   = highs[i].takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.run { if (isNumber) asDouble else 0.0 }  ?: 0.0
            val low    = lows[i].takeIf  { it.isJsonPrimitive }?.asJsonPrimitive?.run { if (isNumber) asDouble else 0.0 }  ?: 0.0
            val close  = closes[i].takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.run { if (isNumber) asDouble else 0.0 } ?: 0.0
            if (ts > 0 && close > 0) {
                list.add(Candle(ts, open, high, low, close, 0.0))
            }
        }
        list.takeLast(count)
    } catch (_: Exception) { emptyList() }
}

/**
 * Tushare Pro 获取日/周/月线 OHLCV（收费平台，需要在设置中配置 Token）。
 * 免费账号基础配额即可使用 daily/weekly/monthly 接口。
 * 申请: https://tushare.pro/register
 */
private fun fetchTushareFull(symbol: String, exchange: String, klt: Int, count: Int): List<Candle> {
    val token = try { StockMonitorSettings.getInstance().tusharePro } catch (_: Exception) { "" }
    if (token.isBlank()) return emptyList()

    // Tushare 代码格式：000001.SZ / 518880.SH
    val tsCode = "${symbol}.${exchange.uppercase()}"
    val api = when (klt) {
        101  -> "daily"
        102  -> "weekly"
        103  -> "monthly"
        else -> return emptyList()   // 分钟线不走 Tushare（限流严格）
    }

    // 计算 start_date：往前推足够天数
    val calDays = when (klt) {
        102  -> count * 7 + 30
        103  -> count * 31 + 60
        else -> count + 30
    }
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, -calDays)
    val startDate = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(cal.time)

    val body = """{"api_name":"$api","token":"$token","params":{"ts_code":"$tsCode","start_date":"$startDate"},"fields":"trade_date,open,high,low,close,vol"}"""
    return try {
        val conn = URL("https://api.tushare.pro").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout    = TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.outputStream.bufferedWriter().use { it.write(body) }
        val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        conn.disconnect()

        val root = JsonParser.parseString(raw).asJsonObject
        val code = root["code"]?.asInt ?: -1
        if (code != 0) return emptyList()   // token 无效或接口错误

        val data  = root["data"]?.asJsonObject ?: return emptyList()
        val items = data["items"]?.asJsonArray  ?: return emptyList()
        val sdf   = SimpleDateFormat("yyyyMMdd", Locale.CHINA)

        // Tushare 返回倒序（最新在前），需要反转
        val candles = items.mapNotNull { el ->
            try {
                val arr = el.asJsonArray
                if (arr.size() < 6) return@mapNotNull null
                val ts    = try { sdf.parse(arr[0].asString)?.time ?: 0L } catch (_: Exception) { 0L }
                if (ts == 0L) return@mapNotNull null
                Candle(
                    timestamp = ts,
                    open   = arr[1].asString.toDoubleOrNull() ?: 0.0,
                    high   = arr[2].asString.toDoubleOrNull() ?: 0.0,
                    low    = arr[3].asString.toDoubleOrNull() ?: 0.0,
                    close  = arr[4].asString.toDoubleOrNull() ?: 0.0,
                    volume = arr[5].asString.toDoubleOrNull() ?: 0.0
                )
            } catch (_: Exception) { null }
        }.reversed()   // 转为时间升序

        candles.takeLast(count)
    } catch (_: Exception) { emptyList() }
}

/**
 * 统一入口：获取任意时间周期的完整 K 线数据（带多平台 fallback）。
 * 优先级: 东方财富 → 新浪财经 → 腾讯财经（仅分钟线）→ Yahoo Finance（月线备用）→ Tushare Pro（需配置 Token）
 */
fun fetchKlineFull(
    symbol: String,
    exchange: String,
    klt: Int,
    count: Int = 150
): List<Candle> {
    // 平台1：东方财富（日/周/月/分钟线全支持）
    val em = fetchEastMoneyFull(symbol, exchange, klt, count)
    if (em.size >= 3) return em

    // 平台2：新浪财经（日/周/分钟线全支持，月线新浪可能为空）
    val sina = fetchSinaKlineFull(symbol, exchange, klt, count)
    if (sina.size >= 3) return sina

    // 平台3：腾讯财经（仅分钟线 5/15/30/60min）
    val tx = fetchTencentKlineFull(symbol, exchange, klt, count)
    if (tx.size >= 3) return tx

    // 平台4：Yahoo Finance（月线专用免费备用）
    val yahoo = fetchYahooFull(symbol, exchange, klt, count)
    if (yahoo.size >= 3) return yahoo

    // 平台5：Tushare Pro（日/周/月线，需在设置中配置 Token）
    val ts = fetchTushareFull(symbol, exchange, klt, count)
    if (ts.size >= 3) return ts

    return emptyList()
}

/** 计算 MA 均价（取最近 period 个收盘价） */
fun calcMaCandles(candles: List<Candle>, period: Int): Double? {
    if (candles.size < period) return null
    return candles.takeLast(period).map { it.close }.average()
}

/** 计算 MA 序列 */
fun calcMaSeriesCandles(candles: List<Candle>, period: Int): List<Double?> {
    return candles.mapIndexed { idx, _ ->
        if (idx + 1 < period) null
        else candles.subList(idx + 1 - period, idx + 1).map { it.close }.average()
    }
}
