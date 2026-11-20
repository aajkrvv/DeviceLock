package app.devicelock

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 규칙이 "적용되는" 시간 조건. 이 조건에 해당하는 동안에만 제한이 걸린다.
 * days: 1=월 ... 7=일, 비어 있으면 매일.
 * 시작 > 종료이면 자정을 넘기는 구간이며, 시작한 날의 요일/날짜 기준으로 판단한다.
 */
data class Schedule(
    val days: Set<Int> = emptySet(),
    val allDay: Boolean = true,
    val startMin: Int = 0,
    val endMin: Int = 0,
    val dateFrom: Long? = null, // epochDay, 포함
    val dateTo: Long? = null,   // epochDay, 포함
) {
    private fun windowStartsOn(date: LocalDate): Boolean {
        val d = date.toEpochDay()
        if (dateFrom != null && d < dateFrom) return false
        if (dateTo != null && d > dateTo) return false
        return days.isEmpty() || date.dayOfWeek.value in days
    }

    fun isActive(now: LocalDateTime): Boolean {
        val today = now.toLocalDate()
        // 시작과 종료가 같으면 구간을 알 수 없으므로 종일로 취급한다(제한 쪽으로 안전하게)
        if (allDay || startMin == endMin) return windowStartsOn(today)
        val m = now.hour * 60 + now.minute
        return if (startMin < endMin) {
            windowStartsOn(today) && m in startMin until endMin
        } else {
            (windowStartsOn(today) && m >= startMin) ||
                (windowStartsOn(today.minusDays(1)) && m < endMin)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("days", JSONArray(days.sorted()))
        put("allDay", allDay)
        put("startMin", startMin)
        put("endMin", endMin)
        if (dateFrom != null) put("dateFrom", dateFrom)
        if (dateTo != null) put("dateTo", dateTo)
    }

    companion object {
        fun fromJson(o: JSONObject): Schedule {
            val arr = o.optJSONArray("days")
            val days = if (arr == null) emptySet() else (0 until arr.length()).map { arr.getInt(it) }.toSet()
            return Schedule(
                days = days,
                allDay = o.optBoolean("allDay", true),
                startMin = o.optInt("startMin", 0),
                endMin = o.optInt("endMin", 0),
                dateFrom = if (o.has("dateFrom")) o.getLong("dateFrom") else null,
                dateTo = if (o.has("dateTo")) o.getLong("dateTo") else null,
            )
        }
    }
}

data class Group(val id: String, val name: String, val enabled: Boolean = true)

data class AppRule(
    val id: String,
    val name: String,
    val groupId: String,
    val packages: List<String>,
    val schedule: Schedule = Schedule(),
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("groupId", groupId)
        put("packages", JSONArray(packages))
        put("schedule", schedule.toJson())
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): AppRule {
            val pk = o.getJSONArray("packages")
            return AppRule(
                id = o.getString("id"),
                name = o.optString("name", ""),
                groupId = o.optString("groupId", DEFAULT_GROUP_ID),
                packages = (0 until pk.length()).map { pk.getString(it) },
                schedule = o.optJSONObject("schedule")?.let { Schedule.fromJson(it) } ?: Schedule(),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

const val DEFAULT_GROUP_ID = "default"

data class RuleSet(val groups: List<Group>, val rules: List<AppRule>) {

    /** 지금 시각에 차단되어야 하는 패키지 집합 */
    fun blockedPackages(now: LocalDateTime): Set<String> {
        val enabledGroups = groups.filter { it.enabled }.map { it.id }.toSet()
        val out = HashSet<String>()
        for (r in rules) {
            if (r.enabled && r.groupId in enabledGroups && r.schedule.isActive(now)) out.addAll(r.packages)
        }
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("groups", JSONArray().also { a ->
            groups.forEach { g ->
                a.put(JSONObject().put("id", g.id).put("name", g.name).put("enabled", g.enabled))
            }
        })
        put("rules", JSONArray().also { a -> rules.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun empty() = RuleSet(listOf(Group(DEFAULT_GROUP_ID, "기본")), emptyList())

        fun fromJson(o: JSONObject): RuleSet {
            val ga = o.getJSONArray("groups")
            val ra = o.getJSONArray("rules")
            val groups = (0 until ga.length()).map {
                val g = ga.getJSONObject(it)
                Group(g.getString("id"), g.optString("name", ""), g.optBoolean("enabled", true))
            }
            val rules = (0 until ra.length()).map { AppRule.fromJson(ra.getJSONObject(it)) }
            return RuleSet(groups.ifEmpty { empty().groups }, rules)
        }
    }
}

fun formatMinutes(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

fun Schedule.summary(): String {
    val d = if (days.isEmpty()) "매일" else days.sorted().joinToString("") { "월화수목금토일"[it - 1].toString() }
    val t = if (allDay) "종일" else "${formatMinutes(startMin)}~${formatMinutes(endMin)}"
    val r = if (dateFrom != null || dateTo != null) {
        " · ${dateFrom?.let { LocalDate.ofEpochDay(it).toString() } ?: ""}~${dateTo?.let { LocalDate.ofEpochDay(it).toString() } ?: ""}"
    } else ""
    return "$d $t$r"
}
