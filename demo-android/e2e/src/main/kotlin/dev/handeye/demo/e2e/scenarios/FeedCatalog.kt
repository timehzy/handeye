package dev.handeye.demo.e2e.scenarios

import dev.handeye.orchestrator.orchestrator.FixtureNeed
import dev.handeye.orchestrator.orchestrator.HostPage
import dev.handeye.orchestrator.orchestrator.ScenarioMeta

/** Feed demo 场景目录 —— 覆盖「用户操作 → 网络请求 → 数据缓存 → UI 更新」链条的核心路径。 */
val feedScenarios: List<ScenarioMeta> = listOf(
    ScenarioMeta(
        "refresh_shows_latest_feed",
        setOf("smoke", "feed"),
        HostPage.DEMO,
        FixtureNeed.media(),
        run = ::runRefreshShowsLatestFeed,
    ),
    ScenarioMeta(
        "refresh_failure_keeps_cache",
        setOf("feed", "failure"),
        HostPage.DEMO,
        FixtureNeed.media(),
        run = ::runRefreshFailureKeepsCache,
    ),
    ScenarioMeta(
        "refresh_is_idempotent",
        setOf("feed", "idempotency"),
        HostPage.DEMO,
        FixtureNeed.media(),
        run = ::runRefreshIsIdempotent,
    ),
    ScenarioMeta(
        "toggle_like_persists",
        setOf("feed", "like"),
        HostPage.DEMO,
        FixtureNeed.media(),
        run = ::runToggleLikePersists,
    ),
    ScenarioMeta(
        "like_survives_recreate",
        setOf("feed", "like"),
        HostPage.DEMO,
        FixtureNeed.media(),
        run = ::runLikeSurvivesRecreate,
    ),
    ScenarioMeta(
        "cold_start_from_cache",
        setOf("feed", "coldstart"),
        HostPage.DEMO,
        FixtureNeed.media(),
        run = ::runColdStartFromCache,
    ),
)
