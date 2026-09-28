package dev.handeye.orchestrator.factsource

interface FactSource<T> {
    val name: String
    suspend fun fetch(): T?
}
