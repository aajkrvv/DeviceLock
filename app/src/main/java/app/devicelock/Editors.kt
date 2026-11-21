package app.devicelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

private val TIME_RE = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

private fun parseMinutes(s: String): Int? {
    val m = TIME_RE.matchEntire(s.trim()) ?: return null
    return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
}

private fun parseDate(s: String): Long? = runCatching { LocalDate.parse(s.trim()).toEpochDay() }.getOrNull()

// ----------------------------------------------------------------------
// 스케줄 입력 (앱 차단 규칙과 도메인 허용 규칙이 함께 쓴다)
// ----------------------------------------------------------------------

@Stable
class ScheduleForm(sc: Schedule) {
    var days by mutableStateOf(sc.days)
    var allDay by mutableStateOf(sc.allDay)
    // 종일 규칙은 시작=종료(0)로 저장되어 있으므로, 시간 지정으로 바꿀 때를 위해 기본 시각을 채워 둔다
    var start by mutableStateOf(if (sc.startMin == sc.endMin) "21:00" else formatMinutes(sc.startMin))
    var end by mutableStateOf(if (sc.startMin == sc.endMin) "06:00" else formatMinutes(sc.endMin))
    var from by mutableStateOf(sc.dateFrom?.let { LocalDate.ofEpochDay(it).toString() } ?: "")
    var to by mutableStateOf(sc.dateTo?.let { LocalDate.ofEpochDay(it).toString() } ?: "")

    /** 입력을 검증해 Schedule 로 만든다. 오류면 (null, 메시지) */
    fun build(): Pair<Schedule?, String?> {
        val s = parseMinutes(start)
        val e = parseMinutes(end)
        val df = if (from.isBlank()) null else parseDate(from)
        val dt = if (to.isBlank()) null else parseDate(to)
        val err = when {
            !allDay && (s == null || e == null) -> "시간은 HH:mm 형식으로 입력해 주세요"
            !allDay && s == e -> "시작과 종료 시각이 같을 수 없습니다"
            from.isNotBlank() && df == null -> "시작 날짜 형식이 올바르지 않습니다"
            to.isNotBlank() && dt == null -> "종료 날짜 형식이 올바르지 않습니다"
            df != null && dt != null && dt < df -> "종료 날짜가 시작 날짜보다 빠릅니다"
            else -> null
        }
        if (err != null) return null to err
        return Schedule(days = days, allDay = allDay, startMin = s ?: 0, endMin = e ?: 0, dateFrom = df, dateTo = dt) to null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleFields(f: ScheduleForm, daysLabel: String, allDayLabel: String) {
    Text(daysLabel, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, label ->
            val d = i + 1
            FilterChip(
                selected = d in f.days,
                onClick = { f.days = if (d in f.days) f.days - d else f.days + d },
                label = { Text(label) },
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(allDayLabel, modifier = Modifier.weight(1f))
        Switch(checked = f.allDay, onCheckedChange = { f.allDay = it })
    }
    if (!f.allDay) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = f.start, onValueChange = { f.start = it }, singleLine = true,
                label = { Text("시작 HH:mm") }, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = f.end, onValueChange = { f.end = it }, singleLine = true,
                label = { Text("종료 HH:mm") }, modifier = Modifier.weight(1f),
            )
        }
        Text("시작이 종료보다 늦으면 자정을 넘기는 구간입니다 (예: 21:00~06:00).", style = MaterialTheme.typography.bodySmall)
    }

    Text("날짜 범위 (비우면 제한 없음)", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = f.from, onValueChange = { f.from = it }, singleLine = true,
            label = { Text("시작 yyyy-MM-dd") }, modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = f.to, onValueChange = { f.to = it }, singleLine = true,
            label = { Text("종료 yyyy-MM-dd") }, modifier = Modifier.weight(1f),
        )
    }
}

// ----------------------------------------------------------------------
// 위치 예외 입력 (앱 차단 규칙과 도메인 허용 규칙이 함께 쓴다)
// ----------------------------------------------------------------------

@Stable
class ExemptionForm(ex: Exemption?) {
    var enabled by mutableStateOf(ex != null)
    var lat by mutableStateOf(ex?.lat?.toString() ?: "")
    var lon by mutableStateOf(ex?.lon?.toString() ?: "")
    var radius by mutableStateOf((ex?.radiusM ?: 200).toString())
    val window = ScheduleForm(ex?.window ?: Schedule(allDay = false, startMin = 21 * 60, endMin = 6 * 60))

    /** 꺼져 있으면 (null, null). 오류면 (null, 메시지) */
    fun build(): Pair<Exemption?, String?> {
        if (!enabled) return null to null
        val la = lat.trim().toDoubleOrNull()
        val lo = lon.trim().toDoubleOrNull()
        val r = radius.trim().toIntOrNull()
        val (w, windowErr) = window.build()
        val err = when {
            la == null || la < -90.0 || la > 90.0 -> "위도는 -90~90 사이 숫자로 입력해 주세요"
            lo == null || lo < -180.0 || lo > 180.0 -> "경도는 -180~180 사이 숫자로 입력해 주세요"
            r == null || r < Exemption.MIN_RADIUS || r > Exemption.MAX_RADIUS ->
                "반경은 ${Exemption.MIN_RADIUS}~${Exemption.MAX_RADIUS}m 로 입력해 주세요"
            else -> windowErr
        }
        if (err != null || la == null || lo == null || r == null || w == null) return null to (err ?: "위치 예외 입력을 확인해 주세요")
        return Exemption(la, lo, r, w) to null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExemptionFields(f: ExemptionForm) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("위치 예외 (일시 비활성화)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = f.enabled, onCheckedChange = { f.enabled = it })
    }
    if (!f.enabled) return

    Text(
        "아래 위치의 반경 안에 있고, 아래 시간대에 해당하는 동안 이 규칙을 일시적으로 끕니다. " +
            "위치를 알 수 없거나 확인된 지 오래됐으면 규칙은 그대로 적용됩니다.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = f.lat, onValueChange = { f.lat = it }, singleLine = true,
            label = { Text("위도") }, modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = f.lon, onValueChange = { f.lon = it }, singleLine = true,
            label = { Text("경도") }, modifier = Modifier.weight(1f),
        )
    }
    OutlinedButton(onClick = {
        val g = LocationGate.lastKnownForSetup(ctx)
        if (g == null) {
            msg = "현재 위치를 가져오지 못했습니다. 위치 권한과 기기 위치 설정을 확인하고, 지도 앱 등으로 위치를 한 번 확인한 뒤 다시 시도해 주세요."
        } else {
            f.lat = g.lat.toString()
            f.lon = g.lon.toString()
            msg = null
        }
    }) { Text("현재 위치 사용") }

    Text("반경", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(100, 200, 300, 500, 1000).forEach { m ->
            FilterChip(
                selected = f.radius.trim() == m.toString(),
                onClick = { f.radius = m.toString() },
                label = { Text("${m}m") },
            )
        }
    }
    OutlinedTextField(
        value = f.radius, onValueChange = { f.radius = it }, singleLine = true,
        label = { Text("반경 직접 입력 (m)") }, modifier = Modifier.fillMaxWidth(),
    )
    msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    ScheduleFields(f.window, daysLabel = "예외 적용 요일 (선택 없음 = 매일)", allDayLabel = "하루 종일")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupChips(groups: List<Group>, selectedId: String, onSelect: (String) -> Unit) {
    Text("그룹", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { g ->
            FilterChip(selected = g.id == selectedId, onClick = { onSelect(g.id) }, label = { Text(g.name) })
        }
    }
}

// ----------------------------------------------------------------------
// 앱 차단 규칙 편집
// ----------------------------------------------------------------------

@Composable
fun RuleEditorDialog(initial: AppRule?, groups: List<Group>, onDismiss: () -> Unit, onSave: (AppRule) -> Unit) {
    val form = remember { ScheduleForm(initial?.schedule ?: Schedule(allDay = true, startMin = 21 * 60, endMin = 6 * 60)) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var groupId by remember { mutableStateOf(initial?.groupId ?: groups.first().id) }
    var packages by remember { mutableStateOf(initial?.packages?.toSet() ?: emptySet()) }
    var err by remember { mutableStateOf<String?>(null) }
    var pickApps by remember { mutableStateOf(false) }
    val exForm = remember { ExemptionForm(initial?.exemption) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "규칙 추가" else "규칙 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("규칙 이름") })
                GroupChips(groups, groupId) { groupId = it }
                OutlinedButton(onClick = { pickApps = true }) { Text("차단할 앱 선택 (${packages.size}개)") }

                ScheduleFields(form, daysLabel = "차단 요일 (선택 없음 = 매일)", allDayLabel = "하루 종일")
                ExemptionFields(exForm)

                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (sched, schedErr) = form.build()
                val (exemption, exErr) = exForm.build()
                err = when {
                    name.isBlank() -> "규칙 이름을 입력해 주세요"
                    packages.isEmpty() -> "차단할 앱을 한 개 이상 선택해 주세요"
                    else -> schedErr ?: exErr
                }
                if (err == null && sched != null) {
                    onSave(
                        AppRule(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            groupId = groupId,
                            packages = packages.toList().sorted(),
                            schedule = sched,
                            enabled = initial?.enabled ?: true,
                            exemption = exemption,
                        ),
                    )
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )

    if (pickApps) {
        AppPickerDialog(packages, { pickApps = false }) { packages = it; pickApps = false }
    }
}

// ----------------------------------------------------------------------
// 도메인 허용 규칙 편집
// ----------------------------------------------------------------------

/**
 * @param others 편집 중인 규칙을 뺀 나머지 도메인 규칙(전체 허용 목록 길이 한도 검사용)
 */
@Composable
fun DomainRuleEditorDialog(
    initial: DomainRule?,
    groups: List<Group>,
    others: List<DomainRule>,
    onDismiss: () -> Unit,
    onSave: (DomainRule) -> Unit,
) {
    // 새 규칙의 기본값: 매일 18:00~20:00 허용
    val form = remember { ScheduleForm(initial?.schedule ?: Schedule(allDay = false, startMin = 18 * 60, endMin = 20 * 60)) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var groupId by remember { mutableStateOf(initial?.groupId ?: groups.first().id) }
    var text by remember { mutableStateOf(initial?.domains?.joinToString("\n") ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    val exForm = remember { ExemptionForm(initial?.exemption) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "도메인 허용 규칙 추가" else "도메인 허용 규칙 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("규칙 이름") })
                GroupChips(groups, groupId) { groupId = it }

                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("허용할 주소 (줄바꿈·쉼표로 구분)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "도메인과 그 하위 도메인이 모두 허용됩니다. 주소창의 URL을 붙여넣어도 됩니다. " +
                        "한 서비스가 여러 도메인을 쓰면 모두 넣어야 합니다 (예: YouTube = youtube.com, googlevideo.com, ytimg.com).",
                    style = MaterialTheme.typography.bodySmall,
                )

                ScheduleFields(form, daysLabel = "허용 요일 (선택 없음 = 매일)", allDayLabel = "하루 종일 허용")
                Text("위 요일·시간에만 허용됩니다. 그 밖의 시간에는 네트워크 화이트리스트가 막습니다.", style = MaterialTheme.typography.bodySmall)
                ExemptionFields(exForm)

                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (sched, schedErr) = form.build()
                val (exemption, exErr) = exForm.build()
                val parsed = text.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.map { it to Domains.normalize(it) }
                val bad = parsed.filter { it.second == null }.map { it.first }
                val domains = parsed.mapNotNull { it.second }.distinct().sorted()
                val union = (others.flatMap { it.domains } + domains).toSet()
                val listLen = Domains.listLength(Domains.bypassEntries(Domains.SYSTEM_ALLOWED + union))
                err = when {
                    name.isBlank() -> "규칙 이름을 입력해 주세요"
                    bad.isNotEmpty() ->
                        "올바르지 않은 주소: ${bad.take(3).joinToString(", ")}${if (bad.size > 3) " 등" else ""} " +
                            "(한글 도메인, IPv6, 점이 없는 이름은 지원하지 않습니다)"
                    domains.isEmpty() -> "허용할 주소를 한 개 이상 입력해 주세요"
                    listLen > Domains.MAX_LIST_CHARS -> "허용 도메인이 너무 많습니다. 다른 규칙과 합쳐 전체 개수를 줄여 주세요"
                    else -> schedErr ?: exErr
                }
                if (err == null && sched != null) {
                    onSave(
                        DomainRule(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            groupId = groupId,
                            domains = domains,
                            schedule = sched,
                            enabled = initial?.enabled ?: true,
                            exemption = exemption,
                        ),
                    )
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

// ----------------------------------------------------------------------
// 앱 선택
// ----------------------------------------------------------------------

private data class AppEntry(val pkg: String, val label: String, val installed: Boolean)

/**
 * 런처에 보이는 앱 + 이미 선택돼 있지만 런처 목록에 없는 앱(삭제됐거나 런처 아이콘이 없는 앱).
 * 후자를 보여 줘야 사용자가 선택을 해제할 수 있고, 삭제된 앱은 다시 설치되면 자동으로 차단된다는 것도 알 수 있다.
 */
private fun loadPickerApps(ctx: Context, selected: Set<String>): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launcher = pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), true) }
        .distinctBy { it.pkg }
        .filter { it.pkg != ctx.packageName }
        .sortedBy { it.label.lowercase() }
    val listed = launcher.map { it.pkg }.toSet()
    val extra = selected.filter { it !in listed }.map { pkg ->
        val label = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull()
        AppEntry(pkg, label ?: pkg, label != null)
    }.sortedBy { it.label.lowercase() }
    return extra + launcher
}

@Composable
fun AppPickerDialog(selected: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var sel by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    // 목록을 연 뒤에 앱이 설치·삭제·변경되면 자동으로 다시 불러온다
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                refreshKey++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    // 짧은 시간에 이벤트가 몰리면 마지막 하나만 처리한다(앞의 것은 재시작으로 취소됨)
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) delay(300)
        apps = withContext(Dispatchers.Default) { loadPickerApps(ctx, selected) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("차단할 앱 선택") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("검색") })
                Spacer(Modifier.height(8.dp))
                val list = apps?.filter {
                    query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
                }
                if (list == null) {
                    Text("불러오는 중...")
                } else {
                    LazyColumn(Modifier.height(360.dp)) {
                        items(list, key = { it.pkg }) { e ->
                            Row(
                                Modifier.fillMaxWidth().clickable { sel = if (e.pkg in sel) sel - e.pkg else sel + e.pkg }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(checked = e.pkg in sel, onCheckedChange = null)
                                Column {
                                    Text(if (e.installed) e.label else "(미설치) ${e.label}")
                                    Text(
                                        if (e.installed) e.pkg else "${e.pkg} · 다시 설치되면 자동으로 차단됩니다",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(sel) }) { Text("완료 (${sel.size}개)") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
