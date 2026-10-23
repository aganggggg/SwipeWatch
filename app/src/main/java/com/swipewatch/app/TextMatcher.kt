package com.swipewatch.app

/**
 * 文字匹配：把 OCR 结果和关键词做模糊比对。
 *
 * 之所以不用「完全包含」，是因为 OCR 对中文小字经常错一两个字
 * （滑→猾、继→断 之类）。这里用滑动窗口 + 最长公共子序列，
 * 只要窗口内的字符匹配率超过阈值就算命中。
 */
object TextMatcher {

    data class MatchResult(
        val keyword: String?,
        val score: Float,
        val normalizedText: String
    ) {
        val isHit: Boolean get() = keyword != null
    }

    /** 只保留字母与数字（包括汉字），丢掉空格与标点 */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * 两个字符串的字符集合 Jaccard 相似度。
     * 用来判断「两次读到的文字是不是同一屏内容」——换了一集或者换了个广告，
     * 整段文字会完全不同，据此把防重复触发的闩锁放下来。
     */
    fun sceneSimilarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f

        val setA = a.toHashSet()
        val setB = b.toHashSet()
        var intersection = 0
        for (ch in setA) if (ch in setB) intersection++

        val union = setA.size + setB.size - intersection
        return if (union == 0) 1f else intersection.toFloat() / union
    }

    fun bestMatch(rawText: String, keywords: List<String>): MatchResult {
        val text = normalize(rawText)
        if (text.isEmpty() || keywords.isEmpty()) return MatchResult(null, 0f, text)

        var bestScore = 0f
        var bestKeyword: String? = null
        for (keyword in keywords) {
            val target = normalize(keyword)
            if (target.isEmpty()) continue
            val score = similarity(text, target)
            if (score > bestScore) {
                bestScore = score
                bestKeyword = keyword
            }
        }
        return MatchResult(bestKeyword, bestScore, text)
    }

    private fun similarity(text: String, target: String): Float {
        if (text.contains(target)) return 1f
        val n = target.length
        if (text.length <= n) return peak(text, target)

        var best = 0f
        for (start in 0..text.length - n) {
            val score = peak(text.substring(start, start + n), target)
            if (score > best) best = score
            if (best >= 1f) break
        }
        return best
    }

    /**
     * 两种比法取大的那个。
     *
     * 只用 LCS 会漏掉语序不同的同义说法：红果有个广告位写的是
     * 「3秒后可继续上滑观看短剧」，字符和「上滑继续」一模一样，
     * 只是「继续」和「上滑」换了位置，LCS 只能拿到 0.5，白白错过。
     * 所以再加一个不看顺序的字符重叠率兜底。
     */
    private fun peak(window: String, target: String): Float =
        maxOf(lcsRatio(window, target), overlapRatio(window, target))

    /** 字符多重集重叠率：不看顺序，只看到底凑齐了关键词里的几个字 */
    private fun overlapRatio(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val counts = HashMap<Char, Int>(b.length)
        for (ch in b) counts[ch] = (counts[ch] ?: 0) + 1

        var hit = 0
        for (ch in a) {
            val left = counts[ch] ?: 0
            if (left > 0) {
                hit++
                counts[ch] = left - 1
            }
        }
        return hit.toFloat() / b.length
    }

    /** 2 * LCS / (lenA + lenB)，结果范围 0 ~ 1 */
    private fun lcsRatio(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val dp = IntArray(b.length + 1)
        for (i in 1..a.length) {
            var diagonal = 0
            for (j in 1..b.length) {
                val saved = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) diagonal + 1 else maxOf(dp[j], dp[j - 1])
                diagonal = saved
            }
        }
        return 2f * dp[b.length] / (a.length + b.length)
    }
}
