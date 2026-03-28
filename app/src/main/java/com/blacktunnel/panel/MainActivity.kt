package com.blacktunnel.panel

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class ClientItem(
        val id: String,
        val name: String,
        val daysLeft: Int,
        val active: Boolean,
        val expiresAt: Long
    )

    private val client = OkHttpClient()
    private val actionButtonIds = listOf(
        R.id.btnSaveConfig, R.id.btnCheck, R.id.btnList, R.id.btnCreate, R.id.btnAddDays,
        R.id.btnDelete, R.id.btnToggleToken, R.id.btnFilterAll, R.id.btnFilterExp3,
        R.id.btnFilterExp7, R.id.btnSortDays, R.id.btnExport
    )

    private lateinit var baseUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var clientIdInput: EditText
    private lateinit var clientNameInput: EditText
    private lateinit var daysInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var expiringText: TextView
    private lateinit var buildCodeText: TextView
    private lateinit var configCard: MaterialCardView
    private lateinit var progress: ProgressBar
    private lateinit var rootView: View

    private val cachedClients = mutableListOf<ClientItem>()
    private var expiringFilterDays: Int? = null
    private var sortAsc = true
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private var exportPendingPayload: String? = null

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
        searchInput = findViewById(R.id.inputSearch)
        statusText = findViewById(R.id.textStatus)
        resultText = findViewById(R.id.textResult)
        expiringText = findViewById(R.id.textExpiring)
        buildCodeText = findViewById(R.id.textBuildCode)
        progress = findViewById(R.id.progress)

        bindAction(R.id.btnSaveConfig) { saveConfig() }
        bindAction(R.id.btnCheck) { checkConnection() }
        bindAction(R.id.btnList) { refreshClients() }
        bindAction(R.id.btnCreate) { createClient() }
        bindAction(R.id.btnAddDays) { addDaysToClient() }
        bindAction(R.id.btnDelete) { deleteClient() }
        bindAction(R.id.btnToggleToken) { toggleTokenVisibility() }
        bindAction(R.id.btnFilterAll) { setFilter(null) }
        bindAction(R.id.btnFilterExp3) { setFilter(3) }
        bindAction(R.id.btnFilterExp7) { setFilter(7) }
        bindAction(R.id.btnSortDays) { toggleSort() }
        bindAction(R.id.btnExport) { exportCurrentData() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyClientFilters()
        })

        buildCodeText.text = getString(R.string.build_code_value, BuildConfig.SELLER_CODE)

        if (BuildConfig.HAS_INJECTED_CONFIG) {
            setupInjectedConfig()
        } else {
            loadConfig()
            showStatus("Modo editable activo: define URL/token y guarda.", true)
        }

        animateHeader()
        refreshClients()
    }

    private fun bindAction(buttonId: Int, action: () -> Unit) {
        val btn = findViewById<MaterialButton>(buttonId)
        btn.setOnClickListener {
            if (progress.visibility == View.VISIBLE) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            animatePress(it)
            action()
        }
    }

    private fun setFilter(days: Int?) {
        expiringFilterDays = days
        showToast(if (days == null) "Filtro: todos" else "Filtro: expiran en $days días")
        applyClientFilters()
    }

    private fun toggleSort() {
        sortAsc = !sortAsc
        findViewById<MaterialButton>(R.id.btnSortDays).text = if (sortAsc) "Orden días ↑" else "Orden días ↓"
        applyClientFilters()
    }

    private fun setupInjectedConfig() {
        baseUrlInput.setText(BuildConfig.INJECTED_BASE_URL)
        tokenInput.setText(BuildConfig.INJECTED_TOKEN)
        baseUrlInput.isEnabled = false
        tokenInput.isEnabled = false
        findViewById<MaterialButton>(R.id.btnSaveConfig).visibility = View.GONE
        findViewById<MaterialButton>(R.id.btnToggleToken).visibility = View.GONE
        configCard.alpha = 0.92f
        showStatus("App preconfigurada: lista para entregar al usuario final.", true)
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
            parseClientCache(json)
            applyClientFilters()
        }
    }

    private fun refreshClients() {
        request("GET", "/clients", null) { json ->
            parseClientCache(json)
            applyClientFilters()
            showStatus("Listado actualizado.", true)
        }
    }

    private fun parseClientCache(json: JSONObject) {
        val arr = json.optJSONArray("clients") ?: JSONArray()
        cachedClients.clear()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            cachedClients += ClientItem(
                id = item.optString("id"),
                name = item.optString("name", "sin-nombre"),
                daysLeft = item.optInt("days_left", 0),
                active = item.optBoolean("active", false),
                expiresAt = item.optLong("expires_at", 0)
            )
        }
    }

    private fun applyClientFilters() {
        val query = searchInput.text.toString().trim().lowercase(Locale.getDefault())

        val filtered = cachedClients
            .asSequence()
            .filter { expiringFilterDays == null || (it.active && it.daysLeft <= expiringFilterDays!!) }
            .filter {
                query.isBlank() || it.id.lowercase(Locale.getDefault()).contains(query) ||
                    it.name.lowercase(Locale.getDefault()).contains(query)
            }
            .sortedBy { if (sortAsc) it.daysLeft else -it.daysLeft }
            .toList()

        val expiringSoon = cachedClients.count { it.active && it.daysLeft <= 7 }
        expiringText.text = "Por expirar (<=7 días): $expiringSoon"

        resultText.text = if (filtered.isEmpty()) {
            "Sin resultados"
        } else {
            filtered.joinToString(separator = "\n") {
                "• ${it.id} | ${it.name} | días: ${it.daysLeft} | ${if (it.active) "activo" else "expirado"}"
            }
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
        val body = JSONObject().put("id", id).put("name", name).put("days", days)

        request("POST", "/client/create", body) {
            showStatus("Cliente creado/actualizado.", true)
            refreshClients()
        }
    }

    private fun addDaysToClient() {
        val id = clientIdInput.text.toString().trim()
        val days = daysInput.text.toString().trim().toIntOrNull() ?: 0
        if (id.isBlank() || days <= 0) {
            showStatus("Indica client id y días > 0.", false)
            return
        }

        val body = JSONObject().put("id", id).put("add_days", days)
        request("POST", "/client/update", body) {
            showStatus("Días agregados correctamente.", true)
            refreshClients()
        }
    }

    private fun deleteClient() {
        val id = clientIdInput.text.toString().trim()
        if (id.isBlank()) {
            showStatus("Falta client id.", false)
            return
        }
        val body = JSONObject().put("id", id)
        request("POST", "/client/delete", body) {
            showStatus("Cliente eliminado.", true)
            refreshClients()
        }
    }

    private fun exportCurrentData() {
        if (cachedClients.isEmpty()) {
            showStatus("No hay clientes para exportar.", false)
            return
        }

        val json = JSONObject().apply {
            put("generated_at", System.currentTimeMillis())
            put("count", cachedClients.size)
            put("clients", JSONArray().apply {
                cachedClients.forEach {
                    put(JSONObject().put("id", it.id).put("name", it.name).put("days_left", it.daysLeft).put("active", it.active).put("expires_at", it.expiresAt))
                }
            })
        }.toString(2)

        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            exportPendingPayload = json
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 9090)
            return
        }

        saveExport(json)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9090 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            exportPendingPayload?.let { saveExport(it) }
            exportPendingPayload = null
        }
    }

    private fun saveExport(payload: String) {
        val fileName = "adm_vps_clients_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                Uri.fromFile(file)
            }

            if (uri == null) {
                showStatus("No se pudo crear el archivo de exportación.", false)
                return
            }

            contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                ?: run {
                    FileOutputStream(File(uri.path ?: "")).use { it.write(payload.toByteArray()) }
                }

            showStatus("Exportado: $fileName", true)
        } catch (e: Exception) {
            showStatus("Error exportando: ${e.message}", false)
        }
    }

    private fun request(method: String, path: String, body: JSONObject?, onSuccess: (JSONObject) -> Unit) {
        val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()

        if (baseUrl.isBlank() || token.isBlank()) {
            showStatus("No hay configuración de servidor/token.", false)
            return
        }

        val reqBuilder = Request.Builder().url("$baseUrl$path").addHeader("X-Token", token)
        when (method) {
            "GET" -> reqBuilder.get()
            "POST" -> reqBuilder.post((body?.toString() ?: "{}").toRequestBody(jsonMedia))
        }

        setLoading(true)
        Thread {
            try {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                    runOnUiThread {
                        setLoading(false)
                        if (resp.isSuccessful) onSuccess(json)
                        else {
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
        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT).apply {
            setBackgroundTint(Color.parseColor("#1B1B1F"))
            setTextColor(Color.WHITE)
            show()
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        actionButtonIds.forEach { id -> findViewById<MaterialButton>(id).isEnabled = !loading }
    }
}
