package app.devicelock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/** 일시중지 동기화 결과. refused: 시스템이 거부한 앱, missing: 설치되어 있지 않은 앱 */
data class SyncResult(val refused: Set<String>, val missing: Set<String>, val errors: List<String>)

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

        // 일시중지는 사용자(프로필)별 상태다. 새 프로필·사용자·클론 앱에서는 차단된 앱이 멀쩡히 열리므로 만들지 못하게 한다.
        step("사용자 전환 차단") { dpm.addUserRestriction(admin, UserManager.DISALLOW_USER_SWITCH) }
        step("관리 프로필 추가 차단") { dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_MANAGED_PROFILE) }
        if (Build.VERSION.SDK_INT >= 34) {
            step("클론 프로필 추가 차단") { dpm.addUserRestriction(admin, RESTRICTION_ADD_CLONE_PROFILE) }
        }

        // 디버그 빌드에서는 개발을 위해 USB 디버깅을 막지 않는다
        if (!BuildConfig.DEBUG) {
            step("디버깅 차단") { dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES) }
        }

        // 기기 시간: 네트워크 시간 강제 + 수동 변경 메뉴 차단
        step("자동 시간 강제") { enforceAutoTime() }
        step("시간 설정 차단") { dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME) }
        step("스마트 스위치 설정") { applySmartSwitch(RuleStore(ctx).isSmartSwitchAllowed()) }

        return failures
    }

    /**
     * 삼성 스마트 스위치는 Device Owner 상태에서 기본적으로 꺼진다.
     * 관리 서비스가 하듯 관리 구성 값 allow_run=true 를 넣어 풀어 본다.
     * 주의: 이 방식이 실제로 동작하는지는 검증되지 않았다(실기기 확인 필요).
     */
    fun applySmartSwitch(allowed: Boolean) {
        if (!isDeviceOwner()) return
        // 관리 서비스 문서들이 스마트 스위치 허용과 함께 "백업 서비스 허용"을 켜라고 안내한다.
        // Device Owner 기기는 백업이 기본 비활성이라는 자료가 있어, 같은 스위치로 함께 제어한다.
        dpm.setBackupServiceEnabled(admin, allowed)
        val b = Bundle()
        if (allowed) b.putBoolean("allow_run", true)
        // 빈 Bundle 을 넣으면 설정이 지워진다
        dpm.setApplicationRestrictions(admin, SMART_SWITCH_PKG, b)
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

    // ------------------------------------------------------------------
    // 앱 일시중지
    // ------------------------------------------------------------------

    /**
     * 지금 차단되어야 하는 앱 집합에 맞춰 일시중지 상태를 동기화한다.
     * 일시중지된 앱은 실행, 알림, 위젯 상호작용이 막히며 화면 위 오버레이 같은 상주 프로세스가 필요 없다.
     *
     * 이 함수는 어떤 예외도 밖으로 던지지 않는다. 규칙 평가가 도중에 끊기면 다음 알람도 예약되지 않아
     * 이후 모든 규칙이 멈추기 때문이다.
     *
     * - 설치 여부와 상관없이 일단 전부 일시중지를 시도한다(안 보인다고 건너뛰면 차단이 새므로).
     *   시스템이 돌려준 실패 목록은 사후에 "미설치"와 "시스템이 거부"로 나눈다.
     * - 일괄 호출이 예외로 실패하면 패키지를 하나씩 시도해, 문제 있는 패키지 하나가 나머지를 막지 못하게 한다.
     */
    fun syncSuspension(blocked: Set<String>): SyncResult {
        if (!isDeviceOwner()) return SyncResult(emptySet(), emptySet(), emptyList())
        val store = RuleStore(ctx)
        val errors = mutableListOf<String>()
        return try {
            val previous = store.loadSuspended()
            val wanted = blocked - ctx.packageName

            val failedSuspend = setSuspended(wanted, true, errors)
            val missing = failedSuspend.filterNot { isInstalled(it) }.toSet()
            val refused = failedSuspend - missing

            // 더 이상 차단 대상이 아닌 앱은 해제한다
            val toRelease = previous - wanted
            val failedRelease = setSuspended(toRelease, false, errors).filter { isInstalled(it) }.toSet()

            // 해제에 실패한(설치된) 앱은 추적 목록에 남겨 다음 평가에서 다시 시도한다
            store.saveSuspended((wanted - failedSuspend) + failedRelease)
            store.saveFailed(refused)
            store.saveMissing(missing)
            store.saveSyncErrors(errors)
            SyncResult(refused, missing, errors)
        } catch (e: Throwable) {
            Log.e(TAG, "syncSuspension 실패", e)
            errors += "동기화 실패 (${e.javaClass.simpleName})"
            store.saveSyncErrors(errors)
            SyncResult(emptySet(), emptySet(), errors)
        }
    }

    /** setPackagesSuspended 를 안전하게 호출하고, 요청대로 되지 않은 패키지 집합을 돌려준다. */
    private fun setSuspended(pkgs: Set<String>, suspend: Boolean, errors: MutableList<String>): Set<String> {
        if (pkgs.isEmpty()) return emptySet()
        try {
            return dpm.setPackagesSuspended(admin, pkgs.toTypedArray(), suspend).toSet()
        } catch (e: SecurityException) {
            errors += "일시중지 권한 오류 (${e.javaClass.simpleName})"
            return pkgs
        } catch (e: Exception) {
            Log.w(TAG, "일괄 일시중지 실패, 개별 처리로 전환", e)
        }
        val failed = mutableSetOf<String>()
        for (p in pkgs) {
            try {
                if (dpm.setPackagesSuspended(admin, arrayOf(p), suspend).isNotEmpty()) failed += p
            } catch (e: Exception) {
                failed += p
                errors += "$p: ${e.javaClass.simpleName}"
            }
        }
        return failed
    }

    private fun isInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        true // 확인할 수 없으면 설치된 것으로 보고 "시스템 거부" 쪽으로 분류한다
    }

    // ------------------------------------------------------------------
    // 네트워크 화이트리스트 (VPN 미사용)
    // ------------------------------------------------------------------

    /**
     * 전역 HTTP 프록시를 "반드시 실패하는 주소"로 지정하고, 허용 도메인만 프록시 예외 목록에 넣는다.
     * 예외 목록에 있는 주소는 프록시를 거치지 않고 바로 접속하고, 나머지는 닫힌 포트로 가서 실패한다.
     * 상주 서비스·VPN·로컬 프록시 서버가 필요 없다.
     *
     * 한계: 시스템 프록시 설정을 따르는 앱(브라우저, 대부분의 HTTP 앱)만 걸러진다.
     * 프록시를 무시하는 앱(일부 게임·메신저의 자체 소켓, UDP 등)은 이 방식으로 막을 수 없다.
     *
     * @param allowed null 이면 화이트리스트 끔(프록시 해제). 아니면 지금 허용할 사용자 도메인.
     * @return 실패 사유. 성공이면 null
     */
    fun syncNetwork(allowed: Set<String>?, force: Boolean = false): String? {
        if (!isDeviceOwner()) return null
        val store = RuleStore(ctx)
        val entries = allowed?.let { Domains.bypassEntries(Domains.SYSTEM_ALLOWED + it) }
        val signature = if (entries == null) "off" else "on:" + entries.joinToString(",")
        if (!force && store.loadNetSignature() == signature) return null

        val error: String? = try {
            if (entries == null) {
                dpm.setRecommendedGlobalProxy(admin, null)
                if (isOurProxyActive()) "프록시 해제를 확인하지 못했습니다" else null
            } else if (Domains.listLength(entries) > Domains.MAX_LIST_CHARS) {
                "허용 도메인이 너무 많아 적용하지 못했습니다"
            } else {
                dpm.setRecommendedGlobalProxy(
                    admin,
                    ProxyInfo.buildDirectProxy(DEAD_PROXY_HOST, DEAD_PROXY_PORT, entries),
                )
                // 형식이 틀리면 시스템이 설정을 조용히 무시하므로 반드시 확인한다
                if (isOurProxyActive()) null else "프록시가 적용되었는지 확인하지 못했습니다(적용되지 않았을 수 있음)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncNetwork 실패", e)
            "네트워크 제한 적용 실패 (${e.javaClass.simpleName})"
        }

        store.saveNetError(error)
        // 성공했을 때만 서명을 저장해, 실패하면 다음 평가에서 다시 시도한다
        store.saveNetSignature(if (error == null) signature else null)
        return error
    }

    private fun isOurProxyActive(): Boolean {
        val p = ctx.getSystemService(ConnectivityManager::class.java)?.defaultProxy ?: return false
        return p.host == DEAD_PROXY_HOST && p.port == DEAD_PROXY_PORT
    }

    // ------------------------------------------------------------------
    // 비밀번호로 앱 제거
    // ------------------------------------------------------------------

    /**
     * 걸어 둔 제한을 모두 풀고 Device Owner 를 내려놓는다. 성공하면 빈 목록을 돌려주며,
     * 그 뒤에 호출자가 시스템 제거 화면을 띄운다.
     * Device Owner 해제에 실패하면 풀어 둔 제한을 다시 걸고 실패 사유를 돌려준다.
     */
    @Suppress("DEPRECATION")
    fun releaseForUninstall(): List<String> {
        if (!isDeviceOwner()) return listOf("Device Owner 권한이 없습니다")
        val failures = mutableListOf<String>()

        fun step(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                failures += "$name 실패 (${e.javaClass.simpleName})"
            }
        }

        val store = RuleStore(ctx)
        step("앱 차단 해제") {
            val all = store.loadSuspended() + store.load().rules.flatMap { it.packages }
            setSuspended(all - ctx.packageName, false, mutableListOf())
        }
        step("네트워크 제한 해제") { dpm.setRecommendedGlobalProxy(admin, null) }
        step("스마트 스위치 설정 해제") { dpm.setApplicationRestrictions(admin, SMART_SWITCH_PKG, Bundle()) }

        for (key in RELEASE_RESTRICTIONS) {
            step("제한 해제($key)") { dpm.clearUserRestriction(admin, key) }
        }
        step("강제 종료 차단 해제") {
            if (Build.VERSION.SDK_INT >= 30) dpm.setUserControlDisabledPackages(admin, emptyList())
        }
        step("제거 차단 해제") { dpm.setUninstallBlocked(admin, ctx.packageName, false) }

        // 위 단계에서 실패가 있어도 Device Owner 해제는 시도한다. 해제되면 남은 정책도 함께 사라진다.
        var cleared = false
        try {
            dpm.clearDeviceOwnerApp(ctx.packageName)
            cleared = true
        } catch (e: Exception) {
            failures += "Device Owner 해제 실패 (${e.javaClass.simpleName})"
        }

        if (!cleared || isDeviceOwner()) {
            // 해제되지 않았다면 보호 상태를 되돌린다
            runCatching { applyBaseline() }
            runCatching { RuleEngine.evaluate(ctx, force = true) }
            if (failures.isEmpty()) failures += "Device Owner 가 해제되지 않았습니다"
            return failures
        }
        store.saveNetSignature(null)
        return emptyList()
    }

    companion object {
        const val SMART_SWITCH_PKG = "com.sec.android.easyMover"

        private const val TAG = "PolicyController"

        // 권한 없는 앱은 1024 미만 포트를 열 수 없으므로, 다른 앱이 이 주소를 가로채 프록시 노릇을 할 수 없다
        const val DEAD_PROXY_HOST = "127.0.0.1"
        const val DEAD_PROXY_PORT = 9

        // UserManager.DISALLOW_ADD_CLONE_PROFILE (API 34)
        private const val RESTRICTION_ADD_CLONE_PROFILE = "no_add_clone_profile"

        private val RELEASE_RESTRICTIONS = listOf(
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_ADD_MANAGED_PROFILE,
            RESTRICTION_ADD_CLONE_PROFILE,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
        )
    }
}
