package com.swipewatch.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 盲区兜底：整页读不到任何文字节点时（小程序广告这类全自绘页面），
 * 用无障碍服务自带的 takeScreenshot 主动截一帧，只留屏幕底部一条交给离线 OCR。
 *
 * 相比原来那套 MediaProjection 的优势：
 * 1. 用的是同一个无障碍授权，不需要每次弹「允许录屏」；
 * 2. 主动截图，不会因为画面静止、VirtualDisplay 不再产帧而永远等不到图；
 * 3. 不需要前台服务和通知栏常驻。
 *
 * 成本上，正常页面节点里本来就有文字，压根走不到这里，等于零。
 */
object StripOcr {

    private const val TAG = "StripOcr"

    /** 条带缩放后的宽度。够 OCR 认字，又比原图小一个数量级 */
    private const val TARGET_WIDTH = 480

    /** 顶部平均亮度低于这个值，才认为是「上下留黑边」的画中画页面 */
    private const val DARK_TOP_LUMA = 15

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "strip-ocr") }

    @Volatile
    private var recognizer: TextRecognizer? = null

    /** takeScreenshot 是 API 30 才有的能力 */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 截当前屏幕，取底部 [bottomRatio] 的区域做识别。
     *
     * 先用「顶部是不是黑的」做一次几乎免费的预筛：短剧正常播放是全屏画面，顶部是亮的；
     * 而小程序广告是画中画形态，上下都是黑边。只有后者才值得付出 OCR 的代价。
     *
     * @return 识别出的文字；截图失败或预筛不通过返回 null（调用方据此保持状态不变）
     */
    suspend fun scan(service: AccessibilityService, bottomRatio: Float): String? {
        if (!isSupported) return null

        val t0 = SystemClock.elapsedRealtime()
        val full = capture(service) ?: return null
        val t1 = SystemClock.elapsedRealtime()

        // 预筛不通过就直接返回，省掉整段 OCR
        if (!isTopDark(full)) {
            full.recycle()
            return null
        }

        val strip = try {
            cropBottom(full, bottomRatio)
        } finally {
            full.recycle()
        } ?: return null
        val t2 = SystemClock.elapsedRealtime()

        return try {
            val text = recognize(strip)
            val t3 = SystemClock.elapsedRealtime()
            Log.i(TAG, "截图 ${t1 - t0}ms · 裁剪 ${t2 - t1}ms · 识别 ${t3 - t2}ms")
            text
        } finally {
            strip.recycle()
        }
    }

    /** 顶部 10% 区域的平均亮度是否很暗。缩到 16x8 再算，开销可以忽略 */
    private fun isTopDark(src: Bitmap): Boolean {
        val sampleW = 16
        val sampleH = 8
        val small = try {
            Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            return true
        }
        try {
            Canvas(small).drawBitmap(
                src,
                Rect(0, 0, src.width, (src.height * 0.10f).toInt().coerceAtLeast(1)),
                Rect(0, 0, sampleW, sampleH),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            var sum = 0
            for (y in 0 until sampleH) {
                for (x in 0 until sampleW) {
                    val c = small.getPixel(x, y)
                    sum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                }
            }
            return sum / (sampleW * sampleH) < DARK_TOP_LUMA
        } catch (e: Exception) {
            return true
        } finally {
            small.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun capture(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    io,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            var copy: Bitmap? = null
                            try {
                                val hardware = Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer,
                                    result.colorSpace
                                )
                                // 硬件位图不能直接用在软件 Canvas 上，先拷成 ARGB_8888
                                copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (e: Exception) {
                                Log.w(TAG, "读取截图失败: ${e.message}")
                            } finally {
                                try {
                                    result.hardwareBuffer.close()
                                } catch (_: Exception) {
                                }
                            }
                            if (cont.isActive) cont.resume(copy)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "无障碍截图失败，errorCode=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "发起截图异常: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    /** 只保留底部一条，并缩到 TARGET_WIDTH 宽 */
    private fun cropBottom(src: Bitmap, bottomRatio: Float): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        val ratio = bottomRatio.coerceIn(0.02f, 0.5f)
        val top = (h * (1f - ratio)).toInt().coerceIn(0, h - 1)
        val sh = h - top
        if (sh <= 1) return null

        val scale = TARGET_WIDTH.toFloat() / w
        val dh = (sh * scale).toInt().coerceAtLeast(1)

        val out = try {
            Bitmap.createBitmap(TARGET_WIDTH, dh, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "条带分配失败")
            return null
        }
        Canvas(out).drawBitmap(
            src,
            Rect(0, top, w, h),
            Rect(0, 0, TARGET_WIDTH, dh),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    private suspend fun recognize(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { cont ->
            val client = recognizer ?: run {
                val options = ChineseTextRecognizerOptions.Builder().build()
                TextRecognition.getClient(options).also { recognizer = it }
            }
            try {
                client.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(io) { text ->
                        if (cont.isActive) cont.resume(text.text)
                    }
                    .addOnFailureListener(io) { e ->
                        Log.w(TAG, "OCR 失败: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "提交 OCR 失败: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
}
