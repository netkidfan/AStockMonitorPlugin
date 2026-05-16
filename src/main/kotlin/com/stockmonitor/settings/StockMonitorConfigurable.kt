package com.stockmonitor.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.stockmonitor.services.MonitorService
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableModel

// ─────────────────────────────────────────────────────────────
//  设置页 UI
// ─────────────────────────────────────────────────────────────

class StockMonitorConfigurable : Configurable {

    private val settings = StockMonitorSettings.getInstance()

    // ── 品种表格（允许直接编辑单元格）──────────────
    private val tableModel = DefaultTableModel(
        arrayOf<Any>("代码", "名称", "交易所(sh/sz)"), 0
    )
    private val symbolTable = JBTable(tableModel).apply {
        preferredScrollableViewportSize = Dimension(500, 180)
        rowHeight = 24
    }

    // ── 参数字段 ────────────────────────────────
    private lateinit var maPeriodField:    JBTextField
    private lateinit var confirmBarsField: JBTextField
    private lateinit var pollField:        JBTextField

    // ── 通知开关 ────────────────────────────────
    private lateinit var ideaNotifyCheck:     JBCheckBox
    private lateinit var dingtalkNotifyCheck: JBCheckBox
    private lateinit var tradingHoursCheck:   JBCheckBox

    // ── 钉钉字段 ────────────────────────────────
    private lateinit var webhookField:  JBTextField
    private lateinit var secretField:   JBTextField
    private lateinit var keywordField:  JBTextField

    // ── 高级数据源字段 ──────────────────────────
    private lateinit var tushareField: JBTextField

    // ─────────────────────────────────────────────
    override fun getDisplayName() = "Stock Monitor（A股监控）"

    override fun createComponent(): JComponent {
        loadSymbolsToTable()
        return panel {
            // ── 监控品种 ──────────────────────────────
            group("监控品种") {
                row {
                    scrollCell(symbolTable).align(AlignX.FILL).resizableColumn()
                }
                row {
                    button("添加") { addRow() }
                    button("删除选中") { removeSelected() }
                    button("⬆ 上移") { moveUp() }
                    button("⬇ 下移") { moveDown() }
                    button("恢复默认") { resetDefaultSymbols() }
                }
                row {
                    comment("品种顺序会影响侧边栏显示顺序及监控优先级")
                }
            }

            // ── 均线参数 ──────────────────────────────
            group("均线参数") {
                row("MA 周期（根K线）:") {
                    maPeriodField = textField()
                        .text(settings.maPeriod.toString())
                        .columns(8).component
                }
                row("连续确认根数:") {
                    confirmBarsField = textField()
                        .text(settings.confirmBars.toString())
                        .columns(8).component
                    comment("连续 N 根K线满足条件才触发信号")
                }
                row("轮询间隔（秒）:") {
                    pollField = textField()
                        .text(settings.pollInterval.toString())
                        .columns(8).component
                    comment("建议 180~300 秒，避免接口限频")
                }
                row {
                    tradingHoursCheck = checkBox("仅在交易时段运行（9:30-11:30, 13:00-15:00）")
                        .selected(settings.tradingHoursOnly).component
                }
            }

            // ── 通知设置 ──────────────────────────────
            group("通知设置") {
                row {
                    ideaNotifyCheck = checkBox("IDEA 内置通知（气泡弹窗）")
                        .selected(settings.notifyIdea).component
                }
                row {
                    dingtalkNotifyCheck = checkBox("钉钉机器人推送")
                        .selected(settings.notifyDingtalk).component
                }
            }

            // ── 钉钉配置 ──────────────────────────────
            group("钉钉机器人配置") {
                row("Webhook 地址:") {
                    webhookField = textField()
                        .text(settings.dingtalkWebhook)
                        .align(AlignX.FILL).resizableColumn().component
                }
                row("加签密钥（SEC...）:") {
                    secretField = textField()
                        .text(settings.dingtalkSecret)
                        .align(AlignX.FILL).resizableColumn().component
                }
                row("安全关键词:") {
                    keywordField = textField()
                        .text(settings.dingtalkKeyword)
                        .columns(16).component
                }
                row {
                    button("测试钉钉连接") { testDingtalk() }
                }
            }

            // ── 高级数据源 ────────────────────────────
            group("高级数据源（可选）") {
                row {
                    comment(
                        "<html><b>数据源优先级</b>：东方财富 → 新浪财经 → 腾讯财经（分钟线）<br>" +
                        "若以上均失败，可配置以下收费平台作为最终备用</html>"
                    )
                }
                row("Tushare Pro Token:") {
                    tushareField = textField()
                        .text(settings.tusharePro)
                        .align(AlignX.FILL).resizableColumn().component
                }
                row {
                    comment(
                        "<html>Tushare Pro 免费注册，基础接口无需付费 · " +
                        "<a href='https://tushare.pro/register'>前往申请 Token</a><br>" +
                        "填写 Token 后，当所有免费数据源均失败时自动启用 Tushare</html>"
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    //  辅助操作
    // ─────────────────────────────────────────────

    private fun loadSymbolsToTable() {
        tableModel.rowCount = 0
        settings.symbolsRaw.lines()
            .map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { line ->
                val parts = line.split(",")
                tableModel.addRow(arrayOf(
                    parts.getOrElse(0) { "" }.trim(),
                    parts.getOrElse(1) { "" }.trim(),
                    parts.getOrElse(2) { "sh" }.trim()
                ))
            }
    }

    private fun addRow() {
        tableModel.addRow(arrayOf("", "名称", "sh"))
        symbolTable.editCellAt(tableModel.rowCount - 1, 0)
    }

    private fun removeSelected() {
        val sel = symbolTable.selectedRows.sortedDescending()
        sel.forEach { tableModel.removeRow(it) }
    }

    private fun moveUp() {
        val row = symbolTable.selectedRow
        if (row <= 0) return
        val cols = (0 until tableModel.columnCount).map { tableModel.getValueAt(row, it) }
        tableModel.removeRow(row)
        tableModel.insertRow(row - 1, cols.toTypedArray())
        symbolTable.setRowSelectionInterval(row - 1, row - 1)
    }

    private fun moveDown() {
        val row = symbolTable.selectedRow
        if (row < 0 || row >= tableModel.rowCount - 1) return
        val cols = (0 until tableModel.columnCount).map { tableModel.getValueAt(row, it) }
        tableModel.removeRow(row)
        tableModel.insertRow(row + 1, cols.toTypedArray())
        symbolTable.setRowSelectionInterval(row + 1, row + 1)
    }

    private fun resetDefaultSymbols() {
        val confirm = Messages.showYesNoDialog(
            "确定恢复默认品种列表？当前配置将被覆盖。",
            "恢复默认", Messages.getQuestionIcon()
        )
        if (confirm == Messages.YES) {
            val defaults = StockMonitorSettings().symbolsRaw
            settings.symbolsRaw = defaults
            loadSymbolsToTable()
        }
    }

    private fun testDingtalk() {
        val wh  = webhookField.text.trim()
        val sec = secretField.text.trim()
        val kw  = keywordField.text.trim()
        if (wh.isEmpty()) {
            Messages.showWarningDialog("请先填写 Webhook 地址", "测试钉钉")
            return
        }
        Thread {
            val (ok, msg) = com.stockmonitor.services.DingtalkSender.send(
                webhook  = wh,
                secret   = sec,
                keyword  = kw,
                title    = "【${kw}】Stock Monitor 测试消息",
                content  = "✅ 钉钉连接测试成功，Stock Monitor 插件配置正常。"
            )
            SwingUtilities.invokeLater {
                if (ok) Messages.showInfoMessage("发送成功！", "测试钉钉")
                else    Messages.showErrorDialog("发送失败: $msg", "测试钉钉")
            }
        }.start()
    }

    // ─────────────────────────────────────────────
    //  Configurable 接口
    // ─────────────────────────────────────────────

    override fun isModified(): Boolean {
        val s = settings
        return maPeriodField.text.toIntOrNull()    != s.maPeriod          ||
               confirmBarsField.text.toIntOrNull() != s.confirmBars       ||
               pollField.text.toIntOrNull()         != s.pollInterval      ||
               ideaNotifyCheck.isSelected           != s.notifyIdea        ||
               dingtalkNotifyCheck.isSelected       != s.notifyDingtalk    ||
               tradingHoursCheck.isSelected         != s.tradingHoursOnly  ||
               webhookField.text                    != s.dingtalkWebhook   ||
               secretField.text                     != s.dingtalkSecret    ||
               keywordField.text                    != s.dingtalkKeyword   ||
               tushareField.text                    != s.tusharePro        ||
               tableToRaw()                         != s.symbolsRaw
    }

    override fun apply() {
        val s = settings
        s.maPeriod        = maPeriodField.text.toIntOrNull()    ?: s.maPeriod
        s.confirmBars     = confirmBarsField.text.toIntOrNull() ?: s.confirmBars
        s.pollInterval    = pollField.text.toIntOrNull()         ?: s.pollInterval
        s.notifyIdea      = ideaNotifyCheck.isSelected
        s.notifyDingtalk  = dingtalkNotifyCheck.isSelected
        s.tradingHoursOnly = tradingHoursCheck.isSelected
        s.dingtalkWebhook = webhookField.text.trim()
        s.dingtalkSecret  = secretField.text.trim()
        s.dingtalkKeyword = keywordField.text.trim()
        s.tusharePro      = tushareField.text.trim()
        s.symbolsRaw      = tableToRaw()

        // 应用配置后重启监控服务
        MonitorService.getInstance()?.restart()
    }

    override fun reset() {
        maPeriodField.text    = settings.maPeriod.toString()
        confirmBarsField.text = settings.confirmBars.toString()
        pollField.text        = settings.pollInterval.toString()
        ideaNotifyCheck.isSelected      = settings.notifyIdea
        dingtalkNotifyCheck.isSelected  = settings.notifyDingtalk
        tradingHoursCheck.isSelected    = settings.tradingHoursOnly
        webhookField.text    = settings.dingtalkWebhook
        secretField.text     = settings.dingtalkSecret
        keywordField.text    = settings.dingtalkKeyword
        tushareField.text    = settings.tusharePro
        loadSymbolsToTable()
    }

    private fun tableToRaw(): String {
        val sb = StringBuilder()
        for (i in 0 until tableModel.rowCount) {
            val code  = tableModel.getValueAt(i, 0).toString().trim()
            val name  = tableModel.getValueAt(i, 1).toString().trim()
            val exch  = tableModel.getValueAt(i, 2).toString().trim()
            if (code.isNotEmpty()) sb.appendLine("$code,$name,$exch")
        }
        return sb.toString().trimEnd()
    }
}
