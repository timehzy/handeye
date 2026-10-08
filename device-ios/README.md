# device-ios：iOS 原生版设备端（二期）

**你是谁**：handeye 设备端的 iOS 原生（Swift Package）入口。

**状态**：**二期**。本期（0.1.1）冻结接口签名、包骨架与 bootstrap 启动契约，不实现——
`Sources/HandeyeDevice/Handeye.swift` 是与 device-kmp `commonMain` 一一对应的协议草案，
`docs/protocol-mapping.md` 是协议 v1 到 Swift 侧的映射；
[`docs/bootstrap-contract.md`](docs/bootstrap-contract.md) 是 bootstrap 冷启动进态契约（harness 脚本 `scripts/bootstrap_ios.sh` 的
文件注入机制与 App 侧接收时机 / 路由 / 端口约定）。
验收标准：二期实现跑过与 KMP/Android 相同的 conformance 套件。

**给谁用**：纯 iOS 工程（不接 KMP）想接入 handeye 的场景。
KMP 工程请直接用 `device-kmp` 的 iosMain 变体（注入式转发）。
