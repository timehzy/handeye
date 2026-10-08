# 真机排查手册（高频坑清单）

**面向读者**：在真机上跑 handeye 场景遇到怪现象的人。每个坑固定三段式：**症状 / 根因 / 绕过**。
绕过方案全部指向本仓脚本与文档的真实机制，不依赖任何外部内部工具。

先看顶部「按现象 → 看哪」索引表定位，再跳到对应坑读细节。

## 按现象 → 看哪

| 现象 | 看哪 |
|---|---|
| `/health` 一直空返回 | iOS：坑 2（取错设备）；Android：坑 1（没进页、debug server 没起）或坑 7（进程崩了） |
| bootstrap 脚本报退出码 6 / 8 | Android：坑 1（冷启动窗口不足）；iOS：退出码 8 → 坑 4（热启动没杀透），退出码 6 → 坑 3（写配置失败，容器姿势问题） |
| 改了 iOS bootstrap 配置不生效 | 坑 4 |
| iOS 写配置报 `Permission denied` | 坑 3 |
| 管道 / 重定向后的退出码永远 0 | 坑 5 |
| 跑全绿但 artifacts 里找不到任何产物 | 坑 6（设计如此，不是丢了） |
| `dlopen failed: library "…" not found` | 坑 7 |
| 批跑大面积 FAIL、单跑复现且失败集合逐 run 漂移 | 时序污染定性，见 [how-to-write.md §七](how-to-write.md)「断言确定性与事件时序」 |

---

## 坑 1 · Android 冷启动 bootstrap 进页失败（最常见）

**症状**：[bootstrap_android.sh](../scripts/bootstrap_android.sh)（或经 [run.sh](../scripts/run.sh) 批跑）报退出码 6（deeplink 注入失败）或退出码 8（进态超时）。超时错误的尾部诊断里 `Track B · 最后 pidof='' · top-Activity=''`——App 冷启动后停在启动入口 Activity，没进目标页，debug server 没起或 bootstrap 源没注册。

**根因**：新装包 / 首次冷启动的初始化窗口不足（native 库加载、装配器 install、debug server 起监听都耗在启动期），deeplink 撞进页时依赖未就绪，静默失败。固定 sleep 等启动的方案对新设备窗口偏紧，本仓脚本已用「重试 + 双轨判定」替代单次注入。

**绕过**：脚本已内建吸收机制，偶发撞窗**直接重跑**即可，无需手动干预：

- deeplink 注入最多 **3 次**：每次 `force-stop → launcher 暖机（默认 12s）→ deeplink → 等 3s 判 /source?name=bootstrap 非 null`，3 次仍失败才 exit 6。
- 进态判定默认 **30s** 双轨轮询（每秒一次）：Track A（强判定）= `/health` 就绪 + bootstrap source 非 null；Track B（弱判定）= 进程存活 + 前台为目标 Activity，通过时打 warn（debug server 未就绪，事件流可能拿不到）。
- 窗口参数可用环境变量调：`HANDEYE_BOOTSTRAP_WARMUP_SEC`（默认 12）、`HANDEYE_BOOTSTRAP_SETTLE_SEC`（默认 3）、`HANDEYE_BOOTSTRAP_READY_TIMEOUT_SEC`（默认 30）。
- App 已在目标态附近时可用 `--no-relaunch` 跳过冷启动快速接入；持续失败先读脚本失败时自动附上的 logcat 尾部 30 行。

## 坑 2 · iOS 多设备取错设备

**症状**：`afcclient` / `devicectl` / `iproxy` 全打错设备，`/health` 一直空返回。

**根因**：脚本的设备缺省解析落到 `idevice_id -l` 第一台；多台 iPhone 插着时输出排序不可控，可能取到没装 App 的那台。

**绕过**：显式指定 udid。[setup_ios.sh](../scripts/setup_ios.sh) 与 [build_install_ios.sh](../scripts/build_install_ios.sh) 的解析链一致，优先级从高到低：

1. `-u <udid>` 参数
2. 环境变量 `HANDEYE_UDID`
3. fixtures 的 `.devices.ios_udid`（jq 读取）
4. `idevice_id -l` 第一台（多机时脚本会打 warn 提醒）

多机场景建议把 `HANDEYE_UDID=<udid>` 写进 `scripts/handeye.local.sh`，一次配置处处生效。

## 坑 3 · iOS afcclient 用 `--container` 不是 `--documents`

**症状**：用 `afcclient --documents` 手动读写 App 容器报 `Permission denied`。

**根因**：iOS 26 上 `--documents` 直接列容器根会被拒。`--container <APP_ID>` 进的是容器根（`/` 下有 `Documents/`、`Library/` 等），写 Documents 目录要拼 `Documents/` 前缀。

**绕过**：手动排查统一用 `afcclient --container <APP_ID> -u <udid>`，写文件走 `put Documents/<name>`。本仓脚本（[bootstrap_ios.sh](../scripts/bootstrap_ios.sh) 写 `Documents/handeye_bootstrap.json`）已按此处理，别在排查时换错姿势。完整契约（含 `put` 不截断旧文件需要先 `rm` 的覆盖语义）见 [bootstrap-contract.md §1](../device-ios/docs/bootstrap-contract.md)。

## 坑 4 · iOS 冷启动不读配置（改配置不生效）

**症状**：改了 bootstrap 配置但 App 行为没变，`/source?name=bootstrap` 一直 null，脚本报退出码 8。

**根因**：读 `handeye_bootstrap.json` 的代码注册在 App 进程**冷启动完成回调**里，同一进程生命周期只读一次；App 退后台被唤醒（热启动）不重读。手动 terminate 没杀透进程时，relaunch 走的是热启动，停在首页不进页。

**绕过**：`bootstrap_ios.sh` 的冷启动重拉是「真杀进程（SIGKILL）+ 重新 launch」，不杀透就没有下一次冷启动回调。手动排查按 [bootstrap-contract.md §5](../device-ios/docs/bootstrap-contract.md) 的命令组：先 `xcrun devicectl device process list --device <udid>` 拿 pid，`xcrun devicectl device process signal --device <udid> --pid <pid> --signal SIGKILL` 真杀，`sleep 2` 等进程回收，再 `xcrun devicectl device process launch --device <udid> <APP_ID>` 拉起。读取时机契约见该文档 §2。

## 坑 5 · 管道 exit code 失真

**症状**：`./scripts/run.sh ... | tail` 的退出码是 `tail` 的（永远 0），批跑挂没挂从退出码看不出来。

**根因**：shell 管道的退出码默认取最后一个命令的，`tee` / `tail` 自己不失败。

**绕过**：[run.sh](../scripts/run.sh) 已内建对策，正常用法不需要自己包管道：

- 每组输出 `tee -a` 完整落盘 `e2e/artifacts/<批跑时间戳>/run.log`；
- gradle 真实退出码用 `PIPESTATUS` 拿，不依赖管道末位；
- 批跑结果以汇总表和 `run.log` 里的 `[PASS]` / `[FAIL]` 行为准：`grep -E '\[PASS\]|\[FAIL\]' e2e/artifacts/<ts>/run.log`。

自己重定向批跑输出（`> xxx.log 2>&1`）时注意别只看 `tail`——前几个分组的 `[PASS]` 行会被截掉，数结果要 grep 全量日志。

## 坑 6 · artifacts 只写失败场景

**症状**：一次跑全绿，想在 artifacts 目录里找运行证据，一个场景目录都没有。

**根因**：失败 artifact 只在 FAIL 时产出（[ArtifactDumper](../orchestrator/src/main/kotlin/dev/handeye/orchestrator/runner/ArtifactDumper.kt)：`hasFailure()` 为真才落盘，PASS 零 IO），这是设计行为。落盘路径 `e2e/artifacts/<dump 时间戳>/<场景名>/`，内含：

- `report.md`：断言树 + 环境信息；
- `projected-facts.json`：projected facts 快照；
- host context 提供的 raw artifacts——demo 场景含 `events.jsonl` 全事件链、各 source 快照、`health.json`（见 [FeedContext.kt](../demo-android/e2e/src/main/kotlin/dev/handeye/demo/e2e/FeedContext.kt)）。

批跑日志 `run.log` 在另一个同级目录（以批跑起始时间戳命名，与场景 dump 时间戳不同）。

**绕过**：按目录时间戳数一次批跑的失败集合；定位失败根因先看对应场景目录下的 `events.jsonl` 与 `report.md` 断言树，再看批跑级 `run.log`。

## 坑 7 · 新装包首次启动窗口内 deeplink 进态崩溃（按需解压的 native 库未就绪）

**适用路径**：App 内含按需解压的大 so / 资源（打包时显式 exclude、首次启动由 App 异步解压到私有 `files` 目录）时，新装包第一次启动的解压窗口内 deeplink 进态会崩。走 `run.sh` / `bootstrap_android.sh` 一般不必担心——装包后的暖机等待与 3 次重试覆盖了这段窗口，撞上了重跑一次也会好（见坑 1）。本坑主要影响**手动**注入 deeplink 的调试路径。

**症状**：刚 `adb install` 完新包、App 还没完整启动过一次时手动 deeplink，进页失败、App 进程消失。crash buffer 里是 `UnsatisfiedLinkError: dlopen failed: library "…" not found`。

**根因**：该 so 在打包时被显式 exclude（避免进程内出现双实例），首次启动时由 App 异步解压到私有 `files` 目录。新包第一次启动时解压还没完成，deeplink 触发的进态路径先要加载它。

**绕过**：换包后先让 App 正常启动一次（走一遍正常主页流程），用 `adb shell run-as <pkg> ls files/<解压目录>/` 确认目标 so 已落盘，再手动 deeplink 进态。

---

**相关文档**：[how-to-write.md](how-to-write.md)（scenario 写作与断言确定性）· [integration-points.md](integration-points.md)（App 侧接入点）· [bootstrap-contract.md](../device-ios/docs/bootstrap-contract.md)（iOS 注入契约全文）
