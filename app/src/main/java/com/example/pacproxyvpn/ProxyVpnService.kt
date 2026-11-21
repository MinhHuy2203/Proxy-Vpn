package com.example.pacproxyvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlin.concurrent.thread

class ProxyVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.example.pacproxyvpn.START"
        const val ACTION_STOP = "com.example.pacproxyvpn.STOP"
        const val CHANNEL_ID = "pac_proxy_vpn"
        const val NOTIFICATION_ID = 1001
    }

    private var tun: ParcelFileDescriptor? = null
    private var engine: TunEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            Stats.state = "OFF"
            Stats.log("Đã ngắt kết nối")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val pac = intent?.getStringExtra("pac_url") ?: ""
        val pkg = intent?.getStringExtra("pkg") ?: "busidol.mobile.tower"
        startForeground(NOTIFICATION_ID, notification("Đang kết nối…"))
        Stats.state = "STARTING"
        thread(name = "vpn-setup") { setup(pac, pkg) }
        return START_NOT_STICKY
    }

    private fun setup(pacInput: String, pkg: String) {
        try {
            shutdown()
            Stats.resetCounters()
            val (host, port) = ProxyUtil.resolve(pacInput)
            Stats.log("Proxy upstream: $host:$port")
            Stats.log(ProxyUtil.probe(host, port))

            val b = Builder()
                .setSession("PAC Proxy VPN")
                .setMtu(1400)
                .setBlocking(true)
                .addAddress("10.77.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd77:77::2", 128)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            try {
                b.addAllowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                throw IllegalStateException("Không tìm thấy app \"$pkg\" trên máy")
            }
            val t = b.establish() ?: throw IllegalStateException("Không tạo được VPN (chưa cấp quyền?)")
            tun = t
            engine = TunEngine(this, t, host, port).also { it.start() }
            Stats.state = "ON"
            Stats.log("VPN bật — chỉ $pkg đi qua proxy $host:$port")
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("$pkg → $host:$port"))
        } catch (e: Exception) {
            Stats.state = "ERROR"
            Stats.log("✗ ${e.message}")
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PAC Proxy VPN", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PAC Proxy VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    private fun shutdown() {
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    override fun onRevoke() {
        shutdown()
        Stats.state = "OFF"
        Stats.log("VPN bị app khác thu hồi")
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
