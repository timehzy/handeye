# conformance：协议一致性套件（JVM）

**你是谁**：不跑 App 也能守住 protocol/spec.md v1 的工具。

**给谁用**：三端实现的开发者与 CI。

**怎么用**：
`./gradlew :conformance:run --args="validate <events.jsonl>"`（结构校验），
`./gradlew :conformance:run --args="diff <actual.jsonl> <golden.jsonl>"`（golden 对拍，
忽略 t 与 seq）。exit 0/1。二期 device-ios 的验收标准 = 跑过本套件。
