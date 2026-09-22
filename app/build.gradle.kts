plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.edu.jxau.tools"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.edu.jxau.tools"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // 离线缓存里没有 lint 产物，关闭打包时顺带跑的 vital lint（不影响单独执行 :app:lint）
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 版本严格对齐本机 ~/.gradle 缓存（见 docs 第 8 节工具链勘察）
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // 过渡动画（`AnimatedContent` / `animateContentSize` / `animateItem`）。
    // foundation 会把它作为传递依赖带进来，但这里**显式声明**：过渡动画是本应用
    // 交互约定的一部分（唯一入口见 `ui/Motion.kt`），不该依赖「别人顺手带进来」。
    // 版本仍由上面的 BOM 管。
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // 网络：okhttp 4.12.0（okhttp-urlconnection 未缓存，CookieJar 自行实现）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // HTML 解析：用于从教务系统主页面里提取左侧菜单（课表等接口路径的唯一可靠来源）
    implementation("org.jsoup:jsoup:1.18.3")
    // JSON：只走运行时 JsonElement API。注意 kotlin-serialization-compiler-plugin-embeddable
    // 不在缓存里，因此**不能**启用 kotlin("plugin.serialization") 插件。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
