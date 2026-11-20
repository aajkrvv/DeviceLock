package app.devicelock

import android.content.Context
import org.json.JSONObject

class RuleStore(ctx: Context) {

    private val prefs = ctx.applicationContext.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun load(): RuleSet {
        val raw = prefs.getString("rules", null) ?: return RuleSet.empty()
        return runCatching { RuleSet.fromJson(JSONObject(raw)) }.getOrElse { RuleSet.empty() }
    }

    fun save(set: RuleSet) {
        prefs.edit().putString("rules", set.toJson().toString()).commit()
    }

    /** 현재 일시중지 상태로 적용해 둔 패키지 (해제 대상 계산용) */
    fun loadSuspended(): Set<String> = prefs.getStringSet("suspended", emptySet())!!.toSet()

    fun saveSuspended(s: Set<String>) {
        prefs.edit().putStringSet("suspended", s).commit()
    }

    /** 시스템이 일시중지를 거부해 차단하지 못한 패키지 (화면 표시용) */
    fun loadFailed(): Set<String> = prefs.getStringSet("failed", emptySet())!!.toSet()

    fun saveFailed(s: Set<String>) {
        prefs.edit().putStringSet("failed", s).commit()
    }
}
