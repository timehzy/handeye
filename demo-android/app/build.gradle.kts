plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.handeye.demo"
    compileSdk = libs.versions.android.compile.get().toInt()

    defaultConfig {
        applicationId = "dev.handeye.demo"
        minSdk = libs.versions.android.min.get().toInt()
        targetSdk = libs.versions.android.target.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // 与 local.sh 的 HANDEYE_DEEPLINK_SCHEME 对应；改 scheme 时两边一起改
        buildConfigField("String", "HANDEYE_SCHEME", "\"handeye\"")
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":device-kmp"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // 单测走 kotlin-test（JVM 跑，android 类型不进测试面）
    testImplementation(kotlin("test"))
}
