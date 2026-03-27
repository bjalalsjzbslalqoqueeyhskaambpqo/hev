package com.blacktunnel.panel

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
    private lateinit var progress: ProgressBar

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        baseUrlInput = findViewById(R.id.inputBaseUrl)
        tokenInput = findViewById(R.id.inputToken)
        clientIdInput = findViewById(R.id.inputClientId)
        clientNameInput = findViewById(R.id.inputClientName)
        daysInput = findViewById(R.id.inputDays)
        statusText = findViewById(R.id.textStatus)
        resultText = findViewById(R.id.textResult)
        progress = findViewById(R.id.progress)

        findViewById<Button>(R.id.btnSaveConfig).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { checkConnection() }
        findViewById<Button>(R.id.btnList).setOnClickListener { listClients() }
        findViewById<Button>(R.id.btnCreate).setOnClickListener { createClient() }
        findViewById<Button>(R.id.btnAddDays).setOnClickListener { addDaysToClient() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { deleteClient() }
        findViewById<Button>(R.id.btnToggleToken).setOnClickListener { toggleTokenVisibility() }

        loadConfig()
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
            showStatus("Configura base URL y token primero.", false)
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
        statusText.setTextColor(if (ok) Color.parseColor("#0D7A30") else Color.parseColor("#B00020"))
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
