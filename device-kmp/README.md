# device-kmp：设备端核心（KMP）

**你是谁**：handeye 设备端——事件记录、命令表、命名快照源、内嵌 HTTP debug server（7 端点）。

**给谁用**：KMP 工程（Android/iOS 共用）与 Android 原生工程（经 `device-android` AAR 入口）。

**怎么依赖**：KMP 工程 `implementation(project(":device-kmp"))`；androidDebug 带 Ktor CIO
server，androidRelease 是 no-op stub（零 socket 零订阅），iosMain 为注入式转发
（未注入时抛 IllegalStateException，iOS 纯 Swift 实现见二期 `device-ios`）。
装配入口 `HandeyeInstaller.install(...)`，幂等。
