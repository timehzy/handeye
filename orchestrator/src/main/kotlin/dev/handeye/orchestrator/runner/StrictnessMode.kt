package dev.handeye.orchestrator.runner

/**
 * scenario 断言严格度。
 *
 * - [NonExhaustive]（默认）：只声明目标 Fact，中间 emission 不校验
 * - [Strict]：段内每个 emission 必须被声明（scoped withStrict { } 打开）
 */
enum class StrictnessMode { NonExhaustive, Strict }
