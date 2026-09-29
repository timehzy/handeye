// swift-tools-version: 5.9
import PackageDescription

// device-ios：二期纯 Swift 实现（L1/L2 与协议 v1 对齐）。
// 本期只冻结接口签名与包骨架，验收标准 = 跑过同一 conformance 套件。
let package = Package(
    name: "HandeyeDevice",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "HandeyeDevice", targets: ["HandeyeDevice"]),
    ],
    targets: [
        .target(name: "HandeyeDevice"),
    ]
)
