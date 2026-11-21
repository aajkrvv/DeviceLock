package app.devicelock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.Manifest
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
 * 한 시점에 적용해야 하는 네트워크 상태.
 * @param blackout 강력 모드 대기 중: 모든 통신 차단(화이트리스트 설정과 무관)
 * @param whitelistOn 화이트리스트가 켜져 있는지
 * @param allowed 지금 허용할 사용자 도메인(whitelistOn 일 때)
 */
data class NetPlan(
    val blackout: Boolean,
    val whitelistOn: Boolean,
    val allowed: Set<String>,
    val modes: Set<String>,
    val privateDnsHost: String,
) {
    val vpnMode: String
        get() = when {
            blackout -> NetState.VPN_BLACKHOLE
            whitelistOn && NET_DNS_VPN in modes -> NetState.VPN_DNS
            else -> NetState.VPN_OFF
        }
}

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

        // 위치 예외용 권한. Android 12 이상에서는 기기에 따라 거부될 수 있어 실패해도 오류로 세지 않는다(화면의 위치 상태에서 확인).
        runCatching { grantLocationPermissions() }

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

    /** 위치 예외에 필요한 위치 권한을 Device Owner 권한으로 스스로에게 부여한다. */
    fun grantLocationPermissions() {
        if (!isDeviceOwner()) return
        val perms = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        for (perm in perms) {
            runCatching {
                dpm.setPermissionGrantState(
                    admin, ctx.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
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
    // 네트워크 제한: 프록시 / Private DNS / 내장 DNS VPN 을 선택해 적용하고, 강력 모드 대기 중에는 전부 차단한다
    // ------------------------------------------------------------------

    /**
     * 계획(NetPlan)대로 네트워크 제한을 맞춘다. 방식별로 서명을 저장해, 바뀐 부분만 다시 적용한다.
     *
     * - 프록시: 전역 프록시를 닫힌 포트로 지정하고 허용 도메인만 예외 목록에 넣는다. 프록시 설정을 따르는 앱만 걸러진다.
     * - Private DNS: 지정한 DNS-over-TLS 호스트를 강제한다. 허용 목록은 그 DNS 서비스(예: 허용 목록 기능이 있는 서비스)가 가진다.
     * - 내장 DNS VPN: 이 앱의 로컬 VPN 이 DNS 질의에서 허용 도메인만 통과시킨다(시간 규칙 반영). 다른 VPN 과 동시에 쓸 수 없다.
     * - 강력 모드 대기(blackout): 프록시는 예외 없이 닫고, 로컬 VPN 으로 전체 트래픽을 버리며 상시 VPN 차단(lockdown)을 건다.
     *
     * @return 실패 사유. 모두 성공이면 null
     */
    fun syncNetwork(plan: NetPlan, force: Boolean = false): String? {
        if (!isDeviceOwner()) return null
        val store = RuleStore(ctx)
        val modes = plan.modes
        val vpnMode = plan.vpnMode
        if (vpnMode == NetState.VPN_DNS) NetState.setDnsAllowed(ctx, plan.allowed) // 시간 규칙 반영(서명과 무관)

        val proxyEntries: List<String>? = when {
            plan.blackout -> emptyList()
            plan.whitelistOn && NET_PROXY in modes -> Domains.bypassEntries(Domains.SYSTEM_ALLOWED + plan.allowed)
            else -> null
        }
        val privateDns: String? =
            if (!plan.blackout && plan.whitelistOn && NET_PRIVATE_DNS in modes && plan.privateDnsHost.isNotBlank())
                plan.privateDnsHost.trim()
            else null

        val errors = listOfNotNull(
            applyProxy(store, proxyEntries, force),
            applyPrivateDns(store, privateDns, force),
            applyVpn(store, vpnMode, force),
        )
        val error = errors.joinToString("; ").ifEmpty { null }
        store.saveNetError(error)
        return error
    }

    private fun applyProxy(store: RuleStore, entries: List<String>?, force: Boolean): String? {
        val sig = if (entries == null) "off" else "on:" + entries.joinToString(",")
        if (!force && store.loadNetSignature("proxy") == sig) return null
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
            Log.e(TAG, "프록시 적용 실패", e)
            "프록시 적용 실패 (${e.javaClass.simpleName})"
        }
        // 성공했을 때만 서명을 저장해, 실패하면 다음 평가에서 다시 시도한다
        store.saveNetSignature("proxy", if (error == null) sig else null)
        return error
    }

    private fun applyPrivateDns(store: RuleStore, host: String?, force: Boolean): String? {
        val sig = host ?: "off"
        if (!force && store.loadNetSignature("pdns") == sig) return null
        if (host == null && !store.isPrivateDnsOwned()) {
            // 우리가 바꾼 적이 없으면 사용자의 Private DNS 설정은 건드리지 않는다.
            store.saveNetSignature("pdns", sig)
            return null
        }
        val error: String? = try {
            if (host == null) {
                val oldMode = store.priorPrivateDnsMode()
                val result = when (oldMode) {
                    DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME -> {
                        val oldHost = store.priorPrivateDnsHost()
                        if (oldHost.isNullOrBlank()) DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                        else dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, oldHost)
                    }
                    // DevicePolicyManager 에는 OFF 로 되돌리는 API 가 없다. OFF 였던 경우에는 자동(기회적) 모드로 복원한다.
                    else -> dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                }
                if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    store.setPrivateDnsOwned(false)
                    store.clearPrivateDnsSnapshot()
                    if (oldMode == DevicePolicyManager.PRIVATE_DNS_MODE_OFF) {
                        Log.w(TAG, "Private DNS 의 원래 OFF 상태는 DevicePolicyManager API로 복원할 수 없어 자동 모드로 복원했습니다")
                    }
                    null
                } else "Private DNS 복원 실패 (코드 $result)"
            } else {
                // 기존 시스템 설정은 최초 1회만 저장한다. 앱을 거듭 평가해도 원래 값이 덮이지 않는다.
                if (!store.isPrivateDnsOwned() && !store.hasPrivateDnsSnapshot()) {
                    store.savePrivateDnsSnapshot(
                        dpm.getGlobalPrivateDnsMode(admin),
                        dpm.getGlobalPrivateDnsHost(admin),
                    )
                }
                when (val r = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)) {
                    DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> {
                        store.setPrivateDnsOwned(true)
                        null
                    }
                    DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING ->
                        "Private DNS 호스트에 연결하지 못했습니다(주소 확인 또는 네트워크 연결 필요)"
                    else -> "Private DNS 설정 실패 (코드 $r)"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Private DNS 적용 실패", e)
            "Private DNS 적용 실패 (${e.javaClass.simpleName})"
        }
        store.saveNetSignature("pdns", if (error == null) sig else null)
        return error
    }

    private fun applyVpn(store: RuleStore, mode: String, force: Boolean): String? {
        // 서비스가 죽어 있으면(사용자가 다른 VPN 을 켜서 해제된 경우 등) 서명이 같아도 다시 시작한다.
        val alive = mode == NetState.VPN_OFF || NetworkVpnService.running
        if (!force && alive && store.loadNetSignature("vpn") == mode) return null
        val error: String? = try {
            NetState.setVpnMode(ctx, mode)
            if (mode == NetState.VPN_OFF) {
                if (store.isAlwaysOnOwned()) restorePreviousAlwaysOnVpn(store)
                NetworkVpnService.stop(ctx)
                NetState.setError(ctx, null)
            } else {
                // 타사 VPN 의 기존 Always-on 설정을 먼저 저장한다. 앱 자체 VPN 을 해제할 때 복원한다.
                if (!store.isAlwaysOnOwned() && !store.hasAlwaysOnSnapshot()) {
                    val previous = dpm.getAlwaysOnVpnPackage(admin)
                    val lockdown = previous != null && dpm.isAlwaysOnVpnLockdownEnabled(admin)
                    store.saveAlwaysOnSnapshot(previous, lockdown)
                }
                dpm.setAlwaysOnVpnPackage(admin, ctx.packageName, mode == NetState.VPN_BLACKHOLE)
                store.setAlwaysOnOwned(true)
                NetworkVpnService.apply(ctx)
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "VPN 적용 실패", e)
            "VPN 적용 실패 (${e.javaClass.simpleName})"
        }
        store.saveNetSignature("vpn", if (error == null) mode else null)
        return error
    }

    /** 이 앱의 VPN을 끄면서, takeover 전에 저장한 상시 VPN 설정을 복원한다. */
    private fun restorePreviousAlwaysOnVpn(store: RuleStore) {
        if (store.hasAlwaysOnSnapshot()) {
            val oldPackage = store.priorAlwaysOnPackage()
            val oldLockdown = store.priorAlwaysOnLockdown()
            if (oldPackage == null) {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
            } else {
                dpm.setAlwaysOnVpnPackage(admin, oldPackage, oldLockdown)
            }
            store.clearAlwaysOnSnapshot()
        } else {
            // 이전 버전에서 snapshot 없이 소유 상태만 저장된 경우의 안전한 fallback.
            dpm.setAlwaysOnVpnPackage(admin, null, false)
        }
        store.setAlwaysOnOwned(false)
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
        if (StrongMode(ctx).isOn() && StrongMode(ctx).phase() !is StrongPhase.Ready) {
            return listOf("강력 모드에서는 24시간 대기가 끝난 30분 편집 가능 구간에서만 앱을 제거할 수 있습니다")
        }
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
        step("VPN 해제 및 기존 상시 VPN 복원") {
            if (store.isAlwaysOnOwned()) restorePreviousAlwaysOnVpn(store)
            NetworkVpnService.stop(ctx)
            NetState.setVpnMode(ctx, NetState.VPN_OFF)
        }
        step("Private DNS 해제 및 기존 설정 복원") {
            if (store.isPrivateDnsOwned()) {
                val oldMode = store.priorPrivateDnsMode()
                val result = if (oldMode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
                    val oldHost = store.priorPrivateDnsHost()
                    if (oldHost.isNullOrBlank()) DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                    else dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, oldHost)
                } else dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                if (result != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    throw IllegalStateException("Private DNS 복원 실패 (코드 $result)")
                }
                store.setPrivateDnsOwned(false)
                store.clearPrivateDnsSnapshot()
            }
        }
        step("위치 확인 해제") { LocationGate.refresh(ctx, needed = false, reregister = true) }
        step("스마트 스위치 설정 해제") { dpm.setApplicationRestrictions(admin, SMART_SWITCH_PKG, Bundle()) }

        for (key in RELEASE_RESTRICTIONS) {
            step("제한 해제($key)") { dpm.clearUserRestriction(admin, key) }
        }
        step("강제 종료 차단 해제") {
            if (Build.VERSION.SDK_INT >= 30) dpm.setUserControlDisabledPackages(admin, emptyList())
        }
        step("제거 차단 해제") { dpm.setUninstallBlocked(admin, ctx.packageName, false) }

        // 네트워크 원복에 실패했다면 Device Owner 를 유지한다. 남은 Always-on VPN 때문에 통신이 막히는 것을 방지.
        if (store.isAlwaysOnOwned() || store.isPrivateDnsOwned() || isOurProxyActive()) {
            failures += "네트워크 설정 복원에 실패해 Device Owner 해제를 중단했습니다. 상태를 확인한 뒤 다시 시도하세요."
            runCatching { applyBaseline() }
            runCatching { RuleEngine.evaluate(ctx, force = true) }
            return failures
        }

        // Android API 는 Device Owner 해제를 테스트 용도로만 안내하므로 실패 가능성을 UI 에 표시한다.
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
        store.clearNetSignatures()
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
