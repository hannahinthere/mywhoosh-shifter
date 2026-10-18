package io.github.hannahinthere.mywhooshshifter

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 在局域网里冒充一个 OpenBikeControl 换档器，让 MyWhoosh 连上来（协议：github.com/OpenBikeControl/openbikecontrol-protocol）。
 *
 * mDNS 广播 `_openbikecontrol._tcp` → MyWhoosh 发现后 TCP 连进来 → 发一包 0x04 自报家门（app id、版本、支持的按键），
 * 之后每 4 秒一个 0x00 心跳。我们按键时发 [0x01, 按键, 0x01] 按下、[0x01, 按键, 0x00] 松开；加档 0x01、减档 0x02。
 * 1016 在 Mac 上用 Python 原型端到端验过：MyShift 每收一下，FTMS 坡度动一档。
 */
class ObcServer(private val ctx: Context, private val onState: (State) -> Unit, private val onHaptic: (Int) -> Unit) {

    data class State(val listening: Boolean, val appName: String?, val clients: Int)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<Socket>()
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    @Volatile private var appName: String? = null
    @Volatile private var listening = false

    fun start() {
        io.execute {
            try {
                val ss = ServerSocket(0)   // 端口让系统挑，mDNS 里会带上
                server = ss
                main.post { register(ss.localPort) }
                while (!ss.isClosed) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    clients += s
                    push()
                    io.execute { serve(s) }
                }
            } catch (e: Exception) {
                Log.i(TAG, "server stopped: $e")
            }
        }
    }

    fun stop() {
        regListener?.let { runCatching { nsd?.unregisterService(it) } }
        regListener = null
        runCatching { server?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        multicast?.let { if (it.isHeld) it.release() }
        io.shutdownNow()
    }

    /** 按一下：按下 → 80ms → 松开。在后台线程发，别卡 UI。 */
    fun press(button: Int) {
        io.execute {
            send(byteArrayOf(0x01, button.toByte(), 0x01))
            Thread.sleep(80)
            send(byteArrayOf(0x01, button.toByte(), 0x00))
        }
    }

    private fun send(b: ByteArray) {
        for (s in clients) {
            try {
                s.getOutputStream().apply { write(b); flush() }
            } catch (e: Exception) {
                drop(s)
            }
        }
    }

    private fun register(port: Int) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicast = wifi.createMulticastLock("mywhoosh-shifter-mdns").apply { setReferenceCounted(false); acquire() }
        val app = ctx.getString(R.string.app_name)
        val name = "$app (${Build.MODEL})"   // 同一个 Wi-Fi 下好几台手机时，MyWhoosh 里靠这个分清
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_openbikecontrol._tcp"
            setPort(port)
            setAttribute("version", "1")
            setAttribute("id", deviceId())
            setAttribute("name", name)
            setAttribute("service-uuids", "d273f680-d548-419d-b9d1-fa0472345229")
            setAttribute("manufacturer", app)
            setAttribute("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { listening = true; push() }
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) { Log.w(TAG, "nsd fail $code"); listening = false; push() }
            override fun onServiceUnregistered(si: NsdServiceInfo) { listening = false; push() }
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }
        regListener = l
        nsd = (ctx.getSystemService(Context.NSD_SERVICE) as NsdManager).also {
            it.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        }
    }

    /** 每台手机首次启动随机生成一个 12 位 hex 的设备 id，之后固定。写死的话两台手机在同一个网里会撞 id。 */
    private fun deviceId(): String {
        val prefs = ctx.getSharedPreferences("obc", Context.MODE_PRIVATE)
        prefs.getString("id", null)?.let { return it }
        val id = (1..6).joinToString("") { "%02x".format(java.security.SecureRandom().nextInt(256)) }
        prefs.edit().putString("id", id).apply()
        return id
    }

    private fun serve(s: Socket) {
        try {
            s.getOutputStream().apply { write(byteArrayOf(0x02, 0xFF.toByte(), 0x01)); flush() }   // 设备状态：没电池可报、就绪
            val inp = s.getInputStream()
            while (true) {
                when (val t = inp.read()) {
                    -1 -> break
                    0x00 -> {}                                   // 心跳
                    0x03 -> {                                    // 振动：[pattern, duration, intensity]
                        val p = inp.read(); inp.read(); inp.read()
                        if (p > 0) main.post { onHaptic(p) }
                    }
                    0x04 -> readAppInfo(inp)
                    else -> Log.d(TAG, "unknown msg 0x%02x".format(t))
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "client gone: $e")
        }
        drop(s)
    }

    /** 0x04 自报家门：[版本] [id 长度] id [版本号长度] 版本号 [按键数] 按键... */
    private fun readAppInfo(inp: InputStream) {
        inp.read()
        val id = String(readN(inp, inp.read()))
        val ver = String(readN(inp, inp.read()))
        readN(inp, inp.read())
        appName = (if (id.equals("mywhoosh", true)) "MyWhoosh" else id) + " " + ver
        push()
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(maxOf(n, 0))
        var o = 0
        while (o < b.size) {
            val r = inp.read(b, o, b.size - o)
            if (r < 0) break
            o += r
        }
        return b
    }

    private fun drop(s: Socket) {
        runCatching { s.close() }
        if (clients.remove(s)) {
            if (clients.isEmpty()) appName = null
            push()
        }
    }

    private fun push() {
        val st = State(listening, appName, clients.size)
        main.post { onState(st) }
    }

    companion object {
        const val UP = 0x01
        const val DOWN = 0x02
        private const val TAG = "mywhoosh-shifter"
    }
}
