import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 商店版的正式签名，放在仓库外；没有这个文件时 oppo 渠道的 release 包不签名
val storeSigning = Properties().apply {
    val f = File(System.getProperty("user.home"), ".tooodo/release-signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tooodo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tooodo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.2.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (!storeSigning.isEmpty) create("store") {
            storeFile = file(storeSigning.getProperty("storeFile"))
            storePassword = storeSigning.getProperty("storePassword")
            keyAlias = storeSigning.getProperty("keyAlias")
            keyPassword = storeSigning.getProperty("keyPassword")
        }
    }

    // gitee：侧载版，带应用内更新，继续用 debug 签名，已装的手机才能覆盖升级。
    // oppo：应用商店版，不联网、没有自更新，用正式签名。
    flavorDimensions += "channel"
    productFlavors {
        create("gitee") {
            dimension = "channel"
            signingConfig = signingConfigs.getByName("debug")
        }
        create("oppo") {
            dimension = "channel"
            signingConfig = signingConfigs.findByName("store")
        }
    }

    // 隐私政策只写一份：docs/privacy/privacy.md，既打进 APK，也是商店填写的网址
    sourceSets["main"].assets.srcDir("../docs/privacy")

    buildTypes {
        release {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
    // Android 自带的 org.json 在单元测试里是空壳，换成真的实现
    testImplementation("org.json:json:20240303")
}
