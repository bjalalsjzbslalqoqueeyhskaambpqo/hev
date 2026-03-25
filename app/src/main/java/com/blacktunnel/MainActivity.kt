package com.blacktunnel

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var toggleButton: Button
    private lateinit var logsView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refreshTask = object : Runnable {
        override fun run() {
            logsView.text = LogStore.dump()
            if (logsView.layout != null) {
                val delta = logsView.layout.getLineTop(logsView.lineCount) - logsView.height
                if (delta > 0) logsView.scrollTo(0, delta) else logsView.scrollTo(0, 0)
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        toggleButton = Button(this).apply {
            text = "ON"
            setOnClickListener { onToggle() }
        }
        logsView = TextView(this).apply {
            movementMethod = ScrollingMovementMethod()
            textSize = 12f
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(toggleButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(
                logsView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        setContentView(root)

        handler.post(refreshTask)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        super.onDestroy()
    }

    private fun onToggle() {
        if (toggleButton.text == "ON") {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                startActivityForResult(prepareIntent, REQ_VPN_PREPARE)
                return
            }
            startVpn()
        } else {
            stopService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
            startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
            toggleButton.text = "ON"
            LogStore.add("VPN requested OFF")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE && resultCode == RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_START))
        toggleButton.text = "OFF"
        LogStore.add("VPN requested ON")
    }

    companion object {
        private const val REQ_VPN_PREPARE = 11
    }
}
