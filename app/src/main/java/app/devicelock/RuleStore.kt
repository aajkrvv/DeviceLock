package app.devicelock

import android.content.Context
import android.util.Log
import org.json.JSONObject

class RuleStore(ctx: Context) {

    private val prefs = ctx.applicationContext.getSharedPreferences("rules", Context.MODE_PRIVATE)

    /**
     * 규칙을 읽는다. 저장본이 깨졌으면 직전 저장본으로 복구를 시도한다.
     * 둘 다 실패하면 빈 규칙을 돌려주되(= 차단이 풀리는 상황), 화면에 경고가 뜨도록 표시를 남긴다.
     */
    fun load(): RuleSet {
        val raw = prefs.getString(KEY_RULES, null)
        val bak = prefs.getString(KEY_RULES_BAK, null)
        if (raw == null && bak == null) return RuleSet.empty()

        if (raw != null) {
            runCatching { RuleSet.fromJson(JSONObject(raw)) }.onSuccess {
                if (prefs.contains(KEY_LOAD_ISSUE)) prefs.edit().remove(KEY_LOAD_ISSUE).apply()
                return it
            }.onFailure { Log.e(TAG, "규칙 파싱 실패", it) }
        }
        if (bak != null) {
            runCatching { RuleSet.fromJson(JSONObject(bak)) }.onSuccess {
                prefs.edit().putString(KEY_LOAD_ISSUE, "저장된 규칙이 손상되어 직전 백업으로 복구했습니다").apply()
                return it
            }
        }
        prefs.edit().putString(KEY_LOAD_ISSUE, "저장된 규칙을 읽지 못해 모든 규칙이 비어 있습니다").apply()
        return RuleSet.empty()
    }

    fun save(set: RuleSet) {
        val old = prefs.getString(KEY_RULES, null)
        val edit = prefs.edit().putString(KEY_RULES, set.toJson().toString())
        // 직전 저장본은 정상적으로 읽히는 경우에만 백업으로 남긴다
        if (old != null && runCatching { JSONObject(old) }.isSuccess) edit.putString(KEY_RULES_BAK, old)
        edit.remove(KEY_LOAD_ISSUE).commit()
    }

    fun loadIssue(): String? = prefs.getString(KEY_LOAD_ISSUE, null)

    /** 현재 일시중지 상태로 적용해 둔 패키지 (해제 대상 계산용) */
    fun loadSuspended(): Set<String> = prefs.getStringSet("suspended", emptySet())!!.toSet()

    fun saveSuspended(s: Set<String>) {
        prefs.edit().putStringSet("suspended", s).commit()
    }

    /** 스마트 스위치 허용 여부 (기본값: 꺼짐) */
    fun isSmartSwitchAllowed(): Boolean = prefs.getBoolean("smartSwitch", false)

    fun setSmartSwitchAllowed(v: Boolean) {
        prefs.edit().putBoolean("smartSwitch", v).commit()
    }

    /** 시스템이 일시중지를 거부해 차단하지 못한 패키지 (화면 표시용) */
    fun loadFailed(): Set<String> = prefs.getStringSet("failed", emptySet())!!.toSet()

    fun saveFailed(s: Set<String>) {
        prefs.edit().putStringSet("failed", s).commit()
    }

    /** 규칙에는 있지만 기기에 설치되어 있지 않은 패키지. 다시 설치되면 다음 평가에서 자동으로 차단된다. */
    fun loadMissing(): Set<String> = prefs.getStringSet("missing", emptySet())!!.toSet()

    fun saveMissing(s: Set<String>) {
        prefs.edit().putStringSet("missing", s).commit()
    }

    /** 마지막 동기화 중 예상 밖 예외 요약 (화면 표시용) */
    fun loadSyncErrors(): List<String> = prefs.getStringSet("syncErrors", emptySet())!!.toList().sorted()

    fun saveSyncErrors(l: List<String>) {
        prefs.edit().putStringSet("syncErrors", l.take(5).toSet()).commit()
    }

    /**
     * 네트워크 제한 적용 결과. part(proxy / pdns / vpn)별로 마지막에 성공 적용한 상태의 서명을 저장한다.
     * 서명이 같으면 다시 적용하지 않는다.
     */
    fun loadNetSignature(part: String): String? = prefs.getString("netSig_$part", null)

    fun saveNetSignature(part: String, s: String?) {
        prefs.edit().apply { if (s == null) remove("netSig_$part") else putString("netSig_$part", s) }.commit()
    }

    fun clearNetSignatures() {
        prefs.edit().remove("netSig_proxy").remove("netSig_pdns").remove("netSig_vpn").commit()
    }

    /** 이 앱이 Private DNS 를 지정해 둔 상태인지(복원 여부 판단용. 사용자가 직접 정한 설정을 건드리지 않기 위함) */
    fun isPrivateDnsOwned(): Boolean = prefs.getBoolean("pdnsOwned", false)

    fun setPrivateDnsOwned(v: Boolean) {
        prefs.edit().putBoolean("pdnsOwned", v).commit()
    }

    /** 이 앱이 상시 VPN 을 지정해 둔 상태인지(사용자가 직접 정한 상시 VPN 을 지우지 않기 위함) */
    fun isAlwaysOnOwned(): Boolean = prefs.getBoolean("alwaysOnOwned", false)

    fun setAlwaysOnOwned(v: Boolean) {
        prefs.edit().putBoolean("alwaysOnOwned", v).commit()
    }

    fun hasAlwaysOnSnapshot(): Boolean = prefs.getBoolean("alwaysOnSnapshot", false)

    fun saveAlwaysOnSnapshot(packageName: String?, lockdown: Boolean) {
        prefs.edit().apply {
            putBoolean("alwaysOnSnapshot", true)
            putBoolean("priorAlwaysOnLockdown", lockdown)
            if (packageName == null) remove("priorAlwaysOnPackage")
            else putString("priorAlwaysOnPackage", packageName)
        }.commit()
    }

    fun priorAlwaysOnPackage(): String? = prefs.getString("priorAlwaysOnPackage", null)

    fun priorAlwaysOnLockdown(): Boolean = prefs.getBoolean("priorAlwaysOnLockdown", false)

    fun clearAlwaysOnSnapshot() {
        prefs.edit().remove("alwaysOnSnapshot")
            .remove("priorAlwaysOnPackage")
            .remove("priorAlwaysOnLockdown")
            .commit()
    }

    /** Private DNS 설정을 처음 변경하기 직전의 모드와 호스트. */
    fun hasPrivateDnsSnapshot(): Boolean = prefs.getBoolean("pdnsSnapshot", false)

    fun savePrivateDnsSnapshot(mode: Int, host: String?) {
        prefs.edit().apply {
            putBoolean("pdnsSnapshot", true)
            putInt("priorPdnsMode", mode)
            if (host == null) remove("priorPdnsHost") else putString("priorPdnsHost", host)
        }.commit()
    }

    fun priorPrivateDnsMode(): Int = prefs.getInt("priorPdnsMode", -1)

    fun priorPrivateDnsHost(): String? = prefs.getString("priorPdnsHost", null)

    fun clearPrivateDnsSnapshot() {
        prefs.edit().remove("pdnsSnapshot")
            .remove("priorPdnsMode")
            .remove("priorPdnsHost")
            .commit()
    }

    fun loadNetError(): String? = prefs.getString("netErr", null)

    fun saveNetError(s: String?) {
        prefs.edit().apply { if (s == null) remove("netErr") else putString("netErr", s) }.commit()
    }

    private companion object {
        const val TAG = "RuleStore"
        const val KEY_RULES = "rules"
        const val KEY_RULES_BAK = "rules_bak"
        const val KEY_LOAD_ISSUE = "loadIssue"
    }
}
