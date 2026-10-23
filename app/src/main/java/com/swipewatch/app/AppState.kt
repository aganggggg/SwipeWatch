package com.swipewatch.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 检测端 -> 界面的单向数据流。
 * 服务端只管写，界面只管订阅，避免用 Intent/Binder 来回传。
 */
object AppState {

    /** 识别来源：节点直读 / 截图 OCR */
    enum class Source(val label: String) {
        NODE("节点"),
        OCR("OCR")
    }

    data class LogEntry(
        val time: Long,
        val recognizedText: String,
        val score: Float,
        val hit: Boolean,
        val triggered: Boolean,
        val costMs: Long,
        val source: Source,
        /** 命中但没滑动的原因，例如「冷却中 3.0s」「已滑过，等提示消失」 */
        val note: String? = null
    )

    /** 节点循环（跑在无障碍服务里，含盲区截图兜底）是否在运行 */
    val nodeLoopRunning = MutableStateFlow(false)

    val triggerCount = MutableStateFlow(0)

    val logs = MutableStateFlow<List<LogEntry>>(emptyList())

    private const val MAX_LOGS = 12

    fun pushLog(entry: LogEntry) {
        val current = logs.value
        val next = ArrayList<LogEntry>(current.size + 1)
        next.add(entry)
        next.addAll(current.take(MAX_LOGS - 1))
        logs.value = next
    }

    fun reset() {
        logs.value = emptyList()
        triggerCount.value = 0
    }
}
