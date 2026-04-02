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

    enum class ServiceMode { VLESS, SSH }
    enum class ContentMode { CREATE, LIST }

    data class ClientItem(val id: String, val name: String, val daysLeft: Int, val active: Boolean)

    private val http = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val vlessClients = mutableListOf<ClientItem>()
    private val sshClients = mutableListOf<ClientItem>()

    private lateinit var vlessClientIdInput: EditText
    private lateinit var vlessClientNameInput: EditText
    private lateinit var vlessDaysInput: EditText
    private lateinit var vlessSearchInput: EditText
    private lateinit var vlessExpiringText: TextView

    private lateinit var sshUserInput: EditText
    private lateinit var sshPasswordInput: EditText
    private lateinit var sshNameInput: EditText
    private lateinit var sshDaysInput: EditText
    private lateinit var sshSearchInput: EditText
    private lateinit var sshExpiringText: TextView

    private lateinit var statusText: TextView
    private lateinit var buildCodeText: TextView
    private lateinit var progress: View

    private lateinit var panelCreateVless: View
    private lateinit var panelListVless: View
    private lateinit var panelCreateSsh: View
    private lateinit var panelListSsh: View

    private lateinit var vlessSortButton: MaterialButton
    private lateinit var sshSortButton: MaterialButton

    private lateinit var vlessAdapter: ClientAdapter
    private lateinit var sshAdapter: ClientAdapter

    private var selectedService = ServiceMode.VLESS
    private var selectedContent = ContentMode.CREATE

    private var sortAscVless = true
    private var expiringFilterVless: Int? = null
    private var sortAscSsh = true
    private var expiringFilterSsh: Int? = null

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

        refreshClientsVless()
        refreshClientsSsh()
        updateVisiblePanel()
    }

    private fun bindViews() {
        vlessClientIdInput = findViewById(R.id.inputClientId)
        vlessClientNameInput = findViewById(R.id.inputClientName)
        vlessDaysInput = findViewById(R.id.inputDays)
        vlessSearchInput = findViewById(R.id.inputSearch)
        vlessExpiringText = findViewById(R.id.textExpiring)

        sshUserInput = findViewById(R.id.inputSshUser)
        sshPasswordInput = findViewById(R.id.inputSshPassword)
        sshNameInput = findViewById(R.id.inputSshName)
        sshDaysInput = findViewById(R.id.inputSshDays)
        sshSearchInput = findViewById(R.id.inputSearchSsh)
        sshExpiringText = findViewById(R.id.textExpiringSsh)

        statusText = findViewById(R.id.textStatus)
        buildCodeText = findViewById(R.id.textBuildCode)
        progress = findViewById(R.id.progress)

        panelCreateVless = findViewById(R.id.panelCreateVless)
        panelListVless = findViewById(R.id.panelListVless)
        panelCreateSsh = findViewById(R.id.panelCreateSsh)
        panelListSsh = findViewById(R.id.panelListSsh)

        vlessSortButton = findViewById(R.id.btnSortDays)
        sshSortButton = findViewById(R.id.btnSortDaysSsh)

        val vlessRecycler = findViewById<RecyclerView>(R.id.recyclerClients)
        vlessRecycler.layoutManager = LinearLayoutManager(this)
        vlessAdapter = ClientAdapter(mutableListOf()) { item -> showItemMenu(item, ServiceMode.VLESS) }
        vlessRecycler.adapter = vlessAdapter

        val sshRecycler = findViewById<RecyclerView>(R.id.recyclerClientsSsh)
        sshRecycler.layoutManager = LinearLayoutManager(this)
        sshAdapter = ClientAdapter(mutableListOf()) { item -> showItemMenu(item, ServiceMode.SSH) }
        sshRecycler.adapter = sshAdapter
    }

    private fun setupTabs() {
        val serviceTabs = findViewById<TabLayout>(R.id.tabService)
        serviceTabs.addTab(serviceTabs.newTab().setText("VLESS"))
        serviceTabs.addTab(serviceTabs.newTab().setText("SSH"))
        serviceTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedService = if (tab.position == 0) ServiceMode.VLESS else ServiceMode.SSH
                updateVisiblePanel()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        val sectionTabs = findViewById<TabLayout>(R.id.tabSection)
        sectionTabs.addTab(sectionTabs.newTab().setText("Crear"))
        sectionTabs.addTab(sectionTabs.newTab().setText("Listar"))
        sectionTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedContent = if (tab.position == 0) ContentMode.CREATE else ContentMode.LIST
                updateVisiblePanel()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun setupActions() {
        findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener { createVlessClient() }
        findViewById<MaterialButton>(R.id.btnAddDays).setOnClickListener { addDaysVless() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { deleteVlessClient() }
        findViewById<MaterialButton>(R.id.btnList).setOnClickListener { refreshClientsVless() }
        vlessSortButton.setOnClickListener {
            sortAscVless = !sortAscVless
            vlessSortButton.text = if (sortAscVless) "Días ↑" else "Días ↓"
            applyFiltersVless()
        }
        findViewById<MaterialButton>(R.id.btnFilterAll).setOnClickListener { expiringFilterVless = null; applyFiltersVless() }
        findViewById<MaterialButton>(R.id.btnFilterExp3).setOnClickListener { expiringFilterVless = 3; applyFiltersVless() }
        findViewById<MaterialButton>(R.id.btnFilterExp7).setOnClickListener { expiringFilterVless = 7; applyFiltersVless() }

        findViewById<MaterialButton>(R.id.btnCreateSsh).setOnClickListener { createSshClient() }
        findViewById<MaterialButton>(R.id.btnAddDaysSsh).setOnClickListener { addDaysSsh() }
        findViewById<MaterialButton>(R.id.btnDeleteSsh).setOnClickListener { deleteSshClient() }
        findViewById<MaterialButton>(R.id.btnListSsh).setOnClickListener { refreshClientsSsh() }
        sshSortButton.setOnClickListener {
            sortAscSsh = !sortAscSsh
            sshSortButton.text = if (sortAscSsh) "Días ↑" else "Días ↓"
            applyFiltersSsh()
        }
        findViewById<MaterialButton>(R.id.btnFilterAllSsh).setOnClickListener { expiringFilterSsh = null; applyFiltersSsh() }
        findViewById<MaterialButton>(R.id.btnFilterExp3Ssh).setOnClickListener { expiringFilterSsh = 3; applyFiltersSsh() }
        findViewById<MaterialButton>(R.id.btnFilterExp7Ssh).setOnClickListener { expiringFilterSsh = 7; applyFiltersSsh() }

        vlessSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFiltersVless()
        })

        sshSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFiltersSsh()
        })
    }

    private fun updateVisiblePanel() {
        panelCreateVless.visibility = if (selectedService == ServiceMode.VLESS && selectedContent == ContentMode.CREATE) View.VISIBLE else View.GONE
        panelListVless.visibility = if (selectedService == ServiceMode.VLESS && selectedContent == ContentMode.LIST) View.VISIBLE else View.GONE
        panelCreateSsh.visibility = if (selectedService == ServiceMode.SSH && selectedContent == ContentMode.CREATE) View.VISIBLE else View.GONE
        panelListSsh.visibility = if (selectedService == ServiceMode.SSH && selectedContent == ContentMode.LIST) View.VISIBLE else View.GONE
    }

    private fun apiBase() = BuildConfig.INJECTED_BASE_URL.trim().trimEnd('/')
    private fun apiToken() = BuildConfig.INJECTED_TOKEN.trim()

    private fun createVlessClient() {
        val id = vlessClientIdInput.text.toString().trim()
        if (id.isBlank()) return showStatus("Falta client ID", false)
        val name = vlessClientNameInput.text.toString().trim().ifBlank { "sin-nombre" }
        val days = vlessDaysInput.text.toString().trim().toIntOrNull() ?: 30
        request("POST", "/client/create", JSONObject().put("id", id).put("name", name).put("days", days)) {
            showStatus("Cliente VLESS creado", true)
            refreshClientsVless()
        }
    }

    private fun addDaysVless() {
        val id = vlessClientIdInput.text.toString().trim()
        val days = vlessDaysInput.text.toString().trim().toIntOrNull() ?: 0
        if (id.isBlank() || days <= 0) return showStatus("ID o días inválidos", false)
        request("POST", "/client/update", JSONObject().put("id", id).put("add_days", days)) {
            showStatus("Días agregados en VLESS", true)
            refreshClientsVless()
        }
    }

    private fun deleteVlessClient() {
        val id = vlessClientIdInput.text.toString().trim()
        if (id.isBlank()) return showStatus("Falta client ID", false)
        request("POST", "/client/delete", JSONObject().put("id", id)) {
            showStatus("Cliente VLESS eliminado", true)
            refreshClientsVless()
        }
    }

    private fun refreshClientsVless() = request("GET", "/clients", null) { json ->
        vlessClients.clear()
        val arr = json.optJSONArray("clients") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            vlessClients += ClientItem(
                id = item.optString("id"),
                name = item.optString("name", "sin-nombre"),
                daysLeft = item.optInt("days_left", 0),
                active = item.optBoolean("active", false)
            )
        }
        applyFiltersVless()
    }

    private fun createSshClient() {
        val user = sshUserInput.text.toString().trim()
        val password = sshPasswordInput.text.toString().trim()
        if (user.isBlank() || password.isBlank()) return showStatus("Usuario y contraseña son obligatorios", false)
        val name = sshNameInput.text.toString().trim().ifBlank { user }
        val days = sshDaysInput.text.toString().trim().toIntOrNull() ?: 30
        request("POST", "/ssh/create", JSONObject().put("user", user).put("password", password).put("name", name).put("days", days)) {
            showStatus("Usuario SSH creado", true)
            refreshClientsSsh()
        }
    }

    private fun addDaysSsh() {
        val user = sshUserInput.text.toString().trim()
        val days = sshDaysInput.text.toString().trim().toIntOrNull() ?: 0
        if (user.isBlank() || days <= 0) return showStatus("Usuario o días inválidos", false)
        request("POST", "/ssh/update", JSONObject().put("user", user).put("days", days).put("add_days", days)) {
            showStatus("Días actualizados en SSH", true)
            refreshClientsSsh()
        }
    }

    private fun deleteSshClient() {
        val user = sshUserInput.text.toString().trim()
        if (user.isBlank()) return showStatus("Falta usuario SSH", false)
        request("POST", "/ssh/delete", JSONObject().put("user", user)) {
            showStatus("Usuario SSH eliminado", true)
            refreshClientsSsh()
        }
    }

    private fun refreshClientsSsh() = request("GET", "/ssh/users", null) { json ->
        sshClients.clear()
        val arr = json.optJSONArray("users") ?: json.optJSONArray("clients") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val daysLeft = item.optInt("days_left", item.optInt("days", item.optInt("remaining_days", 0)))
            val isActive = if (item.has("active")) item.optBoolean("active", false) else daysLeft > 0
            sshClients += ClientItem(
                id = item.optString("user", item.optString("id")),
                name = item.optString("name", "sin-nombre"),
                daysLeft = daysLeft,
                active = isActive
            )
        }
        applyFiltersSsh()
    }

    private fun applyFiltersVless() {
        val q = vlessSearchInput.text.toString().trim().lowercase(Locale.getDefault())
        val filtered = vlessClients.asSequence()
            .filter { expiringFilterVless == null || (it.active && it.daysLeft <= expiringFilterVless!!) }
            .filter { q.isBlank() || it.id.lowercase(Locale.getDefault()).contains(q) || it.name.lowercase(Locale.getDefault()).contains(q) }
            .sortedBy { if (sortAscVless) it.daysLeft else -it.daysLeft }
            .toMutableList()

        vlessExpiringText.text = "Por expirar (≤7 días): ${vlessClients.count { it.active && it.daysLeft <= 7 }}"
        vlessAdapter.update(filtered)
    }

    private fun applyFiltersSsh() {
        val q = sshSearchInput.text.toString().trim().lowercase(Locale.getDefault())
        val filtered = sshClients.asSequence()
            .filter { expiringFilterSsh == null || (it.active && it.daysLeft <= expiringFilterSsh!!) }
            .filter { q.isBlank() || it.id.lowercase(Locale.getDefault()).contains(q) || it.name.lowercase(Locale.getDefault()).contains(q) }
            .sortedBy { if (sortAscSsh) it.daysLeft else -it.daysLeft }
            .toMutableList()

        sshExpiringText.text = "Por expirar (≤7 días): ${sshClients.count { it.active && it.daysLeft <= 7 }}"
        sshAdapter.update(filtered)
    }

    private fun showItemMenu(item: ClientItem, mode: ServiceMode) {
        val options = arrayOf("Editar nombre", "Aumentar días", "Eliminar")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> editName(item, mode)
                    1 -> promptAddDays(item, mode)
                    2 -> deleteFromList(item, mode)
                }
            }.show()
    }

    private fun deleteFromList(item: ClientItem, mode: ServiceMode) {
        if (mode == ServiceMode.VLESS) {
            request("POST", "/client/delete", JSONObject().put("id", item.id)) { refreshClientsVless() }
        } else {
            request("POST", "/ssh/delete", JSONObject().put("user", item.id)) { refreshClientsSsh() }
        }
    }

    private fun editName(item: ClientItem, mode: ServiceMode) {
        val input = EditText(this).apply { setText(item.name) }
        AlertDialog.Builder(this)
            .setTitle("Editar nombre")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = input.text.toString().trim()
                if (mode == ServiceMode.VLESS) {
                    request("POST", "/client/update", JSONObject().put("id", item.id).put("name", newName)) { refreshClientsVless() }
                } else {
                    request("POST", "/ssh/update", JSONObject().put("user", item.id).put("name", newName)) { refreshClientsSsh() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptAddDays(item: ClientItem, mode: ServiceMode) {
        val input = EditText(this).apply { setText("30") }
        AlertDialog.Builder(this)
            .setTitle("Aumentar días")
            .setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                val days = input.text.toString().toIntOrNull() ?: 0
                if (days > 0) {
                    if (mode == ServiceMode.VLESS) {
                        request("POST", "/client/update", JSONObject().put("id", item.id).put("add_days", days)) { refreshClientsVless() }
                    } else {
                        request("POST", "/ssh/update", JSONObject().put("user", item.id).put("days", days).put("add_days", days)) { refreshClientsSsh() }
                    }
                }
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
        listOf(
            R.id.btnCreate,
            R.id.btnAddDays,
            R.id.btnDelete,
            R.id.btnList,
            R.id.btnSortDays,
            R.id.btnFilterAll,
            R.id.btnFilterExp3,
            R.id.btnFilterExp7,
            R.id.btnCreateSsh,
            R.id.btnAddDaysSsh,
            R.id.btnDeleteSsh,
            R.id.btnListSsh,
            R.id.btnSortDaysSsh,
            R.id.btnFilterAllSsh,
            R.id.btnFilterExp3Ssh,
            R.id.btnFilterExp7Ssh
        ).forEach { findViewById<MaterialButton>(it).isEnabled = false }
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
            holder.info.text = "${item.id} · días ${item.daysLeft} · ${if (item.active) "activo" else "expirado"}"
            holder.menu.setOnClickListener { onMenu(item) }
        }

        fun update(newItems: MutableList<ClientItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
    }
}
