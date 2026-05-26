package com.blacktunnel.panel

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class ClientItem(
        val id: String,
        val name: String,
        val daysLeft: Int,
        val hoursLeft: Int,
        val minutesLeft: Int,
        val active: Boolean
    )

    private val http = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val clients = mutableListOf<ClientItem>()

    private lateinit var clientIdInput: EditText
    private lateinit var clientNameInput: EditText
    private lateinit var daysInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var expiringText: TextView
    private lateinit var buildCodeText: TextView
    private lateinit var panelCreate: View
    private lateinit var panelList: View
    private lateinit var progress: View
    private lateinit var adapter: ClientAdapter

    private var sortAsc = true
    private var expiringFilter: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupTabs()
        setupActions()

        buildCodeText.text = getString(R.string.build_code_value, BuildConfig.SELLER_CODE)

        if (!BuildConfig.HAS_INJECTED_CONFIG) {
            showStatus("Error: falta PANEL_CONFIG.txt en build", false)
            disableAllActions()
            return
        }

        refreshClients()
    }

    private fun bindViews() {
        clientIdInput = findViewById(R.id.inputClientId)
        clientNameInput = findViewById(R.id.inputClientName)
        daysInput = findViewById(R.id.inputDays)
        searchInput = findViewById(R.id.inputSearch)
        statusText = findViewById(R.id.textStatus)
        expiringText = findViewById(R.id.textExpiring)
        buildCodeText = findViewById(R.id.textBuildCode)
        panelCreate = findViewById(R.id.panelCreate)
        panelList = findViewById(R.id.panelList)
        progress = findViewById(R.id.progress)

        val recycler = findViewById<RecyclerView>(R.id.recyclerClients)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ClientAdapter(mutableListOf()) { item -> showItemMenu(item) }
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
        findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener { createClient() }
        findViewById<MaterialButton>(R.id.btnAddDays).setOnClickListener { addDays() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { deleteClient() }
        findViewById<MaterialButton>(R.id.btnList).setOnClickListener { refreshClients() }
        findViewById<MaterialButton>(R.id.btnSortDays).setOnClickListener {
            sortAsc = !sortAsc
            (it as MaterialButton).text = if (sortAsc) "Días ↑" else "Días ↓"
            applyFilters()
        }
        findViewById<MaterialButton>(R.id.btnFilterAll).setOnClickListener { expiringFilter = null; applyFilters() }
        findViewById<MaterialButton>(R.id.btnFilterExp3).setOnClickListener { expiringFilter = 3; applyFilters() }
        findViewById<MaterialButton>(R.id.btnFilterExp7).setOnClickListener { expiringFilter = 7; applyFilters() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilters()
        })
    }

    private fun apiBase() = BuildConfig.INJECTED_BASE_URL.trim().trimEnd('/')
    private fun apiToken() = BuildConfig.INJECTED_TOKEN.trim()

    private fun createClient() {
        val id = clientIdInput.text.toString().trim()
        if (id.isBlank()) return showStatus("Falta client ID", false)
        val name = clientNameInput.text.toString().trim().ifBlank { "sin-nombre" }
        val days = daysInput.text.toString().trim().toIntOrNull() ?: 30
        request("POST", "/client/create", JSONObject().put("id", id).put("name", name).put("days", days)) {
            showStatus("Cliente creado", true)
            refreshClients()
        }
    }

    private fun addDays() {
        val id = clientIdInput.text.toString().trim()
        val days = daysInput.text.toString().trim().toIntOrNull() ?: 0
        if (id.isBlank() || days <= 0) return showStatus("ID o días inválidos", false)
        request("POST", "/client/update", JSONObject().put("id", id).put("add_days", days)) {
            showStatus("Días agregados", true)
            refreshClients()
        }
    }

    private fun deleteClient() {
        val id = clientIdInput.text.toString().trim()
        if (id.isBlank()) return showStatus("Falta client ID", false)
        request("POST", "/client/delete", JSONObject().put("id", id)) {
            showStatus("Cliente eliminado", true)
            refreshClients()
        }
    }

    private fun refreshClients() = request("GET", "/clients", null) { json ->
        clients.clear()
        val arr = json.optJSONArray("clients") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            clients += ClientItem(
                id = it.optString("id"),
                name = it.optString("name", "sin-nombre"),
                daysLeft = it.optInt("days_left", 0),
                hoursLeft = it.optInt("hours_left", 0),
                minutesLeft = it.optInt("minutes_left", 0),
                active = it.optBoolean("active", false)
            )
        }
        applyFilters()
    }

    private fun applyFilters() {
        val q = searchInput.text.toString().trim().lowercase(Locale.getDefault())
        val filtered = clients.asSequence()
            .filter { expiringFilter == null || (it.active && it.daysLeft <= expiringFilter!!) }
            .filter { q.isBlank() || it.id.lowercase(Locale.getDefault()).contains(q) || it.name.lowercase(Locale.getDefault()).contains(q) }
            .sortedBy { if (sortAsc) it.daysLeft else -it.daysLeft }
            .toMutableList()

        expiringText.text = "Por expirar (≤7 días): ${clients.count { it.active && it.daysLeft <= 7 }}"
        adapter.update(filtered)
    }

    private fun showItemMenu(item: ClientItem) {
        val options = arrayOf("Editar nombre", "Aumentar días", "Eliminar")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> editName(item)
                    1 -> promptAddDays(item)
                    2 -> request("POST", "/client/delete", JSONObject().put("id", item.id)) { refreshClients() }
                }
            }.show()
    }

    private fun editName(item: ClientItem) {
        val input = EditText(this).apply { setText(item.name) }
        AlertDialog.Builder(this)
            .setTitle("Editar nombre")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                request("POST", "/client/update", JSONObject().put("id", item.id).put("name", input.text.toString().trim())) { refreshClients() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptAddDays(item: ClientItem) {
        val input = EditText(this).apply { setText("30") }
        AlertDialog.Builder(this)
            .setTitle("Aumentar días")
            .setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                val days = input.text.toString().toIntOrNull() ?: 0
                if (days > 0) request("POST", "/client/update", JSONObject().put("id", item.id).put("add_days", days)) { refreshClients() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun request(method: String, path: String, body: JSONObject?, onSuccess: (JSONObject) -> Unit) {
        val baseUrl = apiBase()
        val token = apiToken()

        if (baseUrl.isBlank() || token.isBlank()) {
            showStatus("Config inyectada inválida", false)
            return
        }

        val req = Request.Builder().url("$baseUrl$path").addHeader("X-Token", token).apply {
            if (method == "GET") get() else post((body?.toString() ?: "{}").toRequestBody(jsonMedia))
        }.build()

        setLoading(true)
        Thread {
            try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                    runOnUiThread {
                        setLoading(false)
                        if (resp.isSuccessful) onSuccess(json) else showStatus("Error ${resp.code}", false)
                    }
                }
            } catch (_: IOException) {
                runOnUiThread {
                    setLoading(false)
                    showStatus("Fallo de red", false)
                }
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun disableAllActions() {
        listOf(R.id.btnCreate, R.id.btnAddDays, R.id.btnDelete, R.id.btnList, R.id.btnSortDays, R.id.btnFilterAll, R.id.btnFilterExp3, R.id.btnFilterExp7)
            .forEach { findViewById<MaterialButton>(it).isEnabled = false }
    }

    private fun showStatus(msg: String, ok: Boolean) {
        statusText.text = msg
        statusText.setTextColor(if (ok) Color.parseColor("#86EFAC") else Color.parseColor("#FCA5A5"))
        Snackbar.make(findViewById(R.id.rootLayout), msg, Snackbar.LENGTH_SHORT).show()
    }

    class ClientAdapter(private val items: MutableList<ClientItem>, private val onMenu: (ClientItem) -> Unit) : RecyclerView.Adapter<ClientAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.textClientName)
            val info: TextView = v.findViewById(R.id.textClientInfo)
            val menu: ImageButton = v.findViewById(R.id.btnClientMenu)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_client, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            val status = when {
                !item.active -> "EXPIRADO"
                item.daysLeft == 0 -> "Vence hoy en ${item.hoursLeft}h ${item.minutesLeft}m"
                else -> "${item.daysLeft}d ${item.hoursLeft}h"
            }
            val statusColor = when {
                !item.active -> Color.parseColor("#EF4444")
                item.daysLeft == 0 -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#22C55E")
            }
            holder.info.text = "${item.id} · $status"
            holder.info.setTextColor(statusColor)
            holder.menu.setOnClickListener { onMenu(item) }
        }

        fun update(newItems: MutableList<ClientItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
    }
}
