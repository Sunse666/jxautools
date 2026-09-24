package cn.edu.jxau.tools.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import cn.edu.jxau.tools.core.JxauLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 课表底图的文件管道：把相册里选的一张图，变成应用私有目录里一张降采样过的 JPEG。
 *
 * ## 为什么必须拷贝，而不是存 photo picker 的 URI
 * `PickVisualMedia` 给的读授权是**临时的**（进程结束即失效）。把 URI 直接存进偏好，
 * 表现是「选完当天好看，第二天冷启动底图凭空消失」——能编译、第一次还好用、
 * 不崩不报错，是最阴的那类静默失效。所以选完立刻拷贝进 `filesDir`，
 * 偏好里只存文件路径，读取不依赖任何授权。
 *
 * ## 为什么降采样 + 重压缩
 * 相机原图常见 4000×3000（ARGB_8888 ≈ 48MB），decode 进内存再做背景，掉帧是必然的。
 * 显示端是整页铺满 + `ContentScale.Crop`，超过屏幕分辨率的部分不产生任何清晰度收益。
 * 上限 [MAX_DIMENSION_PX]（1440）覆盖主流机型屏高，重压缩成 JPEG 统一格式控体积。
 * 不用 RGB_565 省内存：图上要叠半透明蒙层，565 的色带会在过渡处被放大。
 *
 * ## 写入顺序（谁先谁后是保命问题）
 * **写新文件 → 改偏好（`SettingsRepository.setTimetableBg`）→ 删旧文件。**
 * 任意调换都会在某一步崩溃时丢图或留下悬空路径。临时文件 + rename 保证
 * 「写一半崩溃」只会得到一个被丢弃的 `.tmp`，而不是损坏的正式文件。
 */
object TimetableBgStore {

    /** filesDir 里的正式文件名（单文件覆盖式，不做图片库——见实施大纲 §0） */
    const val FILE_NAME = "timetable_bg.jpg"

    /** 降采样上限（最长边，px） */
    const val MAX_DIMENSION_PX = 1440

    /** JPEG 压缩质量 */
    const val JPEG_QUALITY = 85

    /** 正式文件的位置（filesDir：应用私有、随卸载删除、不需要任何存储权限） */
    fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /** 偏好里存的路径现在还读得到吗。null 恒为 false（= 未启用），由调用方区分语义 */
    fun exists(context: Context, path: String?): Boolean =
        path != null && File(path).isFile

    /**
     * 从相册 Uri 落盘一张新底图，返回新文件绝对路径。全程 IO 线程。
     *
     * @throws IllegalStateException Uri 流读不到 / 解码失败（调用方 catch 后给用户明确反馈）
     */
    suspend fun saveFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val resolver = app.contentResolver
        val finalFile = file(app)
        val tmpFile = File(app.filesDir, "$FILE_NAME.tmp")

        // 1) 先探边界：拿不到宽高 = 读不出一张图，直接失败，不留半截文件
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalStateException("选中的图片读不到（授权或来源已失效）")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("选中的文件不是一张可解码的图片")
        }

        // 2) 降采样解码：inSampleSize 是 2 的幂，取「还 >= 上限」的最大值
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION_PX)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val sampled = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: throw IllegalStateException("图片解码失败（读取中断）")

        // 3) inSampleSize 是粗筛（2 的幂），仍超上限时再精确缩一次（保持长宽比）
        val bitmap = downscaleTo(sampled, MAX_DIMENSION_PX)

        // 4) 临时文件 + rename 原子替换
        try {
            tmpFile.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw IllegalStateException("图片压缩失败")
                }
            }
            if (!tmpFile.renameTo(finalFile)) {
                // Windows 模拟器 / 个别文件系统上 rename 不覆盖已有文件，退化为删后改名的两步
                finalFile.delete()
                if (!tmpFile.renameTo(finalFile)) {
                    throw IllegalStateException("底图文件替换失败")
                }
            }
        } finally {
            bitmap.recycle()
            tmpFile.delete()
        }
        finalFile.absolutePath
    }

    /** 删旧底图文件。失败只记日志不抛——偏好已改，文件残留顶多占几兆，不该让用户看见报错 */
    fun deleteQuietly(path: String?) {
        if (path == null) return
        try {
            if (!File(path).delete() && File(path).isFile) {
                JxauLog.e("底图旧文件删除失败：$path")
            }
        } catch (e: Exception) {
            JxauLog.e("底图旧文件删除异常：${e.message}")
        }
    }

    /**
     * 为显示解码：按 [MAX_DIMENSION_PX] 上限降采样，失败返回 null（渲染端按「无底图」兜底）。
     *
     * 与 [saveFromUri] 分开：落盘是一次性动作（失败要给用户反馈），
     * 读取是每次冷启动都走的路径（失败只能静默降级，不能崩主界面）。
     * 读不到/解不开时**不清偏好**——可能只是备份恢复丢文件、用户清理工具误删，
     * 自动清会把「临时读不到」升级成「设置凭空消失」；重选一张图即可自愈。
     */
    fun decodeForDisplay(context: Context, path: String): Bitmap? {
        val file = File(path)
        if (!file.isFile) {
            JxauLog.e("课表底图文件丢失：$path（按无底图显示，设置保留）")
            return null
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION_PX)
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            JxauLog.e("课表底图解码失败：${e.message}（按无底图显示，设置保留）")
            null
        }
    }

    /**
     * 「还 >= [max]」的最大 2 的幂采样。纯函数，期望值由 Python 对账脚本独立重算。
     *
     * 源图本来就不超过上限时返回 1（不放大——inSampleSize 只会缩不会放大，这是系统语义）。
     */
    internal fun sampleSizeFor(width: Int, height: Int, max: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= max) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    /** 降采样后仍超上限时做精确等比缩小。源图不超上限时原样返回（不放大） */
    private fun downscaleTo(bitmap: Bitmap, max: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= max) return bitmap
        val scale = max.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
