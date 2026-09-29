import Foundation

/// device-ios（二期）接口签名草案 —— 与 device-kmp 的 commonMain API 一一对应，
/// 实现须满足 protocol/spec.md v1 与 conformance 套件。本期不实现。
///
/// 端点与事件契约见 docs/protocol-mapping.md。

/// 事件信封：events.jsonl 每行一条，与协议 v1 一致。
public struct HandeyeEvent: Codable {
    public var seq: Int64
    public var t: Int64
    public var kind: String
    public var name: String
    public var payload: [String: AnyCodable]?
}

/// 回调式记录：拦截器 / observer 等非流式场景。
public protocol HandeyeRecording {
    func record(kind: String, name: String, payload: [String: AnyCodable]?)
}

/// 命名快照源：GET /source?name=<name> 的提供方。
public protocol HandeyeStateProviding {
    var name: String { get }
    func snapshot() -> [String: AnyCodable]?
}

/// POST /cmd 的命令表：稳定字符串 key → 处理器。
public protocol HandeyeCommandDispatching {
    func register(key: String, handler: @escaping ([String: AnyCodable]) -> Void)
}

/// L1 事件记录器：注册 Flow（KMP 侧为 kotlinx.coroutines.Flow，
/// Swift 侧二期定稿为 AsyncSequence 或回调闭包）、手动 record、flush/reset。
public protocol HandeyeEventRecording: HandeyeRecording {
    func registerStream(
        kind: String,
        nameKey: String,
        serialize: @escaping (Any) -> [String: AnyCodable],
        stream: Any
    )
    func flush() async
    func reset()
}

/// L2 装配入口：宿主工程一行调用；幂等；release 零开销。
public enum HandeyeInstaller {
    public struct InstallResult {
        public let port: UInt16
        public let logFilePath: String
        public let installId: Int64
    }

    public static func install(
        filesDir: URL,
        recorder: any HandeyeEventRecording,
        providers: [any HandeyeStateProviding],
        commands: any HandeyeCommandDispatching,
        enabled: Bool?
    ) -> Result<InstallResult, Error> {
        // 二期实现。
        fatalError("device-ios 二期实现")
    }
}

/// AnyCodable 占位：二期定稿前先用类型擦除装 payload。
public struct AnyCodable: Codable {
    public let value: Any
    public init(_ value: Any) { self.value = value }
    public init(from decoder: Decoder) throws { self.value = decoder.singleValueContainer() }
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(String(describing: value))
    }
}
