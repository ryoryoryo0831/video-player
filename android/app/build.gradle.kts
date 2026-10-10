import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions の実行番号をバージョンに使う（更新インストールできるように毎回増える）
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// 署名鍵は GitHub Secrets から渡す。無い場合はデバッグ鍵で署名する
val keystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
if (keystoreFile == null || keystorePassword == null) {
    // 黙ってデバッグ鍵で署名すると、上書きインストールできない APK ができてしまうので知らせる
    logger.warn(
        "警告：署名鍵（KEYSTORE_FILE・KEYSTORE_PASSWORD）が無いため、デバッグ鍵で署名します。" +
            "この APK は今入っている Orbit に上書きインストールできません（入れ直すと履歴などが消えます）。"
    )
}

android {
    namespace = "com.ryose.videoplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ryose.videoplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // libVLC はCPUごとの部品が大きいので、CPUの種類ごとにAPKを分ける
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // libVLC のネイティブライブラリを圧縮して APK を小さくする
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (keystoreFile != null && keystorePassword != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                storeType = "pkcs12"
                keyAlias = "videoplayer"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        // 品質チェックの結果は CI のログに出す（いまは見つかってもビルドは止めない）
        abortOnError = false
        textReport = true
        textOutput = file("build/reports/lint.txt")
    }
    testOptions {
        // テストでは Android の部品は使わない（呼ばれても既定値を返す）
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ライブラリは compileSdk 36・AGP 8 のまま使える版にそろえている
// （これより新しい版は compileSdk 36.1・37 や AGP 9 が必要になり、作り直しに近い移行になるため）
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.12.4")
    // 起動中の画面（Android 12 以降のスプラッシュ画面を、古い端末でも同じように出す）
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    // 通知・ロック画面・イヤホンのボタンからの操作（MediaSession）
    implementation("androidx.media:media:1.7.0")

    // VLC と同じ再生エンジン
    implementation("org.videolan.android:libvlc-all:3.7.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("io.coil-kt:coil:2.7.0")

    testImplementation("junit:junit:4.13.2")
}
