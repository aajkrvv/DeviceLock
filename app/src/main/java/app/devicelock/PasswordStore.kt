package app.devicelock

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface VerifyResult {
    object Ok : VerifyResult
    data class Wrong(val failures: Int) : VerifyResult
    data class LockedOut(val untilMillis: Long) : VerifyResult
}

/**
 * 비밀번호는 해시로만 저장한다. 복구 기능은 의도적으로 없다.
 * 5회 연속 실패하면 대기 시간이 생기고, 실패가 이어질수록 길어진다(최대 1시간).
 */
class PasswordStore(ctx: Context) {

    private val prefs = ctx.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun isSet(): Boolean = prefs.contains("hash")

    fun set(password: String) {
        check(!isSet()) { "비밀번호가 이미 설정되어 있습니다" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(password, salt), Base64.NO_WRAP))
            .commit()
    }

    fun verify(password: String): VerifyResult {
        val now = System.currentTimeMillis()
        val lockedUntil = prefs.getLong("lockedUntil", 0L)
        if (now < lockedUntil) return VerifyResult.LockedOut(lockedUntil)

        val salt = Base64.decode(prefs.getString("salt", "") ?: "", Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("hash", "") ?: "", Base64.NO_WRAP)
        if (MessageDigest.isEqual(expected, hash(password, salt))) {
            prefs.edit().putInt("fails", 0).putLong("lockedUntil", 0L).commit()
            return VerifyResult.Ok
        }

        val fails = prefs.getInt("fails", 0) + 1
        val edit = prefs.edit().putInt("fails", fails)
        if (fails >= 5) {
            val wait = minOf(30_000L * (1L shl minOf(fails - 5, 7)), 3_600_000L)
            edit.putLong("lockedUntil", now + wait)
        }
        edit.commit()
        return VerifyResult.Wrong(fails)
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 120_000, 256))
            .encoded
}

/** 비밀번호 입력 후 5분 동안만 앱 안에서 규칙을 바꿀 수 있다. 메모리에만 유지된다. */
object Session {
    private var unlockedUntil = 0L

    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntil
    fun unlock() { unlockedUntil = System.currentTimeMillis() + 5 * 60_000L }
    fun lock() { unlockedUntil = 0L }
}
