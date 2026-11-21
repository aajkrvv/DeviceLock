package app.devicelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 알람, 부팅, 시간/시간대 변경, 앱 업데이트, 앱 설치·삭제 때 규칙을 다시 평가한다.
 *
 * 참고: Android 8 이상에서는 매니페스트로 받을 수 있는 앱 설치/삭제 브로드캐스트가 제한된다.
 * 그래서 설치 알림이 오지 않아도 재설치를 잡을 수 있도록 RuleEngine 이 짧은 주기 평가를 겸한다.
 */
class SchedulerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val policy = PolicyController(context)
            if (!policy.isDeviceOwner()) return

            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    policy.applyBaseline()
                    RuleEngine.evaluate(context, force = true)
                }
                else -> RuleEngine.evaluate(context)
            }
        } catch (t: Throwable) {
            Log.e("SchedulerReceiver", "처리 실패", t)
            // 어떻게든 다음 평가가 예약되도록 한 번 더 시도한다
            runCatching { RuleEngine.evaluate(context) }
        }
    }

    companion object {
        const val ACTION_TICK = "app.devicelock.TICK"
    }
}
