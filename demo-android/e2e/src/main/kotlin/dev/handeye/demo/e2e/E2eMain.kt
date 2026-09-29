package dev.handeye.demo.e2e

import dev.handeye.demo.e2e.scenarios.feedScenarios
import dev.handeye.orchestrator.orchestrator.DefaultScenarioRegistry
import dev.handeye.orchestrator.orchestrator.ScenarioRunner
import dev.handeye.orchestrator.orchestrator.cli

/**
 * Feed demo e2e 入口 —— `./gradlew :demo-android:e2e:run --args="--all http://localhost:<port>"`。
 * 参数形态见 [ScenarioRunner.cli]：`--all <baseUrl>` / `--tag <tag> <baseUrl>` / `<scenario> <baseUrl>`。
 */
fun main(args: Array<String>) {
    val registry = DefaultScenarioRegistry().apply {
        register(*feedScenarios.toTypedArray())
    }
    ScenarioRunner(registry).cli(args)
}
