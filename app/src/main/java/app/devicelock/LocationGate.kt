package app.devicelock

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.Manifest
import android.os.Build
import android.util.Log

/**
 * 위치 예외용 위치 확인.
 * 상주 서비스 없이, "위치 예외 시간대가 활성인 동안에만" 시스템 위치 업데이트를 PendingIntent 로 받는다.
 * 받은 위치는 시각과 함께 저장하고, MAX_AGE_MS 보다 오래되면 "위치 모름"으로 취급한다(= 규칙 유지).
 * 모의 위치(mock location)는 무시한다.
 */
object LocationGate {

    const val ACTION_LOCATION = "app.devicelock.LOCATION"

    private const val TAG = "LocationGate"
    // 짧은 유효기간과 오차 반경 보수 계산으로, 구역을 떠난 뒤 오래 예외가 남는 상황을 줄인다.
    private const val MAX_AGE_MS = 5 * 60_000L
    private const val SETUP_MAX_AGE_MS = 24 * 60 * 60_000L
    private const val INTERVAL_MS = 2 * 60_000L
    private const val MAX_ACCURACY_M = 100f

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("geo", Context.MODE_PRIVATE)

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun issue(ctx: Context): String? = prefs(ctx).getString("issue", null)

    private fun setIssue(ctx: Context, msg: String?) {
        prefs(ctx).edit().apply { if (msg == null) remove("issue") else putString("issue", msg) }.commit()
    }

    @Suppress("DEPRECATION")
    private fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider

    /** 위치 수신 처리. 모의 위치와 정확도가 낮은 위치는 버린다. */
    fun onLocation(ctx: Context, l: Location) {
        if (isMock(l)) {
            setIssue(ctx, "모의 위치가 감지되어 무시했습니다")
            return
        }
        if (!l.hasAccuracy() || l.accuracy > MAX_ACCURACY_M) return
        val t = if (l.time > 0) l.time else System.currentTimeMillis()
        val p = prefs(ctx)
        if (t < p.getLong("time", 0L)) return
        p.edit()
            .putString("lat", l.latitude.toString())
            .putString("lon", l.longitude.toString())
            .putFloat("accuracy", l.accuracy)
            .putLong("time", t)
            .commit()
    }

    /** 최근(MAX_AGE_MS 이내) 위치. 없거나 오래됐으면 null */
    fun current(ctx: Context): GeoState? {
        if (!hasPermission(ctx) || !hasBackgroundPermission(ctx)) return null
        val p = prefs(ctx)
        val t = p.getLong("time", 0L)
        val age = System.currentTimeMillis() - t
        if (t == 0L || age !in 0..MAX_AGE_MS) return null
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        val accuracy = p.getFloat("accuracy", Float.MAX_VALUE).toDouble()
        if (!accuracy.isFinite() || accuracy > MAX_ACCURACY_M) return null
        return GeoState(lat, lon, accuracy)
    }

    /** 규칙 편집 화면에서 "현재 위치 사용"에 쓰는 마지막 위치(최근 24시간 이내) */
    fun lastKnownForSetup(ctx: Context): GeoState? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.applicationContext.getSystemService(LocationManager::class.java) ?: return null
        val l = bestLastKnown(lm, SETUP_MAX_AGE_MS) ?: return null
        val accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else return null
        return GeoState(l.latitude, l.longitude, accuracy)
    }

    private fun bestLastKnown(lm: LocationManager, maxAgeMs: Long): Location? {
        val all = lm.allProviders
        val now = System.currentTimeMillis()
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { it in all }
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { !isMock(it) && now - it.time in 0..maxAgeMs && it.hasAccuracy() && it.accuracy <= MAX_ACCURACY_M }
            // 최근 시각보다는 정확도가 더 나은 위치를 우선한다.
            .minByOrNull { it.accuracy }
    }

    private fun pendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            1,
            Intent(ctx, SchedulerReceiver::class.java).setAction(ACTION_LOCATION),
            // 위치 정보가 extra 로 채워지므로 Android 12 이상에서는 mutable 이어야 한다
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /** 재부팅하면 등록이 사라지므로 부팅 때 호출한다 */
    fun reset(ctx: Context) {
        prefs(ctx).edit().putBoolean("registered", false).commit()
    }

    /**
     * needed 가 true 이면 위치 업데이트를 등록하고(이미 등록돼 있으면 reregister 일 때만 다시 등록),
     * false 이면 해제한다. 실패 사유는 issue() 로 화면에 표시된다.
     */
    fun refresh(ctx: Context, needed: Boolean, reregister: Boolean) {
        val app = ctx.applicationContext
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        val p = prefs(app)
        try {
            if (!needed) {
                if (p.getBoolean("registered", false)) {
                    lm.removeUpdates(pendingIntent(app))
                    p.edit().putBoolean("registered", false).commit()
                }
                return
            }
            if (!hasPermission(app)) {
                setIssue(app, "위치 권한이 없습니다")
                return
            }
            bestLastKnown(lm, MAX_AGE_MS)?.let { onLocation(app, it) }
            if (!reregister && p.getBoolean("registered", false)) return
            if (reregister && p.getBoolean("registered", false)) {
                runCatching { lm.removeUpdates(pendingIntent(app)) }
                p.edit().putBoolean("registered", false).commit()
            }

            var registeredCount = 0
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (provider !in lm.allProviders || !runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) continue
                val registered = runCatching {
                    lm.requestLocationUpdates(provider, INTERVAL_MS, 0f, pendingIntent(app))
                    true
                }.getOrDefault(false)
                if (registered) registeredCount++
            }
            if (registeredCount == 0) {
                setIssue(app, "사용할 수 있는 위치 제공자가 없습니다(기기 위치가 꺼져 있거나 권한이 부족할 수 있음)")
                return
            }
            p.edit().putBoolean("registered", true).commit()
            if (issue(app)?.startsWith("모의") != true) setIssue(app, null)
        } catch (e: SecurityException) {
            setIssue(app, "위치 권한 오류")
        } catch (t: Throwable) {
            Log.e(TAG, "위치 업데이트 등록 실패", t)
            setIssue(app, "위치 확인 실패 (${t.javaClass.simpleName})")
        }
    }
}
