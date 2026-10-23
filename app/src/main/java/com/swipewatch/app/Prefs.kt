package com.swipewatch.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 运行参数。所有设置立即落盘。
 *
 * 注意两种引擎跑在不同地方：
 * - 纯节点模式跑在无障碍服务里（不需要录屏、不需要前台服务）
 * - OCR 模式跑在 CaptureService 里（需要 MediaProjection）
 * 两个循环每轮都会重新读一次设置，所以改开关能立刻生效。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 节点轮询的间隔（毫秒）。读一次节点树只要 1~3ms，可以设得比较密 */
    var intervalMs: Long
        get() = sp.getLong(KEY_INTERVAL, DEFAULT_INTERVAL)
        set(value) = sp.edit().putLong(KEY_INTERVAL, value).apply()

    /** 盲区兜底：整页读不到文字节点时，截图底部一条做识别 */
    var blindOcr: Boolean
        get() = sp.getBoolean(KEY_BLIND_OCR, true)
        set(value) = sp.edit().putBoolean(KEY_BLIND_OCR, value).apply()

    /** 盲区截图的节流间隔（毫秒）。一次截图+识别 80~150ms，别设太密 */
    var ocrIntervalMs: Long
        get() = sp.getLong(KEY_OCR_INTERVAL, DEFAULT_OCR_INTERVAL)
        set(value) = sp.edit().putLong(KEY_OCR_INTERVAL, value).apply()

    /** 盲区截图只保留屏幕底部多高的区域 */
    var bottomRatio: Float
        get() = sp.getFloat(KEY_BOTTOM_RATIO, DEFAULT_BOTTOM_RATIO)
        set(value) = sp.edit().putFloat(KEY_BOTTOM_RATIO, value).apply()

    /** 节点循环是否在运行（持久化，无障碍服务重连后自动恢复） */
    var nodeLoopEnabled: Boolean
        get() = sp.getBoolean(KEY_NODE_LOOP, false)
        set(value) = sp.edit().putBoolean(KEY_NODE_LOOP, value).apply()

    /** 触发一次上滑后，多少毫秒内不再重复触发 */
    var cooldownMs: Long
        get() = sp.getLong(KEY_COOLDOWN, DEFAULT_COOLDOWN)
        set(value) = sp.edit().putLong(KEY_COOLDOWN, value).apply()

    /** 关键词，多个用逗号分隔 */
    var keywords: String
        get() = sp.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        set(value) = sp.edit().putString(KEY_KEYWORDS, value).apply()

    /** 文字相似度阈值 0.5 ~ 1.0，越低越宽松 */
    var threshold: Float
        get() = sp.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) = sp.edit().putFloat(KEY_THRESHOLD, value).apply()

    /** 滑动手势持续时间（毫秒） */
    var swipeDurationMs: Long
        get() = sp.getLong(KEY_SWIPE_DURATION, DEFAULT_SWIPE_DURATION)
        set(value) = sp.edit().putLong(KEY_SWIPE_DURATION, value).apply()

    /** 滑动总距离，占屏幕高度的比例 */
    var swipeDistanceRatio: Float
        get() = sp.getFloat(KEY_SWIPE_DISTANCE, DEFAULT_SWIPE_DISTANCE)
        set(value) = sp.edit().putFloat(KEY_SWIPE_DISTANCE, value).apply()

    /** 手势起点的高度位置（占屏幕高度） */
    var swipeStartYRatio: Float
        get() = sp.getFloat(KEY_SWIPE_START_Y, DEFAULT_SWIPE_START_Y)
        set(value) = sp.edit().putFloat(KEY_SWIPE_START_Y, value).apply()

    fun keywordList(): List<String> =
        keywords.split(',', '，', '|', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    companion object {
        private const val NAME = "swipe_watch_prefs"

        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_OCR_INTERVAL = "ocr_interval_ms"
        private const val KEY_BLIND_OCR = "blind_ocr"
        private const val KEY_NODE_LOOP = "node_loop_enabled"
        private const val KEY_BOTTOM_RATIO = "bottom_ratio"
        private const val KEY_COOLDOWN = "cooldown_ms"
        private const val KEY_KEYWORDS = "keywords"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_SWIPE_DURATION = "swipe_duration_ms"
        private const val KEY_SWIPE_DISTANCE = "swipe_distance_ratio"
        private const val KEY_SWIPE_START_Y = "swipe_start_y_ratio"

        const val DEFAULT_INTERVAL = 400L
        const val DEFAULT_OCR_INTERVAL = 1200L
        const val DEFAULT_BOTTOM_RATIO = 0.12f
        const val DEFAULT_COOLDOWN = 2500L
        const val DEFAULT_KEYWORDS = "上滑继续观看"
        const val DEFAULT_THRESHOLD = 0.70f
        const val DEFAULT_SWIPE_DURATION = 350L
        const val DEFAULT_SWIPE_DISTANCE = 0.55f
        const val DEFAULT_SWIPE_START_Y = 0.85f
    }
}
