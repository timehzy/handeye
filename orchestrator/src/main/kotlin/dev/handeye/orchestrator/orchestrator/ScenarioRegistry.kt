package dev.handeye.orchestrator.orchestrator

/**
 * 场景注册表接口 —— 仅暴露只读查询能力。
 *
 * 增删改能力由具体实现（如 [DefaultScenarioRegistry]）提供，不进接口，保持接口只关注
 * runner / plan 等消费方的查询契约。
 */
interface ScenarioRegistry {
    /** 返回当前已注册的全部场景，顺序与注册顺序一致。 */
    fun all(): List<ScenarioMeta>

    /** 按 name 精确匹配单条场景；未命中返回 null。 */
    fun findByName(name: String): ScenarioMeta?

    /** 按 tag 过滤，返回命中场景列表，顺序与 [all] 一致。 */
    fun filterByTag(tag: String): List<ScenarioMeta>
}
