plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.handeye.conformance.ConformanceMainKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit"))
}

tasks.test {
    useJUnit()
    // golden 样本在 protocol/golden，经 rootProject 绝对路径注入，测试不依赖 working dir。
    systemProperty("golden.dir", rootProject.file("protocol/golden").absolutePath)
}
