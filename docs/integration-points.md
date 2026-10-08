# 接入点清单（双端）

面向要把 handeye 接进自己 App 的开发者：不读脚本源码，按本文清单逐项完成接入即可。
每条接入点都给出四件事——**做什么 / 在哪个文件 / demo 样例 / 自定义配置键**，并附
「不满足时的症状」，方便反向定位哪一步没接对。

配置项的完整列表与默认值见 [scripts/handeye.example.sh](../scripts/handeye.example.sh)；
三级读取优先级（环境变量 > `scripts/handeye.local.sh` > example 默认值）见
[scripts/_common.sh](../scripts/_common.sh) 的 `load_config`。

---

## 1. 快速开始

```bash
# 1. 复制配置模板（local.sh 已 gitignore，不会误提交）
cp scripts/handeye.example.sh scripts/handeye.local.sh

# 2. 按你的工程改默认值，最少 5 个：
#    APP_ID / HANDEYE_DEEPLINK_SCHEME（双端通用）
#    ANDROID_GRADLE_TASK / ANDROID_APK_GLOB（跑 Android 时）
#    IOS_SCHEME（跑 iOS 时）

# 3. 诊断集成态，逐项报告不阻断
./scripts/run.sh --check-integration
```

demo 的出厂配置就是这份 example——接入者要写的配置与 demo 同款，照抄 demo 样例即可。

---

## 2. Android 接入清单

### 2.1 依赖：gradle 引入 device-android AAR（debug 变体）

- **做什么**：把 `device-android` AAR 加进你的 App 模块依赖，仅 debug 变体生效
  （release 变体是 no-op，零开销）。
- **在哪个文件**：你工程的 App 模块 `build.gradle.kts`（version catalog 由你工程自己维护）。
- **demo 样例**：[demo-android/app/build.gradle.kts](../demo-android/app/build.gradle.kts)
  （scheme 的 BuildConfig 常量见 2.3）。
- **自定义配置键**：无——依赖坐标是工程事实，不进 handeye 配置。
- **不满足时的症状**：装配（2.2）之后内嵌 debug server 不存在，
  `scripts/setup_android.sh` 反查不到监听端口，`/health` 探测失败（退出码 4）。

### 2.2 装配：Application 里一行 `HandeyeInstaller.install`

- **做什么**：`Application.onCreate` 里装配事件记录器、三个命名快照源（UI / 内存 /
  持久化）、命令注册表；`enabled = BuildConfig.DEBUG` 保证 release 变体零开销。
- **在哪个文件**：你的 `Application` 类。
- **demo 样例**：[demo-android/app/src/main/java/dev/handeye/demo/FeedApp.kt](../demo-android/app/src/main/java/dev/handeye/demo/FeedApp.kt)
  （`stateProvider("ui"/"memory"/"persist"/"bootstrap")` + `CommandRegistry`）。
- **自定义配置键**：无——装配是代码事实，不进 handeye 配置（Android 的 debug server
  端口无需约定，`scripts/setup_android.sh` 会从 `/proc/<pid>/net/tcp` 反查）。
- **不满足时的症状**：`adb shell pidof <包名>` 有进程但没有任何 loopback 端口通过
  `/health` 实测——`setup_android.sh` 打「App 是否 install 成功、是否 debug 变体」
  的排查指引后以退出码 4 结束。

### 2.3 进态：Manifest 注册 deeplink intent-filter + launcher Activity 解析

- **做什么**：Manifest 给目标 Activity 注册 `<data android:scheme="..." android:host="bootstrap"/>`
  的 VIEW intent-filter；Activity 把 intent data 解析成 bootstrap 参数快照，并注册名为
  `bootstrap` 的命名源。scheme 字面量在 **Manifest 与 BuildConfig 两处独立维护，改必须同改**。
- **在哪个文件**：你的 `AndroidManifest.xml` + launcher Activity + App 模块 `build.gradle.kts`。
- **demo 样例**：
  - intent-filter：[AndroidManifest.xml](../demo-android/app/src/main/AndroidManifest.xml)
  - 解析器：[BootstrapIntentParser.kt](../demo-android/app/src/main/java/dev/handeye/demo/BootstrapIntentParser.kt)
  - 参数快照：[BootstrapState.kt](../demo-android/app/src/main/java/dev/handeye/demo/BootstrapState.kt)
  - Activity 落值（冷启动 `onCreate` / 热启动 `onNewIntent`）：[MainActivity.kt](../demo-android/app/src/main/java/dev/handeye/demo/MainActivity.kt)
  - `bootstrap` 命名源注册：[FeedApp.kt](../demo-android/app/src/main/java/dev/handeye/demo/FeedApp.kt) 的 `stateProvider("bootstrap")`
  - scheme 的 BuildConfig 维护点：[build.gradle.kts](../demo-android/app/build.gradle.kts)
- **自定义配置键**：`HANDEYE_DEEPLINK_SCHEME`（host 侧注入用的 scheme，必须与
  Manifest / BuildConfig 两处字面量一致）；`ANDROID_MAIN_ACTIVITY`（被拉起的入口 Activity）。
- **不满足时的症状**：
  - Manifest 没注册 intent-filter → `scripts/run.sh --check-integration` 的 aapt 探测
    打 `[warn]`（"Manifest 未注册 deeplink intent-filter"），`bootstrap_android.sh` 的
    `am start -d` 进不了目标 Activity，轮询 `bootstrap` 源超时。
  - scheme 只改了一处 → 解析不命中，App 普通启动，`bootstrap` 源恒为 null，
    bootstrap 阶段报进态超时。
  - 没注册 `bootstrap` 命名源（或名字拼错）→ `GET /source?name=bootstrap` 恒为 null / 404。

### 2.4 构建：gradle task 与 APK 产物路径

- **做什么**：告诉脚手架用哪个 gradle task 构建、构建产物 APK 在哪（glob）。
- **在哪个文件**：`scripts/handeye.local.sh` 覆写 example 默认值。
- **demo 样例**：默认值 `:demo-android:app:assembleDebug` 与
  `demo-android/app/build/outputs/apk/debug/*.apk` 就是 demo 的真实路径，见
  [scripts/handeye.example.sh](../scripts/handeye.example.sh)。
- **自定义配置键**：`ANDROID_GRADLE_TASK`、`ANDROID_APK_GLOB`；多机时 `ANDROID_SERIAL`；
  新鲜度判定监听的源码路径 `SOURCE_PATHS_ANDROID`（改动晚于装包时间则判陈旧自动重装）。
- **不满足时的症状**：task 名不对 → `build_install_android.sh` 以退出码 5（build 失败）
  结束；glob 对不上 → 同样退出码 5（取不到产物）。两者错误输出里都会带上你配置的值，
  便于一眼看出是配置问题。

---

## 3. iOS 接入（三条路线）

iOS 没有 `adb shell am start` 等价物，进态改为**文件注入**：harness 把 deeplink url 写进
App 容器的 `Documents/handeye_bootstrap.json`，App 冷启动完成回调里自读一次。
进态契约（注入格式 / 读取时机 / 路由 / 端口）的完整事实源是
[device-ios/docs/bootstrap-contract.md](../device-ios/docs/bootstrap-contract.md)，
下文只做路线选择。

### 3.1 路线一：KMP 工程（Podfile 本地 framework）

**适用**：你的 iOS App 已用 Kotlin/Native framework 接入共享业务代码——这是大多数
KMP iOS 项目的现实路径。

- **做什么**：在 App 工程的 Podfile 里以 `:path` 本地源码方式引入 handeye 的 KMP
  framework，并限定 Debug configuration（release 不带调试能力）。
- **在哪个文件**：你 iOS 工程的 `Podfile`（工程目录由 `IOS_PROJECT_DIR` 指向）。
- **样例**：[docs/samples/ios-podfile-local.rb](samples/ios-podfile-local.rb)——
  照抄改 `:path` 与 target 名即可。
- **自定义配置键**：`IOS_PROJECT_DIR`（含 xcodeproj/workspace 与 Podfile 的工程目录）。
- **不满足时的症状**：
  - 走二进制发布物而非本地源码 → debug 装配能力不进包，scenario 全部 FAIL
    （内嵌 debug server 不存在，`/health` 探测超时）。
  - Podfile 存在但无 `:path` 本地依赖 → `run.sh --check-integration` 打 `[warn]`
    指路本样例；脚本不阻断，但跑起来就是上一条症状。

### 3.2 路线二：原生 Swift App（SPM，二期）

**适用**：纯 Swift 工程、不接 KMP。

- **做什么**：以 Swift Package 方式依赖 `device-ios`，在 `AppDelegate` 段完成装配。
- **在哪个文件**：Xcode 的 Package Dependencies + `AppDelegate`。
- **现状**：`device-ios` 二期落地（接口草案已冻结），落地前本路线仅保留契约文档：
  [device-ios/docs/bootstrap-contract.md](../device-ios/docs/bootstrap-contract.md)。
- **自定义配置键**：落地后同 3.3。
- **不满足时的症状**：二期前走此路线无可装配产物——`IOS_PROJECT_DIR` 指向的目录
  不含可构建工程时，`build_install_ios.sh` 以退出码 4 结束并提示。

### 3.3 进态（两路线共用）：冷启动读 `handeye_bootstrap.json`

- **做什么**：冷启动完成回调（`AppDelegate didFinishLaunchingWithOptions`，纯
  SceneDelegate App 为 `scene(_:willConnectTo:options:)`）里读一次容器里的
  `Documents/handeye_bootstrap.json`，解析 `url` 的 scheme / host（`host == "bootstrap"`）
  与 query 参数集，落为名为 `bootstrap` 的快照源；scheme 常量在 Info.plist 的
  `CFBundleURLSchemes` 与代码判定处两处维护，改必须同改。
- **在哪个文件**：你的 AppDelegate / SceneDelegate + Info.plist。
- **样例与症状**：逐条见
  [device-ios/docs/bootstrap-contract.md](../device-ios/docs/bootstrap-contract.md)
  §1–§3（文件路径 / JSON schema / 读取时机 / 路由判据 / 各自「不满足时的症状」）。
- **自定义配置键**：`HANDEYE_DEEPLINK_SCHEME`（必须等于 App 侧两处 scheme 常量）；
  `IOS_DEVICE_PORT`（device 端 debug server 固定监听端口，默认 27778，App 侧监听必须与
  配置一致——iOS 无 `/proc` 反查，纯靠约定）。
- **不满足时的症状（速查）**：文件路径 / 文件名对不上、热启动重读、scheme 只改一处 →
  全部表现为 harness 轮询 `bootstrap` 源超时（`bootstrap_ios.sh` 退出码 8）；
  端口不一致 → `setup_ios.sh` 退出码 4。

### 3.4 构建：xcodebuild scheme / workspace

- **做什么**：告诉脚手架 scheme（与 workspace，非 CocoaPods 或已集成 Pods 的工程）
  与产物定位方式。
- **在哪个文件**：`scripts/handeye.local.sh` 覆写 example 默认值。
- **样例**：`IOS_SCHEME` 默认 `handeye-demo`；`IOS_WORKSPACE` 非空用 `-workspace`，
  否则自动探测 `*.xcodeproj`，见
  [scripts/handeye.example.sh](../scripts/handeye.example.sh)。
- **自定义配置键**：`IOS_PROJECT_DIR`、`IOS_SCHEME`、`IOS_WORKSPACE`；多机时 `HANDEYE_UDID`
  （注意：`idevice_id -l` 的排序不可控，多机必须显式指定）。
- **不满足时的症状**：scheme 名不对 → `build_install_ios.sh` 以退出码 9（xcodebuild
  失败）结束；未装真机 / 无 udid → 退出码 4，错误输出逐项列出解析过的来源
  （`-u` 参数 / `HANDEYE_UDID` / fixtures 的 `.devices.ios_udid`）。

---

## 4. 软前置与 `--check-integration`

前置条件分四类处理（软化的意思是：**诊断并指路，不阻断执行**）：

| 前置类型 | 例子 | 处理方式 |
|---|---|---|
| 可自动检测的工具链 | adb / idevice_id / iproxy / devicectl 是否在 PATH | 硬检查：逐项打印安装指引（`brew install …`） |
| 项目特有的构建事实 | gradle task 名、Xcode scheme / workspace 名、包名 / bundle id | 不硬阻断：默认值 + `handeye.local.sh` 覆写 + `-h` 显式列出 |
| 项目特有的集成态 | iOS Podfile 是否源码依赖、debug 能力是否打进包、Manifest 是否注册 scheme | 软诊断：`run.sh --check-integration` 探一圈并报告 |
| 环境事实 | 设备是否连接、多机选哪台 | 硬检查（这是「跑不了」而非「项目差异」） |

`./scripts/run.sh --check-integration`（`scripts/run.sh` 的 `check_integration`）逐项报告：

1. **有效配置回显**：解析后的每个配置键值，标注三级来源
   （`[env]` 环境变量 / `[local]` handeye.local.sh / `[example]` 默认值）——
   覆写没生效时一眼能看出读的是哪一级。
2. **Android 集成态**：aapt 离线探测 Manifest 是否注册了
   `HANDEYE_DEEPLINK_SCHEME` 的 intent-filter；有在线设备时追加安装态与进程存活检查。
3. **iOS 集成态**：`IOS_PROJECT_DIR` 存在性、Podfile 是否含 `:path` 本地依赖
   （命中打 info，未命中打 warn 并指路
   [ios-podfile-local.rb](samples/ios-podfile-local.rb)）；
   AppDelegate / SceneDelegate 是否读取 `handeye_bootstrap.json` 不做代码扫描，指路本文 §3.3。

所有输出均为提示，退出码恒为 0（仅「配置缺失」与「java 缺失」两个硬前置例外，退出码 4）。

---

## 5. 生命周期钩子（扩展流程的注入点）

构建 / 安装 / bootstrap 脚本在六个点各留一个可执行注入点，项目特有流程
（Podfile 切态、资源拷贝、签名设置等）插在这里，**不改脚本本体**。
执行器见 `scripts/_common.sh` 的 `run_hook`：优先取环境变量
`HANDEYE_HOOK_<NAME>`（命令字符串），否则执行 `scripts/hooks/<name>.sh`
（可执行文件）；两者都不存在则静默跳过。钩子失败时脚本以钩子自身的退出码终止。

| 钩子名 | 触发时机 | 典型用途 |
|---|---|---|
| `pre-build` | gradle / xcodebuild 之前 | Podfile 切态、版本号注入 |
| `post-build` | 构建成功、取产物之前 | 产物拷贝、二次打包 |
| `pre-install` | `adb install` / `devicectl install` 之前 | 重签名、设备清理 |
| `post-install` | 装包成功之后 | 预置数据、权限授予 |
| `pre-bootstrap` | 进态注入之前 | 清理旧的 bootstrap 配置 / 素材 |
| `post-bootstrap` | 进态判定成功之后 | 截图留档、通知外部系统 |

使用方式（二选一）：

```bash
# 方式一：环境变量（一次性 / CI 里动态生成）
HANDEYE_HOOK_PRE_BUILD='./your-toolchain/switch-podfile.sh debug' ./scripts/run.sh --all

# 方式二：脚本文件（`scripts/hooks/*.sh` 已 gitignore，样例需自行复制保存）
cp your-hook.sh scripts/hooks/pre-build.sh && chmod +x scripts/hooks/pre-build.sh
```
