import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "net.pocketnai"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.pocketnai"
        minSdk = 26
        targetSdk = 35
        // CI 注入构建号（github.run_number，单调递增）—— 应用内的"检查更新"就是拿
        // BuildConfig.VERSION_CODE 与最新 Release 的 tag 数字比较，因此这里的值
        // 必须与 Release 的 tag 同源。本地构建保持 1 / "0.1.0"。
        versionCode = System.getenv("PNAI_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("PNAI_VERSION_NAME") ?: "0.1.0"

        // 仪器化测试只在本地设备上跑（用于验证必须依赖 Android 位图 API 的图片后处理），
        // 不参与任何发布产物。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 首版只在设备本地存储，不使用任何开发者中转服务器。
        //
        // 注意：NovelAI 的 api.novelai.net 已经不再接受第三方工具的 Persistent API Token
        // （会返回 400 "Please refresh NovelAI.net. If using a third-party tool, update to the
        // image URL."）。账户状态与图像生成都必须走 image.novelai.net，因此这里只有一个
        // API 主机常量，避免以后有人再往 api 主机上发请求。
        buildConfigField("String", "NOVELAI_API_BASE_URL", "\"https://image.novelai.net\"")

        // 检查更新读的 GitHub 仓库（owner/name）。CI 把每次提交发布成这里的 Release，
        // 应用只匿名读取公开的版本信息；换仓库时只改这一处。
        buildConfigField("String", "UPDATE_REPO", "\"Alahjarko/PocketNAI\"")
    }

    // CI 通过环境变量显式指定签名密钥（必须与用户手机上既有安装是同一把）：
    // 不再依赖 AGP 对 ~/.android/debug.keystore 的默认查找 —— 2026-09-18 实测
    // runner 上那个方案没被采用（构建用了自动生成的新密钥，更新包签名校验失败），
    // 显式指定文件才是可靠的。本地不设这些变量，debug 构建行为不变。
    val pinnedKeystorePath = System.getenv("PNAI_KEYSTORE_PATH")
    signingConfigs {
        if (pinnedKeystorePath != null) {
            create("pinned") {
                storeFile = file(pinnedKeystorePath)
                // debug keystore 的标准密码，非敏感（所有 Android 开发者都知道）。
                storePassword = System.getenv("PNAI_KEYSTORE_STORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("PNAI_KEYSTORE_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("PNAI_KEYSTORE_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 只在 CI 显式指定了密钥时才覆盖签名配置。**不能无条件赋值**（包括赋 null）：
            // 那会把 AGP 预置的默认 debug 签名清掉，本地构建直接产出 app-debug-unsigned.apk
            // （2026-09-18 实测踩中：构建成功、产物却没签名）。
            signingConfigs.findByName("pinned")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 迁移测试要能读到导出的 schema（`app/schemas`），否则 MigrationTestHelper
    // 建不出旧版本的库，"迁移是否保住了历史"就只能靠人工装机试。
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room 导出 schema，后续版本升级必须基于它写显式迁移。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.coil.compose)

    // 只用于本地派生 NovelAI Access Key（BLAKE2b + Argon2id）。
    // 直接实例化原语，不在系统里注册或替换任何 Provider。
    implementation(libs.bouncycastle.bcprov)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // 仪器化测试：验证必须用 Android 位图 API 的那部分（生成结果的裁切与 PNG 元数据保全）。
    androidTestImplementation(libs.androidx.room.testing)
    // 分辨率控件的界面测试：尺寸对照文案是"不静默调整"的落点，必须能被断言。
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
