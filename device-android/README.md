# device-android：Android 原生版设备端

**你是谁**：handeye 设备端的 Android 原生（AAR）入口。

**给谁用**：纯 Android 工程（不接 KMP）想接入 handeye 的场景。

**怎么依赖**：`api(project(":device-android"))`（或后续发布的 AAR 坐标）。
本模块内就是 `device-kmp` 同一份 Kotlin 源码——不是第二套实现，
只是给 Android 原生工程一个独立的依赖坐标，随包转发 KMP 工程的 android 变体。

接入三步（详见仓库首页 README）：

1. debug 依赖本模块，release 不依赖（零开销）；
2. `HandeyeInstaller.install(...)` 一行装配 recorder / 命令表 / 快照源 / 内嵌 server；
3. App 内对应处埋点 `recorder.record(...)` / `registerFlow(...)` / `stateProvider(...)`。

release 变体是 no-op stub：不建 socket、不订阅、不写字，随构建裁剪。
