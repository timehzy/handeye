# handeye protocol v1

handeye 设备端与编排器之间的线协议。实现者（设备端任意语言）与驱动者（编排器、Agent、
curl）只依赖本文档，不依赖任何 SDK。

## 1. 事件信封（events.jsonl）

设备端把采集到的事件以 JSONL（每行一个 JSON Object）追加写入事件文件，经 `/events` 端点对外提供。

| 字段 | 类型 | 约束 |
|---|---|---|
| `seq` | long | 进程内严格单调递增；reset 清空文件后 seq 不回退 |
| `t` | long | 单调时钟毫秒（进程内相对值，非 epoch）；单调非负 |
| `kind` | string | 非空；领域自由（如 `state` / `networkRequest` / `cacheWrite`） |
| `name` | string | 非空；领域自由 |
| `payload` | object | 可选；领域自由 |

示例：

```json
{"seq":12,"t":340,"kind":"networkResponse","name":"GET /feed","payload":{"status":200}}
```

## 2. 端点

全部端点只绑 `127.0.0.1`。除注明外响应均为 `application/json`。

### GET /health

探活 + 订阅就绪 barrier。设备端在响应前应确保已注册的 Flow 订阅全部建立（或超时）。

响应 200：

```json
{"ok":true,"hookInstalled":true,"pid":12345,"logFilePath":"/data/data/<pkg>/files/handeye/events.jsonl"}
```

`ok=false` 表示 VM/宿主未就绪或订阅超时，驱动方应重试。

### POST /cmd

反注入语义命令。请求体：

```json
{"key":"Feed.Refresh","args":{"page":1}}
```

- `key`：接入方在 CommandRegistry 登记的稳定字符串；未登记 → 400 `{"accepted":false,"error":"unknown command: <key>"}`
- 语义为**仅入队**：响应 ack 时命令可能尚未执行完成，一切状态断言走 `/source` 与 `/events`

响应 200：`{"accepted":true}`

### GET /source?name=&lt;name&gt;

拉取命名快照源。`name` 由接入方注册（多源即多次调用）。

- 源可用：200 `{"source":"ui","data":{...}}`
- 源当前不可用：200 `{"source":"ui","data":null}`
- 未注册的 name：404 `{"source":"<name>","data":null}`

### GET /events?afterSeq=&lt;long&gt;&kinds=&lt;a,b&gt;

增量拉事件。响应 200：

```json
{"events":[<envelope>,...]}
```

- `afterSeq` 缺省 0，只返回 seq 大于该值的事件
- `kinds` 可选，逗号分隔过滤
- 设备端响应前必须先把挂起的事件落盘（flush barrier）

### POST /reset?mode=events

清事件文件并重置时钟基点；seq 不回退。响应 200：`{"reset":true}`

### POST /wait-for

轮询等某类事件出现。请求体：

```json
{"kind":"state","name":"FeedUiState","afterSeq":0,"timeoutMs":5000}
```

`name` 可选。100ms 间隔轮询 `/events` 数据源，命中或超时返回 200：`{"matched":true}`

### POST /snapshot

手动 dump 全部已注册快照源。响应 200：

```json
{"ui":{...},"memory":{...},"persist":{...}}
```

## 3. 传输与安全

- **只绑 127.0.0.1**：局域网不可访问，协议级约束，所有实现必须遵守
- 端口：由设备端选空闲临时端口；**端口发现**约定——Android 实现从 `/proc/<pid>/net/tcp6`
  反查进程 LISTEN 端口，host 侧用 `adb forward tcp:<port> tcp:<port>` 建隧道；
  其他平台的隧道方式不限，但发现机制须写入实现文档
- 事件文件路径：`<filesDir>/handeye/events.jsonl`，经 `/health` 的 `logFilePath` 暴露

## 4. 版本

- 当前 `protocolVersion = 1`
- 事件信封增删字段、端点增删改语义，都必须 bump protocolVersion 并在本文档记录变更
