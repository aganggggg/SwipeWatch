package com.swipewatch.app

import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * 命中判定 + 触发 + 记账，节点模式和 OCR 模式共用。
 *
 * 这里的关键是「边沿触发」：
 * 滑过一次之后，必须先确认那行提示真的消失了（连续 [CLEAR_STREAK_TO_ARM] 轮扫不到），
 * 才重新允许触发。否则会出现一种很难受的现象——广告页/直播页上提示一直挂着、
 * 滑了也没用，冷却时间一到又滑一次，每 10 秒循环一遍。
 *
 * 状态只有两个：armed（可触发）与 !armed（已滑过，等提示消失）。
 */
object Detector {

    private const val LOG_MIN_GAP_MS = 1500L

    /** 连续多少轮确认「提示不在」才重新允许触发，用来过滤节点树的瞬时抖动 */
    private const val CLEAR_STREAK_TO_ARM = 2

    /** 上一次成功触发的时间，用来算冷却 */
    @Volatile
    private var lastTriggerAt = 0L

    /** 是否允许触发。成功滑一次后置 false，等提示消失再回 true */
    @Volatile
    private var armed = true

    /** 触发之后，连续观察到「提示消失」的次数 */
    @Volatile
    private var clearStreak = 0

    /** 上次触发时那屏文字，用来判断有没有换页 */
    @Volatile
    private var lastHitText = ""

    @Volatile
    private var lastLogAt = 0L

    private const val TAG = "SwipeGesture"

    /** 两屏文字的字符重叠低于这个比例，就认为是换了新的一屏 */
    private const val SCENE_CHANGE_RATIO = 0.35f

    /** 一轮扫描的结果 */
    sealed interface Scan {
        /**
         * 本轮读到的全部可见文字。
         * 节点模式是整棵窗口树里拼起来的文字，OCR 模式是底部条带的识别结果。
         * 两者都交给 [TextMatcher] 做模糊匹配——不能用系统侧的子串查询，
         * 因为「3秒后可继续上滑观看短剧」这种倒计时文案，字符一样但语序是反的。
         */
        data class Text(val content: String) : Scan

        /** 读不到，状态未知——不能据此判定「提示消失」，否则会误复位 */
        data object Unknown : Scan
    }

    /** 当前是否允许触发（滑动后要等提示消失） */
    fun isArmed(): Boolean = armed

    /** 距离上一次触发还剩多少毫秒冷却 */
    fun cooldownLeft(prefs: Prefs): Long {
        if (lastTriggerAt == 0L) return 0L
        val left = prefs.cooldownMs - (SystemClock.elapsedRealtime() - lastTriggerAt)
        return if (left > 0L) left else 0L
    }

    /** 开始检测时把状态清零 */
    fun reset() {
        lastTriggerAt = 0L
        armed = true
        clearStreak = 0
        lastHitText = ""
    }

    /**
     * @param scan 本轮扫描结果
     * @param costMs 本轮耗时，仅用于日志展示
     */
    fun accept(scan: Scan, prefs: Prefs, source: AppState.Source, costMs: Long) {
        val now = SystemClock.elapsedRealtime()

        if (scan is Scan.Unknown) {
            // 状态未知，什么都不做：既不算命中，也不算消失
            return
        }

        val content = (scan as Scan.Text).content
        val match = TextMatcher.bestMatch(content, prefs.keywordList())
        val hit = match.keyword != null && match.score >= prefs.threshold

        if (!hit) {
            clearStreak += 1
            if (clearStreak >= CLEAR_STREAK_TO_ARM) armed = true
            writeLog(
                text = content,
                score = match.score,
                hit = false,
                triggered = false,
                note = null,
                costMs = costMs,
                source = source,
                force = false,
                now = now
            )
            return
        }

        // 看到提示了，之前的「消失」计数作废
        clearStreak = 0

        // 如果整段文字和上次触发时几乎不沾边，说明已经换了一屏（新的一集、下一个广告）。
        // 这种情况要重新允许触发，否则会出现：剧集结束时滑了一次，紧接着的广告页
        // 提示一直挂着、文字又完全不同，却因为闩锁没放开而永远不滑。
        val normalized = TextMatcher.normalize(content)
        if (lastHitText.isNotEmpty() &&
            TextMatcher.sceneSimilarity(normalized, lastHitText) < SCENE_CHANGE_RATIO
        ) {
            armed = true
        }

        val cooldown = cooldownLeft(prefs)
        var triggered = false
        var note: String? = null

        if (cooldown > 0L) {
            // 冷却期内不滑，但仍然记一条，方便看出「为什么没动」
            note = "冷却中 ${formatSeconds(cooldown)}"
        } else if (!armed) {
            // 上一轮已经滑过，提示还挂在屏幕上：不动，等它消失
            note = "已滑过，等提示消失"
        } else {
            triggered = SwipeAccessibilityService.swipeUp(
                prefs.swipeStartYRatio,
                prefs.swipeDistanceRatio,
                prefs.swipeDurationMs
            )
            if (triggered) {
                lastTriggerAt = SystemClock.elapsedRealtime()
                armed = false
                lastHitText = normalized
                AppState.triggerCount.value += 1
            } else {
                note = "手势未执行"
            }
        }

        writeLog(
            text = content,
            score = match.score,
            hit = true,
            triggered = triggered,
            note = note,
            costMs = costMs,
            source = source,
            force = triggered,   // 真正滑了的那一条一定要留下
            now = now
        )
    }

    private fun writeLog(
        text: String,
        score: Float,
        hit: Boolean,
        triggered: Boolean,
        note: String?,
        costMs: Long,
        source: AppState.Source,
        force: Boolean,
        now: Long
    ) {
        // 400ms 一轮会把日志刷爆，这里统一节流
        if (!force && now - lastLogAt < LOG_MIN_GAP_MS) return
        lastLogAt = now

        Log.i(
            TAG,
            "hit=$hit triggered=$triggered score=${(score * 100).toInt()}% " +
                "note=${note ?: "-"} text=${truncate(text.trim().replace('\n', ' ')).take(40)}"
        )

        AppState.pushLog(
            AppState.LogEntry(
                time = System.currentTimeMillis(),
                recognizedText = truncate(text.trim().replace('\n', ' ')),
                score = score,
                hit = hit,
                triggered = triggered,
                costMs = costMs,
                source = source,
                note = note
            )
        )
    }

    private fun formatSeconds(ms: Long): String =
        if (ms < 10_000L) {
            String.format(Locale.US, "%.1fs", ms / 1000f)
        } else {
            "${(ms + 500L) / 1000L}s"
        }

    private fun truncate(text: String): String {
        if (text.isEmpty()) return "（未读到文字）"
        return if (text.length <= 70) text else text.substring(0, 70) + "…"
    }
}
