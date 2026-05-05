import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.stockmonitor"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 支持 IDEA 2023.1+
        intellijIdeaCommunity("2023.3.8")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }
    implementation("com.google.code.gson:gson:2.10.1")
}

intellijPlatform {
    pluginConfiguration {
        name = "Stock Monitor"
        version = "1.0.0"
        description = """
            <p>A股均线突破监控插件</p>
            <ul>
                <li>在 IDEA 侧边栏实时监控 A 股品种</li>
                <li>支持 MA60 多周期均线突破/跌破信号检测</li>
                <li>东方财富/新浪/腾讯三平台自动故障切换</li>
                <li>IDEA 通知弹窗 + 钉钉机器人双通道推送</li>
            </ul>
        """.trimIndent()
        changeNotes = """
            <ul>
                <li>1.0.0 — Initial release</li>
                <li>支持 A股多品种 MA60 均线突破/跌破监控</li>
                <li>东方财富 / 新浪财经 / 腾讯财经三平台自动故障切换</li>
                <li>IDEA 通知弹窗 + 钉钉机器人双通道推送</li>
                <li>分时 / 日 / 周 / 月 / 5分 / 15分 / 30分 / 60分 K线图表</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            // 支持 IntelliJ IDEA 2021.2 及以上版本
            // Build 版本号参考: https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html
            // 212.x  = 2021.2.x
            // 221.x  = 2022.1.x
            // 231.x  = 2023.1.x
            // 232.x  = 2023.2.x
            // 233.x  = 2023.3.x
            // 241.x  = 2024.1.x
            // 242.x  = 2024.2.x
            // 243.x  = 2024.3.x
            // 251.x  = 2025.1.x
            // 252.x  = 2025.2.x
            sinceBuild = "212"
            untilBuild = "253.*"
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

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
