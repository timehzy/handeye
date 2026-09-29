plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.handeye.device.android"
    compileSdk = libs.versions.android.compile.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.min.get().toInt()
    }
}

dependencies {
    // device-android 不是第二套实现：AAR 内承载的就是 device-kmp 同一份 Kotlin 源码。
    // 本模块给 Android 原生工程一个独立坐标入口，随包转发 device-kmp 的 android 变体。
    api(project(":device-kmp"))
}
