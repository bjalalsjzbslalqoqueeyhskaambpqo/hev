package com.blacktunnel.panel

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class ClientItem(val id: String, val name: String, val daysLeft: Int, val active: Boolean, val expiresAt: Long)

    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private lateinit var baseUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var clientIdInput: EditText
    private lateinit var clientNameInput: EditText
    private lateinit var daysInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var expiringText: TextView
    private lateinit var buildCodeText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var configCard: MaterialCardView
    private lateinit var panelCreate: View
    private lateinit var panelList: View
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ClientAdapter

    private var sortAsc = true
    private var expiringFilter: Int? = null
    private val cached = mutableListOf<ClientItem>()

    private val pickImport = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupTabs()
        setupActions()

        buildCodeText.text = getString(R.string.build_code_value, BuildConfig.SELLER_CODE)

        if (BuildConfig.HAS_INJECTED_CONFIG) {
            baseUrlInput.setText(BuildConfig.INJECTED_BASE_URL)
            tokenInput.setText(BuildConfig.INJECTED_TOKEN)
            configCard.visibility = View.GONE
        } else {
            loadConfig()
        }

        refreshClients()
    }

    private fun bindViews() {
        baseUrlInput = findViewById(R.id.inputBaseUrl)
        tokenInput = findViewById(R.id.inputToken)
        clientIdInput = findViewById(R.id.inputClientId)
        clientNameInput = findViewById(R.id.inputClientName)
        daysInput = findViewById(R.id.inputDays)
        searchInput = findViewById(R.id.inputSearch)
        statusText = findViewById(R.id.textStatus)
        expiringText = findViewById(R.id.textExpiring)
        buildCodeText = findViewById(R.id.textBuildCode)
        progress = findViewById(R.id.progress)
        configCard = findViewById(R.id.configCard)
        panelCreate = findViewById(R.id.panelCreate)
        panelList = findViewById(R.id.panelList)
        recycler = findViewById(R.id.recyclerClients)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ClientAdapter(mutableListOf()) { showClientMenu(it) }
        recycler.adapter = adapter
    }

    private fun setupTabs() {
        val tabs = findViewById<TabLayout>(R.id.tabLayout)
        tabs.addTab(tabs.newTab().setText("Crear"))
        tabs.addTab(tabs.newTab().setText("Listar"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position == 0) {
                    panelCreate.visibility = View.VISIBLE
                    panelList.visibility = View.GONE
                } else {
                    panelCreate.visibility = View.GONE
                    panelList.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun setupActions() {
        findViewById<MaterialButton>(R.id.btnSaveConfig).setOnClickListener { saveConfig() }
        findViewById<MaterialButton>(R.id.btnCheck).setOnClickListener { testApi() }
        findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener { createClient() }
        findViewById<MaterialButton>(R.id.btnAddDays).setOnClickListener { addDays() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { deleteClient() }
        findViewById<MaterialButton>(R.id.btnList).setOnClickListener { refreshClients() }
        findViewById<MaterialButton>(R.id.btnSortDays).setOnClickListener {
            sortAsc = !sortAsc
            it as MaterialButton
            it.text = if (sortAsc) "Días ↑" else "Días ↓"
            applyFilters()
        }
        findViewById<MaterialButton>(R.id.btnFilterAll).setOnClickListener { expiringFilter = null; applyFilters() }
        findViewById<MaterialButton>(R.id.btnFilterExp3).setOnClickListener { expiringFilter = 3; applyFilters() }
        findViewById<MaterialButton>(R.id.btnFilterExp7).setOnClickListener { expiringFilter = 7; applyFilters() }
        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener { exportClients() }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener { pickImport.launch("application/json") }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilters()
        })
    }

    private fun prefs() = EncryptedSharedPreferences.create(
        this,
        "panel_cfg",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun saveConfig() {
        prefs().edit().putString("base_url", baseUrlInput.text.toString().trim().trimEnd('/'))
            .putString("token", tokenInput.text.toString().trim()).apply()
        showStatus("Configuración guardada.", true)
    }

    private fun loadConfig() {
        val p = prefs()
        baseUrlInput.setText(p.getString("base_url", "") ?: "")
        tokenInput.setText(p.getString("token", "") ?: "")
    }

    private fun testApi() = request("GET", "/clients", null) {
        showStatus("API responde correctamente.", true)
    }

    private fun createClient() {
        val id = clientIdInput.text.toString().trim()
        if (id.isBlank()) return showStatus("Falta client ID", false)
        val name = clientNameInput.text.toString().trim().ifBlank { "sin-nombre" }
        val days = daysInput.text.toString().trim().toIntOrNull() ?: 30
        request("POST", "/client/create", JSONObject().put("id", id).put("name", name).put("days", days)) {
            showStatus("Cliente creado", true); refreshClients()
        }
    }

    private fun addDays() {
        val id = clientIdInput.text.toString().trim()
        val days = daysInput.text.toString().trim().toIntOrNull() ?: 0
        if (id.isBlank() || days <= 0) return showStatus("ID o días inválidos", false)
        request("POST", "/client/update", JSONObject().put("id", id).put("add_days", days)) {
            showStatus("Días agregados", true); refreshClients()
        }
    }

    private fun deleteClient() {
        val id = clientIdInput.text.toString().trim()
        if (id.isBlank()) return showStatus("Falta client ID", false)
        request("POST", "/client/delete", JSONObject().put("id", id)) {
            showStatus("Cliente eliminado", true); refreshClients()
        }
    }

    private fun refreshClients() = request("GET", "/clients", null) { json ->
        cached.clear()
        val arr = json.optJSONArray("clients") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            cached += ClientItem(o.optString("id"), o.optString("name", "sin-nombre"), o.optInt("days_left"), o.optBoolean("active"), o.optLong("expires_at"))
        }
        applyFilters()
    }

    private fun applyFilters() {
        val q = searchInput.text.toString().trim().lowercase(Locale.getDefault())
        val list = cached.asSequence()
            .filter { expiringFilter == null || (it.active && it.daysLeft <= expiringFilter!!) }
            .filter { q.isBlank() || it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
            .sortedBy { if (sortAsc) it.daysLeft else -it.daysLeft }
            .toMutableList()
        adapter.update(list)
        expiringText.text = "Por expirar (<=7 días): ${cached.count { it.active && it.daysLeft <= 7 }}"
    }

    private fun showClientMenu(item: ClientItem, anchor: View) {
        val options = arrayOf("Editar nombre", "Aumentar días", "Eliminar")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> editName(item)
                    1 -> quickAddDays(item)
                    2 -> request("POST", "/client/delete", JSONObject().put("id", item.id)) { refreshClients() }
                }
            }.show()
    }

    private fun editName(item: ClientItem) {
        val input = EditText(this).apply { setText(item.name) }
        AlertDialog.Builder(this).setTitle("Editar nombre")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                request("POST", "/client/update", JSONObject().put("id", item.id).put("name", input.text.toString().trim())) { refreshClients() }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun quickAddDays(item: ClientItem) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("30") }
        AlertDialog.Builder(this).setTitle("Agregar días")
            .setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                val d = input.text.toString().toIntOrNull() ?: 0
                if (d > 0) request("POST", "/client/update", JSONObject().put("id", item.id).put("add_days", d)) { refreshClients() }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun exportClients() {
        if (cached.isEmpty()) return showStatus("No hay clientes", false)
        val payload = JSONObject().put("clients", JSONArray().apply {
            cached.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("days", it.daysLeft)) }
        }).toString(2)
        val fileName = "adm_vps_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                })
            } else {
                Uri.fromFile(java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName))
            } ?: return showStatus("No se pudo exportar", false)
            contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
            showStatus("Exportado en Descargas", true)
        } catch (e: Exception) {
            showStatus("Error exportación: ${e.message}", false)
        }
    }

    private fun importFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            val arr = JSONObject(text).optJSONArray("clients") ?: JSONArray()
            var imported = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) continue
                val name = o.optString("name", "sin-nombre")
                val days = o.optInt("days", 30)
                request("POST", "/client/create", JSONObject().put("id", id).put("name", name).put("days", days), silent = true) {}
                imported++
            }
            showStatus("Importados $imported clientes", true)
            refreshClients()
        } catch (e: Exception) {
            showStatus("Importación falló: ${e.message}", false)
        }
    }

    private fun request(method: String, path: String, body: JSONObject?, onSuccess: (JSONObject) -> Unit, silent: Boolean = false) {
        val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        if (baseUrl.isBlank() || token.isBlank()) return showStatus("Falta URL/token", false)

        val req = Request.Builder().url("$baseUrl$path").addHeader("X-Token", token).apply {
            if (method == "GET") get() else post((body?.toString() ?: "{}").toRequestBody(jsonMedia))
        }.build()

        if (!silent) setLoading(true)
        Thread {
            try {
                client.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    val json = if (txt.isBlank()) JSONObject() else JSONObject(txt)
                    runOnUiThread {
                        if (!silent) setLoading(false)
                        if (resp.isSuccessful) onSuccess(json) else showStatus("Error ${resp.code}", false)
                    }
                }
            } catch (_: IOException) {
                runOnUiThread { if (!silent) setLoading(false); showStatus("Fallo de red", false) }
            }
        }.start()
    }

    private fun showStatus(msg: String, ok: Boolean) {
        statusText.text = msg
        statusText.setTextColor(if (ok) Color.parseColor("#86EFAC") else Color.parseColor("#FCA5A5"))
        Snackbar.make(findViewById(R.id.rootLayout), msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    class ClientAdapter(private val items: MutableList<ClientItem>, val onMenu: (ClientItem, View) -> Unit) : RecyclerView.Adapter<ClientAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.textClientName)
            val info: TextView = v.findViewById(R.id.textClientInfo)
            val menu: ImageButton = v.findViewById(R.id.btnClientMenu)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_client, parent, false))
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.info.text = "${item.id} · días ${item.daysLeft} · ${if (item.active) "activo" else "expirado"}"
            holder.menu.setOnClickListener { onMenu(item, holder.menu) }
        }

        fun update(newItems: MutableList<ClientItem>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }
    }
}
