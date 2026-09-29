package dev.handeye.orchestrator.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.File

/**
 * [FixtureCatalogValidator] 单测——media 数组反查逻辑。
 *
 * 覆盖：满足下界 / 下界不足 / 缺约束 key（被排除）/ 多候选选首个 / 多素材逐元素反查 /
 * 唯一性约束 / 缺 catalog 文件。
 */
class FixtureCatalogValidatorTest {

    private fun scenario(vararg constraints: Map<String, String>): ScenarioMeta =
        ScenarioMeta(
            name = "speed_range_confirm_then_undo_restores",
            tags = setOf("story", "speed"),
            page = HostPage.STORY,
            fixture = MediaNeed(constraints.toList()),
            run = { _ -> error("not used") },
        )

    private fun catalogJson(vararg media: String): File {
        val tmp = File.createTempFile("e2e-catalog", ".json")
        tmp.deleteOnExit()
        tmp.writeText("""{"media":[${media.joinToString(",")}]}""")
        return tmp
    }

    private fun mediaEntry(devicePath: String, attrs: String) =
        """{"device_path":"$devicePath","host_path":"/tmp/$devicePath","smb_path":"\\\\10.0.159.200\\$devicePath","attrs":{$attrs}}"""

    @Test
    fun `素材时长满足下界返回选中素材`() {
        val catalog = catalogJson(mediaEntry("a.mp4", "\"minDurationMs\":\"5289\",\"supportsSpeed\":\"true\""))
        val (resolved, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf(FixtureNeed.MIN_DURATION_MS to "4000", "supportsSpeed" to "true"))),
            catalog,
        )
        assertTrue(violations.isEmpty(), "应无违规：$violations")
        assertEquals("a.mp4", resolved["speed_range_confirm_then_undo_restores"]?.firstOrNull()?.devicePath)
    }

    @Test
    fun `素材时长不足下界记录违规`() {
        val catalog = catalogJson(mediaEntry("a.mp4", "\"minDurationMs\":\"2000\""))
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf(FixtureNeed.MIN_DURATION_MS to "4000"))),
            catalog,
        )
        assertEquals(
            listOf("无素材满足属性要求 {minDurationMs=4000}"),
            violations["speed_range_confirm_then_undo_restores"],
        )
    }

    @Test
    fun `素材缺约束 key 被排除`() {
        // 素材只标 supportsSpeed，缺 minDurationMs → 不满足 minDurationMs 约束
        val catalog = catalogJson(mediaEntry("a.mp4", "\"supportsSpeed\":\"true\""))
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf(FixtureNeed.MIN_DURATION_MS to "4000"))),
            catalog,
        )
        assertTrue(violations.isNotEmpty(), "应记录违规（素材缺 key 被排除）")
    }

    @Test
    fun `首条缺 attrs 挡路第二条满足命中`() {
        // 首条无 attrs、第二条满足约束 → 应跳过首条继续反查，而非 return null 误报无匹配
        val catalog = catalogJson(
            """{"device_path":"x.mp4","host_path":"/tmp/x.mp4","smb_path":"y"}""",
            mediaEntry("a.mp4", "\"supportsSpeed\":\"true\""),
        )
        val (resolved, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertTrue(violations.isEmpty(), "应无违规：$violations")
        assertEquals("a.mp4", resolved["speed_range_confirm_then_undo_restores"]?.firstOrNull()?.devicePath)
    }

    @Test
    fun `命中素材 device_path 为空记违规`() {
        val catalog = catalogJson("""{"device_path":"","host_path":"/tmp/x.mp4","smb_path":"y","attrs":{"supportsSpeed":"true"}}""")
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertEquals(
            listOf("反查命中的素材 device_path 非法（非空相对段，<空>）"),
            violations["speed_range_confirm_then_undo_restores"],
        )
    }

    @Test
    fun `命中素材 device_path 为绝对路径记违规`() {
        val catalog = catalogJson(mediaEntry("/abs/a.mp4", "\"supportsSpeed\":\"true\""))
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertEquals(
            listOf("反查命中的素材 device_path 非法（非空相对段，/abs/a.mp4）"),
            violations["speed_range_confirm_then_undo_restores"],
        )
    }

    @Test
    fun `media 数组含非 object 条目直接跳过`() {
        // 首个条目是字符串（非 object）→ 跳过不抛异常，第二条 object 命中
        val catalog = catalogJson(
            "\"not-an-object\"",
            mediaEntry("a.mp4", "\"supportsSpeed\":\"true\""),
        )
        val (resolved, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertTrue(violations.isEmpty(), "应无违规：$violations")
        assertEquals("a.mp4", resolved["speed_range_confirm_then_undo_restores"]?.firstOrNull()?.devicePath)
    }

    @Test
    fun `空约束首项非法第二项合法命中第二项`() {
        // 空约束下也统一遍历跳过非 object，首项是字符串、第二项是合法 object
        val catalog = catalogJson(
            "\"not-an-object\"",
            mediaEntry("a.mp4", "\"supportsSpeed\":\"true\""),
        )
        val (resolved, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(emptyMap())),
            catalog,
        )
        assertTrue(violations.isEmpty(), "应无违规：$violations")
        assertEquals("a.mp4", resolved["speed_range_confirm_then_undo_restores"]?.firstOrNull()?.devicePath)
    }

    @Test
    fun `device_path 含双点文件名不误判为穿越`() {
        val catalog = catalogJson(mediaEntry("e2e_media/take..v2.mp4", "\"supportsSpeed\":\"true\""))
        val (resolved, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertTrue(violations.isEmpty(), "应无违规：$violations")
        assertEquals("e2e_media/take..v2.mp4", resolved["speed_range_confirm_then_undo_restores"]?.firstOrNull()?.devicePath)
    }

    @Test
    fun `device_path 含上级穿越段记违规`() {
        val catalog = catalogJson(mediaEntry("../escape.mp4", "\"supportsSpeed\":\"true\""))
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertEquals(
            listOf("反查命中的素材 device_path 非法（非空相对段，../escape.mp4）"),
            violations["speed_range_confirm_then_undo_restores"],
        )
    }

    @Test
    fun `多候选选首个满足`() {
        val catalog = catalogJson(
            mediaEntry("first.mp4", "\"supportsSpeed\":\"true\""),
            mediaEntry("second.mp4", "\"supportsSpeed\":\"true\""),
        )
        val (resolved, _) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertEquals("first.mp4", resolved["speed_range_confirm_then_undo_restores"]?.firstOrNull()?.devicePath)
    }

    @Test
    fun `catalog 文件不存在抛 CatalogMissingException`() {
        assertFailsWith<FixtureCatalogValidator.CatalogMissingException> {
            FixtureCatalogValidator.resolve(listOf(scenario(emptyMap())), File("/nonexistent/catalog.json"))
        }
    }

    @Test
    fun `catalog JSON 语法错误抛 CatalogInvalidException`() {
        val tmp = File.createTempFile("e2e-bad", ".json").apply {
            deleteOnExit()
            writeText("{not-valid-json")
        }
        assertFailsWith<FixtureCatalogValidator.CatalogInvalidException> {
            FixtureCatalogValidator.resolve(listOf(scenario(emptyMap())), tmp)
        }
    }

    @Test
    fun `catalog media 非数组抛 CatalogInvalidException`() {
        // 旧版 name-keyed object schema：media 是对象不是数组
        val tmp = File.createTempFile("e2e-old", ".json").apply {
            deleteOnExit()
            writeText("""{"media":{"single_normal":{"device_path":"a.mp4"}}}""")
        }
        assertFailsWith<FixtureCatalogValidator.CatalogInvalidException> {
            FixtureCatalogValidator.resolve(listOf(scenario(emptyMap())), tmp)
        }
    }

    @Test
    fun `attrs 非 object 条目跳过不抛异常`() {
        // attrs 写成数组，jsonObject 转换应安全跳过该条目，不能抛未包装异常
        val catalog = catalogJson(
            """{"device_path":"a.mp4","host_path":"/tmp/a.mp4","smb_path":"y","attrs":[]}""",
        )
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertTrue(violations.isNotEmpty(), "attrs 非 object 应跳过后无匹配，记录违规")
    }

    @Test
    fun `attrs 值非 primitive 条目跳过不抛异常`() {
        // supportsSpeed 写成数组而非字符串，应安全跳过
        val catalog = catalogJson(
            """{"device_path":"a.mp4","host_path":"/tmp/a.mp4","smb_path":"y","attrs":{"supportsSpeed":["true"]}}""",
        )
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertTrue(violations.isNotEmpty(), "属性值非 primitive 应跳过后无匹配")
    }

    @Test
    fun `device_path 含 URI 特殊字符记违规`() {
        val catalog = catalogJson(mediaEntry("e2e_media/a&b.mp4", "\"supportsSpeed\":\"true\""))
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertEquals(
            listOf("反查命中的素材 device_path 非法（非空相对段，e2e_media/a&b.mp4）"),
            violations["speed_range_confirm_then_undo_restores"],
        )
    }

    @Test
    fun `host_path 与 smb_path 都为空记违规`() {
        val catalog = catalogJson(
            """{"device_path":"a.mp4","host_path":"","smb_path":"","attrs":{"supportsSpeed":"true"}}""",
        )
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertEquals(
            listOf("反查命中的素材无可用源路径（host_path / smb_path 都为空）"),
            violations["speed_range_confirm_then_undo_restores"],
        )
    }

    @Test
    fun `host_path 非绝对路径记违规`() {
        val catalog = catalogJson(
            """{"device_path":"a.mp4","host_path":"relative/a.mp4","smb_path":"\\\\10.0.159.200\\share\\a.mp4","attrs":{"supportsSpeed":"true"}}""",
        )
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertEquals(
            listOf("反查命中的素材 host_path 非法（非 host 本地绝对路径，relative/a.mp4）"),
            violations["speed_range_confirm_then_undo_restores"],
        )
    }

    @Test
    fun `smb_path 非 UNC 且非绝对路径记违规`() {
        val catalog = catalogJson(
            """{"device_path":"a.mp4","host_path":"/tmp/a.mp4","smb_path":"just-a-name","attrs":{"supportsSpeed":"true"}}""",
        )
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertEquals(
            listOf("反查命中的素材 smb_path 非法（非 UNC 或已挂载绝对路径，just-a-name）"),
            violations["speed_range_confirm_then_undo_restores"],
        )
    }

    @Test
    fun `host_path 含中文空格仍是合法源路径`() {
        // host_path 源路径不收紧字符集，中文/空格/连字符是合法本地路径
        val catalog = catalogJson(
            """{"device_path":"a.mp4","host_path":"/Volumes/技术中心-测试部/C-t/视频 a.mp4","smb_path":"","attrs":{"supportsSpeed":"true"}}""",
        )
        val (resolved, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(mapOf("supportsSpeed" to "true"))),
            catalog,
        )
        assertTrue(violations.isEmpty(), "应无违规：$violations")
        assertEquals(
            "/Volumes/技术中心-测试部/C-t/视频 a.mp4",
            resolved["speed_range_confirm_then_undo_restores"]?.firstOrNull()?.hostPath,
        )
    }

    @Test
    fun `多素材逐元素反查返回多个素材`() {
        val catalog = catalogJson(
            mediaEntry("v.mp4", "\"supportsSpeed\":\"true\""),
            mediaEntry("img.dng", "\"supportsSpeed\":\"false\""),
        )
        val (resolved, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(
                mapOf("supportsSpeed" to "true"),
                mapOf("supportsSpeed" to "false"),
            )),
            catalog,
        )
        assertTrue(violations.isEmpty(), "应无违规：$violations")
        val list = resolved["speed_range_confirm_then_undo_restores"]!!
        assertEquals(listOf("v.mp4", "img.dng"), list.map { it.devicePath })
    }

    @Test
    fun `默认可复用选同一素材两次`() {
        val catalog = catalogJson(mediaEntry("v.mp4", "\"supportsSpeed\":\"true\""))
        val (resolved, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(
                mapOf("supportsSpeed" to "true"),
                mapOf("supportsSpeed" to "true"),
            )),
            catalog,
        )
        assertTrue(violations.isEmpty(), "应无违规：$violations")
        val list = resolved["speed_range_confirm_then_undo_restores"]!!
        assertEquals(listOf("v.mp4", "v.mp4"), list.map { it.devicePath })
    }

    @Test
    fun `uniqueMedia 要求不可复用另一需求已选素材`() {
        val catalog = catalogJson(
            mediaEntry("v.mp4", "\"supportsSpeed\":\"true\""),
            mediaEntry("v2.mp4", "\"supportsSpeed\":\"true\""),
        )
        val (resolved, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(
                mapOf("supportsSpeed" to "true"),
                mapOf("supportsSpeed" to "true", FixtureNeed.UNIQUE_MEDIA to "true"),
            )),
            catalog,
        )
        assertTrue(violations.isEmpty(), "应无违规：$violations")
        val list = resolved["speed_range_confirm_then_undo_restores"]!!
        assertEquals(listOf("v.mp4", "v2.mp4"), list.map { it.devicePath })
    }

    @Test
    fun `uniqueMedia 唯一素材不足则记违规`() {
        val catalog = catalogJson(mediaEntry("v.mp4", "\"supportsSpeed\":\"true\""))
        val (_, violations) = FixtureCatalogValidator.resolve(
            listOf(scenario(
                mapOf("supportsSpeed" to "true"),
                mapOf("supportsSpeed" to "true", FixtureNeed.UNIQUE_MEDIA to "true"),
            )),
            catalog,
        )
        assertTrue(violations.isNotEmpty(), "唯一素材不足应记违规")
    }
}
