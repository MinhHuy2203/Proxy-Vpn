package com.example.pacproxyvpn

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

private class PowerIcon(ctx: Context) : View(ctx) {
    var color = Color.WHITE
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        p.strokeWidth = w * 0.09f
        p.color = color
        val r = w * 0.30f
        val cx = w / 2f
        val cy = height / 2f + w * 0.03f
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, p)
        c.drawLine(cx, cy - r * 1.15f, cx, cy - r * 0.1f, p)
    }
}

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var etProxy: EditText
    private lateinit var etPkg: EditText
    private lateinit var tvState: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvConn: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvPk: TextView
    private lateinit var power: FrameLayout
    private lateinit var icon: PowerIcon
    private lateinit var glow: View
    private var pulse: ObjectAnimator? = null
    private var shown = ""

    private val teal = Color.rgb(46, 230, 166)
    private val amber = Color.rgb(255, 194, 92)
    private val red = Color.rgb(255, 107, 122)
    private val idle = Color.rgb(120, 134, 176)
    private val dim = Color.rgb(154, 165, 196)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = round(Color.argb(26, 255, 255, 255), 20, Color.argb(34, 255, 255, 255))
    }

    private fun input(hint: String, value: String) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(100, 112, 145))
        textSize = 14f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = round(Color.argb(90, 6, 10, 24), 12, Color.argb(50, 255, 255, 255))
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("vpn", MODE_PRIVATE)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(10, 15, 31), Color.rgb(27, 16, 56))
            )
            setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
                ins
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        scroll.addView(root)

        root.addView(tv("PAC Proxy", 28f, Color.WHITE, true))
        root.addView(tv("VPN theo từng app  •  HTTP proxy + HTTPS tunnel", 12f, dim), lp(2))

        // Nút nguồn
        val hero = FrameLayout(this)
        glow = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(60, 120, 134, 176)) } }
        hero.addView(glow, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER))
        icon = PowerIcon(this)
        power = FrameLayout(this).apply {
            setOnClickListener { onPower() }
            addView(icon, FrameLayout.LayoutParams(dp(150), dp(150)))
        }
        hero.addView(power, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        root.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(8) })

        tvState = tv("Chưa kết nối", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        tvSub = tv("", 12f, dim).apply { gravity = Gravity.CENTER }
        root.addView(tvState, lp())
        root.addView(tvSub, lp(4))

        // Thống kê
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(title: String): TextView {
            val c = card()
            c.addView(tv(title, 10f, dim, true))
            val v = tv("0", 18f, Color.WHITE, true)
            c.addView(v)
            stats.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (stats.childCount > 0) leftMargin = dp(8)
            })
            return v
        }
        tvUp = tile("↑ GỬI")
        tvDown = tile("↓ NHẬN")
        tvConn = tile("TCP MỞ")
        root.addView(stats, lp(18))
        tvPk = tv("", 11f, dim)
        root.addView(tvPk, lp(8))

        // Cấu hình
        val cfg = card()
        cfg.addView(tv("PROXY HOẶC URL PAC", 10f, dim, true))
        etProxy = input("127.0.0.1:8888 hoặc http://127.0.0.1:8888/proxy.pac",
            prefs.getString("pac", "http://127.0.0.1:8888/proxy.pac")!!)
        cfg.addView(etProxy, lp(8))
        cfg.addView(tv("PACKAGE ỨNG DỤNG CẦN PROXY", 10f, dim, true), lp(14))
        etPkg = input("busidol.mobile.tower", prefs.getString("pkg", "busidol.mobile.tower")!!)
        cfg.addView(etPkg, lp(8))
        val test = tv("KIỂM TRA PROXY", 13f, teal, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = round(Color.TRANSPARENT, 12, teal)
            setOnClickListener { testProxy() }
        }
        cfg.addView(test, lp(14))
        root.addView(cfg, lp(14))

        // Log
        val logCard = card()
        logCard.addView(tv("NHẬT KÝ", 10f, dim, true))
        tvLog = tv("", 11f, Color.rgb(190, 200, 225)).apply { typeface = Typeface.MONOSPACE }
        logCard.addView(tvLog, lp(8))
        root.addView(logCard, lp(14))

        root.addView(tv("HTTP thường được gửi tới Python để inject; HTTPS chỉ CONNECT tunnel, không giải mã TLS. UDP (game realtime) đi thẳng.", 11f, Color.rgb(110, 122, 155)), lp(14))

        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        poll.run()
    }

    private val poll = object : Runnable {
        override fun run() {
            updateUi()
            h.postDelayed(this, 700)
        }
    }

    private fun fmt(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
        else -> "%.1f MB".format(n / 1048576.0)
    }

    private fun updateUi() {
        val st = Stats.state
        if (st != shown) {
            shown = st
            val (col, title) = when (st) {
                "ON" -> teal to "Đã kết nối"
                "STARTING" -> amber to "Đang kết nối…"
                "ERROR" -> red to "Lỗi kết nối"
                else -> idle to "Chưa kết nối"
            }
            tvState.text = title
            tvState.setTextColor(if (st == "OFF") Color.WHITE else col)
            icon.color = col
            power.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(46, Color.red(col), Color.green(col), Color.blue(col)))
                setStroke(dp(3), col)
            }
            (glow.background as GradientDrawable).setColor(Color.argb(55, Color.red(col), Color.green(col), Color.blue(col)))
            pulse?.cancel()
            glow.scaleX = 1f; glow.scaleY = 1f
            if (st == "ON" || st == "STARTING") {
                pulse = ObjectAnimator.ofPropertyValuesHolder(
                    glow,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.12f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.12f)
                ).apply {
                    duration = 1100
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
            tvSub.text = when (st) {
                "ON" -> "${etPkg.text} → proxy"
                "ERROR" -> "Xem nhật ký bên dưới"
                else -> "Chạm nút nguồn để bật"
            }
        }
        tvUp.text = fmt(Stats.up.get())
        tvDown.text = fmt(Stats.down.get())
        tvConn.text = "${Stats.tcpActive.get()} (✓${Stats.tcpOk.get()} ✗${Stats.tcpFail.get()})"
        val ruleTxt = if (PacRules.active.isEmpty()) "không lọc (proxy hết)" else "${PacRules.active.size} luật"
        tvPk.text = "Gói: ${Stats.pkts.get()}  •  PAC: $ruleTxt  •  Qua proxy: ${Stats.viaProxyConns.get()}  •  Đi thẳng: ${Stats.viaDirectConns.get()}"
        tvLog.text = Stats.tail(14).ifEmpty { "Chưa có log." }
    }

    private fun onPower() {
        if (Stats.state == "ON" || Stats.state == "STARTING") {
            startService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
            Stats.state = "OFF"
            return
        }
        getSharedPreferences("vpn", MODE_PRIVATE).edit()
            .putString("pac", etProxy.text.toString().trim())
            .putString("pkg", etPkg.text.toString().trim()).apply()
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, 10) else startVpn()
    }

    private fun startVpn() {
        Stats.state = "STARTING"
        Stats.log("Bắt đầu kết nối…")
        startForegroundService(
            Intent(this, ProxyVpnService::class.java)
                .setAction(ProxyVpnService.ACTION_START)
                .putExtra("pac_url", etProxy.text.toString().trim())
                .putExtra("pkg", etPkg.text.toString().trim())
        )
    }

    private fun testProxy() {
        val input = etProxy.text.toString().trim()
        Stats.log("Đang kiểm tra proxy…")
        thread {
            try {
                val (host, port) = ProxyUtil.resolve(input)
                Stats.log(ProxyUtil.probe(host, port))
            } catch (e: Exception) {
                Stats.log("✗ ${e.message}")
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) startVpn()
    }

    override fun onDestroy() {
        h.removeCallbacks(poll)
        super.onDestroy()
    }
}
