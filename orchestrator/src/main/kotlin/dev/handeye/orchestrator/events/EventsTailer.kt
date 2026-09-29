package dev.handeye.orchestrator.events

/**
 * 事件尾追器 —— 封装 [EventsFetcher] 并维护单调递增的 `afterSeq` 游标，提供
 * [pollLatest] 拉取自上次消费以来的新增事件。
 *
 * 该类不感知具体 kind，也不做任何 kind-specific 的格式化；所有事件统一以 [E2eEvent]
 * 返回，由调用方决定如何打印或消费。
 *
 * ## afterSeq 递进
 *
 * 每次 [pollLatest] 返回事件后，[currentSeq] 推进为本次返回事件的最大 `seq`，
 * 下次拉取从该 seq 之后开始。 events.jsonl 每条事件都有进程内单调 `seq`，服务端
 * 负责跳过 `seq <= afterSeq` 的事件。
 *
 * ## 环形缓冲
 *
 * 内部维护一个 [MutableList] 环形缓冲（最大 [maxSize] 条），保留最近消费的事件。
 * 缓冲目前主要用于调用方在需要时回溯近期事件；不向外部暴露写接口。
 */
class EventsTailer(
    private val eventsFetcher: EventsFetcher,
    private val startSeq: Long = 0L,
) {
    /** 上一次已消费的最大事件序号。 */
    private var currentSeq: Long = startSeq

    private val buffer = mutableListOf<E2eEvent>()
    private val maxSize = 1000

    /**
     * 拉取自 [currentSeq] 之后的新事件。
     *
     * @param limit 单次拉取最大事件数；默认 1000，与 device 端 events 端点默认一致。
     * @return 新增事件列表，按 [E2eEvent.seq] 升序排列（由 [EventsFetcher] 实现保证）。
     */
    suspend fun pollLatest(limit: Int = 1000): List<E2eEvent> {
        val events = eventsFetcher.fetchAfter(seq = currentSeq, kinds = null, limit = limit)
        if (events.isNotEmpty()) {
            currentSeq = events.maxOf { it.seq }
            appendToBuffer(events)
        }
        return events
    }

    private fun appendToBuffer(events: List<E2eEvent>) {
        buffer.addAll(events)
        val overflow = buffer.size - maxSize
        if (overflow > 0) {
            repeat(overflow) { buffer.removeFirst() }
        }
    }
}
