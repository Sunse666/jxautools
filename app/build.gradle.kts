import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// release 签名凭据：`.secrets/keystore.properties`（该目录已在 .gitignore 里排除）。
// **缺失时不报错**，release 退化为「未签名包」—— 新克隆的机器照样能编译，只是打不出可安装的正式包。
val keystorePropsFile = rootProject.file(".secrets/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "cn.edu.jxau.tools"
    compileSdk = 35

    defaultConfig {
        // ⚠️ **与 Pro 版（`cn.edu.jxau.tools`）故意不同** —— 去抢课精简版是**另一条安装链**。
        // 改这一行的代价要说清楚：它决定「谁能覆盖安装谁」。
        //   · 同 id 才能覆盖升级（也必须同一把密钥签名）；换 id = 换了一个 App，两版可同机共存，
        //     但**精简版的用户想换到 Pro 版必须卸载重装**（连带清空会话与设置）。
        //   · 2026-09-23 拍板选共存：精简版是给同学的分发版、Pro 是自用全功能版。
        // namespace 不变（= 源码包名 `cn.edu.jxau.tools`，只影响 R 类与 BuildConfig 的包名）。
        // `AndroidManifest.xml` 里 FileProvider 用的是 `${applicationId}.fileprovider` 占位符，
        // 会自动跟着这里走，不需要手改。
        applicationId = "cn.edu.jxau.tools.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // minSdk 26 的机器只读 v2/v3，但第三方安装器（应用宝、手机厂商商店的本地安装）
                // 与部分国产 ROM 仍会查 v1 签名。三个都开，代价只是 APK 里多一段签名块。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 段 1：**先不开 R8**。签名与混淆分两步走 —— R8 的失败是运行期静默的，
            // 和签名捆在同一步做，出问题分不清是哪一边（见 docs/发布说明 §阶段划分）。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 显式写出来：debuggable 是「能编译、界面正常、但私有目录可被 run-as 拖走」的那类开关，
            // 靠默认值等于靠别人替你记着。
            isDebuggable = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
