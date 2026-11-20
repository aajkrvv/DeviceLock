package app.devicelock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.provider.Settings

/**
 * Device Owner 권한으로 거는 정책 모음.
 * 공장 초기화(DISALLOW_FACTORY_RESET)는 의도적으로 막지 않는다. 최후의 복구 수단이기 때문이다.
 */
class PolicyController(private val ctx: Context) {

    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(ctx, DeviceLockAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(ctx.packageName)

    /** 제거 방지, 시간 강제 등 항상 유지되어야 하는 정책. 여러 번 호출해도 안전하다. 실패한 항목 설명을 돌려준다. */
    fun applyBaseline(): List<String> {
        if (!isDeviceOwner()) return listOf("Device Owner 권한이 없습니다")
        val failures = mutableListOf<String>()

        fun step(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                failures += "$name 실패 (${e.javaClass.simpleName})"
            }
        }

        // 제거 방지
        step("앱 제거 차단") { dpm.setUninstallBlocked(admin, ctx.packageName, true) }

        // 강제 종료 / 데이터 삭제 차단
        step("강제 종료·데이터 삭제 차단") {
            if (Build.VERSION.SDK_INT >= 30) {
                dpm.setUserControlDisabledPackages(admin, listOf(ctx.packageName))
            } else {
                // Android 10: 앱 단위 지정이 없어 모든 앱의 강제 종료/삭제/비활성화가 막힌다
                dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
            }
        }

        step("안전 모드 차단") { dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }
        step("사용자 추가 차단") { dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER) }

        // 디버그 빌드에서는 개발을 위해 USB 디버깅을 막지 않는다
        if (!BuildConfig.DEBUG) {
            step("디버깅 차단") { dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES) }
        }

        // 기기 시간: 네트워크 시간 강제 + 수동 변경 메뉴 차단
        step("자동 시간 강제") { enforceAutoTime() }
        step("시간 설정 차단") { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME) }

        return failures
    }

    private fun enforceAutoTime() {
        if (Build.VERSION.SDK_INT >= 30) {
            dpm.setAutoTimeEnabled(admin, true)
            dpm.setAutoTimeZoneEnabled(admin, true)
        } else {
            dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "1")
            dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME_ZONE, "1")
        }
    }

    /**
     * 지금 차단되어야 하는 앱 집합에 맞춰 일시중지 상태를 동기화한다.
     * 일시중지된 앱은 실행, 알림, 위젯 상호작용이 막히며 화면 위 오버레이 같은 상주 프로세스가 필요 없다.
     * 시스템이 일시중지를 거부한 패키지(전화, 설정, 런처 등)는 실패 목록으로 돌려준다.
     */
    fun syncSuspension(blocked: Set<String>): List<String> {
        if (!isDeviceOwner()) return emptyList()
        val store = RuleStore(ctx)
        val previous = store.loadSuspended()
        val failed = mutableSetOf<String>()

        val toSuspend = blocked.filter { it != ctx.packageName }
        val toRelease = (previous - blocked).toList()

        try {
            if (toSuspend.isNotEmpty()) {
                failed += dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true)
            }
            if (toRelease.isNotEmpty()) {
                dpm.setPackagesSuspended(admin, toRelease.toTypedArray(), false)
            }
        } catch (e: SecurityException) {
            store.saveFailed(toSuspend.toSet())
            return toSuspend
        }

        store.saveSuspended(toSuspend.toSet() - failed)
        store.saveFailed(failed)
        return failed.toList()
    }
}
