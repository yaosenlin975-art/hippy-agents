import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lin.hippyagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lin.hippyagent"
        minSdk = 26
        targetSdk = 34

        // 自动版本号：基于打包时间，每次构建强制重新计算
        // versionCode: 26000000 + 从2026-01-01起的分钟数（确保大于旧格式 yyMMddHH 且不溢出Int）
        // versionName: 大版本.小版本.时间戳 格式（如 1.0.2604291053）
        val now = Date()
        val cal = Calendar.getInstance()
        cal.set(2026, 0, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val baseMinutes = cal.timeInMillis / 60_000
        versionCode = 26_000_000 + (now.time / 60_000 - baseMinutes).toInt()
        versionName = "0.1.${SimpleDateFormat("yyMMddHHmm").format(now)}"
        // 将版本名写入 BuildConfig 确保应用内可读取最新值
        buildConfigField("String", "VERSION_NAME", "\"$versionName\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // moonshine-voice 和 onnxruntime-android 都打包了 libonnxruntime.so，保留显式依赖的版本
            useLegacyPackaging = true
            pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            storePassword = "Angel6659"
            keyAlias = "hippy-agent"
            keyPassword = "Angel6659"
        }
    }

    buildTypes {
        all {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isShrinkResources = true
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Biometric
    implementation("androidx.biometric:biometric:1.1.0")

    // Room
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Chinese Segmentation
    implementation("com.huaban:jieba-analysis:1.0.2")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Jsoup (HTML parsing for skillhub.cn provider)
    implementation("org.jsoup:jsoup:1.17.2")

    // ZXing (QR code generation)
    implementation("com.google.zxing:core:3.5.3")

    // NanoHTTPD
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Koin DI
    implementation("io.insert-koin:koin-android:3.5.3")
    implementation("io.insert-koin:koin-androidx-compose:3.5.3")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Compose Markdown
    implementation("com.github.jeziellago:compose-markdown:0.7.1")

    // Coil (图片加载)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Apache Commons Compress (for tar.gz extraction)
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // DocumentFile (SAF)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Moonshine Voice SDK (STT - 离线语音转文字)
    // SDK minSdk=35, 使用反射调用 + 运行时 API level 检查兼容低版本设备
    // 排除 moonshine 自带的 onnxruntime（使用下方独立 onnxruntime 版本避免冲突）
    implementation("ai.moonshine:moonshine-voice:0.0.59") {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }

    // ONNX Runtime (YOLO UI detection)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.0")

    // LiteRT-LM (端侧模型推理)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.12.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

