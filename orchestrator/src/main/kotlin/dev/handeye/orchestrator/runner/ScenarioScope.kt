package dev.handeye.orchestrator.runner

import dev.handeye.orchestrator.context.E2eContext

/** DSL state collected by the generic [hostVmTest] entry point. */
class ScenarioScope<Ctx : E2eContext>(val ctx: Ctx) {

    @PublishedApi
    internal var givenStateBlock: (suspend ActScope.() -> Unit)? = null

    @PublishedApi
    internal var actBlock: (suspend ActScope.() -> Unit)? = null

    @PublishedApi
    internal var expectFactBlock: (ExpectFactScope.(Map<String, Any?>) -> Unit)? = null

    @PublishedApi
    internal var expectDependenciesBlock: (ExpectDependencyScope.() -> Unit)? = null

    @PublishedApi
    internal var strictnessMode: StrictnessMode = StrictnessMode.NonExhaustive

    fun givenState(block: suspend ActScope.() -> Unit) {
        givenStateBlock = block
    }

    fun act(block: suspend ActScope.() -> Unit) {
        actBlock = block
    }

    fun expectFact(block: ExpectFactScope.(Map<String, Any?>) -> Unit) {
        expectFactBlock = block
    }

    fun expectDependencies(block: ExpectDependencyScope.() -> Unit) {
        expectDependenciesBlock = block
    }

    fun strictness(mode: StrictnessMode) {
        strictnessMode = mode
    }
}
