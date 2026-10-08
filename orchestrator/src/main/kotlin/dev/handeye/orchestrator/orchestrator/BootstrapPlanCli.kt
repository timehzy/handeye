package dev.handeye.orchestrator.orchestrator

import java.io.File

/**
 * `--print-bootstrap-plan` 子命令 —— 把本批次 scenario 的 (page, source) 依赖打印给
 * e2e.sh 消费，支撑 per-scenario bootstrap（backlog 2026-07-22 素材硬编码 P0 + 跨页面
 * 混跑 P1 两条目的 α 方案）。
 *
 * 打印前先走 [FixtureCatalogValidator] 校验素材约束（backlog 2026-09-08 素材结构化声明），
 * 不满足则打印违规明细并返回 2。
 *
 * ## 调用形式
 *
 * ```
 * ScenarioRunner --print-bootstrap-plan <scenario>
 * ScenarioRunner --print-bootstrap-plan --tag <tag>
 * ScenarioRunner --print-bootstrap-plan --all
 * ```
 *
 * ## 输出契约（stdout，供 e2e.sh 逐行解析）
 *
 * 每行一个 scenario，Tab 分隔四列，顺序与注入 registry 的定义一致：
 *
 * ```
 * <name>\t<page>\t<source>\t<host_path>
 * ```
 *
 * - `page`：[HostPage.id]（当前仅 `demo`）
 * - `source`：media 需求反查出的 `device_path` 相对段（如 `e2e_media/xxx.mp4`）；draft 需求
 *   输出 `draft:<key>`
 * - `host_path`：media 需求反查素材的 host 本地源路径，供脚本拉取兜底；draft 需求该列为空
 *
 * media 需求声明多个素材时，`source` / `host_path` 两列各自按 `|` 连接，列内
 * 元素顺序与需求的 `needs` 顺序一致（`e2e.sh` 按 `|` 拆回多个素材）。
 *
 * ## 退出码
 *
 * - 0：plan 已完整打印
 * - 2：参数错误（缺选择器 / 未知 scenario / 未知 tag / 选择器多余）或素材反查不满足
 */
object BootstrapPlanCli {

    const val FLAG = "--print-bootstrap-plan"

    fun run(args: List<String>, registry: ScenarioRegistry): Int =
        run(args, registry, catalogJson = defaultCatalogJson())

    /**
     * 带 catalog 覆盖的重载：单测注入预置 JSON，或真实跑批显式指定路径。
     * [catalogJson] 为 null 时跳过反查（无 catalog 仍打印 plan，仅打 stderr 提示）。
     */
    fun run(args: List<String>, registry: ScenarioRegistry, catalogJson: File?): Int {
        val scenarios = resolve(args, registry) ?: return 2

        // media 需求反查素材，draft 需求不参与。反查失败早报。
        val resolved: Map<String, List<FixtureCatalogValidator.ResolvedMedia>> = if (catalogJson != null) {
            try {
                val (selected, violations) = FixtureCatalogValidator.resolve(scenarios, catalogJson)
                if (violations.isNotEmpty()) {
                    for ((name, msgs) in violations) {
                        System.err.println("素材反查不满足 · $name: ${msgs.joinToString("；")}")
                    }
                    return 2
                }
                selected
            } catch (e: FixtureCatalogValidator.CatalogMissingException) {
                System.err.println("错误: ${e.message}")
                if (scenarios.any { it.fixture is MediaNeed }) {
                    System.err.println("本批次含 MediaNeed 场景，缺少 catalog 无法反查素材（检查 HANDEYE_FIXTURES_JSON 或 fixtures 目录）")
                    return 2
                }
                System.err.println("本批次无 MediaNeed 场景，跳过素材反查")
                emptyMap()
            } catch (e: FixtureCatalogValidator.CatalogInvalidException) {
                System.err.println("错误: ${e.message}")
                if (scenarios.any { it.fixture is MediaNeed }) {
                    System.err.println("本批次含 MediaNeed 场景，catalog 非法无法反查素材（检查 JSON 语法与 media 数组结构）")
                    return 2
                }
                System.err.println("本批次无 MediaNeed 场景，跳过素材反查")
                emptyMap()
            }
        } else {
            if (scenarios.any { it.fixture is MediaNeed }) {
                System.err.println("错误: 本批次含 MediaNeed 场景，但未提供 catalog 无法反查素材")
                return 2
            }
            System.err.println("本批次无 MediaNeed 场景，跳过素材反查")
            emptyMap()
        }

        for (meta in scenarios) {
            val (source, hostPath) = when (val need = meta.fixture) {
                is MediaNeed -> {
                    val list = resolved[meta.name] ?: emptyList()
                    list.joinToString("|") { it.devicePath } to
                        list.joinToString("|") { it.hostPath.orEmpty() }
                }
                is DraftNeed -> "draft:${need.key}" to ""
            }
            println("${meta.name}\t${meta.page.id}\t$source\t$hostPath")
        }
        return 0
    }

    /**
     * 解析 fixtures catalog 路径：`HANDEYE_FIXTURES_JSON` > `e2e.local.json`（本机覆盖）>
     * `e2e.example.json`（模板回退）。相对候选覆盖 working dir 在 `insedit/` 根或
     * `e2e-scenarios/` 子项目两种 gradle run 布局；本机 catalog 存在时优先用本机值。
     */
    private fun defaultCatalogJson(): File {
        val env = System.getenv("HANDEYE_FIXTURES_JSON")
        if (!env.isNullOrBlank()) return File(env)
        val candidates = listOf(
            "e2e-scenarios/fixtures/e2e.local.json",
            "fixtures/e2e.local.json",
            "e2e-scenarios/fixtures/e2e.example.json",
            "fixtures/e2e.example.json",
        )
        return candidates.firstOrNull { File(it).exists() }?.let { File(it) }
            ?: File(candidates.first())
    }

    /**
     * 把 CLI 参数解析成 scenario 列表；非法输入打 stderr 并返回 null。
     * 独立出来便于单测直接断言解析结果（不走 stdout）。
     */
    fun resolve(args: List<String>, registry: ScenarioRegistry): List<ScenarioMeta>? = when {
        args.size == 2 && args[0] == "--tag" -> {
            val matched = registry.filterByTag(args[1])
            if (matched.isEmpty()) {
                System.err.println("tag \"${args[1]}\" 未命中任何场景")
                System.err.println("已注册 tag: ${registry.all().flatMap { it.tags }.toSortedSet().joinToString()}")
                null
            } else {
                matched
            }
        }
        args.size == 1 && args[0] == "--all" -> registry.all()
        args.size == 1 && !args[0].startsWith("--") -> {
            val meta = registry.findByName(args[0])
            if (meta == null) {
                System.err.println("未知场景: ${args[0]}")
                System.err.println("可选场景: ${registry.all().joinToString { it.name }}")
                null
            } else {
                listOf(meta)
            }
        }
        else -> {
            System.err.println("用法:")
            System.err.println("  ScenarioRunner $FLAG <scenario>")
            System.err.println("  ScenarioRunner $FLAG --tag <tag>")
            System.err.println("  ScenarioRunner $FLAG --all")
            null
        }
    }
}
