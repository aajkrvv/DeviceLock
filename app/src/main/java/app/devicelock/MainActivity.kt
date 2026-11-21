package app.devicelock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        Text("규칙을 바꾸거나 삭제할 때, 그리고 앱을 제거할 때 필요합니다. 분실하면 복구할 방법이 없고 기기 초기화만 가능합니다.")
        OutlinedTextField(
            value = a, onValueChange = { a = it }, singleLine = true,
            label = { Text("비밀번호 (6자 이상)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            value = b, onValueChange = { b = it }, singleLine = true,
            label = { Text("비밀번호 확인") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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

/** Device Owner 를 해제한 뒤 호출한다. 시스템 제거 확인 화면이 뜬다. */
private fun startUninstall(ctx: Context) {
    val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i)
}

@Composable
fun MainScreen(policy: PolicyController, pw: PasswordStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { RuleStore(ctx) }
    var rules by remember { mutableStateOf(store.load()) }
    var unlocked by remember { mutableStateOf(Session.isUnlocked()) }
    var smartSwitch by remember { mutableStateOf(store.isSmartSwitchAllowed()) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var statusTick by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showPw by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editingDomain by remember { mutableStateOf<DomainRule?>(null) }
    var creatingDomain by remember { mutableStateOf(false) }
    var addingGroup by remember { mutableStateOf(false) }
    var deleteRule by remember { mutableStateOf<AppRule?>(null) }
    var deleteDomain by remember { mutableStateOf<DomainRule?>(null) }
    var deleteGroup by remember { mutableStateOf<Group?>(null) }
    var confirmNetOn by remember { mutableStateOf(false) }
    var uninstallStep by remember { mutableStateOf(0) } // 0 없음, 1 비밀번호 확인, 2 최종 확인
    var uninstallError by remember { mutableStateOf<String?>(null) }

    val strong = remember { StrongMode(ctx) }
    var strongOn by remember { mutableStateOf(strong.isOn()) }
    var phase by remember { mutableStateOf<StrongPhase>(strong.phase()) }
    var confirmStrong by remember { mutableStateOf(false) }
    var editingNet by remember { mutableStateOf(false) }

    // 편집 가능 여부: 일반 모드는 비밀번호 입력 후 5분, 강력 모드는 24시간 대기가 끝난 구간 안에서 비밀번호 입력 후 5분
    fun canEdit(): Boolean =
        Session.isUnlocked() && (!strong.isOn() || strong.phase() is StrongPhase.Ready)

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        statusTick++
        scope.launch { withContext(Dispatchers.Default) { RuleEngine.evaluate(ctx, force = true) } }
    }

    LaunchedEffect(Unit) {
        notes = withContext(Dispatchers.Default) {
            val failures = policy.applyBaseline()
            RuleEngine.evaluate(ctx, force = true)
            failures
        }
        statusTick++
        while (true) {
            delay(3000)
            val before = phase
            val after = strong.phase()
            phase = after
            unlocked = canEdit()
            // 대기 시작·종료·편집 구간 종료 때 네트워크 상태를 즉시 맞춘다
            if (after::class != before::class) {
                withContext(Dispatchers.Default) { RuleEngine.evaluate(ctx, force = true) }
                statusTick++
            }
            // 제거를 시도하다 취소하는 등 Device Owner 가 내려간 경우 화면을 안내 화면으로 되돌린다
            if (!policy.isDeviceOwner()) {
                (ctx as? Activity)?.recreate()
                break
            }
        }
    }

    fun commit(next: RuleSet) {
        store.save(next)
        rules = next
        statusTick++
        // Private DNS 확인 등 시간이 걸릴 수 있어 메인 스레드에서 돌리지 않는다
        scope.launch {
            withContext(Dispatchers.Default) { RuleEngine.evaluate(ctx) }
            statusTick++
        }
    }

    fun guard(action: () -> Unit) {
        if (canEdit()) action() else {
            pending = action
            showPw = true
        }
    }

    val refused = remember(rules, notes, statusTick) { store.loadFailed() }
    val missing = remember(rules, notes, statusTick) { store.loadMissing() }
    val syncErrors = remember(rules, notes, statusTick) { store.loadSyncErrors() }
    val netError = remember(rules, notes, statusTick) { store.loadNetError() }
    val loadIssue = remember(rules, notes, statusTick) { store.loadIssue() }
    val vpnError = remember(rules, notes, statusTick) { NetState.error(ctx) }
    val needVpnConsent = remember(statusTick) { runCatching { VpnService.prepare(ctx) != null }.getOrDefault(false) }
    val geoKnown = remember(rules, statusTick) { LocationGate.current(ctx) != null }
    val geoIssue = remember(rules, statusTick) { LocationGate.issue(ctx) }
    val geoPermOk = remember(rules, statusTick) { LocationGate.hasPermission(ctx) && LocationGate.hasBackgroundPermission(ctx) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Device Owner: 활성", style = MaterialTheme.typography.titleMedium)
                    Text("지금 차단 중인 앱: ${rules.blockedPackages(LocalDateTime.now()).size}개")
                    if (notes.isEmpty()) Text("기본 정책 적용: 정상")
                    else notes.forEach { Text("· $it", color = MaterialTheme.colorScheme.error) }
                    if (refused.isNotEmpty()) {
                        Text(
                            "시스템이 차단을 거부한 앱: ${refused.joinToString()}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (missing.isNotEmpty()) {
                        Text(
                            "설치되지 않은 규칙 앱 ${missing.size}개: ${missing.joinToString()} — 다시 설치되면 자동으로 차단됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    syncErrors.forEach { Text("· $it", color = MaterialTheme.colorScheme.error) }
                    netError?.let { Text("네트워크 제한: $it", color = MaterialTheme.colorScheme.error) }
                    vpnError?.let { Text("VPN: $it", color = MaterialTheme.colorScheme.error) }
                    loadIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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

        // ---- 강력 모드 ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("강력 모드", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "켜면 규칙을 바꾸거나 앱을 제거하기 전에 비밀번호를 입력하고 24시간 동안 일반 앱의 인터넷 연결이 VPN 잠금으로 차단된 채 기다려야 합니다. 일부 시스템 앱은 예외일 수 있습니다. 한 번 켜면 앱에서 끌 수 없습니다.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = strongOn,
                            enabled = !strongOn,
                            onCheckedChange = { on -> if (on && !strongOn) guard { confirmStrong = true } },
                        )
                    }
                    when (val p = phase) {
                        is StrongPhase.Waiting -> {
                            Text(
                                "대기 중 — 남은 시간 ${formatDuration(p.remainingMs)}. 일반 앱의 인터넷 연결이 차단되어 있습니다(일부 시스템 앱 예외 가능).",
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = { pending = null; showPw = true }) { Text("대기 중단…") }
                        }
                        is StrongPhase.Ready ->
                            Text("규칙 변경 가능 구간 — 남은 시간 ${formatDuration(p.remainingMs)} (비밀번호 입력 필요)")
                        is StrongPhase.Idle ->
                            Text("규칙을 바꾸려면 비밀번호를 입력해 24시간 대기를 시작해야 합니다.", style = MaterialTheme.typography.bodySmall)
                        else -> {}
                    }
                }
            }
        }

        // ---- 네트워크 화이트리스트 ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("네트워크 화이트리스트", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "허용 주소 규칙을 프록시 또는 내장 DNS VPN 으로 적용합니다. Private DNS 는 DNS 서버만 지정하며 단독으로 허용 목록을 차단하지 않습니다.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = rules.netWhitelist, onCheckedChange = { on ->
                            guard {
                                if (on) {
                                    confirmNetOn = true
                                } else {
                                    commit(rules.copy(netWhitelist = false))
                                }
                            }
                        })
                    }
                    if (rules.netWhitelist) {
                        Text("지금 허용 중인 도메인: ${rules.allowedDomains(LocalDateTime.now()).size}개 (연결 확인용 시스템 주소는 항상 허용)")
                    } else {
                        Text("꺼져 있습니다. 아래 도메인 허용 규칙은 켜야 적용됩니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("차단 방식: " + modesLabel(rules), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "프록시는 시스템 프록시를 따르는 앱에만 적용됩니다. 내장 DNS VPN 은 UDP 53 DNS 질의 필터이며 IP 직접 접속·자체 DoH/DoT·TCP DNS는 막지 못합니다. Private DNS 단독은 차단 기능이 아닙니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { guard { editingNet = true } }) { Text("차단 방식 선택") }
                        if (needVpnConsent) {
                            OutlinedButton(onClick = {
                                VpnService.prepare(ctx)?.let { vpnLauncher.launch(it) }
                            }) { Text("VPN 권한 허용") }
                        }
                    }
                    if (needVpnConsent) {
                        Text(
                            "내장 DNS VPN 과 강력 모드의 일반 앱 인터넷 차단은 VPN 권한이 필요합니다. 일부 시스템 통신은 Android가 예외 처리할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (rules.hasExemptions()) {
                        Text(
                            "위치 예외: " + (if (geoKnown) "최근 위치 확인됨" else "위치 미확인(예외 적용 안 됨)"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        geoIssue?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (!geoPermOk) {
                            Text(
                                "위치 권한(항상 허용)이 필요합니다. 자동 부여가 거부된 기기에서는 직접 허용해 주세요.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }) { Text("앱 권한 설정 열기") }
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("백업·스마트 스위치 허용")
                    Text(
                        "Device Owner 상태에서는 백업과 삼성 스마트 스위치가 기본 차단됩니다. 켜면 백업 서비스를 허용하고 스마트 스위치 허용 설정을 시도합니다(동작 미검증).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = smartSwitch, onCheckedChange = { on ->
                    guard {
                        store.setSmartSwitchAllowed(on)
                        smartSwitch = on
                        runCatching { policy.applySmartSwitch(on) }
                    }
                })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { guard { creating = true } }) { Text("앱 규칙 추가") }
                OutlinedButton(onClick = { guard { creatingDomain = true } }) { Text("도메인 허용 규칙") }
            }
        }
        item {
            OutlinedButton(onClick = { guard { addingGroup = true } }) { Text("그룹 추가") }
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
                        Text("차단 ${r.schedule.summary()} · 앱 ${r.packages.size}개", style = MaterialTheme.typography.bodySmall)
                        r.exemption?.let { Text("위치 예외 · ${it.summary()}", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { guard { editing = r } }) { Text("수정") }
                            TextButton(onClick = { guard { deleteRule = r } }) { Text("삭제") }
                        }
                    }
                }
            }
            items(rules.domainRules.filter { it.groupId == g.id }, key = { "d-${it.id}" }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Switch(checked = r.enabled, onCheckedChange = {
                                guard {
                                    commit(rules.copy(domainRules = rules.domainRules.map { x -> if (x.id == r.id) x.copy(enabled = !r.enabled) else x }))
                                }
                            })
                        }
                        Text("허용 ${r.schedule.summary()}", style = MaterialTheme.typography.bodySmall)
                        r.exemption?.let { Text("위치 예외 · ${it.summary()}", style = MaterialTheme.typography.bodySmall) }
                        Text(
                            r.domains.take(3).joinToString(", ") + if (r.domains.size > 3) " 외 ${r.domains.size - 3}개" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { guard { editingDomain = r } }) { Text("수정") }
                            TextButton(onClick = { guard { deleteDomain = r } }) { Text("삭제") }
                        }
                    }
                }
            }
        }

        // ---- 앱 제거 ----
        item(key = "uninstall") {
            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("앱 제거", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "비밀번호를 입력하면 앱 차단·네트워크 제한을 풀고 Device Owner 해제를 시도한 뒤 이 앱을 제거합니다." +
                            if (strongOn) " 강력 모드에서는 24시간 대기가 끝난 구간에서만 가능합니다." else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        // 강력 모드: 대기 시작/대기 중 안내를 먼저 거친다. 편집 가능 구간에서만 제거 절차로 넘어간다.
                        if (strongOn && phase !is StrongPhase.Ready) { pending = null; showPw = true } else uninstallStep = 1
                    }) { Text("비밀번호로 앱 제거") }
                }
            }
        }
    }

    if (showPw) {
        val p = phase
        if (strongOn && p is StrongPhase.Waiting) {
            StrongWaitDialog(
                remainingMs = p.remainingMs,
                onKeepWaiting = { showPw = false; pending = null },
                onCancelWait = {
                    strong.cancelWait()
                    phase = strong.phase()
                    unlocked = false
                    showPw = false
                    pending = null
                    scope.launch {
                        withContext(Dispatchers.Default) { RuleEngine.evaluate(ctx, force = true) }
                        statusTick++
                    }
                },
            )
        } else if (strongOn && p !is StrongPhase.Ready) {
            PasswordDialog(
                store = pw,
                title = "강력 모드 — 24시간 대기 시작",
                message = "비밀번호를 입력하면 24시간 대기가 시작되고, 그 동안 일반 앱의 인터넷 연결이 VPN 잠금으로 차단됩니다(일부 시스템 앱 예외 가능). " +
                    "대기가 끝난 뒤 30분 동안만 규칙을 바꿀 수 있습니다. 중간에 중단하면 처음부터 다시 기다려야 합니다.",
                grantSession = false,
                onDismiss = { showPw = false; pending = null },
                onOk = {
                    // VPN 승인이 없으면 24시간 타이머를 시작하지 않는다. 권한 승인 후 사용자가 다시 시작해야 한다.
                    val consent = VpnService.prepare(ctx)
                    if (consent != null) {
                        showPw = false
                        pending = null
                        vpnLauncher.launch(consent)
                    } else {
                        strong.startWait()
                        Session.lock()
                        unlocked = false
                        phase = strong.phase()
                        showPw = false
                        pending = null
                        scope.launch {
                            withContext(Dispatchers.Default) { RuleEngine.evaluate(ctx, force = true) }
                            statusTick++
                        }
                    }
                },
            )
        } else {
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
    }
    if (confirmStrong) {
        ConfirmDialog(
            "강력 모드 켜기",
            "한 번 켜면 다시 끌 수 없습니다. 이후에는 규칙 변경·앱 제거 때마다 비밀번호를 입력하고 24시간 동안 " +
                "일반 앱의 인터넷 연결이 차단된 채 기다려야 하며, 대기가 끝난 뒤 30분 동안만 변경할 수 있습니다. " +
                "비밀번호를 잃어버리면 기기 초기화 외에는 방법이 없습니다. 켤까요?",
            { confirmStrong = false },
        ) {
            confirmStrong = false
            guard {
                strong.enable()
                Session.lock()
                strongOn = true
                unlocked = false
                phase = strong.phase()
                // VPN 권한은 대기 시작 시 다시 확인한다. 이 단계는 강력 모드만 영구 활성화한다.
            }
        }
    }
    if (editingNet) {
        NetModesDialog(rules.netModes, rules.privateDnsHost, { editingNet = false }) { modes, host ->
            editingNet = false
            guard {
                commit(rules.copy(netModes = modes, privateDnsHost = host))
                if (NET_DNS_VPN in modes) VpnService.prepare(ctx)?.let { vpnLauncher.launch(it) }
            }
        }
    }
    if (confirmNetOn) {
        val none = rules.domainRules.none { it.enabled }
        ConfirmDialog(
            "네트워크 화이트리스트 켜기",
            (if (none) "허용 규칙이 하나도 없어 인터넷이 사실상 모두 끊깁니다. " else "") +
                "선택 방식: ${modesLabel(rules)}. 프록시는 시스템 프록시를 따르는 앱만, 내장 DNS VPN은 제한적인 DNS 질의만 필터링합니다. " +
                "Private DNS는 DNS 서버 지정일 뿐 허용 목록을 강제하지 않습니다. 켤까요?",
            { confirmNetOn = false },
        ) {
            confirmNetOn = false
            guard { commit(rules.copy(netWhitelist = true)) }
        }
    }
    if (creating) {
        RuleEditorDialog(null, rules.groups, { creating = false }) { r ->
            creating = false
            guard { commit(rules.copy(rules = rules.rules + r)) }
        }
    }
    editing?.let { cur ->
        RuleEditorDialog(cur, rules.groups, { editing = null }) { r ->
            editing = null
            guard { commit(rules.copy(rules = rules.rules.map { if (it.id == r.id) r else it })) }
        }
    }
    if (creatingDomain) {
        DomainRuleEditorDialog(null, rules.groups, rules.domainRules, { creatingDomain = false }) { r ->
            creatingDomain = false
            guard { commit(rules.copy(domainRules = rules.domainRules + r)) }
        }
    }
    editingDomain?.let { cur ->
        DomainRuleEditorDialog(cur, rules.groups, rules.domainRules.filter { it.id != cur.id }, { editingDomain = null }) { r ->
            editingDomain = null
            guard { commit(rules.copy(domainRules = rules.domainRules.map { if (it.id == r.id) r else it })) }
        }
    }
    if (addingGroup) {
        GroupDialog({ addingGroup = false }) { name ->
            addingGroup = false
            guard { commit(rules.copy(groups = rules.groups + Group(UUID.randomUUID().toString(), name))) }
        }
    }
    deleteRule?.let { r ->
        ConfirmDialog("규칙 삭제", "'${r.name}' 규칙을 삭제할까요?", { deleteRule = null }) {
            deleteRule = null
            guard { commit(rules.copy(rules = rules.rules.filter { it.id != r.id })) }
        }
    }
    deleteDomain?.let { r ->
        ConfirmDialog("규칙 삭제", "'${r.name}' 규칙을 삭제할까요?", { deleteDomain = null }) {
            deleteDomain = null
            guard { commit(rules.copy(domainRules = rules.domainRules.filter { it.id != r.id })) }
        }
    }
    deleteGroup?.let { g ->
        ConfirmDialog("그룹 삭제", "'${g.name}' 그룹과 안의 규칙이 모두 삭제됩니다. 계속할까요?", { deleteGroup = null }) {
            deleteGroup = null
            guard {
                commit(
                    rules.copy(
                        groups = rules.groups.filter { it.id != g.id },
                        rules = rules.rules.filter { it.groupId != g.id },
                        domainRules = rules.domainRules.filter { it.groupId != g.id },
                    ),
                )
            }
        }
    }

    // 앱 제거: 편집 잠금과 별개로 매번 비밀번호를 새로 확인한다
    if (uninstallStep == 1) {
        PasswordDialog(
            store = pw,
            title = "앱 제거 — 비밀번호 확인",
            grantSession = false,
            onDismiss = { uninstallStep = 0 },
            onOk = { uninstallStep = 2 },
        )
    }
    if (uninstallStep == 2) {
        ConfirmDialog(
            "앱 제거",
            "앱 차단과 네트워크 제한을 해제하고 Device Owner 해제를 시도합니다. Android API는 이 해제 방식을 테스트 용도로만 안내하며, 기기에 따라 실패하거나 일부 정책이 남을 수 있습니다. " +
                "제거 화면에서 취소하면 일부 제한은 이미 풀린 상태일 수 있습니다. 계속할까요?",
            { uninstallStep = 0 },
        ) {
            uninstallStep = 0
            scope.launch {
                val failures = withContext(Dispatchers.Default) { policy.releaseForUninstall() }
                if (failures.isEmpty()) {
                    startUninstall(ctx)
                } else {
                    uninstallError = failures.joinToString("\n")
                }
            }
        }
    }
    uninstallError?.let { msg ->
        ConfirmDialog("앱 제거 실패", msg, { uninstallError = null }) { uninstallError = null }
    }
}

private fun modesLabel(rules: RuleSet): String {
    val parts = ArrayList<String>()
    if (NET_PROXY in rules.netModes) parts += "프록시"
    if (NET_DNS_VPN in rules.netModes) parts += "내장 DNS VPN"
    if (NET_PRIVATE_DNS in rules.netModes) parts += "Private DNS(${rules.privateDnsHost})"
    return parts.joinToString(" + ").ifEmpty { "없음" }
}
