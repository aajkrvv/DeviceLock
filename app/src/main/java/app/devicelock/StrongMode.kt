package app.devicelock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * 강력 모드의 현재 단계.
 * - Off: 강력 모드가 꺼져 있음
 * - Idle: 켜져 있고, 규칙을 바꿀 수 없음(비밀번호를 입력하면 대기가 시작된다)
 * - Waiting: 24시간 대기 중. 이 동안 일반 앱의 인터넷 연결을 VPN lockdown 으로 차단한다(일부 시스템 앱은 예외 가능).
 * - Ready: 대기가 끝나 규칙을 바꿀 수 있는 구간(제한 시간 있음)
 */
sealed interface StrongPhase {
    object Off : StrongPhase
    object Idle : StrongPhase
    data class Waiting(val remainingMs: Long) : StrongPhase
    data class Ready(val remainingMs: Long) : StrongPhase
}

/**
 * 강력 모드.
 * - 한 번 켜면 끄는 코드가 없다(공장 초기화 제외).
 * - 켜진 뒤에는 규칙 변경·앱 제거 전에 "비밀번호 입력 → 24시간 네트워크 차단 대기"를 거쳐야 한다.
 * - 대기는 중간에 중단할 수 있지만, 중단하면 처음부터 다시 24시간을 기다려야 한다.
 * - 대기가 끝나면 EDIT_MS 동안만 편집할 수 있고, 그 뒤에는 다시 대기해야 한다.
 *
 * 경과 시간은 같은 부팅 안에서는 단조 시계(elapsedRealtime), 재부팅 뒤에는 벽시계로 계산한다.
 * 시간 수동 변경은 Device Owner 정책으로 막혀 있다.
 */
class StrongMode(ctx: Context) {

    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("strong", Context.MODE_PRIVATE)

    fun isOn(): Boolean = prefs.getBoolean(KEY_ON, false)

    /** 켠다. 되돌리는 함수는 의도적으로 없다. */
    fun enable() {
        prefs.edit().putBoolean(KEY_ON, true).remove(KEY_START_WALL).commit()
    }

    fun startWait() {
        if (!isOn()) return
        prefs.edit()
            .putLong(KEY_START_WALL, System.currentTimeMillis())
            .putLong(KEY_START_ELAPSED, SystemClock.elapsedRealtime())
            .putInt(KEY_BOOT, bootCount())
            .commit()
    }

    /** 대기 중단(또는 편집 구간 종료). 다시 하려면 처음부터 대기해야 한다. */
    fun cancelWait() {
        prefs.edit().remove(KEY_START_WALL).remove(KEY_START_ELAPSED).remove(KEY_BOOT).commit()
    }

    fun phase(): StrongPhase {
        if (!isOn()) return StrongPhase.Off
        val startWall = prefs.getLong(KEY_START_WALL, 0L)
        if (startWall == 0L) return StrongPhase.Idle
        val elapsed = elapsedSinceStart(startWall)
        return when {
            elapsed < WAIT_MS -> StrongPhase.Waiting(WAIT_MS - elapsed)
            elapsed < WAIT_MS + EDIT_MS -> StrongPhase.Ready(WAIT_MS + EDIT_MS - elapsed)
            else -> {
                cancelWait()
                StrongPhase.Idle
            }
        }
    }

    private fun elapsedSinceStart(startWall: Long): Long {
        val nowE = SystemClock.elapsedRealtime()
        val startE = prefs.getLong(KEY_START_ELAPSED, -1L)
        val savedBoot = prefs.getInt(KEY_BOOT, -1)
        val currentBoot = bootCount()
        val sameBoot = savedBoot >= 0 && currentBoot >= 0 && savedBoot == currentBoot && startE in 0..nowE
        return if (sameBoot) nowE - startE else (System.currentTimeMillis() - startWall).coerceAtLeast(0L)
    }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) {
        -1
    }

    companion object {
        const val WAIT_MS = 24L * 60 * 60 * 1000
        const val EDIT_MS = 30L * 60 * 1000

        private const val KEY_ON = "on"
        private const val KEY_START_WALL = "startWall"
        private const val KEY_START_ELAPSED = "startElapsed"
        private const val KEY_BOOT = "boot"
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분 ${s % 60}초"
}
