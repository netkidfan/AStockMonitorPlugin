package com.stockmonitor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.stockmonitor.services.MonitorService

class ToggleMonitorAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val svc = MonitorService.getInstance() ?: return
        if (svc.isRunning()) svc.stop() else svc.start()
    }

    override fun update(e: AnActionEvent) {
        val svc     = MonitorService.getInstance()
        val running = svc?.isRunning() ?: false
        e.presentation.text = if (running) "⏸ 停止监控" else "▶ 启动监控"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
