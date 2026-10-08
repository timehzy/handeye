# handeye 二期：双端设备脚手架迁移（安装 / 启动 / 进态注入 / 批跑总控）

- 日期：2026-09-29
- 状态：草案，待评审
- 上游来源：`insedit/e2e-scenarios/scripts/`（9 个脚本约 3000 行）+ `how-to-run.md` / `how-to-write.md` / `docs/e2e/` 文档沉淀
- 前置：首期 `2026-09-28-handeye-open-source-design.md` 已落地（M1–M5 完成，框架内核与 Android demo 已开源）

## 1. 背景与定位

首期迁完了框架内核（protocol / orchestrator / device-kmp / device-android AAR / conformance）与 Android feed demo，但**设备侧脚手架**没有迁：handeye 现状只有一个 117 行的 `demo-android/scripts/run_demo_e2e.sh` 单管线脚本（装包 → 启动 → 反查端口 → 跑场景），iOS 侧完全空白。

上游 `insedit/e2e-scenarios/scripts/` 沉淀了两年真机踩坑的脚手架机制。本期把其中**与业务零耦合**的部分泛化迁移，让接入者在 Android / iOS 真机上「构建 → 安装 → 冷启动进态 → 建隧道 → 批跑」一条龙。

## 2. 范围

### 2.1 迁入（P0）：双端安装 / 启动 / 隧道 / 总控

| 上游资产 | 行数 | 机制 | 迁移方式 |
|---|---|---|---|
| `build_install_android.sh` | 174 | gradle 构建 → `adb install -r`，APK 新鲜度判定 | 直接泛化 |
| `build_install_ios.sh` | 201 | `xcodebuild` → `devicectl device install app` | 直接泛化（源码联调态硬前置软化，见 §5） |
| `setup_android.sh` | 186 | `adb forward` + probe `/health` | 直接泛化 |
| `setup_ios.sh` | 144 | `iproxy` + probe `/health`（device 固定端口 27778） | 直接泛化 |
| `e2e.sh` | 667 | 总控：bootstrap plan 拉取、按 (page, fixture) 分组重 bootstrap、批跑汇总、`-i` 交互、`--tail-events`、`snapshot diff` 子命令 | 泛化重构为 `run.sh` |

编排器侧的配套能力（`BootstrapPlanCli`、`FixtureCatalogValidator`、`ArtifactDumper`、ScenarioRegistry tag）首期已迁，本次只迁 runner 侧脚本，并逐项核对不重复。

### 2.2 迁入（P1）：冷启动进态注入机制（重写，不搬码）

上游两条「冷启动把参数注进 App」的路线，接收端代码（`E2EBootstrapStoryDeepLink`、`SchemeLauncherActivity`、AppDelegate 段）在 app 仓而不在 insedit，**按机制在 handeye demo 里重写**：

- **Android · deeplink 注入**：自定义 scheme（上游 `insta360://bootstrap?work_path=...`）→ handeye 侧 `handeye://bootstrap?...`。demo App 侧改动：Manifest 注册 VIEW intent-filter + MainActivity 解析 intent data → 转换为 `CommandRegistry` 里的 bootstrap 命令。
- **iOS · 配置文件注入**：`afcclient` 写 `handeye_bootstrap.json` 进容器 Documents → AppDelegate 冷启动完成回调读取 → 路由进态。这是 iOS 无 `am start` 的等价物，同时作为 device-ios 二期的**标准启动契约**写进协议映射文档（首期 spec 里 iOS 只有「固定端口或约定文件」一句话，本次落成完整契约）。
- **素材兜底 `fetch_media.sh`**（143 行）：去掉 SMB / 共享盘内部设施，保留「设备已有 → host 本地」两级兜底；`device-path` 相对段合法性校验（防路径逃逸）直接搬。
- **fixtures 机制**：`media` 数组 + `e2e.local.json` / `e2e.example.json` 模板 + 配置三级读取（环境变量 → local → example）。

### 2.3 迁入（P2）：文档资产

- `how-to-write.md`：scenario / Fact / step 写作手册。
- `how-to-run.md` §3 七坑清单：冷启动 deeplink flaky、native 库未解压完进页崩、iOS 多设备取错、afcclient `--container` vs `--documents`、热启动不重读配置、管道 exit code 失真、artifacts 只写 FAIL——开源用户必然撞上同样的坑。
- `framework-improvement-backlog.md`：框架已知缺陷 ledger。

**编辑筛选规则（硬性）**：只挑有通用价值的内容进库。凡是涉及剪辑领域（story / 变速 / draft / 多轨 / 素材轨道等）或 MVI 架构专有概念（ViewModel/Intent 特有条目）的内容一律去掉或改写为领域无关表述（如「冷启动进态」替代「进多轨页」）。验收：文档全文 grep 无剪辑领域词与 MVI 专有名词残留（§7）。

### 2.4 明确不迁

- **universal link / applinks**：全库检索确认上游不存在该机制（无 associated domains / apple-app-site-association），deeplink 只有自定义 scheme 一条路线。无可迁资产；未来若做是新设计。
- 顶层 SDK 构建发布脚本（`build_all.sh`、pod 发布、`commit-msg` hooks）：与 E2E 框架定位无关。
- ohos 端脚手架、业务场景（speed / story）本身。

## 3. 脱敏清单（迁移时逐项替换）

| 源 | 目标 |
|---|---|
| `com.arashivision.insta360akiko` / `com.insta360.oner` | `dev.handeye.demo` |
| `insta360://bootstrap` scheme | `handeye://bootstrap` |
| `:akiko:assembleDevelopDebug` | `:demo-android:app:assembleDebug` |
| `MVI_E2E_*` 环境变量 / `MVI_E2E_HOST_PORT` 契约 | `HANDEYE_*` / `HANDEYE_HOST_PORT` |
| SMB 共享盘路径（fetch_media） | 删除该级兜底 |
| insedit 源码联调态硬前置（iOS Podfile :path 检查） | 软化为可选诊断 + 可配置接入点（见 §5，不硬阻断） |
| `e2e_bootstrap.json` | `handeye_bootstrap.json` |
| 27778 固定端口（GCDWebServerBridge） | 保留为 handeye iOS 约定端口（沿用 27778，避免无谓变化） |

验收时 `grep -ri` 全库无 `insta360|arashivision|akiko|MVI_E2E|INSKMPEditSDK` 残留。

## 4. 目标结构

```
handeye/
├── scripts/                    # 【新增】runner 侧脚手架，跨 demo 通用
│   ├── build_install_android.sh
│   ├── build_install_ios.sh
│   ├── setup_android.sh        # adb forward + /health probe
│   ├── setup_ios.sh            # iproxy + /health probe（固定 27778）
│   ├── bootstrap_android.sh    # 冷启动进态：handeye://bootstrap deeplink 注入
│   ├── bootstrap_ios.sh        # 冷启动进态：handeye_bootstrap.json 注入
│   ├── run.sh                  # 总控：plan → 分组 bootstrap → 批跑 → 汇总
│   └── fetch_media.sh          # 素材两级兜底（P1）
├── demo-android/
│   └── scripts/run_demo_e2e.sh # 改为薄封装：调 scripts/run.sh --demo feed
│   └── app/ ...                # + VIEW intent-filter + bootstrap 命令接收端
└── device-ios/                 # + 启动契约（handeye_bootstrap.json）写进协议映射文档
```

结构决策：通用脚手架放顶层 `scripts/`（面向接入者），demo 专属入口保留为薄封装。每个脚本继承上游风格：头部注释即 usage（`awk` 提取）、退出码分段契约、前置依赖逐项检查并给安装指引。

## 5. 前置条件软化与接入点发现（双端）

原则：**脚本不替接入者做项目特有的决策，但要把每个「决策点」显式化、可配置化、可发现**。使用者应能在 10 分钟内回答「我的项目要在哪里接入、怎么改脚本适配我的工程」。

### 5.1 前置条件的统一处理规则

| 前置类型 | 例子 | 处理方式 |
|---|---|---|
| 可自动检测的工具链 | adb / idevice_id / iproxy / devicectl 是否在 PATH | 保持**硬检查**，逐项打印安装指引（brew install …） |
| 项目特有的构建事实 | gradle task 名、Xcode scheme / workspace 名、APP_ID / bundle id | **不硬阻断**：默认值 + 配置覆写 + `-h` 中显式列出 |
| 项目特有的集成态 | iOS Podfile 是否源码依赖、debug 能力是否打进包 | **软化为可选诊断**：`run.sh --check-integration` 探一圈并报告，不阻断；症状与修法写进文档 |
| 环境事实 | 设备是否连接、多机选哪台 | 保持硬检查（这是「跑不了」而非「项目差异」） |

每个「软前置」在三处显式出现：脚本 `-h` 的说明段、失败/诊断输出里的指路句、`docs/integration-points.md` 的对应小节（是什么 → 不满足的症状 → 怎么配）。

### 5.2 脚本自定义钩子（所有脚本统一支持）

- **三级配置读取**：环境变量 → `scripts/handeye.local.sh`（gitignore，接入者覆写）→ demo 默认值。覆写项至少包括：`APP_ID` / bundle id、gradle task 或 scheme/workspace、deeplink scheme、设备选择、端口。
- **生命周期钩子**：build 前/后、install 前/后、bootstrap 前/后各留一个可执行注入点（如 `HANDEYE_PRE_BUILD` / `scripts/hooks/pre-build.sh`），让 Podfile 切态、资源拷贝、签名设置等项目特有流程可插拔，**不改脚本本体**。
- demo 自身也走同一套钩子（demo 默认值 = 钩子的出厂配置），保证接入者看到的样例就是自己要写的配置。

### 5.3 双端接入点清单（`docs/integration-points.md`，README 链接）

**Android**

| 步骤 | 接入点 | 自定义方式 |
|---|---|---|
| 依赖 | gradle 引入 `device-android` AAR（debug 变体） | 工程自己的 version catalog |
| 装配 | Application `HandeyeInstaller.install`（首期已有） | — |
| 进态 | Manifest 注册 `handeye://` VIEW intent-filter；launcher Activity 解析 intent data → 转 bootstrap 命令 | `HANDEYE_DEEPLINK_SCHEME` |
| 构建 | 默认 `:demo-android:app:assembleDebug` | `HANDEYE_ANDROID_GRADLE_TASK` |

**iOS（三条接入路线，文档分述）**

| 路线 | 适用 | 注入点 |
|---|---|---|
| KMP 工程 | Kotlin/Native framework 接入 | **本地 framework 经 Podfile `:path` 引入是大多数 KMP iOS 项目的现实路径**——提供 Podfile 注入片段样例（类比上游 `INSKMPEditSDK :path` 联调态）；`--check-integration` 探测 Podfile 是否含本地依赖并提示，不阻断 |
| 原生 Swift App | device-ios Swift Package（二期） | SPM 依赖 + AppDelegate 段 |
| 进态（两路线共用） | 冷启动完成回调读 `handeye_bootstrap.json` → 路由进态 | scene-based App 与 AppDelegate-based App 的接入差异分述 |
| 构建 | 默认 xcodebuild scheme/workspace | `HANDEYE_IOS_SCHEME` / `HANDEYE_IOS_WORKSPACE` |

### 5.4 文档筛选的口径

前置条件软化依赖文档把「项目差异面」讲全，因此 how-to-run / how-to-write 的筛选标准与 §2.3 编辑规则一致：只留对**任何**移动端工程成立的经验（坑清单、退出码约定、隧道/端口原理、素材兜底模式），剪辑领域与 MVI 专有的段落不迁。

## 6. 关键设计决策

1. **机制重写，不搬接收端代码**：进态注入的 demo 接收端按 CommandRegistry / StateProvider 现有 API 重写，不引入第二套机制。
2. **端口双轨保持**：Android 维持 `/proc/$PID/net/tcp` 反查 + 逐候选 `/health` 实测（已有实现）；iOS 维持固定端口 + iproxy。不在本期统一。
3. **新鲜度判定简化保留**：上游对双仓（editsdk + akiko）取源码 mtime max；handeye 单仓，判定改为「安装包 mtime vs `demo-android/app/src` + `device-kmp/src` mtime max」，误判时 `--force-reinstall` 兜底。
4. **协议不动**：bootstrap 注入是 L2 集成模式，不进 protocol/spec.md；只把 iOS 启动契约补进 device-ios 文档。
5. **iOS 硬前置软化**（§5）：上游对 Podfile 源码态的硬阻断是内部联调环境特有；开源版改为「可选诊断 + 明确注入点 + 文档说明」。Podfile 不是必选项，但对多数 iOS 工程是现实路径，给样例不给门槛。

## 7. 验收标准

- Android 全新设备一条命令完成全链路：`./scripts/run.sh --all` → 构建 → 安装 → 冷启动进态 → 隧道 → 6 个 demo scenario 全绿。
- 分组正确性：混合 page / fixture 的场景序列按 (page, source) 分组，跨组自动重 bootstrap（用 demo 现有 scenario 构造最小验证组）。
- `-i` / `--tail-events` / `snapshot diff` 三个增强项可用。
- iOS 三件套脚本就位并通过 shell 语法检查 + dry-run；device-ios 启动契约文档评审通过（完整跑通挂 device-ios 二期）。
- 脱敏 grep 通过（§3 表）。
- 文档：`docs/how-to-write.md` 与「真机坑清单」进库；每个脚本 `-h` 可自解释。
- **接入点可发现**：`docs/integration-points.md` 进库且 README 链接；新人不读脚本源码即可按清单完成接入。
- **钩子可用**：在 `scripts/handeye.local.sh` 里覆写 `APP_ID` + gradle task（或 iOS scheme/workspace）即可让 `run.sh` 指向一个示例接入工程跑通，脚本本体零改动。
- `--check-integration` 对「debug 能力未打进包 / Podfile 非本地依赖」给出可读诊断，不阻断后续流程。
- **文档筛选**：how-to-run / how-to-write / 坑清单全文 grep 无剪辑领域词（story / 变速 / draft / 多轨等）与 MVI 专有名词残留。

## 8. 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| N1 骨架 | `scripts/` 四件套（build/setup × 双端）+ `run.sh` Android 链路跑通（含三级配置读取 + 生命周期钩子） | run.sh --all 全绿；local.sh 覆写指向示例工程跑通 |
| N2 进态注入 | `bootstrap_android.sh` + demo 接收端（Manifest + bootstrap 命令）+ `--check-integration` | deeplink 冷启动进态稳定（重试掩盖冷启动 flaky 的窗口） |
| N3 iOS 三件套 | build_install_ios / setup_ios / bootstrap_ios + device-ios 启动契约文档 + Podfile 注入样例 + integration-points.md | shellcheck + dry-run + 文档评审 |
| N4 兜底与文档 | fetch_media 泛化 + fixtures 模板 + how-to-write + 坑清单（按 §2.3 规则筛选） | 两级兜底验证 + 文档进库 + 领域词 grep 通过 |
