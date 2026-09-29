# device-ios → 协议 v1 映射（二期实现依据）

device-ios 是纯 Swift 的第二实现，验收标准 = 跑过与 KMP/Android 相同的 conformance 套件。
本文件把协议契约映射到 Swift 侧概念，冻结接口、不冻结实现。

## 端点（内嵌 HTTP server，7 个）

| 端点 | 契约 |
|---|---|
| `GET /health` | `{"ok": Bool, "hookInstalled": Bool, "pid": Int?, "logFilePath": String?}`；ok=false 时仍 200 |
| `POST /cmd` | 请求体 `{"key": String, "args": Object}`；同步执行命令处理器；响应 `{"accepted": true}`，错误 400 带 `error` 字段 |
| `GET /source?name=` | 响应 `{"source": name, "data": <快照或 null>}`；未知源 404 |
| `GET /events?afterSeq=&kinds=` | 响应 `{"events": [<envelope>...]}`；读取前 flush 保证写盘可见性 |
| `POST /reset?mode=events` | 清空事件文件、重置时钟基点；seq 不回退 |
| `POST /wait-for` | 轮询等待指定 kind/name 事件出现（可选） |
| `POST /snapshot` | 一次性返回全部命名源快照 |

## 事件 envelope（events.jsonl，每行一条）

```json
{"seq": 1, "t": 0, "kind": "state", "name": "FeedUiState", "payload": {...}}
```

- `seq`：进程内严格单调递增，reset 不回退；Int64。
- `t`：进程单调时钟毫秒，reset 归零基点；非负 Int64。
- `kind` / `name`：非空字符串；name 通常取 payload 的 nameKey 字段。
- `payload`：可缺省，缺省表示无负载；存在时必须是 JSON 对象。
- 文件位置：`<filesDir>/handeye/events.jsonl`。

## Swift 侧映射

| 协议/概念 | device-kmp | device-ios（本草案） |
|---|---|---|
| Flow 订阅 | `EventRecorder.registerFlow(kind, nameKey, serialize, flow)` | `HandeyeEventRecording.registerStream(...)`（AsyncSequence 或回调，二期定稿） |
| 手动记录 | `EventRecorder.record(kind, name, payload)` | `HandeyeRecording.record(...)` |
| 快照源 | `stateProvider(name) { ... }` | `HandeyeStateProviding` |
| 命令表 | `CommandRegistry.register(key, handler)` | `HandeyeCommandDispatching.register(...)` |
| 装配 | `HandeyeInstaller.install(...)` | `HandeyeInstaller.install(...)` |
| HTTP 引擎 | expect/actual（androidDebug=Ktor CIO） | 二期自带内嵌实现（URLSession 或 CocoaAsyncSocket 级），接口不暴露 |

## 不变量

1. 单 writer：事件构造→seq 分配→落盘单线程顺序执行；`flush()` 是写盘可见性 barrier。
2. 幂等装配：同一命令表重复 install 返回既有结果；release 变体 no-op。
3. 端点内部异常一律降级为带 `error` 字段的响应，不炸 App。
