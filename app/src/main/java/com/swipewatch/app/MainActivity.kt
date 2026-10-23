package com.swipewatch.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipewatch.app.ui.SwipeWatchTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SwipeWatchTheme {
                MainScreen(
                    onStart = ::startDetection,
                    onStop = ::stopDetection,
                    onOpenAccessibility = ::openAccessibilitySettings
                )
            }
        }
    }

    private fun startDetection() {
        if (!SwipeAccessibilityService.isEnabledInSettings(this)) {
            toast("请先开启「手势服务」，它是读取文字和执行滑动的前提")
            openAccessibilitySettings()
            return
        }
        startNodeLoop()
    }

    /** 全部能力都挂在无障碍服务上：读节点、截图、滑动手势，没有任何授权弹窗 */
    private fun startNodeLoop() {
        val prefs = Prefs(this)
        prefs.nodeLoopEnabled = true
        AppState.reset()
        Detector.reset()
        SwipeAccessibilityService.setNodeLoopEnabled(true)
        if (!AppState.nodeLoopRunning.value) {
            // 服务实例还没连上时，onServiceConnected 会自动恢复
            toast("已开启检测，等待无障碍服务就绪")
        } else {
            toast("已开始检测")
        }
    }

    private fun stopDetection() {
        Prefs(this).nodeLoopEnabled = false
        SwipeAccessibilityService.setNodeLoopEnabled(false)
        toast("已停止检测")
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            toast("无法打开无障碍设置：${e.message}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    val nodeRunning by AppState.nodeLoopRunning.collectAsStateWithLifecycle()
    val triggerCount by AppState.triggerCount.collectAsStateWithLifecycle()
    val logs by AppState.logs.collectAsStateWithLifecycle()

    val running = nodeRunning

    var accessibilityOn by remember {
        mutableStateOf(SwipeAccessibilityService.isEnabledInSettings(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = SwipeAccessibilityService.isEnabledInSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var blindOcr by remember { mutableStateOf(prefs.blindOcr) }
    var intervalMs by remember { mutableStateOf(prefs.intervalMs) }
    var ocrIntervalMs by remember { mutableStateOf(prefs.ocrIntervalMs) }
    var bottomRatio by remember { mutableStateOf(prefs.bottomRatio) }
    var cooldownMs by remember { mutableStateOf(prefs.cooldownMs) }
    var threshold by remember { mutableStateOf(prefs.threshold) }
    var keywords by remember { mutableStateOf(prefs.keywords) }
    var swipeDuration by remember { mutableStateOf(prefs.swipeDurationMs) }
    var swipeDistance by remember { mutableStateOf(prefs.swipeDistanceRatio) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("上滑助手", fontWeight = FontWeight.SemiBold)
                        Text(
                            "读界面文字 · 自动上滑 · 不录屏",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusCard(
                accessibilityOn = accessibilityOn,
                nodeRunning = nodeRunning,
                triggerCount = triggerCount,
                onOpenAccessibility = onOpenAccessibility
            )

            Button(
                onClick = {
                    if (running) onStop() else onStart()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = if (running) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(
                    if (running) "停止检测" else "开始检测",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "自绘页面兜底",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                if (blindOcr) "整页读不到文字时（小程序广告这类全自绘页面），截底部一条做识别。不需要录屏授权"
                                else "关闭后只读界面文字，这类广告页会检测不到",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = blindOcr,
                            onCheckedChange = {
                                blindOcr = it
                                prefs.blindOcr = it
                            }
                        )
                    }
                }
            }

            SettingCard(
                title = "检测参数",
                onReset = {
                    prefs.intervalMs = Prefs.DEFAULT_INTERVAL
                    prefs.ocrIntervalMs = Prefs.DEFAULT_OCR_INTERVAL
                    prefs.bottomRatio = Prefs.DEFAULT_BOTTOM_RATIO
                    prefs.cooldownMs = Prefs.DEFAULT_COOLDOWN
                    prefs.threshold = Prefs.DEFAULT_THRESHOLD
                    prefs.swipeDurationMs = Prefs.DEFAULT_SWIPE_DURATION
                    prefs.swipeDistanceRatio = Prefs.DEFAULT_SWIPE_DISTANCE
                    intervalMs = Prefs.DEFAULT_INTERVAL
                    ocrIntervalMs = Prefs.DEFAULT_OCR_INTERVAL
                    bottomRatio = Prefs.DEFAULT_BOTTOM_RATIO
                    cooldownMs = Prefs.DEFAULT_COOLDOWN
                    threshold = Prefs.DEFAULT_THRESHOLD
                    swipeDuration = Prefs.DEFAULT_SWIPE_DURATION
                    swipeDistance = Prefs.DEFAULT_SWIPE_DISTANCE
                }
            ) {
                SettingSlider(
                    title = "检测频率",
                    valueText = "$intervalMs ms",
                    value = intervalMs.toFloat(),
                    range = 150f..2000f,
                    onChange = {
                        val snapped = ((it / 50f).roundToInt() * 50).toLong().coerceIn(150, 2000)
                        intervalMs = snapped
                        prefs.intervalMs = snapped
                    },
                    hint = "读一次界面文字只要 1~3ms，可以放心设密"
                )

                if (blindOcr) {
                    SettingSlider(
                        title = "兜底识别间隔",
                        valueText = "$ocrIntervalMs ms",
                        value = ocrIntervalMs.toFloat(),
                        range = 500f..5000f,
                        onChange = {
                            val snapped = ((it / 100f).roundToInt() * 100).toLong().coerceIn(500, 5000)
                            ocrIntervalMs = snapped
                            prefs.ocrIntervalMs = snapped
                        },
                        hint = "只在自绘页面上生效。一次截图加识别约 80~150ms，别设太密"
                    )

                    SettingSlider(
                        title = "兜底截图范围",
                        valueText = "屏幕底部 ${(bottomRatio * 100).roundToInt()}%",
                        value = bottomRatio,
                        range = 0.05f..0.40f,
                        onChange = {
                            bottomRatio = it
                            prefs.bottomRatio = it
                        },
                        hint = "红果的提示在底部 5% 左右，12% 已经能完整覆盖并留出余量"
                    )
                }

                SettingSlider(
                    title = "文字匹配阈值",
                    valueText = "${(threshold * 100).roundToInt()}%",
                    value = threshold,
                    range = 0.50f..1.0f,
                    onChange = {
                        threshold = it
                        prefs.threshold = it
                    },
                    hint = "界面文字模式下几乎不会认错字，可以调高"
                )

                SettingSlider(
                    title = "触发后静默",
                    valueText = formatCooldown(cooldownMs),
                    value = cooldownToSlider(cooldownMs),
                    range = 0f..1f,
                    onChange = {
                        val ms = sliderToCooldown(it)
                        if (ms != cooldownMs) {
                            cooldownMs = ms
                            prefs.cooldownMs = ms
                        }
                    },
                    hint = "上滑后这段时间内完全不检测（1 秒 ~ 5 分钟）。" +
                        "即使提示还挂在屏幕上，静默结束后也不会立刻再滑——" +
                        "要等它消失再出现才算新的一次"
                )

                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                SettingSlider(
                    title = "滑动时长",
                    valueText = "$swipeDuration ms",
                    value = swipeDuration.toFloat(),
                    range = 150f..1000f,
                    onChange = {
                        val snapped = ((it / 50f).roundToInt() * 50).toLong().coerceIn(150, 1000)
                        swipeDuration = snapped
                        prefs.swipeDurationMs = snapped
                    }
                )

                SettingSlider(
                    title = "滑动距离",
                    valueText = "屏幕高度 ${(swipeDistance * 100).roundToInt()}%",
                    value = swipeDistance,
                    range = 0.30f..0.90f,
                    onChange = {
                        swipeDistance = it
                        prefs.swipeDistanceRatio = it
                    }
                )
            }

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "触发关键词",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keywords,
                        onValueChange = {
                            keywords = it
                            prefs.keywords = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("关键词") },
                        placeholder = { Text("上滑继续观看") },
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "多个关键词用逗号分隔。只写前缀即可——红果实际显示的是「上滑继续观看短剧」，写「上滑继续观看」就能命中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DebugCard(logs = logs, running = running)

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "使用步骤",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    TipLine("1", "在系统设置里开启「手势服务」（无障碍），读文字、截图和滑动都靠它")
                    TipLine("2", "回到本页点「开始检测」，全程不需要任何授权弹窗")
                    TipLine("3", "切到红果刷短剧，底部出现提示时自动上滑一次")
                    TipLine("4", "小程序广告这类整页自绘的界面读不到文字，会自动改截底部一条来识别")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "不联网。节点能读到文字时不截图，只在自绘页面上才截底部一条。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    accessibilityOn: Boolean,
    nodeRunning: Boolean,
    triggerCount: Int,
    onOpenAccessibility: () -> Unit
) {
    val statusText = if (nodeRunning) "运行中 · 已上滑 $triggerCount 次" else "未运行"

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusRow(
                ok = accessibilityOn,
                title = "手势服务",
                subtitle = if (accessibilityOn) "已开启，可读文字、可截图、可执行滑动" else "未开启，无法读取文字也无法滑动",
                actionText = if (accessibilityOn) null else "去开启",
                onAction = onOpenAccessibility
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusRow(
                ok = nodeRunning,
                title = "检测状态",
                subtitle = statusText,
                actionText = null,
                onAction = null
            )
        }
    }
}

@Composable
private fun StatusRow(
    ok: Boolean,
    title: String,
    subtitle: String,
    actionText: String?,
    onAction: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(
                    if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    onReset: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onReset) { Text("恢复默认") }
            }
            content()
        }
    }
}

/** 静默时长：1 秒 ~ 5 分钟 */
private const val COOLDOWN_MIN_MS = 1_000L
private const val COOLDOWN_MAX_MS = 300_000L

private val COOLDOWN_MIN_LOG = ln(COOLDOWN_MIN_MS.toFloat())
private val COOLDOWN_MAX_LOG = ln(COOLDOWN_MAX_MS.toFloat())

/**
 * 冷却用对数刻度：滑杆左半段覆盖 1~10 秒（常用区间，500ms 一档），
 * 右半段才进到分钟，5 分钟也够得着。
 */
private fun cooldownToSlider(ms: Long): Float {
    val clamped = ms.coerceIn(COOLDOWN_MIN_MS, COOLDOWN_MAX_MS).toFloat()
    return (ln(clamped) - COOLDOWN_MIN_LOG) / (COOLDOWN_MAX_LOG - COOLDOWN_MIN_LOG)
}

private fun sliderToCooldown(t: Float): Long {
    val ratio = t.coerceIn(0f, 1f)
    val raw = exp(COOLDOWN_MIN_LOG + ratio * (COOLDOWN_MAX_LOG - COOLDOWN_MIN_LOG))
    val step = when {
        raw <= 10_000f -> 500f
        raw <= 60_000f -> 1_000f
        else -> 5_000f
    }
    val snapped = (raw / step).roundToInt() * step.toLong()
    return snapped.coerceIn(COOLDOWN_MIN_MS, COOLDOWN_MAX_MS)
}

private fun formatCooldown(ms: Long): String = when {
    ms < 60_000L -> String.format(Locale.US, "%.1f s", ms / 1000f)
    ms % 60_000L == 0L -> "${ms / 60_000L} 分钟"
    else -> "${ms / 60_000L} 分 ${(ms % 60_000L) / 1000L} 秒"
}

@Composable
private fun SettingSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    hint: String? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DebugCard(
    logs: List<AppState.LogEntry>,
    running: Boolean
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "识别日志",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                Text(
                    if (running) "节点模式不截图；只在整页读不到文字的页面上，才截底部一条做识别" else "尚未开始检测",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))

            if (logs.isEmpty()) {
                Text(
                    "识别记录会显示在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                logs.take(6).forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            formatter.format(Date(entry.time)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(58.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "[${entry.source.label}] ${entry.recognizedText}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                color = if (entry.hit) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                buildString {
                                    append("匹配 ${(entry.score * 100).roundToInt()}%")
                                    append(" · ${entry.costMs}ms")
                                    if (entry.hit) {
                                        append(
                                            when {
                                                entry.triggered -> " · 已上滑"
                                                entry.note != null -> " · ${entry.note}"
                                                else -> " · 命中但手势未执行"
                                            }
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TipLine(index: String, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            index,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(18.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
