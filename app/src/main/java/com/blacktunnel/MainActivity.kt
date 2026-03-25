package com.blacktunnel

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var toggleButton: Button
    private lateinit var mtuInput: EditText
    private lateinit var muxInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        toggleButton = Button(this).apply {
            text = "ON"
            setOnClickListener { onToggle() }
        }

        mtuInput = EditText(this).apply {
            hint = "MTU (1200-9000)"
            setText(TunnelPrefs.getMtu(this@MainActivity).toString())
        }

        muxInput = EditText(this).apply {
            hint = "MUX concurrency (1-64)"
            setText(TunnelPrefs.getMux(this@MainActivity).toString())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(mtuInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(muxInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(toggleButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        setContentView(root)
    }

    private fun onToggle() {
        if (toggleButton.text == "ON") {
            saveSettings()
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
        }
    }

    private fun saveSettings() {
        val mtu = mtuInput.text.toString().toIntOrNull()?.coerceIn(1200, 9000) ?: 1300
        val mux = muxInput.text.toString().toIntOrNull()?.coerceIn(1, 64) ?: 8
        TunnelPrefs.setMtu(this, mtu)
        TunnelPrefs.setMux(this, mux)
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
    }

    companion object {
        private const val REQ_VPN_PREPARE = 11
    }
}
