package app.devicelock

/**
 * 네트워크 화이트리스트용 도메인 처리.
 *
 * 안드로이드의 프록시 예외 목록 검증은 라벨마다 [a-zA-Z0-9]+(-[a-zA-Z0-9]+)* 형태만 허용한다.
 * 형식이 틀리면 시스템이 설정을 "조용히 무시"하므로, 여기서 같은 규칙으로 먼저 거른다.
 * (그래서 한글 도메인의 punycode 표기 xn-- 처럼 하이픈이 연속되는 주소는 지원하지 않는다.)
 */
object Domains {

    /** 시스템 네트워크 연결 확인용 주소. 화이트리스트가 켜져 있어도 항상 허용한다. */
    val SYSTEM_ALLOWED: List<String> = listOf(
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "clients3.google.com",
    )

    /** 내장 DNS VPN 방식에서 추가로 항상 허용하는 주소(시간 동기화). 하위 도메인도 허용된다. */
    val DNS_EXTRA_ALLOWED: List<String> = listOf("time.android.com", "pool.ntp.org")

    /** 시스템 프록시 예외 목록의 문자열 길이 한도(5000자)보다 여유를 둔 값 */
    const val MAX_LIST_CHARS = 4800

    private val SCHEME = Regex("^[a-z][a-z0-9+.\\-]*://")
    private val PORT = Regex(":\\d+$")
    private val LABEL = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

    fun isIpv4(host: String): Boolean = IPV4.matches(host)

    /**
     * 사용자가 넣은 문자열(주소창에 붙여넣은 URL 포함)에서 호스트만 뽑아 정규화한다.
     * 올바르지 않으면 null. 예) "https://www.YouTube.com/watch?v=1" -> "www.youtube.com"
     */
    fun normalize(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.replace(SCHEME, "")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        s = s.replace(PORT, "")
        s = s.removePrefix("*.").removePrefix(".").trimEnd('.')
        if (s.isEmpty() || s.length > 253) return null
        if (isIpv4(s)) return s
        val labels = s.split('.')
        if (labels.size < 2) return null
        if (labels.any { it.length !in 1..63 || !LABEL.matches(it) }) return null
        if (labels.last().all { it.isDigit() }) return null
        return s
    }

    /**
     * 프록시 예외 목록 항목으로 변환한다.
     * 클라이언트마다 와일드카드 해석이 달라서 "도메인 자체"와 "*.도메인"을 둘 다 넣는다.
     */
    fun bypassEntries(domains: Collection<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (d in domains.sorted()) {
            out += d
            if (!isIpv4(d)) out += "*.$d"
        }
        return out.toList()
    }

    /** name 이 allowed 의 도메인이거나 그 하위 도메인이면 true */
    fun matchesAllowed(name: String, allowed: Set<String>): Boolean {
        var n = name.lowercase().trimEnd('.')
        while (n.isNotEmpty()) {
            if (n in allowed) return true
            val i = n.indexOf('.')
            if (i < 0) return false
            n = n.substring(i + 1)
        }
        return false
    }

    /** 콤마로 이어 붙였을 때의 길이 */
    fun listLength(entries: List<String>): Int =
        entries.sumOf { it.length } + (entries.size - 1).coerceAtLeast(0)
}
