package dev.handeye.orchestrator.orchestrator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 素材反查器——在 bootstrap plan 阶段，把 scenario 的 [MediaNeed] 属性要求数组映到
 * `fixtures/e2e.example.json` 的 `media` 数组，每个需求元素返回首个满足 `attrs` 的素材。
 *
 * 反查语义：
 * - 每个约束 key 对应素材 `attrs` 同名 key，按内置语义比较（[FixtureNeed.MIN_DURATION_MS] 下界，其余等值）；
 * - 素材缺某约束 key = 不满足该约束（被排除），不是报错；
 * - 带 [FixtureNeed.UNIQUE_MEDIA] 的需求元素不复用同场景先前已选中的素材；
 * - 无任一素材满足 → 记录违规明细（plan 阶段早报）。
 */
object FixtureCatalogValidator {

    /** 素材条目路径三件套：沙盒相对段 / Mac 本地源 / 共享盘兜底源。 */
    data class ResolvedMedia(
        val devicePath: String,
        val hostPath: String?,
        val smbPath: String?,
    )

    /** 反查失败信息，key 为场景名。 */
    class CatalogMissingException(val catalogJson: File) : RuntimeException(
        "fixture catalog 不存在：${catalogJson.path}",
    )

    /** catalog 文件存在但内容非法（JSON 语法错 / 根非 object / media 非数组）。 */
    class CatalogInvalidException(val catalogJson: File, cause: Throwable) : RuntimeException(
        "fixture catalog 解析失败：${catalogJson.path}（${cause.message}）",
        cause,
    )

    /**
     * 把一批 scenario 的 media 需求逐元素反查成素材；draft 需求跳过。
     * 返回 (场景名 → 按需求顺序选中的素材列表) 与 (场景名 → 违规明细)。
     */
    fun resolve(
        scenarios: List<ScenarioMeta>,
        catalogJson: File,
    ): Pair<Map<String, List<ResolvedMedia>>, Map<String, List<String>>> {
        if (!catalogJson.exists()) {
            throw CatalogMissingException(catalogJson)
        }
        val media = try {
            val root = Json.parseToJsonElement(catalogJson.readText())
            val mediaNode = root.jsonObject["media"] ?: JsonArray(emptyList())
            mediaNode.jsonArray
        } catch (e: Exception) {
            throw CatalogInvalidException(catalogJson, e)
        }

        val resolved = linkedMapOf<String, List<ResolvedMedia>>()
        val violations = linkedMapOf<String, List<String>>()

        for (meta in scenarios) {
            val need = meta.fixture as? MediaNeed ?: continue
            val selectedPaths = mutableSetOf<String>()   // 已选素材的 device_path，供唯一性约束用
            val selected = mutableListOf<ResolvedMedia>()
            val msgs = mutableListOf<String>()
            for (constraints in need.needs) {
                val match = firstMatch(media, constraints, selectedPaths)
                if (match == null) {
                    msgs += "无素材满足属性要求 $constraints"
                    continue
                }
                val devicePath = match.string("device_path")
                if (!isValidRelativePath(devicePath)) {
                    msgs += "反查命中的素材 device_path 非法（非空相对段，${devicePath?.ifBlank { "<空>" }}）"
                    continue
                }
                val hostPath = match.string("host_path")
                val smbPath = match.string("smb_path")
                val sourceViolation = sourceViolation(hostPath, smbPath)
                if (sourceViolation != null) {
                    msgs += sourceViolation
                    continue
                }
                selected += ResolvedMedia(
                    devicePath = devicePath!!,
                    hostPath = hostPath,
                    smbPath = smbPath,
                )
                selectedPaths += devicePath
            }
            if (msgs.isNotEmpty()) violations[meta.name] = msgs
            else resolved[meta.name] = selected
        }
        return resolved to violations
    }

    /**
     * 在 media 数组中找首个满足全部约束的元素，无命中返回 null。
     * 约束含 [FixtureNeed.UNIQUE_MEDIA]（值 "true"）时跳过 [selectedPaths] 已选中的素材。
     */
    private fun firstMatch(
        media: JsonArray,
        constraints: Map<String, String>,
        selectedPaths: Set<String>,
    ): JsonObject? {
        val unique = constraints[FixtureNeed.UNIQUE_MEDIA] == "true"
        outer@ for (element in media) {
            val entry = element as? JsonObject ?: continue@outer
            val attrs = entry["attrs"] as? JsonObject ?: continue@outer
            if (unique) {
                val devicePath = entry["device_path"] as? JsonPrimitive ?: continue@outer
                if (devicePath.contentOrNull in selectedPaths) continue@outer
            }
            for ((key, expected) in constraints) {
                if (key == FixtureNeed.UNIQUE_MEDIA) continue  // 唯一性不参与 attrs 比较
                val actual = (attrs[key] as? JsonPrimitive)?.contentOrNull ?: continue@outer
                if (!satisfied(key, expected, actual)) continue@outer
            }
            return entry
        }
        return null
    }

    /** 按约束 key 内置语义比较。默认等值，[FixtureNeed.MIN_DURATION_MS] 走数值下界。 */
    private fun satisfied(key: String, expected: String, actual: String): Boolean {
        if (key == FixtureNeed.MIN_DURATION_MS) {
            val e = expected.toLongOrNull() ?: return false
            val a = actual.toLongOrNull() ?: return false
            return a >= e
        }
        return actual == expected
    }

    /** device_path 是否合法相对路径：非空、非绝对、分段非空且非 `..`、Chars 仅限安全字符。
     * 该值会被拼进 deep link 的 work_path query，限制字符集避免空格/& 之类破坏 URL 与 shell 分词。 */
    private fun isValidRelativePath(path: String?): Boolean {
        if (path.isNullOrBlank() || path.startsWith("/")) return false
        return path.split("/").none { seg ->
            seg.isBlank() || seg == ".." || seg.any { !it.isLetterOrDigit() && it != '.' && it != '_' && it != '-' }
        }
    }

    /**
     * 校验 media 两条源路径的形态，返回违规文案或 null。
     *
     * 与 [isValidRelativePath] 收紧 device_path 字符集不同，host_path / smb_path 是供
     * bootstrap 脚本调 fetch_media.sh 拉取的宿主源路径，经 bash 引号包裹进 mount_smbfs / cp，
     * 不进 URL query——所以这里只校验「形状是否像合法源」，不收紧字符（中文/空格在本地与
     * 共享盘路径里合法）。规则：
     * - host_path 非空时须是 host 本地绝对路径（`/` 开头）；
     * - smb_path 非空时须是 UNC（`\\` 或 `//` 开头）或已挂载绝对路径（`/` 开头）；
     * - 两者都空 → 设备无素材时无源可拉，记违规早报。
     */
    private fun sourceViolation(hostPath: String?, smbPath: String?): String? {
        val host = hostPath?.takeIf { it.isNotBlank() }
        val smb = smbPath?.takeIf { it.isNotBlank() }
        if (host == null && smb == null) {
            return "反查命中的素材无可用源路径（host_path / smb_path 都为空）"
        }
        if (host != null && !host.startsWith("/")) {
            return "反查命中的素材 host_path 非法（非 host 本地绝对路径，$host）"
        }
        if (smb != null && !(smb.startsWith("/") || smb.startsWith("\\"))) {
            return "反查命中的素材 smb_path 非法（非 UNC 或已挂载绝对路径，$smb）"
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
