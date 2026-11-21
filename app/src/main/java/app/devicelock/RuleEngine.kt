package app.devicelock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TreeSet

/**
 * 현재 시각 기준으로 규칙을 평가해 앱 일시중지·네트워크 상태를 맞추고, 다음 변화 시점에 알람을 예약한다.
 * 상주 서비스나 화면 오버레이 없이 알람 + 브로드캐스트만 사용하므로 배터리 소모가 거의 없다.
 */
object RuleEngine {

    private const val TAG = "RuleEngine"
    private const val SCAN_DAYS = 8

    /** 변화가 없어도 이 간격마다는 다시 평가한다(시계 보정, 시스템이 풀어 버린 상태 복구, 재설치 감지) */
    private const val BASE_TICK_MIN = 30L

    /** 규칙에 있는 앱이 설치되어 있지 않은 동안에는 재설치를 빨리 잡기 위해 더 자주 평가한다 */
    private const val MISSING_TICK_MIN = 5L

    /** 위치 예외 시간대가 활성인 동안에는 위치가 오래되어 예외가 풀리는 시점을 놓치지 않도록 이 간격으로 평가한다 */
    private const val LOCATION_TICK_MIN = 2L

    private val lock = Any()

    /**
     * 메인 스레드(UI·리시버)와 백그라운드에서 동시에 불리면 일시중지 추적 목록이 어긋날 수 있어 직렬화한다.
     * 어떤 단계가 실패해도 마지막의 알람 예약은 반드시 시도한다(예약이 끊기면 규칙이 영영 멈춘다).
     *
     * @param fromLocation 위치 수신 때문에 불린 경우. 위치 업데이트를 다시 등록하지 않는다(등록 → 수신 → 등록 반복 방지).
     */
    fun evaluate(
        ctx: Context,
        now: LocalDateTime = LocalDateTime.now(),
        force: Boolean = false,
        fromLocation: Boolean = false,
    ) {
        synchronized(lock) {
            val policy = PolicyController(ctx)
            if (!policy.isDeviceOwner()) return

            var rules = RuleSet.empty()
            var geo: GeoState? = null
            var missing = false
            var netFailing = false
            var waitMs = 0L
            var locationNeeded = false
            try {
                rules = RuleStore(ctx).load()
                geo = LocationGate.current(ctx)
                locationNeeded = rules.exemptionWindowActive(now)
                val phase = StrongMode(ctx).phase()
                val blackout = phase is StrongPhase.Waiting
                if (phase is StrongPhase.Waiting) waitMs = phase.remainingMs

                val e = rules.enforcementAt(now, geo)
                try {
                    missing = policy.syncSuspension(e.blocked).missing.isNotEmpty()
                } catch (t: Throwable) {
                    Log.e(TAG, "앱 차단 동기화 실패", t)
                }
                try {
                    val plan = NetPlan(
                        blackout = blackout,
                        whitelistOn = e.allowed != null,
                        allowed = e.allowed ?: emptySet(),
                        modes = rules.netModes,
                        privateDnsHost = rules.privateDnsHost,
                    )
                    val error = policy.syncNetwork(plan, force)
                    // 오류가 있거나 VPN 이 아직 올라오지 않았으면 짧은 주기로 다시 확인한다
                    netFailing = error != null ||
                        (plan.vpnMode != NetState.VPN_OFF && !NetworkVpnService.running)
                } catch (t: Throwable) {
                    Log.e(TAG, "네트워크 동기화 실패", t)
                }
                try {
                    LocationGate.refresh(ctx, locationNeeded, reregister = !fromLocation)
                } catch (t: Throwable) {
                    Log.e(TAG, "위치 확인 실패", t)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "규칙 평가 실패", t)
            } finally {
                scheduleNext(ctx, rules, now, geo, missing || netFailing, waitMs, locationNeeded)
            }
        }
    }

    private fun scheduleNext(
        ctx: Context,
        rules: RuleSet,
        now: LocalDateTime,
        geo: GeoState?,
        shortTick: Boolean,
        waitMs: Long,
        locationNeeded: Boolean,
    ) {
        var trigger = now.plusMinutes(if (shortTick) MISSING_TICK_MIN else BASE_TICK_MIN)
        try {
            if (locationNeeded) {
                val t = now.plusMinutes(LOCATION_TICK_MIN)
                if (t.isBefore(trigger)) trigger = t
            }
            // 강력 모드 대기가 끝나는 시각에 네트워크를 복구한다
            if (waitMs > 0) {
                val t = now.plusSeconds(waitMs / 1000 + 2)
                if (t.isBefore(trigger)) trigger = t
            }
            val next = nextChange(rules, now, geo)
            if (next != null && next.isBefore(trigger)) trigger = next
        } catch (t: Throwable) {
            Log.e(TAG, "다음 전환 시각 계산 실패", t)
        }

        val millis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ctx, SchedulerReceiver::class.java).setAction(SchedulerReceiver.ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exactOk) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                // Android 12/12L에서 정확 알람 권한이 없으면 부정확 알람으로 대체한다(몇 분 지연될 수 있음)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "정확 알람 예약 실패, 부정확 알람으로 대체", t)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } catch (t2: Throwable) {
                Log.e(TAG, "알람 예약 실패", t2)
            }
        }
    }

    /**
     * 적용 상태(차단 앱 집합, 허용 도메인 집합)가 바뀌는 가장 가까운 시각.
     * 상태는 규칙 경계(자정, 각 규칙의 시작·종료 시각)에서만 바뀌므로 그 시각들만 확인하면 된다.
     */
    private fun nextChange(rules: RuleSet, now: LocalDateTime, geo: GeoState?): LocalDateTime? {
        val current = rules.enforcementAt(now, geo)
        val minutes = TreeSet<Int>().apply {
            add(0)
            rules.allSchedules().forEach { if (!it.allDay) { add(it.startMin); add(it.endMin) } }
        }
        val base = now.toLocalDate()
        val limit = now.truncatedTo(ChronoUnit.MINUTES)
        for (d in 0..SCAN_DAYS) {
            val date = base.plusDays(d.toLong())
            for (m in minutes) {
                if (m !in 0..1439) continue
                val t = date.atTime(m / 60, m % 60)
                if (t.isAfter(limit) && rules.enforcementAt(t, geo) != current) return t
            }
        }
        return null
    }
}
