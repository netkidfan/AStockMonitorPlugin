# Stock Monitor — IntelliJ IDEA A股均线监控插件

在 IntelliJ IDEA 侧边栏实时监控 A 股品种的均线突破/跌破信号，支持 IDEA 气泡通知与钉钉机器人双通道推送。

---

## 功能特性

| 功能 | 说明 |
|------|------|
| 📊 侧边栏面板 | 左侧 Tool Window，可随时展开/收起，显示所有品种实时 MA 数据 |
| 🚀 突破信号 | 连续 N 根 5min 和 15min K线均收在 MA60 上方 |
| ⚠️ 跌破信号 | 连续 N 根 15min 和 30min K线均收在 MA60 下方 |
| 📈 30min独立突破 | 连续 N 根 30min K线收在 30min MA60 上方 |
| 📉 30min独立跌破 | 连续 N 根 30min K线收在 30min MA60 下方 |
| 🔔 IDEA通知 | 信号触发时弹出 IDEA 气泡通知（右下角）|
| 钉钉推送 | 支持加签验证的钉钉机器人 Markdown 消息 |
| ⚡ 三平台轮询 | 东方财富 → 新浪财经 → 腾讯财经，自动故障切换 |
| ⚙ 参数可配置 | Settings → Tools → Stock Monitor（A股监控）|

---

## 工程结构

```
StockMonitorPlugin/
├── build.gradle.kts                    # Gradle 构建脚本
├── settings.gradle.kts
└── src/main/
    ├── kotlin/com/stockmonitor/
    │   ├── model/
    │   │   └── Models.kt               # 数据模型（StockSymbol / Signal / SymbolStatus）
    │   ├── data/
    │   │   ├── KlineFetcher.kt         # 三平台K线获取 + 轮询策略
    │   │   └── SignalDetector.kt       # MA计算 + 四种信号检测
    │   ├── settings/
    │   │   ├── StockMonitorSettings.kt # 持久化配置（PersistentStateComponent）
    │   │   └── StockMonitorConfigurable.kt  # 设置页UI
    │   ├── services/
    │   │   ├── MonitorService.kt       # 后台轮询服务 + 通知分发
    │   │   └── DingtalkSender.kt       # 钉钉机器人推送（HMAC-SHA256）
    │   ├── toolwindow/
    │   │   ├── StockMonitorToolWindowFactory.kt  # Tool Window 工厂
    │   │   └── StockMonitorPanel.kt    # 侧边栏 UI 面板
    │   └── actions/
    │       └── ToggleMonitorAction.kt  # 启停监控 Action
    └── resources/
        ├── META-INF/plugin.xml         # 插件描述符
        ├── icons/stock_monitor.svg     # 侧边栏图标
        └── messages/StockMonitorBundle.properties
```

---

## 开发环境要求

| 工具 | 版本要求 |
|------|---------|
| JDK  | 17+     |
| Gradle | 8.x   |
| IntelliJ IDEA | 2023.1+（Community 或 Ultimate 均可）|

---

## 构建与运行

### 1. 克隆 / 打开工程

用 IntelliJ IDEA 直接打开 `StockMonitorPlugin/` 目录，等待 Gradle 同步完成。

### 2. 本地调试运行

```bash
# 启动一个带插件的 IDEA 沙箱实例
./gradlew runIde
```

沙箱 IDEA 启动后：
1. 左侧边栏找到 **Stock Monitor** 图标，点击展开面板
2. 点击 **▶ 启动监控** 开始轮询
3. `Settings → Tools → Stock Monitor（A股监控）` 修改参数

### 3. 打包 ZIP 分发

```bash
./gradlew buildPlugin
# 生成文件: build/distributions/StockMonitorPlugin-1.0.0.zip
```

### 4. 安装到 IDEA

`Settings → Plugins → ⚙ → Install Plugin from Disk` → 选择上面生成的 ZIP。

---

## 配置说明

在 `Settings → Tools → Stock Monitor（A股监控）` 中可配置：

### 监控品种
表格中每行一个品种，格式：`代码, 名称, 交易所(sh/sz)`

| 代码   | 名称         | 交易所 |
|--------|--------------|--------|
| 518880 | 黄金ETF华安  | sh     |
| 159934 | 黄金ETF易方达 | sz    |
| …      | …            | …      |

### 均线参数
| 参数 | 默认值 | 说明 |
|------|--------|------|
| MA 周期 | 60 | 计算均线所用 K线根数 |
| 连续确认根数 | 2 | 需连续几根K线满足条件才触发信号 |
| 轮询间隔（秒）| 300 | 建议 180~300，避免接口限频 |

### 通知设置
- **IDEA 内置通知**：在 IDEA 右下角弹出气泡通知
- **钉钉机器人推送**：填入 Webhook 地址和加签密钥后开启

---

## 信号说明

| 信号 | 图标 | 触发条件 |
|------|------|---------|
| 5/15min突破 | 🚀 | 连续 N 根：5min收盘 > 5min MA60 AND 15min收盘 > 15min MA60 |
| 15/30min跌破 | ⚠️ | 连续 N 根：15min收盘 < 15min MA60 AND 30min收盘 < 30min MA60 |
| 30min独立突破 | 📈 | 连续 N 根：30min收盘 > 30min MA60 |
| 30min独立跌破 | 📉 | 连续 N 根：30min收盘 < 30min MA60 |

> ⚠️ 本插件仅供学习和参考，不构成任何投资建议。

---

## 数据来源

三平台轮询，优先级依次为：

1. **东方财富** `push2his.eastmoney.com` — 最稳定
2. **新浪财经** `quotes.sina.cn` — 免费备用
3. **腾讯财经** `ifzq.gtimg.cn` — 免费备用

任一平台失败自动切换，侧边栏「数据源」列显示实际使用平台。
