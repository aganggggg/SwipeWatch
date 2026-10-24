package com.swipewatch.app

import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * 命中判定 + 触发 + 记账，节点模式和 OCR 模式共用。
 *
 * 核心教训：**手势下发成功 ≠ 页面真的滑了**。
 * `dispatchGesture` 返回 true 只说明系统收下了这个手势。广告还在播、页面不接受滑动时，
 * 手势会被直接吞掉，页面一动不动。所以这里不能「滑完就当成功」，只能用
 * 「提示还在不在」来验证结果，验证不过就退避重试。
 *
 * 状态只有两个维度：
 * - [swipeCount]：这屏提示已经滑了几次。0 = 还没滑过，可以直接滑。
 * - 重试：滑过之后提示仍在，就按 [gapAfter] 的退避间隔继续试；试满
 *   [MAX_SWIPES_PER_SCREEN] 次（约 1 分钟）还滑不动才放弃，等提示自己消失。
 */
object Detector {

    private const val TAG = "SwipeGesture"

    private const val LOG_MIN_GAP_MS = 1500L

    /** 连续多少轮确认「提示不在」才认为这屏已经过去了，用来过滤节点树的瞬时抖动 */
    private const val CLEAR_STREAK_TO_ARM = 2

    /**
     * 同一屏提示最多滑几次（1 次首滑 + 8 次重试）。
     * 设上限是必要的：真正滑不动的页面上，无限重试等同于把手机变成自动连点器。
     * 按默认 2.5s 冷却算，累计覆盖约 90 秒，能扛过常见的 15s / 30s / 60s 广告。
     */
    private const val MAX_SWIPES_PER_SCREEN = 9

    /** 两次重试之间的间隔封顶，避免长广告场景下等太久 */
    private const val MAX_RETRY_GAP_MS = 30_000L

    /** 上一次真正下发滑动的时间，用来算冷却与退避 */
    @Volatile
    private var lastSwipeAt = 0L

    /** 当前这屏提示已经滑了几次。0 表示还没滑过 */
    @Volatile
    private var swipeCount = 0

    /** 滑过之后，连续观察到「提示消失」的次数 */
    @Volatile
    private var clearStreak = 0

    /** 上次滑动时那屏文字：既用来判断「换了新的一屏」，也用来判断「滑了但页面没动」 */
    @Volatile
    private var lastHitText = ""

    @Volatile
    private var lastLogAt = 0L

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

    /** 当前是否还没滑过这一屏 */
    fun isArmed(): Boolean = swipeCount == 0

    /** 这屏已经滑了几次 */
    fun swipeCount(): Int = swipeCount

    /**
     * 第 [count] 次滑动之后，隔多久才允许下一次。
     *
     * 线性退避：cooldown × 1、× 2、× 3 …，封顶 [MAX_RETRY_GAP_MS]。
     * 首滑后只等一个 cooldown 就重试，因为「广告还差一两秒播完」是最常见的失败原因，
     * 等太久反而错过；越往后等得越久，长广告也覆盖得到。
     */
    private fun gapAfter(prefs: Prefs, count: Int): Long {
        val base = prefs.cooldownMs.coerceIn(200L, 300_000L)
        val gap = base * count.coerceAtLeast(1)
        return gap.coerceAtMost(MAX_RETRY_GAP_MS)
    }

    /** 下一次允许下发滑动的时刻，0 表示随时可以 */
    private fun nextActionAt(prefs: Prefs): Long {
        // 用 swipeCount 而不是 lastSwipeAt == 0L 判定「还没滑过」。
        // 拿时间戳当哨兵值迟早出事：真滑在 elapsedRealtime 恰好为 0 的那一刻就绕过了退避。
        if (swipeCount <= 0) return 0L
        return lastSwipeAt + gapAfter(prefs, swipeCount)
    }

    /** 距离下一次允许滑动还剩多少毫秒；0 表示现在就能滑 */
    fun cooldownLeft(prefs: Prefs): Long {
        val at = nextActionAt(prefs)
        if (at == 0L) return 0L
        val left = at - SystemClock.elapsedRealtime()
        return if (left > 0L) left else 0L
    }

    /** 开始检测时把状态清零 */
    fun reset() {
        swipeCount = 0
        lastSwipeAt = 0L
        clearStreak = 0
        lastHitText = ""
        lastLogAt = 0L
    }

    /** 这屏已经翻过去了，重新允许触发 */
    private fun rearm() {
        swipeCount = 0
        lastSwipeAt = 0L
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
            if (clearStreak >= CLEAR_STREAK_TO_ARM) rearm()
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
        val normalized = TextMatcher.normalize(content)

        // 整段文字和上次滑动时几乎不沾边，说明已经换了一屏（新的一集、下一个广告）。
        // 这种情况必须重新计数，否则剧集结束后滑的那一次会把后面整段广告的额度吃光。
        if (lastHitText.isNotEmpty() &&
            TextMatcher.sceneSimilarity(normalized, lastHitText) < SCENE_CHANGE_RATIO
        ) {
            rearm()
        }

        // 这屏已经滑够次数还是滑不动，彻底放弃，等提示自己消失
        if (swipeCount >= MAX_SWIPES_PER_SCREEN) {
            writeLog(
                text = content,
                score = match.score,
                hit = true,
                triggered = false,
                note = "已滑 $swipeCount 次仍无效，等提示消失",
                costMs = costMs,
                source = source,
                force = false,
                now = now
            )
            return
        }

        val left = cooldownLeft(prefs)
        if (left > 0L) {
            // 冷却/退避期内不滑，但仍然记一条，方便看出「为什么没动」
            writeLog(
                text = content,
                score = match.score,
                hit = true,
                triggered = false,
                note = if (swipeCount == 0) {
                    "冷却中 ${formatSeconds(left)}"
                } else {
                    "重试退避 ${formatSeconds(left)}（已滑 $swipeCount 次）"
                },
                costMs = costMs,
                source = source,
                force = false,
                now = now
            )
            return
        }

        // 上次滑完到现在整屏文字一字未变 —— 手势多半压根没生效（广告还没播完）。
        // 这个判断只用于日志诊断，不参与是否重试的决策。
        val pageFrozen = swipeCount > 0 && lastHitText.isNotEmpty() && normalized == lastHitText

        val triggered = SwipeAccessibilityService.swipeUp(
            prefs.swipeStartYRatio,
            prefs.swipeDistanceRatio,
            prefs.swipeDurationMs
        )

        if (!triggered) {
            writeLog(
                text = content,
                score = match.score,
                hit = true,
                triggered = false,
                note = "手势未执行",
                costMs = costMs,
                source = source,
                force = false,
                now = now
            )
            return
        }

        lastSwipeAt = now
        swipeCount += 1
        lastHitText = normalized
        AppState.triggerCount.value += 1

        val note = when {
            swipeCount == 1 -> null
            pageFrozen -> "重试第 ${swipeCount - 1} 次（上次滑动页面没动）"
            else -> "重试第 ${swipeCount - 1} 次"
        }

        writeLog(
            text = content,
            score = match.score,
            hit = true,
            triggered = true,
            note = note,
            costMs = costMs,
            source = source,
            force = true,   // 真正滑了的那一条一定要留下
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
                "swipe=$swipeCount note=${note ?: "-"} " +
                "text=${truncate(text.trim().replace('\n', ' ')).take(40)}"
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
