plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tooodo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tooodo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 签名，方便直接侧载安装
            signingConfig = signingConfigs.getByName("debug")
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
