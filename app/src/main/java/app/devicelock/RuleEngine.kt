package app.devicelock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 현재 시각 기준으로 규칙을 평가해 앱 일시중지 상태를 맞추고, 다음 변화 시점에 알람을 예약한다.
 * 상주 서비스나 화면 오버레이 없이 알람 + 브로드캐스트만 사용하므로 배터리 소모가 거의 없다.
 */
object RuleEngine {

    private const val SCAN_DAYS = 7
    private const val MAX_WAIT_HOURS = 6L

    fun evaluate(ctx: Context, now: LocalDateTime = LocalDateTime.now()) {
        val policy = PolicyController(ctx)
        if (!policy.isDeviceOwner()) return
        val rules = RuleStore(ctx).load()
        policy.syncSuspension(rules.blockedPackages(now))
        scheduleNext(ctx, rules, now)
    }

    private fun scheduleNext(ctx: Context, rules: RuleSet, now: LocalDateTime) {
        val base = now.truncatedTo(ChronoUnit.MINUTES)
        val current = rules.blockedPackages(now)

        // 차단 집합이 바뀌는 가장 가까운 분 단위 시각을 찾는다
        var next: LocalDateTime? = null
        for (i in 1..(SCAN_DAYS * 24 * 60)) {
            val t = base.plusMinutes(i.toLong())
            if (rules.blockedPackages(t) != current) {
                next = t
                break
            }
        }
        // 시계 보정이나 누락에 대비해 최소 몇 시간마다 한 번은 다시 평가한다
        val cap = now.plusHours(MAX_WAIT_HOURS)
        val trigger = if (next == null || next.isAfter(cap)) cap else next
        val millis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ctx, SchedulerReceiver::class.java).setAction(SchedulerReceiver.ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactOk) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            // Android 12/12L에서 정확 알람 권한이 없으면 부정확 알람으로 대체한다(몇 분 지연될 수 있음)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }
}
