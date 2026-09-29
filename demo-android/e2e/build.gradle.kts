// demo 的 e2e scenario 承载模块 —— 纯 Kotlin/JVM，只在 host（Mac/Linux）跑，不下发 device。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.handeye.demo.e2e.E2eMainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":orchestrator"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test-junit"))
    testImplementation(testFixtures(project(":orchestrator")))
}

tasks.test {
    useJUnit()
}
