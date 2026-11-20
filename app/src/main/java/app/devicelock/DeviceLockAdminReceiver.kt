package app.devicelock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class DeviceLockAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        PolicyController(context).applyBaseline()
        RuleEngine.evaluate(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "이 앱은 기기 제한 용도로 관리되고 있어 비활성화할 수 없습니다."
    }
}
