package dev.handeye.orchestrator.orchestrator

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * [ScenarioRegistry] 的默认线程安全实现。
 *
 * 使用 [ReentrantReadWriteLock] 保护内部条目列表，支持并发读与互斥写；register / unregister /
 * replaceAll 为 impl 特有方法，不进 [ScenarioRegistry] 接口。
 */
class DefaultScenarioRegistry : ScenarioRegistry {
    private val lock = ReentrantReadWriteLock()
    private val entries = mutableListOf<ScenarioMeta>()

    /** 顺序注册一个或多个场景。 */
    fun register(vararg metas: ScenarioMeta) = lock.write { entries += metas }

    /** 按 name 移除所有匹配场景（name 唯一时等价移除一条）。 */
    fun unregister(name: String) = lock.write { entries.removeAll { it.name == name } }

    /** 全量替换当前注册表；用于 catalog shim 批量初始化或热更新场景列表。 */
    fun replaceAll(metas: List<ScenarioMeta>) = lock.write {
        entries.clear()
        entries += metas
    }

    override fun all(): List<ScenarioMeta> = lock.read { entries.toList() }

    override fun findByName(name: String): ScenarioMeta? =
        lock.read { entries.firstOrNull { it.name == name } }

    override fun filterByTag(tag: String): List<ScenarioMeta> =
        lock.read { entries.filter { tag in it.tags } }
}
