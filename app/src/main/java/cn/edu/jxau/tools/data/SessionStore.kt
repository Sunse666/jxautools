package cn.edu.jxau.tools.data

import android.content.Context
import android.util.Base64
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.Channel
import cn.edu.jxau.tools.data.model.JxauSession

/**
 * 会话与凭据的本地持久化（SharedPreferences）。
 *
 * ⚠️ 安全现状（诚实标注）：密码目前用**固定密钥 XOR + Base64** 混淆后落盘，
 * 这**不是加密**，只是避免明文直读。应用包一旦被反编译或设备被 root，凭据可被还原。
 * 后续计划改用 Android Keystore（生成不可导出的 AES 密钥加密凭据），
 * 那一步做完之前，界面上会明示这一点。
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 会话 ----------

    fun loadSession(): JxauSession? {
        val uuid = prefs.getString(KEY_UUID, null).orEmpty()
        val cookie = prefs.getString(KEY_COOKIE, null).orEmpty()
        if (uuid.isBlank() || cookie.isBlank()) return null
        return JxauSession(
            channel = runCatching {
                Channel.valueOf(prefs.getString(KEY_SESSION_CHANNEL, null) ?: Channel.DIRECT.name)
            }.getOrDefault(Channel.DIRECT),
            uuid = uuid,
            cookie = cookie,
            tgt = prefs.getString(KEY_TGT, null).orEmpty(),
            account = prefs.getString(KEY_ACCOUNT, null).orEmpty(),
            savedAt = prefs.getLong(KEY_SAVED_AT, 0L),
        )
    }

    fun saveSession(session: JxauSession) {
        prefs.edit()
            .putString(KEY_UUID, session.uuid)
            .putString(KEY_COOKIE, session.cookie)
            .putString(KEY_TGT, session.tgt)
            .putString(KEY_SESSION_CHANNEL, session.channel.name)
            .putString(KEY_ACCOUNT, session.account)
            .putLong(KEY_SAVED_AT, session.savedAt)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_UUID)
            .remove(KEY_COOKIE)
            .remove(KEY_TGT)
            .remove(KEY_SESSION_CHANNEL)
            .remove(KEY_SAVED_AT)
            .apply()
        JxauLog.i("已清除本地会话")
    }

    /**
     * 待用 TGT：CAS 登录成功但会话兑换失败时留下的"半个成果"。
     *
     * 有它就能在下次启动时**免验证码**直接续期（TGT 是长期票据），
     * 所以哪怕兑换失败也不该丢弃——否则用户要为了同一个 TGT 重新输一次验证码。
     */
    var pendingTgt: String
        get() = prefs.getString(KEY_PENDING_TGT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PENDING_TGT, value).apply()

    // ---------- 账号信息 ----------

    var account: String
        get() = prefs.getString(KEY_ACCOUNT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    var rememberPassword: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, true)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER, value).apply()

    var password: String
        get() = deobfuscate(prefs.getString(KEY_PASSWORD, "").orEmpty())
        set(value) = prefs.edit().putString(KEY_PASSWORD, obfuscate(value)).apply()

    var channelChoice: Channel
        get() = runCatching {
            Channel.valueOf(prefs.getString(KEY_CHANNEL, null) ?: Channel.AUTO.name)
        }.getOrDefault(Channel.AUTO)
        set(value) = prefs.edit().putString(KEY_CHANNEL, value.name).apply()

    /** 上一次实际使用的通道（探测结果），用于下次登录时给用户一个直观预期 */
    var lastEffectiveChannel: Channel
        get() = runCatching {
            Channel.valueOf(prefs.getString(KEY_EFFECTIVE_CHANNEL, null) ?: Channel.DIRECT.name)
        }.getOrDefault(Channel.DIRECT)
        set(value) = prefs.edit().putString(KEY_EFFECTIVE_CHANNEL, value.name).apply()

    private fun obfuscate(plain: String): String {
        if (plain.isEmpty()) return ""
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val mixed = ByteArray(bytes.size) { (bytes[it].toInt() xor OBFUSCATION_KEY[it % OBFUSCATION_KEY.size].toInt()).toByte() }
        return Base64.encodeToString(mixed, Base64.NO_WRAP)
    }

    private fun deobfuscate(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return runCatching {
            val mixed = Base64.decode(encoded, Base64.NO_WRAP)
            val bytes = ByteArray(mixed.size) { (mixed[it].toInt() xor OBFUSCATION_KEY[it % OBFUSCATION_KEY.size].toInt()).toByte() }
            String(bytes, Charsets.UTF_8)
        }.getOrDefault("")
    }

    private companion object {
        const val PREFS_NAME = "jxau_session"
        const val KEY_UUID = "uuid"
        const val KEY_COOKIE = "cookie"
        const val KEY_TGT = "tgt"
        const val KEY_SESSION_CHANNEL = "session_channel"
        const val KEY_ACCOUNT = "account"
        const val KEY_REMEMBER = "remember_password"
        const val KEY_PASSWORD = "password_obfuscated"
        const val KEY_CHANNEL = "channel_choice"
        const val KEY_EFFECTIVE_CHANNEL = "effective_channel"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_PENDING_TGT = "pending_tgt"

        /** 仅用于避免明文直读，不构成安全边界 */
        val OBFUSCATION_KEY = "jxau-tools-local-obfuscation-v1".toByteArray(Charsets.UTF_8)
    }
}
