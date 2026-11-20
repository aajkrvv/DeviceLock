package app.devicelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 알람, 부팅, 시간/시간대 변경, 앱 업데이트 때 규칙을 다시 평가한다. */
class SchedulerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val policy = PolicyController(context)
        if (!policy.isDeviceOwner()) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> policy.applyBaseline()
        }
        RuleEngine.evaluate(context)
    }

    companion object {
        const val ACTION_TICK = "app.devicelock.TICK"
    }
}
