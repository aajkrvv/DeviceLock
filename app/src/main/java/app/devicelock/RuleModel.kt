package app.devicelock

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 규칙이 "적용되는" 시간 조건.
 * - 앱 차단 규칙: 이 조건에 해당하는 동안 제한이 걸린다.
 * - 도메인 허용 규칙: 이 조건에 해당하는 동안 도메인이 허용된다.
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
            val days = if (arr == null) emptySet() else
                (0 until arr.length()).map { arr.optInt(it, 0) }.filter { it in 1..7 }.toSet()
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

/** 평가 시점에 확인된 기기 위치. 위치를 모르거나 오래됐으면 null 로 넘긴다(= 예외 적용 안 됨, 제한 유지). */
data class GeoState(val lat: Double, val lon: Double, val accuracyM: Double = 0.0)

/** 두 좌표 사이의 거리(미터, haversine) */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/**
 * 위치 예외: 기기가 (lat, lon) 반경 radiusM 안에 있고, 현재 시각이 window 에 해당하는 동안 규칙을 일시적으로 끈다.
 * 위치를 알 수 없으면 예외는 적용되지 않는다(= 규칙 유지).
 */
data class Exemption(
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val window: Schedule,
) {
    fun isActive(now: LocalDateTime, geo: GeoState?): Boolean {
        if (geo == null || !window.isActive(now)) return false
        // 위치 오차 반경까지 고려해 실제로 구역 안이라고 보수적으로 판단한다.
        return distanceMeters(lat, lon, geo.lat, geo.lon) + geo.accuracyM <= radiusM
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        put("radiusM", radiusM)
        put("window", window.toJson())
    }

    companion object {
        const val MIN_RADIUS = 50
        const val MAX_RADIUS = 10_000

        fun fromJson(o: JSONObject): Exemption = Exemption(
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            radiusM = o.optInt("radiusM", 100).coerceIn(MIN_RADIUS, MAX_RADIUS),
            window = Schedule.fromJson(o.getJSONObject("window")),
        )
    }
}

fun Exemption.summary(): String = "반경 ${radiusM}m · 예외 시간 ${window.summary()}"

const val NET_PROXY = "proxy"
const val NET_PRIVATE_DNS = "private_dns"
const val NET_DNS_VPN = "dns_vpn"
val DEFAULT_NET_MODES: Set<String> = setOf(NET_PROXY)
private val KNOWN_NET_MODES = setOf(NET_PROXY, NET_PRIVATE_DNS, NET_DNS_VPN)

data class Group(val id: String, val name: String, val enabled: Boolean = true)

/** 앱 차단 규칙: 스케줄에 해당하는 동안 packages 를 일시중지한다. */
data class AppRule(
    val id: String,
    val name: String,
    val groupId: String,
    val packages: List<String>,
    val schedule: Schedule = Schedule(),
    val enabled: Boolean = true,
    val exemption: Exemption? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("groupId", groupId)
        put("packages", JSONArray(packages))
        put("schedule", schedule.toJson())
        put("enabled", enabled)
        if (exemption != null) put("exemption", exemption.toJson())
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
                exemption = o.optJSONObject("exemption")?.let { runCatching { Exemption.fromJson(it) }.getOrNull() },
            )
        }
    }
}

/**
 * 도메인 허용 규칙: 네트워크 화이트리스트가 켜져 있을 때,
 * 스케줄에 해당하는 동안 domains(와 그 하위 도메인)의 접속을 허용한다.
 * domains 는 Domains.normalize 를 통과한 값이다.
 */
data class DomainRule(
    val id: String,
    val name: String,
    val groupId: String,
    val domains: List<String>,
    val schedule: Schedule = Schedule(),
    val enabled: Boolean = true,
    val exemption: Exemption? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("groupId", groupId)
        put("domains", JSONArray(domains))
        put("schedule", schedule.toJson())
        put("enabled", enabled)
        if (exemption != null) put("exemption", exemption.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): DomainRule {
            val dm = o.getJSONArray("domains")
            return DomainRule(
                id = o.getString("id"),
                name = o.optString("name", ""),
                groupId = o.optString("groupId", DEFAULT_GROUP_ID),
                // 저장된 값이라도 다시 검증해, 잘못된 항목이 프록시 설정 전체를 무효화하지 못하게 한다
                domains = (0 until dm.length()).mapNotNull { Domains.normalize(dm.optString(it, "")) }.distinct(),
                schedule = o.optJSONObject("schedule")?.let { Schedule.fromJson(it) } ?: Schedule(),
                enabled = o.optBoolean("enabled", true),
                exemption = o.optJSONObject("exemption")?.let { runCatching { Exemption.fromJson(it) }.getOrNull() },
            )
        }
    }
}

const val DEFAULT_GROUP_ID = "default"

/** 한 시점에 적용되어야 하는 상태. allowed == null 이면 화이트리스트 꺼짐. */
data class Enforcement(val blocked: Set<String>, val allowed: Set<String>?)

data class RuleSet(
    val groups: List<Group>,
    val rules: List<AppRule>,
    val domainRules: List<DomainRule> = emptyList(),
    val netWhitelist: Boolean = false,
    /** 화이트리스트를 적용하는 방식(NET_PROXY, NET_PRIVATE_DNS, NET_DNS_VPN 중 여러 개). */
    val netModes: Set<String> = DEFAULT_NET_MODES,
    /** NET_PRIVATE_DNS 에서 쓸 DNS-over-TLS 호스트 이름 */
    val privateDnsHost: String = "",
) {

    private fun enabledGroupIds(): Set<String> = groups.filter { it.enabled }.map { it.id }.toSet()

    /** 지금 시각에 차단되어야 하는 패키지 집합. 위치 예외가 적용 중인 규칙은 제외된다. */
    fun blockedPackages(now: LocalDateTime, geo: GeoState? = null): Set<String> {
        val enabledGroups = enabledGroupIds()
        val out = HashSet<String>()
        for (r in rules) {
            if (r.enabled && r.groupId in enabledGroups && r.schedule.isActive(now) &&
                r.exemption?.isActive(now, geo) != true
            ) out.addAll(r.packages)
        }
        return out
    }

    /** 지금 시각에 허용되는 도메인 집합(사용자 규칙만; 시스템 필수 주소는 별도). 위치 예외가 적용 중인 규칙은 제외된다. */
    fun allowedDomains(now: LocalDateTime, geo: GeoState? = null): Set<String> {
        val enabledGroups = enabledGroupIds()
        val out = HashSet<String>()
        for (r in domainRules) {
            if (r.enabled && r.groupId in enabledGroups && r.schedule.isActive(now) &&
                r.exemption?.isActive(now, geo) != true
            ) out.addAll(r.domains)
        }
        return out
    }

    fun enforcementAt(now: LocalDateTime, geo: GeoState? = null): Enforcement =
        Enforcement(blockedPackages(now, geo), if (netWhitelist) allowedDomains(now, geo) else null)

    fun allSchedules(): List<Schedule> =
        rules.map { it.schedule } + domainRules.map { it.schedule } +
            rules.mapNotNull { it.exemption?.window } + domainRules.mapNotNull { it.exemption?.window }

    fun hasExemptions(): Boolean = rules.any { it.exemption != null } || domainRules.any { it.exemption != null }

    /** 지금 시각이 어떤 활성 규칙의 위치 예외 시간대에 들어 있는지(= 위치를 확인해야 하는지) */
    fun exemptionWindowActive(now: LocalDateTime): Boolean {
        val g = enabledGroupIds()
        return rules.any {
            it.enabled && it.groupId in g && it.schedule.isActive(now) && it.exemption?.window?.isActive(now) == true
        } || domainRules.any {
            it.enabled && it.groupId in g && it.schedule.isActive(now) && it.exemption?.window?.isActive(now) == true
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("groups", JSONArray().also { a ->
            groups.forEach { g ->
                a.put(JSONObject().put("id", g.id).put("name", g.name).put("enabled", g.enabled))
            }
        })
        put("rules", JSONArray().also { a -> rules.forEach { a.put(it.toJson()) } })
        put("domainRules", JSONArray().also { a -> domainRules.forEach { a.put(it.toJson()) } })
        put("netWhitelist", netWhitelist)
        put("netModes", JSONArray(netModes.sorted()))
        put("privateDnsHost", privateDnsHost)
    }

    companion object {
        fun empty() = RuleSet(listOf(Group(DEFAULT_GROUP_ID, "기본")), emptyList())

        /**
         * 항목 하나가 깨졌다고 전체를 버리지 않는다(전체를 버리면 모든 차단이 풀린다).
         * 존재하지 않는 그룹을 가리키는 규칙은 기본 그룹으로 옮겨, 조용히 무시되는 일이 없게 한다.
         */
        fun fromJson(o: JSONObject): RuleSet {
            val groups = ArrayList<Group>()
            o.optJSONArray("groups")?.let { ga ->
                for (i in 0 until ga.length()) {
                    runCatching {
                        val g = ga.getJSONObject(i)
                        groups += Group(g.getString("id"), g.optString("name", ""), g.optBoolean("enabled", true))
                    }
                }
            }
            if (groups.none { it.id == DEFAULT_GROUP_ID }) groups.add(0, Group(DEFAULT_GROUP_ID, "기본"))
            val ids = groups.map { it.id }.toSet()

            val rules = ArrayList<AppRule>()
            o.optJSONArray("rules")?.let { ra ->
                for (i in 0 until ra.length()) {
                    runCatching { AppRule.fromJson(ra.getJSONObject(i)) }.getOrNull()?.let {
                        rules += if (it.groupId in ids) it else it.copy(groupId = DEFAULT_GROUP_ID)
                    }
                }
            }
            val domainRules = ArrayList<DomainRule>()
            o.optJSONArray("domainRules")?.let { da ->
                for (i in 0 until da.length()) {
                    runCatching { DomainRule.fromJson(da.getJSONObject(i)) }.getOrNull()?.let {
                        domainRules += if (it.groupId in ids) it else it.copy(groupId = DEFAULT_GROUP_ID)
                    }
                }
            }
            val parsedModes = o.optJSONArray("netModes")?.let { a ->
                (0 until a.length()).map { a.optString(it, "") }.filter { it in KNOWN_NET_MODES }.toSet()
            }
            // Private DNS 는 내장 VPN 과 병행할 수 없으므로, 손상/구버전 설정에서 둘 다 있으면 DNS VPN 을 우선한다.
            val modes = parsedModes?.let {
                if (NET_PRIVATE_DNS in it && NET_DNS_VPN in it) it - NET_PRIVATE_DNS else it
            }
            return RuleSet(
                groups = groups,
                rules = rules,
                domainRules = domainRules,
                netWhitelist = o.optBoolean("netWhitelist", false),
                netModes = if (modes.isNullOrEmpty()) DEFAULT_NET_MODES else modes,
                privateDnsHost = o.optString("privateDnsHost", ""),
            )
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
