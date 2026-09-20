package cn.edu.jxau.tools.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 双通道日志：同时写 logcat（tag = [TAG]）与 UI 列表。
 *
 * logcat 通道是为了让 adb 能直接读到每一步网络行为——排查"请求到底发没发"比猜 UI 快得多：
 *   adb shell "logcat -d -s JXAU_NET | tail -40"
 */
object JxauLog {

    /** adb 过滤用的 tag，改动需同步 mumu-ui-driven-testing 技能里的命令 */
    const val TAG = "JXAU_NET"

    private const val MAX_LINES = 400
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun i(message: String) = emit("I", message, null)

    fun w(message: String, error: Throwable? = null) = emit("W", message, error)

    fun e(message: String, error: Throwable? = null) = emit("E", message, error)

    fun clear() {
        _lines.value = emptyList()
    }

    private fun emit(level: String, message: String, error: Throwable?) {
        val suffix = error?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        val plain = message + suffix
        when (level) {
            "W" -> Log.w(TAG, plain)
            "E" -> Log.e(TAG, plain)
            else -> Log.i(TAG, plain)
        }
        val stamp = LocalTime.now().format(TIME_FMT)
        _lines.value = (_lines.value + "$stamp [$level] $plain").takeLast(MAX_LINES)
    }
}
