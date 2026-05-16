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
        name = "Stock Monitor"
        version = "1.0.0"
        description = "A professional plugin for monitoring A-stock moving average breakout signals in real-time via IDEA's left sidebar tool window. Supports IntelliJ IDEA 2021.2 and later versions.".trimIndent()
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
