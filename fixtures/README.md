# fixtures 模板说明

本目录是 e2e 素材 / 设备映射（fixtures catalog）的**模板**：`e2e.example.json` 是回退模板，
接入带素材场景时复制为 `e2e.local.json` 填真机与素材路径即可。demo 出厂即可跑的场景
见下文「demo 与样例素材」。

## 配置三级读取

catalog 路径按以下优先级解析。**三级回退是经 scripts/ 链路（run.sh / bootstrap_* /
fetch_media.sh）的语义**：`_common.sh` 的 `load_config` 会把 `FIXTURES_JSON` 归一为
仓库根绝对路径，run.sh 再桥接成 `HANDEYE_FIXTURES_JSON` 传给 orchestrator。

1. **环境变量**：shell 脚本读 `FIXTURES_JSON`，orchestrator 反查读
   `HANDEYE_FIXTURES_JSON`（run.sh 会把前者桥接成后者，只需设一个）；
2. **`fixtures/e2e.local.json`**：本机覆盖（已 gitignore）。由模板复制而来：

   ```sh
   cp fixtures/e2e.example.json fixtures/e2e.local.json
   ```

3. **`fixtures/e2e.example.json`**：随仓库提交的模板，前两级都不存在时的回退。

**直调 orchestrator 不走三级回退**：`./gradlew :demo-android:e2e:run` 的 JavaExec
工作目录是 `demo-android/e2e`，orchestrator 的默认候选相对它解析，找不到仓库根的
example，会以「fixture catalog 不存在」报错。直调（如只打印 plan 自检）需显式给出
仓库根绝对路径：

```sh
export HANDEYE_FIXTURES_JSON="$PWD/fixtures/e2e.example.json"
./gradlew :demo-android:e2e:run --args="--print-bootstrap-plan --all" \
    -Dorg.gradle.configuration-cache=false --console=plain -q
```

## devices 字段

| 字段 | 消费方 | 说明 |
| --- | --- | --- |
| `ios_udid` | `scripts/setup_ios.sh` / `build_install_ios.sh` / `bootstrap_ios.sh`（jq 惰性读取） | iOS 真机 udid。优先级：`-u` 参数 > `$HANDEYE_UDID` > 本字段 > `idevice_id -l` 第一台。用 `idevice_id -l` 查询后填入，多机时必须显式指定 |
| `android_serial` | （当前无脚本消费，保留字段） | Android 设备选择走 `$ANDROID_SERIAL` 环境变量 / `-s` 参数 / `adb devices` 第一台，见 `_common.sh` 的 `resolve_device` |

填入真机信息（复制 local 后编辑，或直接命令行写入）：

```sh
cp fixtures/e2e.example.json fixtures/e2e.local.json
# iOS：idevice_id -l 查 udid
jq '.devices.ios_udid = "00008110-XXXXXXXXXXXXXXXX"' fixtures/e2e.local.json > tmp.json && mv tmp.json fixtures/e2e.local.json
# Android（可选；也可走 ANDROID_SERIAL 环境变量）：adb devices 查 serial
jq '.devices.android_serial = "ABC123XYZ"' fixtures/e2e.local.json > tmp.json && mv tmp.json fixtures/e2e.local.json
```

## media 条目字段

orchestrator 的素材反查器（`FixtureCatalogValidator`）逐元素反查 scenario 的素材需求
（`MediaNeed` 的属性约束），**只认 `attrs`，条目名 / 顺序无意义**；命中后取该条目的
`device_path` / `host_path` 交给 bootstrap 链路推送。

| 字段 | 规则 |
| --- | --- |
| `device_path` | 设备沙盒相对段（如 `e2e_media/sample.mp4`）。合法性与 `scripts/fetch_media.sh` 的 `is_valid_relative_path` 校验一致：非空、非绝对路径、每个分段非空且不为 `..`、字符仅限字母数字与 `.` `_` `-`（含路径分隔 `/`）。非法段在反查阶段即报违规 |
| `host_path` | host 侧素材文件**绝对路径**（`/` 开头）：设备沙盒没有该素材时作 push 源（Android 走 `fetch_media.sh`，iOS 走 `bootstrap_ios.sh`）。为空会被判「无可用源路径（host_path 为空）」违规。**模板里是占位符**，复制 local 后改成你机器上的实际路径 |
| `attrs` | 属性约束表，反查匹配依据。约束 key 与 scenario 声明的 key 同名等值比较；`minDurationMs` 是数值下界（毫秒），素材缺某个 key = 不满足该约束（被排除，不是报错）；`supportsSpeed` 取 `"true"` / `"false"`。attrs 非 object 的条目被跳过 |

## demo 与样例素材

`demo-android/e2e` 的 feed 场景全部声明**空约束**素材需求（`FixtureNeed.media()`），
反查命中 media 数组首条带 `attrs` 的条目——本模板内置的样例条目
（`device_path: e2e_media/sample.mp4`）即让 demo 全量场景的反查可绿。素材内容不参与
feed 断言，且设备沙盒已有该文件时推送自动跳过。

`fixtures/media/sample.mp4` 是随仓库提交的样例素材：1 秒、16×16 纯色、约 1.5 KB，
H.264 + faststart，Android / iOS 推送链路（含设备端按扩展名识别媒体类型）均可处理。
local 里的 `host_path` 指到本仓库的该文件即可（前提：已按上文复制出
`fixtures/e2e.local.json`）：

```sh
jq --arg p "$PWD/fixtures/media/sample.mp4" \
   '.media[0].host_path = $p' fixtures/e2e.local.json > tmp.json && mv tmp.json fixtures/e2e.local.json
```

接入自带素材的场景时，往 `media` 数组追加条目：按 scenario 声明的约束补齐
`attrs`（值一律字符串），把素材文件放进 `fixtures/media/`（或任意稳定目录），
`device_path` 取设备沙盒目标相对段、`host_path` 取本机绝对路径。
