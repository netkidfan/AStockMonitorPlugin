package com.stockmonitor.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

// ─────────────────────────────────────────────────────────────
//  持久化设置（存储到 IDE 配置文件）
// ─────────────────────────────────────────────────────────────

@State(
    name = "StockMonitorSettings",
    storages = [Storage("stockMonitor.xml")]
)
class StockMonitorSettings : PersistentStateComponent<StockMonitorSettings> {

    // ── 监控品种列表（格式: "代码,名称,交易所"，多个用换行分隔）──
    var symbolsRaw: String = """
        518880,黄金ETF华安,sh
        159934,黄金ETF易方达,sz
        159981,能源化工ETF建信,sz
        159945,能源ETF广发,sz
        588000,科创50ETF,sh
        600938,中国海油,sh
        600598,北大荒,sh
        300207,欣旺达,sz
        000831,中国稀土,sz
    """.trimIndent()

    // ── 均线参数 ──────────────────────────────────
    var maPeriod: Int     = 60    // MA 周期
    var confirmBars: Int  = 2     // 连续确认根数
    var pollInterval: Int = 300   // 轮询间隔（秒）

    // ── 通知方式 ──────────────────────────────────
    var notifyIdea: Boolean     = true    // IDEA 通知弹窗
    var notifyDingtalk: Boolean = false   // 钉钉推送

    // ── 钉钉配置 ──────────────────────────────────
    var dingtalkWebhook: String = ""
    var dingtalkSecret: String  = ""
    var dingtalkKeyword: String = "监控"

    // ── 交易时段过滤 ──────────────────────────────
    var tradingHoursOnly: Boolean = true   // 仅在交易时段运行

    // ── 高级数据源（收费平台）──────────────────────
    /**
     * Tushare Pro Token（免费注册获得基础配额）。
     * 申请地址: https://tushare.pro/register
     * 填写后将作为第四备用数据源（优先级：东方财富 > 新浪 > 腾讯 > Tushare）
     */
    var tusharePro: String = ""

    // ─────────────────────────────────────────────
    override fun getState(): StockMonitorSettings = this

    override fun loadState(state: StockMonitorSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): StockMonitorSettings =
            ApplicationManager.getApplication()
                .getService(StockMonitorSettings::class.java)
    }
}
