package app.devicelock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ServiceInfo
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** VPN 서비스가 공유하는 상태. 서비스는 시스템이 직접 (재)시작할 수 있으므로 값을 prefs 에도 남긴다. */
object NetState {
    const val VPN_OFF = "off"

    /** 모든 트래픽을 버린다(강력 모드 대기 중 완전 차단) */
    const val VPN_BLACKHOLE = "blackhole"

    /** DNS 질의만 받아 허용 도메인만 응답한다(그 외 트래픽은 VPN을 거치지 않는다) */
    const val VPN_DNS = "dns"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("netstate", Context.MODE_PRIVATE)

    @Volatile private var dnsAllowedCache: Set<String>? = null

    fun vpnMode(ctx: Context): String = prefs(ctx).getString("vpnMode", VPN_OFF) ?: VPN_OFF

    fun setVpnMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString("vpnMode", mode).commit()
    }

    fun dnsAllowed(ctx: Context): Set<String> {
        dnsAllowedCache?.let { return it }
        val s = prefs(ctx).getStringSet("dnsAllowed", emptySet())!!.toSet()
        dnsAllowedCache = s
        return s
    }

    fun setDnsAllowed(ctx: Context, domains: Set<String>) {
        if (dnsAllowed(ctx) == domains) return
        dnsAllowedCache = domains
        prefs(ctx).edit().putStringSet("dnsAllowed", domains).commit()
    }

    fun error(ctx: Context): String? = prefs(ctx).getString("vpnErr", null)

    fun setError(ctx: Context, msg: String?) {
        prefs(ctx).edit().apply { if (msg == null) remove("vpnErr") else putString("vpnErr", msg) }.commit()
    }
}

/**
 * 두 가지 모드로 쓰는 로컬 VPN. 외부 서버로 트래픽을 보내지 않는다.
 *
 * - blackhole: IPv4/IPv6 기본 경로를 VPN 으로 보내고 패킷을 버린다. 상시 VPN + lockdown 으로 일반 앱의 인터넷을 차단한다.
 *   Android 시스템 앱은 VPN 예외가 될 수 있으므로 기기 전체의 모든 통신 차단을 보장하지 않는다.
 * - dns: DNS 서버 주소 하나(DNS_ADDR/32)만 이 VPN 으로 보낸다. 앱의 DNS 질의가 여기로 들어오면
 *   허용 도메인만 상위 DNS 로 전달하고 나머지는 NXDOMAIN 으로 답한다. 다른 트래픽은 VPN 을 거치지 않으므로 속도 영향이 거의 없다.
 *   한계: IP 주소로 직접 접속하는 앱, 자체 DoH/DoT 를 쓰는 앱, TCP 53 DNS 는 걸러지지 않는다. 다른 VPN 과는 동시에 쓸 수 없다.
 */
class NetworkVpnService : VpnService() {

    private val lock = Any()
    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = NetState.vpnMode(this)
        if (mode == NetState.VPN_OFF) {
            closeAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            promoteToForeground(mode)
        } catch (t: Throwable) {
            Log.e(TAG, "포그라운드 서비스 시작 실패", t)
            NetState.setError(this, "VPN 포그라운드 시작 실패 (${t.javaClass.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn(mode)
        return START_STICKY
    }

    override fun onRevoke() {
        closeAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onRevoke()
    }

    override fun onDestroy() {
        closeAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun promoteToForeground(mode: String) {
        val channelId = "devicelock_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "DeviceLock 네트워크 제한", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "네트워크 제한 VPN 이 활성화되어 있을 때 표시됩니다" },
            )
        }
        val label = if (mode == NetState.VPN_BLACKHOLE) "강력 모드: 네트워크 차단 중" else "도메인 DNS 필터 활성화"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("DeviceLock")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startVpn(mode: String) {
        synchronized(lock) {
            closeLocked()
            try {
                val b = Builder().setSession("DeviceLock").setMtu(MTU).setBlocking(true)
                b.addAddress(TUN_ADDR, 32)
                if (mode == NetState.VPN_BLACKHOLE) {
                    b.addAddress(TUN_ADDR6, 128)
                    b.addRoute("0.0.0.0", 0)
                    b.addRoute("::", 0)
                } else {
                    b.addDnsServer(DNS_ADDR)
                    b.addRoute(DNS_ADDR, 32)
                }
                val pfd = b.establish()
                if (pfd == null) {
                    NetState.setError(this, "VPN 권한이 없습니다. 앱에서 'VPN 권한 허용'을 눌러 주세요.")
                    return
                }
                NetState.setError(this, null)
                tun = pfd
                val input = FileInputStream(pfd.fileDescriptor)
                val output = FileOutputStream(pfd.fileDescriptor)
                val executor = Executors.newFixedThreadPool(4)
                pool = executor
                val t = Thread(
                    { if (mode == NetState.VPN_BLACKHOLE) drain(input) else dnsLoop(input, output, executor) },
                    "devicelock-vpn",
                )
                reader = t
                t.start()
                running = true
            } catch (t: Throwable) {
                Log.e(TAG, "VPN 시작 실패", t)
                NetState.setError(this, "VPN 시작 실패 (${t.javaClass.simpleName})")
            }
        }
    }

    private fun closeAll() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        running = false
        reader?.interrupt()
        pool?.shutdownNow()
        runCatching { tun?.close() }
        reader = null
        pool = null
        tun = null
    }

    private fun drain(input: FileInputStream) {
        val buf = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (input.read(buf) < 0) break
            }
        } catch (_: Exception) {
        }
    }

    private fun dnsLoop(input: FileInputStream, output: FileOutputStream, executor: ExecutorService) {
        val buf = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n < 0) break
                if (n < 28) continue
                val pkt = buf.copyOf(n)
                try {
                    executor.execute { handlePacket(pkt, output) }
                } catch (_: RejectedExecutionException) {
                    break
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handlePacket(p: ByteArray, out: FileOutputStream) {
        try {
            if ((p[0].toInt() shr 4) != 4) return
            val ihl = (p[0].toInt() and 0x0F) * 4
            if (ihl < 20 || p.size < ihl + 8) return
            if ((p[9].toInt() and 0xFF) != 17) return // UDP 만 처리
            if (u16(p, ihl + 2) != 53) return
            val clientPort = u16(p, ihl)
            val total = minOf(u16(p, 2), p.size)
            val dnsOff = ihl + 8
            val dnsLen = total - dnsOff
            if (dnsLen < 12) return
            val dns = p.copyOfRange(dnsOff, dnsOff + dnsLen)
            if ((u16(dns, 2) and 0x8000) != 0) return // 응답 패킷은 무시
            if (u16(dns, 4) != 1) return // 질문이 하나인 질의만 처리

            var i = 12
            val sb = StringBuilder()
            while (true) {
                if (i >= dns.size) return
                val len = dns[i].toInt() and 0xFF
                if (len == 0) {
                    i++
                    break
                }
                if ((len and 0xC0) != 0 || i + 1 + len > dns.size) return
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dns, i + 1, len, Charsets.ISO_8859_1))
                i += 1 + len
            }
            val questionEnd = i + 4
            if (questionEnd > dns.size) return

            val reply = if (isAllowed(sb.toString())) forward(dns) else nxdomain(dns, questionEnd)
            if (reply == null || 28 + reply.size > MTU) return
            val packet = buildUdpReply(p, clientPort, reply)
            synchronized(out) { out.write(packet) }
        } catch (t: Throwable) {
            Log.w(TAG, "DNS 처리 실패", t)
        }
    }

    private fun isAllowed(name: String): Boolean =
        Domains.matchesAllowed(name, NetState.dnsAllowed(this)) ||
            Domains.matchesAllowed(name, SYSTEM_SET)

    /** 허용된 질의를 상위 DNS 로 전달한다. 소켓은 VPN 을 거치지 않게 protect 한다. */
    private fun forward(dns: ByteArray): ByteArray? {
        for (host in UPSTREAMS) {
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket()
                if (!protect(s)) return null
                s.soTimeout = 2500
                s.send(DatagramPacket(dns, dns.size, InetAddress.getByName(host), 53))
                val buf = ByteArray(4096)
                val rp = DatagramPacket(buf, buf.size)
                s.receive(rp)
                return buf.copyOf(rp.length)
            } catch (_: Exception) {
                // 다음 상위 DNS 로 시도
            } finally {
                s?.close()
            }
        }
        return null
    }

    private fun nxdomain(dns: ByteArray, questionEnd: Int): ByteArray {
        val r = dns.copyOf(questionEnd)
        val f = u16(dns, 2)
        val nf = 0x8000 or (f and 0x7800) or (f and 0x0100) or 0x0080 or 3 // QR, opcode·RD 복사, RA, NXDOMAIN
        put16(r, 2, nf)
        for (k in 6..11) r[k] = 0 // answer / authority / additional 개수 0
        return r
    }

    private fun buildUdpReply(req: ByteArray, clientPort: Int, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val total = 20 + udpLen
        val r = ByteArray(total)
        r[0] = 0x45
        put16(r, 2, total)
        put16(r, 6, 0x4000)
        r[8] = 64
        r[9] = 17
        System.arraycopy(req, 16, r, 12, 4) // 출발지 = 요청의 목적지(DNS 서버 주소)
        System.arraycopy(req, 12, r, 16, 4) // 목적지 = 요청의 출발지
        put16(r, 10, checksum(r, 20))
        put16(r, 20, 53)
        put16(r, 22, clientPort)
        put16(r, 24, udpLen)
        System.arraycopy(dns, 0, r, 28, dns.size)
        return r
    }

    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) {
            sum += u16(b, i)
            i += 2
        }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    companion object {
        private const val TAG = "NetworkVpnService"
        private const val NOTIFICATION_ID = 6101
        private const val MTU = 1500
        private const val TUN_ADDR = "10.255.255.2"
        private const val TUN_ADDR6 = "fd00:ffff::2"
        private const val DNS_ADDR = "10.255.255.1"
        private val UPSTREAMS = listOf("1.1.1.1", "8.8.8.8")
        private val SYSTEM_SET: Set<String> = (Domains.SYSTEM_ALLOWED + Domains.DNS_EXTRA_ALLOWED).toSet()

        /** 서비스가 VPN 인터페이스를 열어 둔 상태인지 */
        @Volatile
        var running = false
            private set

        /** 현재 NetState.vpnMode 대로 (재)시작한다. 시스템이 이미 상시 VPN 으로 시작했다면 다시 구성만 한다. */
        fun apply(ctx: Context) {
            try {
                val app = ctx.applicationContext
                val intent = Intent(app, NetworkVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "VPN 서비스 시작 실패", t)
                NetState.setError(ctx, "VPN 서비스를 시작하지 못했습니다 (${t.javaClass.simpleName})")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(Intent(ctx.applicationContext, NetworkVpnService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "VPN 서비스 중지 실패", t)
            }
        }
    }
}
