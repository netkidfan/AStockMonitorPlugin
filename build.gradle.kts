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
    }
    implementation("com.google.code.gson:gson:2.10.1")
}

intellijPlatform {
    pluginConfiguration {
        name = "A Stock Monitor"
        version = "1.0.0"
        description = ("This is a tool for real-time monitoring of A-share stock market movements. Based on a moving average breakout system, it monitors the 60-period moving average on 5-minute, 15-minute, and 30-minute charts. Breakouts (either above or below) can be set to notify users via Idea or DingTalk." +
                "这是用于实时监控A股股票行情的工具，根据均线突破体系监控股票5分钟、15分钟、30分钟的60均线，突破或跌破都可以设置通过idea通知或钉钉通知用户。").trimIndent()
        changeNotes = "支持 A股多品种 MA60 均线突破/跌破监控".trimIndent()
        ideaVersion {
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
