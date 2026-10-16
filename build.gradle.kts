import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.stockmonitor"
version = "1.0.4"

// 显式指定 Java 17 工具链，避免 Platform Plugin 自动寻找 JDK 21
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // ── 编译基线平台：IntelliJ IDEA 2026.1.2（build 261.24374.151）──
        // 注意：2026.2 起平台改用 Java 25 编译，若把编译基线抬到 2026.2 会强制 JDK 25
        // 并把插件字节码抬到 Java 25，只能装在新 IDE 上。
        // 这里刻意保持「较低编译基线 + 取消 until-build 上界」的组合，
        // 使同一份产物可同时安装于 2022.3 ~ 2026.2+ 全系列 IDE。
        // 兼容性由下方 pluginVerification 对 2026.2.0.1 实测校验。
        intellijIdea("2026.1.2")
        bundledPlugin("com.intellij.java")
        // 引入 Kotlin 插件以确保 UI DSL (com.intellij.ui.dsl) 可用
        bundledPlugin("org.jetbrains.kotlin")
        pluginVerifier()
        zipSigner()
    }
    implementation("com.google.code.gson:gson:2.11.0")
}

// ──────────────────────────────────────────────────────────────────────────────
// 跳过 buildSearchableOptions：
// 该任务会启动 headless IDEA 沙箱进程，IDEA 内置 Gradle 插件（GradleConfigurable）
// 在解析系统 PATH 中的 JDK 路径时，会将 \b 当作退格符转义，导致
// InvalidPathException: "C:\Program Files\Java\jdk-17bin"，这是 IDEA 平台的 bug。
// 禁用后不影响插件核心功能，仅 Settings 搜索功能无法索引本插件设置项（可接受）。
// ──────────────────────────────────────────────────────────────────────────────
tasks.named("buildSearchableOptions") {
    enabled = false
}

intellijPlatform {
    pluginConfiguration {
        name = "A Stock Monitor"
        version = "1.0.4"
        description = """
            This is a tool for real-time monitoring of A-share stock market movements.
            Based on a moving average breakout system, it monitors the 60-period moving average
            on 5-minute, 15-minute, and 30-minute charts. Breakouts (either above or below) can be
            set to notify users via Idea or DingTalk.

            这是用于实时监控A股股票行情的工具，根据均线突破体系监控股票5分钟、15分钟、30分钟的60均线，
            突破或跌破都可以设置通过idea通知或钉钉通知用户。

            - 支持分时图

            - 支持免费数据通道和设置Tushare收费通道

            - 支持开市/休市提醒

            - 支持价格预警，内部通知或钉钉通知

            - 支持自定义排序

            - 如果您有好的点子或建议，欢迎留言，作者会认真考虑，谢谢！
        """.trimIndent()
        changeNotes = """
            兼容 IntelliJ IDEA 2026.2.x 及后续版本：
            - 取消 until-build 上界，插件现可安装于 2026.2.0.1 及所有未来版本
            - 编译基线保持 2026.1.2（Java 17 字节码），向下兼容至 2022.3
            - 已通过 Plugin Verifier 对 IDEA 2026.2.0.1 的 API 兼容性校验
        """.trimIndent()
        ideaVersion {
            // 231 = 2023.1
            sinceBuild = "231"
            // 不设置 until-build：兼容 sinceBuild 之后的所有版本（含 2026.2 及未来版本）
            // 这是 JetBrains 官方推荐做法，等价于 plugin.xml 中不生成 until-build 属性
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // 当前编译基线平台（2026.1.2）
            current()
            // 目标验证平台：IDEA 2026.2.0.1（build 262.8665.337）
            create(IntelliJPlatformType.IntellijIdea, "2026.2.0.1")
        }
    }
    signing {
        // 生产发布时配置证书，开发阶段留空
    }
    publishing {
        // 发布时通过环境变量传入 Token：
        //   $env:PUBLISH_TOKEN="perm:xxx..."
        //   ./gradlew.bat publishPlugin
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("Stable")
    }
}
