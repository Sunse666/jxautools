package cn.edu.jxau.tools.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import cn.edu.jxau.tools.core.JxauLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

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

        // 0) SAF/document URI 的授权支持持久化收下（photo picker 的临时授权不支持，失败无妨）。
        //    只对「本次读」没意义，但对「进程活着期间反复读」是保险。
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }

        // 1) 先探边界：拿不到宽高 = 读不出一张图，不留半截文件。
        //    失败时把诊断上下文一并带出来（openInputStream 返回 null 不抛异常，
        //    异常消息里没有 URI 和 provider 行为就没法排查「为什么读不到」）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsOk = openImageStream(app, uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } != null && bounds.outWidth > 0 && bounds.outHeight > 0

        // 2) 降采样解码：inSampleSize 是 2 的幂，取「还 >= 上限」的最大值；
        //    整条流式读取链失败时退到缩略图管道（loadThumbnail 走独立的 binder 入口，
        //    与 openFile 家族不同路——MuMu 12 实测它是唯一还可能有响应的通道，
        //    代价是分辨率受限，用作蒙层底图足够）
        val sampled = if (boundsOk) {
            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION_PX)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            openImageStream(app, uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        } else {
            null
        } ?: thumbnailFor(app, uri) ?: throw readFailure(app, uri)

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

    /**
     * 逐条尝试所有读取路径，返回第一条成功的流。
     *
     * 理论上 [ContentResolver.openInputStream] 一条就够；但实测（MuMu 12，2026-09-24）provider
     * 在 openTypedAssetFile 这条 binder 路径上**静默返回 null**（不抛异常、AMS 授权在、元数据可查），
     * 而各路径在 provider 侧走的方法并不相同（openFile / openTypedAssetFile / 裸 MediaStore URI），
     * 全试一遍才能既不亏标准设备、又兼容 ROM 偏差。全失败时返回 null，由调用方带上下文抛错。
     */
    private fun openImageStream(context: Context, uri: Uri): InputStream? {
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.let { return it }
        try {
            resolver.openFileDescriptor(uri, "r")?.let { pfd -> return ParcelFileDescriptor.AutoCloseInputStream(pfd) }
        } catch (_: FileNotFoundException) {
        }
        try {
            resolver.openTypedAssetFileDescriptor(uri, "image/*", null)
                ?.let { return it.createInputStream() }
        } catch (_: FileNotFoundException) {
        }
        // 虚拟文档（MuMu 的 documents 把所有条目标 FLAG_VIRTUAL_DOCUMENT）的正规读法是
        // 带查询到的精确 MIME 再走一次 openTypedAssetFile——通配符 image/* 对虚拟文档
        // 可能匹配不到任何变换
        try {
            val exact = resolver.getType(uri)
            if (exact != null && exact != "image/*") {
                resolver.openTypedAssetFileDescriptor(uri, exact, null)
                    ?.let { return it.createInputStream() }
            }
        } catch (_: FileNotFoundException) {
        }
        // media.documents 的 document id（"image:130"）可以映射成裸 MediaStore URI ——
        // 换一个 provider 入口，有的 ROM 对 documents 门面与裸 URI 的访问检查不一样
        if (uri.authority == "com.android.providers.media.documents") {
            val parts = (uri.lastPathSegment ?: "").split(":")
            if (parts.size == 2) {
                try {
                    val direct = Uri.parse("content://media/external/${parts[0]}s/media/${parts[1]}")
                    resolver.openFileDescriptor(direct, "r")?.let { pfd -> return ParcelFileDescriptor.AutoCloseInputStream(pfd) }
                } catch (_: FileNotFoundException) {
                }
            }
        }
        // 末端绕行（MuMu 12 实测有效路径）：上面四条 provider 流式入口全被 ROM 的
        // media 模块静默挡死时，持有 READ_EXTERNAL_STORAGE（≤Android 12）可以退到
        // `_data` 物理路径直读——FUSE 层按「调用方有没有读权限」放行，
        // 不经过 openFile 那套 URI 授权检查。Android 13+ 没有这条权限，也不会走到这
        // （photo picker 正常）。`_data` 在 documents 门面上查不到（被隐藏），
        // 所以先换算成裸 MediaStore URI 再查。
        directFileFor(context, uri)?.let { f ->
            try {
                return f.inputStream()
            } catch (_: FileNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return null
    }

    /**
     * 缩略图管道兜底：[ContentResolver.loadThumbnail] 与 openFile 家族走不同的 binder 入口
     * （MediaProvider 的 thumbnail 子系统），是流式读取全灭时最后一条可能通的路。
     * 返回 null = 这条也不通。API 29+ 才有 loadThumbnail，更早版本直接放弃。
     */
    private fun thumbnailFor(context: Context, uri: Uri): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        return try {
            context.contentResolver.loadThumbnail(uri, android.util.Size(2048, 2048), null)
        } catch (e: Exception) {
            JxauLog.i("底图缩略图管道失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 从 media URI 解析出图片的物理文件（`_data` 列）。解析不到 / 文件不存在返回 null。
     *
     * 只做查询不做打开——打开留给调用方统一 catch（FileNotFoundException/SecurityException
     * 在不同 ROM 上抛哪个不确定，但都该被当成「这条路也不通」）。
     */
    private fun directFileFor(context: Context, uri: Uri): File? {
        val resolver = context.contentResolver
        val candidates = mutableListOf(uri)
        if (uri.authority == "com.android.providers.media.documents") {
            val parts = (uri.lastPathSegment ?: "").split(":")
            if (parts.size == 2) {
                candidates += Uri.parse("content://media/external/${parts[0]}s/media/${parts[1]}")
            }
        }
        for (u in candidates) {
            try {
                resolver.query(u, arrayOf("_data"), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex("_data")
                        val path = if (idx >= 0) c.getString(idx) else null
                        if (path != null) {
                            val f = File(path)
                            if (f.isFile) return f
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * 「选中的图片读不到」的完整版异常。[ContentResolver.openInputStream] 读不了时**返回 null 而不抛异常**，
     * 光有消息无法区分「授权没拿到 / provider 查得到但打不开 / MIME 异常」。
     * 这里把 URI、读授权、MIME、provider 元数据逐项探一遍再抛；探测自身失败也如实带上，
     * 每一项都可能就是那台设备上的真凶。
     */
    private fun readFailure(context: Context, uri: Uri): IllegalStateException {
        val resolver = context.contentResolver
        val diag = StringBuilder("选中的图片读不到 uri=").append(uri)
        try {
            // ⚠️ 返回值是 PERMISSION_GRANTED(0)/DENIED(-1)，不是 flags 位掩码——别拿它按位与
            val verdict = context.checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            diag.append(if (verdict == PackageManager.PERMISSION_GRANTED) " 授权=有" else " 授权=无")
        } catch (e: Exception) {
            diag.append(" 授权探针失败:").append(e.javaClass.simpleName)
        }
        try {
            diag.append(" MIME=").append(resolver.getType(uri))
        } catch (e: Exception) {
            diag.append(" MIME探针失败:").append(e.javaClass.simpleName)
        }
        // 兜底读权限的状态：末端 _data 直读（directFileFor）只在它为「有」时可能成功，
        // 四条流式路径全败且这里显示「无」时，提示用户授权才是唯一出路
        if (android.os.Build.VERSION.SDK_INT <= 32) {
            try {
                val read = context.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")
                diag.append(if (read == PackageManager.PERMISSION_GRANTED) " READ=有" else " READ=无")
            } catch (_: Exception) {
            }
        }
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    diag.append(" 列=").append(c.columnNames.joinToString(","))
                    diag.append(" 值=").append((0 until c.columnCount).joinToString(",") { c.getString(it) ?: "null" })
                } else {
                    diag.append(" query=空结果")
                }
            } ?: diag.append(" query=null")
        } catch (e: Exception) {
            diag.append(" query失败:").append(e.javaClass.simpleName).append(':').append(e.message)
        }
        return IllegalStateException(diag.toString())
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
