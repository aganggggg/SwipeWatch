package com.swipewatch.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 双职责：
 * 1. 执行手 —— dispatchGesture 注入「底部向上滑」（不 root 的唯一合法途径）
 * 2. 眼睛（默认模式）—— 直接读窗口节点树里的文字，完全不需要截屏
 *
 * 节点探测用的是 findAccessibilityNodeInfosByText：
 * 匹配在系统侧完成，一次 IPC 就够，不需要把整棵树搬到本地遍历。
 */
class SwipeAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    /** 上一次盲区截图的时间，用来单独节流 */
    @Volatile
    private var lastBlindScanAt = 0L

    /** 上一次打印节点统计的时间 */
    @Volatile
    private var lastStatAt = 0L

    companion object {
        private const val TAG = "SwipeGesture"

        /** 一轮最多收集这么多段可见文字，正常页面十几段就够了 */
        private const val MAX_VISIBLE_TEXT_NODES = 24

        @Volatile
        private var instance: SwipeAccessibilityService? = null

        /** 当前前台应用的包名，用来避免在本应用界面内自我触发 */
        @Volatile
        var foregroundPackage: String? = null
            private set

        /** 手势能力是否就绪（服务已被系统连接） */
        fun isReady(): Boolean = instance != null

        fun isAppInForeground(packageName: String): Boolean =
            foregroundPackage?.equals(packageName, ignoreCase = true) == true

        /** 从外部触发一次上滑，返回是否成功下发 */
        fun swipeUp(startYRatio: Float, distanceRatio: Float, durationMs: Long): Boolean {
            val service = instance ?: return false
            return service.performSwipeUp(startYRatio, distanceRatio, durationMs)
        }

        /** 节点循环的启停，由界面控制 */
        fun setNodeLoopEnabled(enabled: Boolean) {
            val service = instance ?: return
            if (enabled) service.startNodeLoop() else service.stopNodeLoop()
        }

        /** 检查无障碍服务是否已在系统设置里被用户开启 */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, SwipeAccessibilityService::class.java)
            val enabledFlag = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
            if (enabledFlag != 1) return false

            val enabledList = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            return enabledList.split(':').any { item ->
                item.equals(expected.flattenToString(), ignoreCase = true) ||
                    item.equals(expected.flattenToShortString(), ignoreCase = true)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接，手势与节点读取就绪")

        // 系统重启或服务被回收后，自动恢复上次的运行状态
        if (Prefs(this).nodeLoopEnabled) {
            startNodeLoop()
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopNodeLoop()
        instance = null
        Log.i(TAG, "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopNodeLoop()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { foregroundPackage = it.toString() }
        }
    }

    override fun onInterrupt() = Unit

    // ---------------- 节点探测 ----------------

    /**
     * 把所有窗口里「用户看得见」的文字收集起来，拼成一段交给 Detector 做模糊匹配。
     *
     * 不用 findAccessibilityNodeInfosByText 是因为它只做字面子串匹配：
     * 广告位的文案是「3秒后可继续上滑观看短剧」，字符和关键词「上滑继续」一样，
     * 只是语序反了，系统侧查询直接返回空。
     *
     * 另外必须遍历所有窗口：广告盖在播放页上面时，rootInActiveWindow 拿到的是底下的播放页。
     */
    private fun scanWindow(): Detector.Scan {
        val windowList = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "取窗口列表失败: ${e.message}")
            return Detector.Scan.Unknown
        }

        if (windowList.isEmpty()) return Detector.Scan.Unknown

        val sb = StringBuilder(256)
        val collected = IntArray(1)

        for (window in windowList) {
            // 只看应用窗口。状态栏、导航栏也会带一堆文字（「中国联通，信号满格」「电池」），
            // 会把收集预算吃光，而且永远不可能命中关键词
            val type = try {
                window.type
            } catch (e: Exception) {
                -1
            }
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION) continue

            val root = try {
                window.root
            } catch (e: Exception) {
                null
            } ?: continue

            // 跳过自己的窗口：设置页里写满了关键词说明文字，不挡掉会对自己滑一下
            val ownerPackage = try {
                root.packageName?.toString()
            } catch (e: Exception) {
                null
            }
            if (ownerPackage == packageName) continue

            try {
                collectVisibleText(root, 0, sb, collected)
            } catch (e: Exception) {
                Log.w(TAG, "读取窗口文字失败: ${e.message}")
            }
        }

        if (SystemClock.elapsedRealtime() - lastStatAt >= 3000L) {
            lastStatAt = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "窗口 ${windowList.size} 个 · ${collected[0]} 段文字：${sb.toString().replace('\n', ' ').take(80)}"
            )
        }

        return Detector.Scan.Text(sb.toString())
    }

    /** 收集可见文字，最多 [MAX_VISIBLE_TEXT_NODES] 段就停（正常页面很快到顶） */
    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        depth: Int,
        sb: StringBuilder,
        collected: IntArray
    ) {
        if (depth > 40 || collected[0] >= MAX_VISIBLE_TEXT_NODES) return

        // ViewPager / RecyclerView 会把相邻页面缓存在树里，
        // 那些节点带文字但用户看不见，不能当证据
        val visible = try {
            node.isVisibleToUser
        } catch (e: Exception) {
            false
        }

        if (visible) {
            val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (label != null) {
                collected[0]++
                sb.append(label).append('\n')
            }
        }

        val count = try {
            node.childCount
        } catch (e: Exception) {
            return
        }
        for (i in 0 until count) {
            if (collected[0] >= MAX_VISIBLE_TEXT_NODES) return
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            collectVisibleText(child, depth + 1, sb, collected)
        }
    }

    // ---------------- 节点轮询循环 ----------------

    private fun startNodeLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            AppState.nodeLoopRunning.value = true
            Log.i(TAG, "节点循环启动")
            try {
                while (isActive) {
                    val prefs = Prefs(this@SwipeAccessibilityService)

                    if (!prefs.nodeLoopEnabled) break

                    if (isAppInForeground(packageName)) {
                        delay(prefs.intervalMs)
                        continue
                    }

                    // 冷却/退避期间绝对不能停扫。
                    // 「上一次滑动到底有没有生效」只能靠一轮轮的观察来验证：停掉扫描，
                    // 提示早就消失了也看不见，闩锁会一直挂着，表现就是「过了 5 秒也不滑」。
                    // 该不该滑由 Detector 内部判断，这里只负责持续供数据。
                    val startedAt = SystemClock.elapsedRealtime()
                    val scan = scanWindow()
                    val nodeCost = SystemClock.elapsedRealtime() - startedAt

                    // 先交给 Detector 判定。命中后由它自己决定「滑 / 退避 / 放弃」，
                    // 因为「滑了到底有没有生效」只能靠后续每一轮观察来验证
                    val before = AppState.triggerCount.value
                    Detector.accept(scan, prefs, AppState.Source.NODE, nodeCost)
                    val triggeredByNode = AppState.triggerCount.value != before

                    // 节点这一轮没滑成，且整页一段文字都读不到（自绘页面），再补一次截图：
                    // 小程序广告的提示是画在视频画面里的，节点里根本没有。
                    // 正常页面能读到十几段文字，不会走到这里，等于零成本
                    val noText = scan is Detector.Scan.Text && scan.content.isBlank()
                    if (!triggeredByNode && noText && prefs.blindOcr && StripOcr.isSupported) {
                        runBlindScan(prefs)
                    }

                    delay(prefs.intervalMs)
                }
            } finally {
                AppState.nodeLoopRunning.value = false
                Log.i(TAG, "节点循环结束")
            }
        }
    }

    private fun stopNodeLoop() {
        loopJob?.cancel()
        loopJob = null
        lastBlindScanAt = 0L
        AppState.nodeLoopRunning.value = false
    }

    /**
     * 盲区兜底：截图 + 只识别底部一条。
     * 单独节流，因为一次截图加 OCR 比读节点贵两个数量级，不能跟着 400ms 的节奏跑。
     */
    private suspend fun runBlindScan(prefs: Prefs) {
        val sinceLast = SystemClock.elapsedRealtime() - lastBlindScanAt
        if (lastBlindScanAt != 0L && sinceLast < prefs.ocrIntervalMs) return

        lastBlindScanAt = SystemClock.elapsedRealtime()
        val startedAt = SystemClock.elapsedRealtime()

        val text = StripOcr.scan(this@SwipeAccessibilityService, prefs.bottomRatio) ?: return

        val cost = SystemClock.elapsedRealtime() - startedAt
        if (text.isNotBlank()) {
            Log.i(TAG, "盲区扫描 ${cost}ms：${text.replace('\n', ' ').take(40)}")
        }
        Detector.accept(Detector.Scan.Text(text), prefs, AppState.Source.OCR, cost)
    }

    // ---------------- 手势 ----------------

    private fun performSwipeUp(startYRatio: Float, distanceRatio: Float, durationMs: Long): Boolean {
        val (width, height) = screenSize()
        if (width <= 0 || height <= 0) return false

        // 横向位置轻微随机，避免每次都是同一条完美竖线
        val x = width * (0.42f + Random.nextFloat() * 0.16f)
        val startY = height * startYRatio
        val endY = (startY - height * distanceRatio).coerceAtLeast(height * 0.04f)
        if (endY >= startY) return false

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(50L, 3000L)
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "上滑手势下发失败: ${e.message}")
            false
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }
}
