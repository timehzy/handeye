# iOS 端 bootstrap 启动契约（冷启动进态注入）

**面向读者**：要把 handeye 接进自己 iOS App 的开发者。读完本文即可知道 harness 会往你的 App
里注入什么、你的 App 必须在什么时机做什么、以及哪一步没做对时会在 harness 侧表现成什么症状。

**harness 侧一键脚本**：`scripts/bootstrap_ios.sh`（构建 + 安装 + 配置注入 + 冷启动重拉 +
隧道 + 就绪判定）。本文是它注入机制的事实源契约；脚本行为与本契约不一致时，以脚本源码为准并提 issue。

**与 Android 的对称关系**：Android 用 `adb shell am start -d "handeye://bootstrap?work_path=..."`
deeplink intent 注入参数（接收端见 `demo-android/app/src/main/java/dev/handeye/demo/` 下
`BootstrapIntentParser.kt` / `BootstrapState.kt` / `AndroidManifest.xml`）；iOS 没有 `am start`
等价物，改为**文件注入**——把同一个语义的 deeplink url 写进 App 容器，App 冷启动后自读。
两条链路的 url 语法、query 语义、进态判据完全同构，区别只在载体。

---

## 1. 配置注入契约

harness 通过 AFC（Apple File Conduit）往你的 App 容器里写一个 JSON 文件：

| 项 | 约定 |
|---|---|
| 文件路径 | App 容器 `Documents/handeye_bootstrap.json`（即 `FileManager` 的 `.documentDirectory` 目录下） |
| JSON schema | `{"url": "<scheme>://bootstrap?<query>"}`——恰好一个字符串字段 `url` |
| url 形态 | scheme = host 侧配置 `HANDEYE_DEEPLINK_SCHEME`，host 固定为 `bootstrap`，query 为 bootstrap 参数集；无素材时无 query，即 `{"url":"<scheme>://bootstrap"}` |
| `work_path` | 当前唯一参数。单个素材是一个**相对 Documents 的路径段**（如 `handeye/media/a.mp4`）；多个素材以 `\|` 连接（如 `handeye/media/a.mp4\|handeye/media/b.mp4`）。App 负责把它拼在 Documents 根之后得到沙盒绝对路径——容器 Documents 根的 UUID 运行时随机，host 无法静态推断 |
| 写入工具 | `afcclient --container <APP_ID> -u <UDID>`，命令走 stdin（`put` / `rm` / `ls`），脚本按 `printf '%s\n' <命令...> quit` 驱动 |
| 写入方式 | 必须 `--container`，**不是** `--documents`：`--container` 进 App 容器根（`/` 下有 `Documents/`、`Library/` 等），写路径拼 `Documents/` 前缀；iOS 26 上 `--documents` 直接列根会 Permission denied |
| 覆盖语义 | 脚本写前先 `rm Documents/handeye_bootstrap.json` 再 `put`：afcclient 的 `put` 覆盖已存在文件**不截断**，旧内容残留会拼出非法 JSON（如 `}\n<旧尾巴>`）。App 侧解析建议容忍尾部垃圾或直接 JSONDecoder 失败即忽略（见 §3） |

**不满足时的症状**：

- 文件路径或文件名对不上 → harness 轮询 `GET /source?name=bootstrap` 一直拿到 `null`，
  `bootstrap_ios.sh` 在默认 30 s 后报「进态超时」退出码 8。
- 误用 `--documents` → 写配置失败，脚本退出码 6（错误文案提示确认容器 `Documents/` 可写）。
- JSON 被污染（自己手写覆盖、或 put 不截断残留旧内容）→ App 解析失败 = 不进态 → 同上退出码 8。
  排查：把容器里的配置拉下来校验——
  `printf '%s\n' "get Documents/handeye_bootstrap.json" quit | afcclient --container $APP_ID -u $UDID`

## 2. 读取时机

| 项 | 约定 |
|---|---|
| 读取位置 | App 进程**冷启动完成回调**里读一次：UIKit 生命周期为 `AppDelegate application(_:didFinishLaunchingWithOptions:)`；纯 SceneDelegate 的 App 为 `scene(_:willConnectTo:options:)`。以你的 App 实际启动链路为准，取「进程冷启动后的首个完成回调」 |
| 读取次数 | 同一进程生命周期内**只读一次**，之后即使文件变化也不重读 |
| 热启动 | 后台唤醒（前台切换回来）**不重读**。App 被 terminate 但若只是退后台，唤醒属于热启动，不会重读配置 |
| harness 侧配合 | 因此 `bootstrap_ios.sh` 的冷启动重拉是「真杀进程（SIGKILL）+ 重新 launch」，不杀透就没有下一次冷启动回调（见 §5 命令组） |

**不满足时的症状**：

- 在 `sceneWillEnterForeground` 之类热启动路径里重读 → 同一进程内配置被中途改写，
  跨素材分组重 bootstrap 时 App 态与 harness 预期错乱（Android 侧的热启动 `onNewIntent` 语义在 iOS 不存在）。
- 每次进前台都重读而 harness 没重新注入 → 读到上一次残留的 `handeye_bootstrap.json`，
  普通调试启动也被拉进目标态，release 用户无法复现。
- harness 没真杀只退后台 → 注入了新配置文件但 App 仍是旧进程，bootstrap 源不更新 → 脚本退出码 8。

## 3. 路由契约

| 项 | 约定 |
|---|---|
| 解析 | 读出 `url` 字符串 → 解析 scheme / host / query：`scheme == <App 侧 scheme 常量>` 且 `host == "bootstrap"` 才命中（与 Android `BootstrapIntentParser.parseCore` 同语义） |
| scheme 常量 | 必须与 host 侧配置 `HANDEYE_DEEPLINK_SCHEME` 一致。Android 侧 scheme 字面量在 Manifest 与 BuildConfig 两处独立维护、改必须同改；iOS 侧同理，URL scheme 注册（Info.plist `CFBundleURLSchemes`）与代码里的判定常量是两处，别只改一边 |
| 参数集 | query 逐个参数落入 bootstrap 载荷（重复 query 参数只保留首个值）；下划线开头的 key 为保留命名空间，业务参数不要用 |
| 进态 | 命中后把参数集落为你的 bootstrap 态；harness 的进态判据是 `GET /source?name=bootstrap` 返回**非 null**——你需要把该快照注册为名为 `bootstrap` 的命名源（对齐 Android demo：`stateProvider("bootstrap") { BootstrapState.args.value }`，见 `demo-android/.../FeedApp.kt`） |
| `work_path` | 命中且带 `work_path` 时，按 §1 的 `\|` 拆分、拼 Documents 根得到素材沙盒绝对路径后进入目标态；不带 `work_path`（无素材注入）进默认态 |
| 未命中 | 文件不存在、scheme/host 不匹配、JSON 解析失败——一律按**普通启动**处理，不报错、不 crash。release 用户没有这个文件，行为必须与未接入 handeye 完全一致 |

**不满足时的症状**：

- 没注册 `bootstrap` 命名源（或名字拼错）→ `/source?name=bootstrap` 恒为 null / 404 → 脚本退出码 8。
- Info.plist 注册了 scheme 但代码判定常量没改（或反之）→ 解析不命中 → 普通启动 → 同上。
- 未命中时弹错误 / 崩溃 → release 用户一启动就中招（他们没有该文件），等于把调试通道的错误抛给了真实用户。
- query 语义与 Android 侧不一致（比如 `\|` 不拆、相对段不当 Documents 相对段拼）→ harness 轮询能通过但
  场景断言读不到素材，表现为 scenario 执行期找不到文件而非 bootstrap 失败，排查方向容易被带偏。

## 4. 端口契约

| 项 | 约定 |
|---|---|
| device 侧端口 | debug server（`HandeyeInstaller.install` 装配的内嵌 HTTP server）**固定监听 27778**；host 侧配置 `IOS_DEVICE_PORT` 可改，App 侧必须与配置一致 |
| 端口来源 | iOS 真机没有 `/proc/<pid>/net/tcp` 反查手段，host 侧靠固定端口约定 + 转发，不做端口探测 |
| host 侧转发 | `iproxy <hostPort>:<devicePort> -u <UDID>`；缺省 `hostPort == devicePort`。隧道是 iproxy 进程，必须由活进程持有——杀掉持有进程即拆隧道（与 Android `adb forward` 的持久语义不同） |
| 就绪判定 | `bootstrap_ios.sh` 后台拉起 `scripts/setup_ios.sh` 建隧道（iproxy + `/health` 探测），随后每 2 s 轮询 `http://127.0.0.1:<hostPort>/source?name=bootstrap`，默认总超时 30 s（`HANDEYE_BOOTSTRAP_READY_TIMEOUT_SEC` 可改） |

**不满足时的症状**：

- App 监听端口与 `IOS_DEVICE_PORT` 配置不符 → `/health` 探测失败，`setup_ios.sh` 退出码 4。
- host 端口被别的进程占用 → iproxy 起后秒退，退出码 3（错误文案会提示换 localPort）。
- 隧道进程被随手 kill → 后续 scenario 批跑全部连不上 `127.0.0.1:<hostPort>`；
  `bootstrap_ios.sh` 成功时会留下持隧道的后台 `setup_ios.sh` 并在 stdout 给出 pid，拆除用 `kill <pid>`。

## 5. devicectl 参考命令组

以下命令与 `bootstrap_ios.sh` 冷启动重拉段逐一对应，变量用 `$UDID` / `$APP_ID` 占位，
可直接复制使用。`xcrun devicectl` 是 Xcode 自带；若裸 `devicectl` 在 PATH 里也可以直接用。

```bash
# 1. 安装（对应 build_install_ios.sh 的 install 段）
xcrun devicectl device install app --device $UDID </path/to/YourApp.app>

# 2. 列进程，按 bundle id 过滤拿 pid（对应 app_running / cold_start 的取 pid 步）
xcrun devicectl device process list --device $UDID | grep $APP_ID

# 3. 真杀进程（必须 SIGKILL；只退后台的热启动不会重读 handeye_bootstrap.json）
PID=$(xcrun devicectl device process list --device $UDID | awk -v app="$APP_ID" '$0 ~ app {print $1; exit}')
xcrun devicectl device process signal --device $UDID --pid $PID --signal SIGKILL

# 4. 重新拉起（对应 `adb shell am start` 的 iOS 等价物；走 CoreDevice 通道，
#    自动挂载 developer disk image，新 iOS 版本上不要退回手工 mount 的老路）
sleep 2   # 等进程回收 + 端口释放，launch 立即跟上会偶发失败
xcrun devicectl device process launch --device $UDID $APP_ID

# 辅助：看设备日志（进态超时排查第三步会用到）
xcrun devicectl device console --device $UDID
```

**不满足时的症状**：

- 第 3 步用了非 SIGKILL 的信号 / 只 suspend 或退后台 → 第 4 步 launch 唤醒的是热启动，
  不重读配置 → harness 侧退出码 8（见 §2）。
- 第 3、4 步之间没有等待窗口 → launch 偶发失败，脚本退出码 7（错误文案提示确认 bundle id 已装、真机已解锁）。

## 6. 与 `scripts/bootstrap_ios.sh` 的对应关系表

| 脚本步骤（函数 / 阶段） | 脚本实际行为 | 对应本契约条目 |
|---|---|---|
| `build_and_install` → `build_install_ios.sh` | xcodebuild 构建 Debug 包 + `devicectl device install app`（可 `--skip-build` / `--skip-install`） | §5 命令组第 1 条（前置，不属于注入契约本体） |
| `push_media` | `afcclient --container` 建目录 `Documents/handeye/media/` 并 `put` 素材（设备已有则跳过；host 路径含中文/空格先复制到 /tmp 的 ASCII 临时路径再 put）；落盘名取相对段 basename | §1（素材落盘位置即 `work_path` 相对段所指） |
| `write_bootstrap_config` | 先 `rm Documents/handeye_bootstrap.json` 再 `put`，内容为 `{"url":"<scheme>://bootstrap[?work_path=...]"}`，多素材以 `\|` 连接 | §1 配置注入契约 |
| `cold_start` | `process list` 取 pid → `signal SIGKILL` → `sleep 2` → `process launch`；`--no-relaunch` 时只做 `app_running` 进程存在性检查 | §2 读取时机 + §5 命令组第 3、4 条 |
| `ensure_tunnel` | 后台拉起 `setup_ios.sh`，读其 stdout 的 `HANDEYE_HOST_PORT` / `HANDEYE_DEVICE_PORT` 契约行；`--skip-forward` 时整段跳过 | §4 端口契约 |
| `wait_ready` | 每 2 s 轮询 `http://127.0.0.1:<hostPort>/source?name=bootstrap`，非 null 即成功；默认 30 s 超时附排查指引 | §3 路由契约（`bootstrap` 命名源注册）+ §4 |
| `emit_result` | stdout 输出 `HANDEYE_HOST_PORT` / `HANDEYE_DEVICE_PORT` 契约行，供 `run.sh --host-port` 消费 | §4 端口契约 |

---

**变更注意**：改 `HANDEYE_DEEPLINK_SCHEME`、`IOS_DEVICE_PORT`、文件名或 JSON schema 中任何一项，
host 侧配置（`scripts/handeye.example.sh`）与 App 侧接收代码必须同改，否则症状就是上表各节的
退出码 6 / 7 / 8，而不是一条明确的「版本不匹配」报错。
