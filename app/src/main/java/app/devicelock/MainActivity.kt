package app.devicelock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }

    override fun onStop() {
        Session.lock()
        super.onStop()
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val policy = remember { PolicyController(ctx) }
    val pw = remember { PasswordStore(ctx) }
    var passwordSet by remember { mutableStateOf(pw.isSet()) }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            when {
                !policy.isDeviceOwner() -> SetupGuide(ctx.packageName)
                !passwordSet -> SetPasswordScreen(pw) { passwordSet = true }
                else -> MainScreen(policy, pw)
            }
        }
    }
}

@Composable
fun SetupGuide(pkg: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device Owner 권한이 필요합니다", style = MaterialTheme.typography.titleLarge)
        Text("기기에 계정(구글 등)이 하나도 없는 상태에서, USB 디버깅을 켠 뒤 PC에서 아래 명령을 실행하세요.")
        SelectionContainer {
            Text("adb shell dpm set-device-owner $pkg/.DeviceLockAdminReceiver")
        }
        Text("실행에 성공하면 이 앱을 다시 열어 주세요.")
    }
}

@Composable
fun SetPasswordScreen(store: PasswordStore, onDone: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("비밀번호 설정", style = MaterialTheme.typography.titleLarge)
        Text("규칙을 바꾸거나 삭제할 때 필요합니다. 분실하면 복구할 방법이 없고 기기 초기화만 가능합니다.")
        OutlinedTextField(
            value = a, onValueChange = { a = it }, singleLine = true,
            label = { Text("비밀번호 (6자 이상)") },
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = b, onValueChange = { b = it }, singleLine = true,
            label = { Text("비밀번호 확인") },
            visualTransformation = PasswordVisualTransformation(),
        )
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            when {
                a.length < 6 -> err = "6자 이상 입력해 주세요"
                a != b -> err = "두 비밀번호가 다릅니다"
                else -> {
                    store.set(a)
                    onDone()
                }
            }
        }) { Text("설정") }
    }
}

@Composable
fun MainScreen(policy: PolicyController, pw: PasswordStore) {
    val ctx = LocalContext.current
    val store = remember { RuleStore(ctx) }
    var rules by remember { mutableStateOf(store.load()) }
    var unlocked by remember { mutableStateOf(Session.isUnlocked()) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showPw by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var addingGroup by remember { mutableStateOf(false) }
    var deleteRule by remember { mutableStateOf<AppRule?>(null) }
    var deleteGroup by remember { mutableStateOf<Group?>(null) }

    LaunchedEffect(Unit) {
        notes = withContext(Dispatchers.Default) {
            val failures = policy.applyBaseline()
            RuleEngine.evaluate(ctx)
            failures
        }
        while (true) {
            delay(3000)
            unlocked = Session.isUnlocked()
        }
    }

    fun commit(next: RuleSet) {
        store.save(next)
        rules = next
        RuleEngine.evaluate(ctx)
    }

    fun guard(action: () -> Unit) {
        if (Session.isUnlocked()) action() else {
            pending = action
            showPw = true
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Device Owner: 활성", style = MaterialTheme.typography.titleMedium)
                    Text("지금 차단 중인 앱: ${rules.blockedPackages(LocalDateTime.now()).size}개")
                    if (notes.isEmpty()) Text("기본 정책 적용: 정상")
                    else notes.forEach { Text("· $it", color = MaterialTheme.colorScheme.error) }
                    val refused = remember(rules, notes) { store.loadFailed() }
                    if (refused.isNotEmpty()) {
                        Text(
                            "시스템이 차단을 거부한 앱: ${refused.joinToString()}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (unlocked) "편집 가능 (5분)" else "잠김", modifier = Modifier.weight(1f))
                if (unlocked) {
                    OutlinedButton(onClick = { Session.lock(); unlocked = false }) { Text("잠그기") }
                } else {
                    Button(onClick = { pending = null; showPw = true }) { Text("잠금 해제") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { guard { creating = true } }) { Text("규칙 추가") }
                OutlinedButton(onClick = { guard { addingGroup = true } }) { Text("그룹 추가") }
            }
        }

        rules.groups.forEach { g ->
            item(key = "g-${g.id}") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (g.id != DEFAULT_GROUP_ID) {
                        TextButton(onClick = { guard { deleteGroup = g } }) { Text("그룹 삭제") }
                    }
                    Switch(checked = g.enabled, onCheckedChange = {
                        guard {
                            commit(rules.copy(groups = rules.groups.map { x -> if (x.id == g.id) x.copy(enabled = !g.enabled) else x }))
                        }
                    })
                }
            }
            items(rules.rules.filter { it.groupId == g.id }, key = { "r-${it.id}" }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Switch(checked = r.enabled, onCheckedChange = {
                                guard {
                                    commit(rules.copy(rules = rules.rules.map { x -> if (x.id == r.id) x.copy(enabled = !r.enabled) else x }))
                                }
                            })
                        }
                        Text("${r.schedule.summary()} · 앱 ${r.packages.size}개", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { guard { editing = r } }) { Text("수정") }
                            TextButton(onClick = { guard { deleteRule = r } }) { Text("삭제") }
                        }
                    }
                }
            }
        }
    }

    if (showPw) {
        PasswordDialog(
            store = pw,
            onDismiss = { showPw = false; pending = null },
            onOk = {
                showPw = false
                unlocked = true
                pending?.invoke()
                pending = null
            },
        )
    }
    if (creating) {
        RuleEditorDialog(null, rules.groups, { creating = false }) { r ->
            creating = false
            commit(rules.copy(rules = rules.rules + r))
        }
    }
    editing?.let { cur ->
        RuleEditorDialog(cur, rules.groups, { editing = null }) { r ->
            editing = null
            commit(rules.copy(rules = rules.rules.map { if (it.id == r.id) r else it }))
        }
    }
    if (addingGroup) {
        GroupDialog({ addingGroup = false }) { name ->
            addingGroup = false
            commit(rules.copy(groups = rules.groups + Group(UUID.randomUUID().toString(), name)))
        }
    }
    deleteRule?.let { r ->
        ConfirmDialog("규칙 삭제", "'${r.name}' 규칙을 삭제할까요?", { deleteRule = null }) {
            deleteRule = null
            commit(rules.copy(rules = rules.rules.filter { it.id != r.id }))
        }
    }
    deleteGroup?.let { g ->
        ConfirmDialog("그룹 삭제", "'${g.name}' 그룹과 안의 규칙이 모두 삭제됩니다. 계속할까요?", { deleteGroup = null }) {
            deleteGroup = null
            commit(rules.copy(groups = rules.groups.filter { it.id != g.id }, rules = rules.rules.filter { it.groupId != g.id }))
        }
    }
}

@Composable
fun PasswordDialog(store: PasswordStore, onDismiss: () -> Unit, onOk: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("비밀번호 입력") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = store.verify(input)) {
                    is VerifyResult.Ok -> { Session.unlock(); onOk() }
                    is VerifyResult.Wrong -> err = "비밀번호가 다릅니다 (연속 실패 ${r.failures}회)"
                    is VerifyResult.LockedOut -> {
                        val sec = ((r.untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                        err = "입력이 잠겼습니다. ${sec}초 후 다시 시도하세요."
                    }
                }
                input = ""
            }) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun GroupDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("그룹 추가") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("그룹 이름") }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("추가") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private val TIME_RE = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

private fun parseMinutes(s: String): Int? {
    val m = TIME_RE.matchEntire(s.trim()) ?: return null
    return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
}

private fun parseDate(s: String): Long? = runCatching { LocalDate.parse(s.trim()).toEpochDay() }.getOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorDialog(initial: AppRule?, groups: List<Group>, onDismiss: () -> Unit, onSave: (AppRule) -> Unit) {
    val sc = initial?.schedule ?: Schedule()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var groupId by remember { mutableStateOf(initial?.groupId ?: groups.first().id) }
    var packages by remember { mutableStateOf(initial?.packages?.toSet() ?: emptySet()) }
    var days by remember { mutableStateOf(sc.days) }
    var allDay by remember { mutableStateOf(sc.allDay) }
    var start by remember { mutableStateOf(if (initial == null) "21:00" else formatMinutes(sc.startMin)) }
    var end by remember { mutableStateOf(if (initial == null) "06:00" else formatMinutes(sc.endMin)) }
    var from by remember { mutableStateOf(sc.dateFrom?.let { LocalDate.ofEpochDay(it).toString() } ?: "") }
    var to by remember { mutableStateOf(sc.dateTo?.let { LocalDate.ofEpochDay(it).toString() } ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    var pickApps by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "규칙 추가" else "규칙 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("규칙 이름") })

                Text("그룹", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { g ->
                        FilterChip(selected = g.id == groupId, onClick = { groupId = g.id }, label = { Text(g.name) })
                    }
                }

                OutlinedButton(onClick = { pickApps = true }) { Text("차단할 앱 선택 (${packages.size}개)") }

                Text("적용 요일 (선택 없음 = 매일)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, label ->
                        val d = i + 1
                        FilterChip(
                            selected = d in days,
                            onClick = { days = if (d in days) days - d else days + d },
                            label = { Text(label) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("하루 종일", modifier = Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                if (!allDay) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = start, onValueChange = { start = it }, singleLine = true,
                            label = { Text("시작 HH:mm") }, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = end, onValueChange = { end = it }, singleLine = true,
                            label = { Text("종료 HH:mm") }, modifier = Modifier.weight(1f),
                        )
                    }
                    Text("시작이 종료보다 늦으면 자정을 넘기는 구간입니다 (예: 21:00~06:00).", style = MaterialTheme.typography.bodySmall)
                }

                Text("날짜 범위 (비우면 제한 없음)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = from, onValueChange = { from = it }, singleLine = true,
                        label = { Text("시작 yyyy-MM-dd") }, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = to, onValueChange = { to = it }, singleLine = true,
                        label = { Text("종료 yyyy-MM-dd") }, modifier = Modifier.weight(1f),
                    )
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = parseMinutes(start)
                val e = parseMinutes(end)
                val df = if (from.isBlank()) null else parseDate(from)
                val dt = if (to.isBlank()) null else parseDate(to)
                err = when {
                    name.isBlank() -> "규칙 이름을 입력해 주세요"
                    packages.isEmpty() -> "차단할 앱을 한 개 이상 선택해 주세요"
                    !allDay && (s == null || e == null) -> "시간은 HH:mm 형식으로 입력해 주세요"
                    !allDay && s == e -> "시작과 종료 시각이 같을 수 없습니다"
                    from.isNotBlank() && df == null -> "시작 날짜 형식이 올바르지 않습니다"
                    to.isNotBlank() && dt == null -> "종료 날짜 형식이 올바르지 않습니다"
                    df != null && dt != null && dt < df -> "종료 날짜가 시작 날짜보다 빠릅니다"
                    else -> null
                }
                if (err == null) {
                    onSave(
                        AppRule(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            groupId = groupId,
                            packages = packages.toList().sorted(),
                            schedule = Schedule(
                                days = days,
                                allDay = allDay,
                                startMin = s ?: 0,
                                endMin = e ?: 0,
                                dateFrom = df,
                                dateTo = dt,
                            ),
                            enabled = initial?.enabled ?: true,
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

private fun loadLauncherApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .distinctBy { it.first }
        .filter { it.first != ctx.packageName }
        .sortedBy { it.second.lowercase() }
}

@Composable
fun AppPickerDialog(selected: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var sel by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadLauncherApps(ctx) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("차단할 앱 선택") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("검색") })
                Spacer(Modifier.height(8.dp))
                val list = apps?.filter {
                    query.isBlank() || it.second.contains(query, ignoreCase = true) || it.first.contains(query, ignoreCase = true)
                }
                if (list == null) {
                    Text("불러오는 중...")
                } else {
                    LazyColumn(Modifier.height(360.dp)) {
                        items(list, key = { it.first }) { (pkg, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { sel = if (pkg in sel) sel - pkg else sel + pkg }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(checked = pkg in sel, onCheckedChange = null)
                                Column {
                                    Text(label)
                                    Text(pkg, style = MaterialTheme.typography.bodySmall)
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
