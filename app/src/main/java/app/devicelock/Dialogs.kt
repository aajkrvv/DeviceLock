package app.devicelock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * @param grantSession true 면 성공 시 5분간 편집 잠금을 푼다. 앱 제거처럼 매번 새로 확인해야 하는 작업은 false.
 */
@Composable
fun PasswordDialog(
    store: PasswordStore,
    onDismiss: () -> Unit,
    onOk: () -> Unit,
    title: String = "비밀번호 입력",
    grantSession: Boolean = true,
    message: String? = null,
) {
    var input by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = store.verify(input)) {
                    is VerifyResult.Ok -> {
                        if (grantSession) Session.unlock()
                        onOk()
                    }
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

/** 강력 모드 대기 중에 규칙을 바꾸려 할 때 보여 주는 대화상자. */
@Composable
fun StrongWaitDialog(remainingMs: Long, onKeepWaiting: () -> Unit, onCancelWait: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepWaiting,
        title = { Text("강력 모드 — 대기 중") },
        text = {
            Text(
                "남은 시간: ${formatDuration(remainingMs)}\n\n" +
                    "대기가 끝나면 규칙을 바꿀 수 있고 네트워크도 복구됩니다. " +
                    "대기를 중단하면 네트워크는 바로 복구되지만, 규칙을 바꾸려면 비밀번호를 다시 입력하고 24시간을 처음부터 기다려야 합니다.",
            )
        },
        confirmButton = { TextButton(onClick = onKeepWaiting) { Text("계속 대기") } },
        dismissButton = { TextButton(onClick = onCancelWait) { Text("대기 중단") } },
    )
}

/** 차단 방식 선택. Private DNS 와 내장 DNS VPN 은 동시에 쓸 수 없다. */
@Composable
fun NetModesDialog(
    modes: Set<String>,
    host: String,
    onDismiss: () -> Unit,
    onSave: (Set<String>, String) -> Unit,
) {
    var proxy by remember { mutableStateOf(NET_PROXY in modes) }
    var pdns by remember { mutableStateOf(NET_PRIVATE_DNS in modes) }
    var vpn by remember { mutableStateOf(NET_DNS_VPN in modes) }
    var hostText by remember { mutableStateOf(host) }
    var err by remember { mutableStateOf<String?>(null) }

    @Composable
    fun option(checked: Boolean, onChange: (Boolean) -> Unit, title: String, desc: String) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column {
                Text(title)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("차단 방식 선택") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                option(
                    proxy, { proxy = it }, "프록시",
                    "허용 도메인 목록을 그대로 적용합니다. 시스템 프록시를 따르는 앱(브라우저 등)만 걸러집니다. " +
                        "타사 VPN 과 함께 쓸 수 있습니다(실기기에서 확인 필요).",
                )
                option(
                    vpn, { vpn = it; if (it) pdns = false }, "내장 DNS VPN",
                    "이 앱의 로컬 VPN 이 UDP 53 DNS 질의에서 허용 도메인만 통과시킵니다(요일·시간 규칙 반영). " +
                        "IP 직접 접속·자체 DoH/DoT·TCP DNS·기기 Private DNS 는 우회할 수 있습니다. 타사 VPN 과 동시에 쓸 수 없습니다.",
                )
                option(
                    pdns, { pdns = it; if (it) vpn = false }, "Private DNS (DNS-over-TLS 호스트 지정)",
                    "DNS-over-TLS 서버만 지정하며, 이 옵션 단독으로 도메인 허용 목록을 차단하지 않습니다. 허용 목록은 해당 DNS 서비스에서 별도로 설정해야 합니다. " +
                        "타사 VPN 과 함께 쓸 수 있는 경우가 있지만 VPN 안팎에서 DNS 서버에 도달할 수 있어야 합니다. 원래 Private DNS 가 OFF 였다면 해제 시 Android API 한계로 자동 모드로 돌아갑니다.",
                )
                if (pdns) {
                    OutlinedTextField(
                        value = hostText, onValueChange = { hostText = it }, singleLine = true,
                        label = { Text("DoT 호스트 (예: xxxx.dns.nextdns.io)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = Domains.normalize(hostText)
                when {
                    !proxy && !pdns && !vpn -> err = "방식을 하나 이상 선택해 주세요"
                    pdns && (h == null || Domains.isIpv4(h)) -> err = "올바른 호스트 이름을 입력해 주세요(IP 주소는 불가)"
                    else -> {
                        val out = HashSet<String>()
                        if (proxy) out += NET_PROXY
                        if (vpn) out += NET_DNS_VPN
                        if (pdns) out += NET_PRIVATE_DNS
                        onSave(out, if (pdns) (h ?: "") else "")
                    }
                }
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
