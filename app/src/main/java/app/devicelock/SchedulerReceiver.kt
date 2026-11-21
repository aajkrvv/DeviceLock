package app.devicelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log

/**
 * 알람, 부팅, 시간/시간대 변경, 앱 업데이트, 앱 설치·삭제 때 규칙을 다시 평가한다.
 *
 * 참고: Android 8 이상에서는 매니페스트로 받을 수 있는 앱 설치/삭제 브로드캐스트가 제한된다.
 * 그래서 설치 알림이 오지 않아도 재설치를 잡을 수 있도록 RuleEngine 이 짧은 주기 평가를 겸한다.
 */
class SchedulerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread({
            try {
                val policy = PolicyController(app)
                if (!policy.isDeviceOwner()) return@Thread

                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        LocationGate.reset(app) // 재부팅·업데이트로 위치 업데이트 등록이 사라진다
                        policy.applyBaseline()
                        RuleEngine.evaluate(app, force = true)
                    }
                    LocationGate.ACTION_LOCATION -> {
                        extractLocation(intent)?.let { LocationGate.onLocation(app, it) }
                        RuleEngine.evaluate(app, fromLocation = true)
                    }
                    else -> RuleEngine.evaluate(app)
                }
            } catch (t: Throwable) {
                Log.e("SchedulerReceiver", "처리 실패", t)
                runCatching { RuleEngine.evaluate(app) }
            } finally {
                pending.finish()
            }
        }, "devicelock-scheduler").start()
    }

    @Suppress("DEPRECATION")
    private fun extractLocation(i: Intent): Location? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
        } else {
            i.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED) as? Location
        }

    companion object {
        const val ACTION_TICK = "app.devicelock.TICK"
    }
}
