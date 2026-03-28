package com.blacktunnel.panel

import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()

    private lateinit var baseUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var clientIdInput: EditText
    private lateinit var clientNameInput: EditText
    private lateinit var daysInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var buildCodeText: TextView
    private lateinit var configCard: MaterialCardView
    private lateinit var progress: ProgressBar
    private lateinit var rootView: View

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.rootLayout)
        configCard = findViewById(R.id.configCard)
        baseUrlInput = findViewById(R.id.inputBaseUrl)
        tokenInput = findViewById(R.id.inputToken)
        clientIdInput = findViewById(R.id.inputClientId)
        clientNameInput = findViewById(R.id.inputClientName)
        daysInput = findViewById(R.id.inputDays)
        statusText = findViewById(R.id.textStatus)
        resultText = findViewById(R.id.textResult)
        buildCodeText = findViewById(R.id.textBuildCode)
        progress = findViewById(R.id.progress)

        bindAction(R.id.btnSaveConfig) { saveConfig() }
        bindAction(R.id.btnCheck) { checkConnection() }
        bindAction(R.id.btnList) { listClients() }
        bindAction(R.id.btnCreate) { createClient() }
        bindAction(R.id.btnAddDays) { addDaysToClient() }
        bindAction(R.id.btnDelete) { deleteClient() }
        bindAction(R.id.btnToggleToken) { toggleTokenVisibility() }

        buildCodeText.text = getString(R.string.build_code_value, BuildConfig.SELLER_CODE)

        if (BuildConfig.HAS_INJECTED_CONFIG) {
            setupInjectedConfig()
        } else {
            loadConfig()
            showStatus("Modo editable activo: define URL/token y guarda.", true)
        }

        animateHeader()
    }

    private fun setupInjectedConfig() {
        baseUrlInput.setText(BuildConfig.INJECTED_BASE_URL)
        tokenInput.setText(BuildConfig.INJECTED_TOKEN)

        baseUrlInput.isEnabled = false
        tokenInput.isEnabled = false

        findViewById<MaterialButton>(R.id.btnSaveConfig).visibility = View.GONE
        findViewById<MaterialButton>(R.id.btnToggleToken).visibility = View.GONE

        configCard.alpha = 0.92f
        showStatus("App preconfigurada: el usuario final no necesita poner URL/token.", true)
    }

    private fun bindAction(buttonId: Int, action: () -> Unit) {
        val btn = findViewById<MaterialButton>(buttonId)
        btn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            animatePress(it)
            action()
        }
    }

    private fun animatePress(view: View) {
        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(OvershootInterpolator()).start()
        }.start()
    }

    private fun animateHeader() {
        ObjectAnimator.ofFloat(findViewById(R.id.headerCard), "alpha", 0f, 1f).apply {
            duration = 500
            start()
        }
    }

    private fun prefs() = EncryptedSharedPreferences.create(
        this,
        "panel_cfg",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun saveConfig() {
        prefs().edit()
            .putString("base_url", baseUrlInput.text.toString().trim().trimEnd('/'))
            .putString("token", tokenInput.text.toString().trim())
            .apply()
        showStatus("Configuración guardada (token cifrado localmente).", true)
    }

    private fun loadConfig() {
        val p = prefs()
        baseUrlInput.setText(p.getString("base_url", "") ?: "")
        tokenInput.setText(p.getString("token", "") ?: "")
    }

    private fun toggleTokenVisibility() {
        val hidden = tokenInput.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD == InputType.TYPE_TEXT_VARIATION_PASSWORD
        tokenInput.inputType = if (hidden) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        tokenInput.setSelection(tokenInput.text.length)
        showToast(if (hidden) "Token visible temporalmente" else "Token oculto")
    }

    private fun checkConnection() {
        request("GET", "/clients", null) { json ->
            val total = json.optInt("total", -1)
            showStatus("Conexión OK. Clientes: $total", true)
            resultText.text = json.toString(2)
        }
    }

    private fun listClients() {
        request("GET", "/clients", null) { json ->
            val clients = json.optJSONArray("clients") ?: JSONArray()
            val rows = buildString {
                for (i in 0 until clients.length()) {
                    val c = clients.getJSONObject(i)
                    append("• ")
                    append(c.optString("id"))
                    append(" | ")
                    append(c.optString("name"))
                    append(" | días: ")
                    append(c.optInt("days_left"))
                    append(" | activo: ")
                    append(if (c.optBoolean("active")) "sí" else "no")
                    append("\n")
                }
            }
            showStatus("Listado actualizado.", true)
            resultText.text = if (rows.isBlank()) "Sin clientes" else rows
        }
    }

    private fun createClient() {
        val id = clientIdInput.text.toString().trim()
        if (id.isBlank()) {
            showStatus("Falta client id.", false)
            return
        }
        val name = clientNameInput.text.toString().trim().ifBlank { "sin-nombre" }
        val days = daysInput.text.toString().trim().toIntOrNull() ?: 30
        val body = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("days", days)

        request("POST", "/client/create", body) { json ->
            showStatus("Cliente creado/actualizado.", true)
            resultText.text = json.toString(2)
        }
    }

    private fun addDaysToClient() {
        val id = clientIdInput.text.toString().trim()
        val days = daysInput.text.toString().trim().toIntOrNull() ?: 0
        if (id.isBlank() || days <= 0) {
            showStatus("Indica client id y días > 0.", false)
            return
        }

        val body = JSONObject()
            .put("id", id)
            .put("add_days", days)

        request("POST", "/client/update", body) { json ->
            showStatus("Días agregados correctamente.", true)
            resultText.text = json.toString(2)
        }
    }

    private fun deleteClient() {
        val id = clientIdInput.text.toString().trim()
        if (id.isBlank()) {
            showStatus("Falta client id.", false)
            return
        }
        val body = JSONObject().put("id", id)
        request("POST", "/client/delete", body) { json ->
            showStatus("Cliente eliminado.", true)
            resultText.text = json.toString(2)
        }
    }

    private fun request(method: String, path: String, body: JSONObject?, onSuccess: (JSONObject) -> Unit) {
        val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()

        if (baseUrl.isBlank() || token.isBlank()) {
            showStatus("No hay configuración de servidor/token.", false)
            return
        }

        val reqBuilder = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("X-Token", token)

        when (method) {
            "GET" -> reqBuilder.get()
            "POST" -> {
                val raw = body?.toString() ?: "{}"
                reqBuilder.post(raw.toRequestBody(jsonMedia))
            }
        }

        setLoading(true)
        Thread {
            try {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                    runOnUiThread {
                        setLoading(false)
                        if (resp.isSuccessful) {
                            onSuccess(json)
                        } else {
                            showStatus("Error ${resp.code}: ${json.optString("error", "sin detalle")}", false)
                            resultText.text = text
                        }
                    }
                }
            } catch (ex: IOException) {
                runOnUiThread {
                    setLoading(false)
                    showStatus("Fallo de red: ${ex.message}", false)
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showStatus("Error inesperado: ${ex.message}", false)
                }
            }
        }.start()
    }

    private fun showStatus(message: String, ok: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        showToast(message)
    }

    private fun showToast(message: String) {
        val bar = Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
        bar.setBackgroundTint(Color.parseColor("#1B1B1F"))
        bar.setTextColor(Color.WHITE)
        bar.show()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
