package dev.handeye.orchestrator.orchestrator

import dev.handeye.orchestrator.runner.ScenarioResult

enum class HostPage(val id: String) {
    DEMO("demo"),
    STORY("story"),
}

/**
 * scenario 对素材的需求，两种形态：
 *
 * - [MediaNeed]：声明属性要求，从 `fixtures/e2e.example.json` 的 `media` 数组反查首个满足
 *   `attrs` 的素材（素材名无意义，只认属性）；
 * - [DraftNeed]：按草稿稳定 key 点选，直接加载已存工程，不做属性反查（草稿属性是工程状态，
 *   与文件素材属性不同维度，暂不并池）。
 */
sealed interface FixtureNeed {
    companion object {
        /** 素材时长下界（毫秒）。scenario 选区端点的安全边界。 */
        const val MIN_DURATION_MS = "minDurationMs"

        /** 是否支持变速（视频=true，图片=false）。变速场景反查时须带 true 约束。 */
        const val SUPPORTS_SPEED = "supportsSpeed"

        /** 唯一性约束：带此 key（值 "true"）的需求元素，其反查出的素材不得被同场景其它需求复用。 */
        const val UNIQUE_MEDIA = "uniqueMedia"

        /** 单素材便捷构造：把单张约束 map 包装成单元素数组。 */
        fun media(constraints: Map<String, String> = emptyMap()) = MediaNeed(listOf(constraints))

        /** 多素材构造：数组每个元素是一个素材的属性要求，逐元素反查。 */
        fun mediaList(needs: List<Map<String, String>>) = MediaNeed(needs)

        fun draft(key: String) = DraftNeed(key)
    }
}

/** 冷开局原始素材：一个或多个素材的属性要求数组，每个元素从 media 数组反查一个素材。 */
data class MediaNeed(val needs: List<Map<String, String>>) : FixtureNeed

/**
 * 已存草稿工程：按稳定 key 点选加载。
 *
 * [key] 是跨设备稳定的草稿别名（如 `camera_movement` / `multi_clip` / `freeze`），不是设备生成的
 * 真实 project id；本机 `e2e.local.json` 里用 `.drafts.<key>.project_id` 存它的真实 id 映射。
 */
data class DraftNeed(val key: String) : FixtureNeed

data class ScenarioMeta(
    val name: String,
    val tags: Set<String>,
    val page: HostPage,
    val fixture: FixtureNeed = FixtureNeed.media(),
    val run: (String) -> ScenarioResult,
)
