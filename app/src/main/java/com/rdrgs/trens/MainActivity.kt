package com.rdrgs.trens

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val BASE = "https://api.trensrj.com.br"

class MainActivity : Activity() {
    private class Station(val id: String, val name: String, val slug: String)
    private data class SavedTrajeto(var name: String, val origin: String, val dest: String)

    private var stations = emptyList<Station>()
    private var resumed = false
    private var nextSeq = 0
    private var planSeq = 0
    private val prefs by lazy { getSharedPreferences("trens", MODE_PRIVATE) }

    private lateinit var origin: AutoCompleteTextView
    private lateinit var dest: AutoCompleteTextView
    private lateinit var date: EditText
    private lateinit var time: EditText
    private lateinit var nextBox: LinearLayout
    private lateinit var planBox: LinearLayout
    private lateinit var savedTrajetosScroll: HorizontalScrollView
    private lateinit var savedChipsBox: LinearLayout
    private lateinit var btnManageTrajetos: Button

    private val savedTrajetos = mutableListOf<SavedTrajeto>()

    // ---------- UI helpers ----------
    private fun now(fmt: String) = SimpleDateFormat(fmt, Locale.US).format(Date())

    private fun label(s: String, styleResId: Int = R.style.TextBody): TextView =
        TextView(ContextThemeWrapper(this, styleResId)).apply { text = s }

    private fun setupStationField(field: AutoCompleteTextView) {
        field.onItemClickListener = AdapterView.OnItemClickListener { _, _, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(
                field.windowToken,
                0
            )
            field.clearFocus()
            refreshAll()
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    private fun color(hex: String?): Int? =
        hex?.let { runCatching { "#$it".toColorInt() }.getOrNull() }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        origin = findViewById(R.id.origin)
        dest = findViewById(R.id.dest)
        date = findViewById(R.id.date)
        time = findViewById(R.id.time)
        nextBox = findViewById(R.id.nextBox)
        planBox = findViewById(R.id.planBox)
        savedTrajetosScroll = findViewById(R.id.savedTrajetosScroll)
        savedChipsBox = findViewById(R.id.savedChipsBox)
        btnManageTrajetos = findViewById(R.id.btnManageTrajetos)

        val btnInvert = findViewById<Button>(R.id.btnInvert)
        val btnRefresh = findViewById<Button>(R.id.btnRefresh)
        val btnPlan = findViewById<Button>(R.id.btnPlan)
        val btnSaveTrajeto = findViewById<Button>(R.id.btnSaveTrajeto)
        val root = findViewById<View>(R.id.root)

        origin.setText(prefs.getString("o", "Queimados")!!, false)
        dest.setText(prefs.getString("d", "Edson Passos")!!, false)
        date.setText(now("yyyy-MM-dd"))
        time.setText(now("HH:mm"))

        setupStationField(origin)
        setupStationField(dest)

        btnInvert.setOnClickListener { invert(); refreshAll() }
        btnRefresh.setOnClickListener { refreshNext() }
        btnPlan.setOnClickListener { refreshPlan() }
        btnSaveTrajeto.setOnClickListener { saveCurrentTrajeto() }
        btnManageTrajetos.setOnClickListener { showManageDialog() }

        // Modern edge-to-edge insets handling
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val p16 = 16
            v.setPadding(
                p16 + bars.left,
                p16 + bars.top,
                p16 + bars.right,
                0
            )
            windowInsets
        }

        loadSavedTrajetos()
        updateSavedChipsUI()
        loadStations()
    }

    override fun onResume() {
        super.onResume()
        // reopening the app refreshes the live departures (first launch is handled by loadStations)
        if (resumed && stations.isNotEmpty()) refreshNext()
        resumed = true
    }

    // ---------- saved trajetos ----------
    private fun loadSavedTrajetos() {
        savedTrajetos.clear()
        val jsonStr = prefs.getString("saved_trajetos_list", null) ?: return
        runCatching {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                savedTrajetos.add(
                    SavedTrajeto(
                        obj.getString("name"),
                        obj.getString("origin"),
                        obj.getString("dest")
                    )
                )
            }
        }
    }

    private fun saveSavedTrajetos() {
        val arr = JSONArray()
        for ((name, origin, dest) in savedTrajetos) {
            val obj = JSONObject()
                .put("name", name)
                .put("origin", origin)
                .put("dest", dest)
            arr.put(obj)
        }
        prefs.edit { putString("saved_trajetos_list", arr.toString()) }
    }

    private fun updateSavedChipsUI() {
        savedChipsBox.removeAllViews()
        if (savedTrajetos.isEmpty()) {
            savedTrajetosScroll.visibility = View.GONE
            btnManageTrajetos.visibility = View.GONE
            return
        }
        savedTrajetosScroll.visibility = View.VISIBLE
        btnManageTrajetos.visibility = View.VISIBLE

        for (item in savedTrajetos) {
            val chip = TextView(ContextThemeWrapper(this, R.style.WidgetChip)).apply {
                text = item.name
                setOnClickListener {
                    origin.setText(item.origin, false)
                    dest.setText(item.dest, false)
                    refreshAll()
                }
                setOnLongClickListener {
                    showManageDialog()
                    true
                }
            }
            savedChipsBox.addView(chip)
        }
    }

    private fun saveCurrentTrajeto() {
        val o = origin.text.toString().trim()
        val d = dest.text.toString().trim()
        if (o.isEmpty() || d.isEmpty()) return

        val defaultName = "$o → $d"
        val existing = savedTrajetos.firstOrNull {
            it.origin.equals(o, ignoreCase = true) && it.dest.equals(d, ignoreCase = true)
        }
        if (existing != null) {
            Toast.makeText(this, getString(R.string.msg_trajeto_saved), Toast.LENGTH_SHORT).show()
            return
        }

        savedTrajetos.add(SavedTrajeto(defaultName, o, d))
        saveSavedTrajetos()
        updateSavedChipsUI()
        Toast.makeText(this, getString(R.string.msg_trajeto_saved), Toast.LENGTH_SHORT).show()
    }

    private fun showManageDialog() {
        if (savedTrajetos.isEmpty()) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_saved_trajetos_title)
            .setView(container)
            .setPositiveButton(R.string.btn_close, null)
            .create()

        fun rebuildDialogItems() {
            container.removeAllViews()
            if (savedTrajetos.isEmpty()) {
                dialog.dismiss()
                updateSavedChipsUI()
                return
            }
            for (i in savedTrajetos.indices) {
                val item = savedTrajetos[i]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }

                val titleTv = TextView(this).apply {
                    text = item.name
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(titleTv)

                if (i > 0) {
                    val btnUp = Button(ContextThemeWrapper(this, R.style.WidgetButtonBorderless)).apply {
                        text = "▲"
                        setOnClickListener {
                            val temp = savedTrajetos[i]
                            savedTrajetos[i] = savedTrajetos[i - 1]
                            savedTrajetos[i - 1] = temp
                            saveSavedTrajetos()
                            updateSavedChipsUI()
                            rebuildDialogItems()
                        }
                    }
                    row.addView(btnUp)
                }

                if (i < savedTrajetos.lastIndex) {
                    val btnDown = Button(ContextThemeWrapper(this, R.style.WidgetButtonBorderless)).apply {
                        text = "▼"
                        setOnClickListener {
                            val temp = savedTrajetos[i]
                            savedTrajetos[i] = savedTrajetos[i + 1]
                            savedTrajetos[i + 1] = temp
                            saveSavedTrajetos()
                            updateSavedChipsUI()
                            rebuildDialogItems()
                        }
                    }
                    row.addView(btnDown)
                }

                val btnRename = Button(ContextThemeWrapper(this, R.style.WidgetButtonBorderless)).apply {
                    text = "✏"
                    setOnClickListener {
                        val input = EditText(this@MainActivity).apply { setText(item.name) }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.dialog_rename_trajeto_title)
                            .setView(input)
                            .setPositiveButton(R.string.btn_rename) { _, _ ->
                                val newName = input.text.toString().trim()
                                if (newName.isNotEmpty()) {
                                    item.name = newName
                                    saveSavedTrajetos()
                                    updateSavedChipsUI()
                                    rebuildDialogItems()
                                }
                            }
                            .setNegativeButton(R.string.btn_close, null)
                            .show()
                    }
                }
                row.addView(btnRename)

                val btnDelete = Button(ContextThemeWrapper(this, R.style.WidgetButtonBorderless)).apply {
                    text = "✕"
                    setOnClickListener {
                        savedTrajetos.removeAt(i)
                        saveSavedTrajetos()
                        updateSavedChipsUI()
                        rebuildDialogItems()
                    }
                }
                row.addView(btnDelete)

                container.addView(row)
            }
        }

        rebuildDialogItems()
        dialog.show()
    }

    // ---------- networking ----------
    private fun <T> bg(work: () -> T, done: (Result<T>) -> Unit) {
        thread {
            val r = runCatching(work)
            runOnUiThread { done(r) }
        }
    }

    private fun http(url: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("accept", "*/*")
            c.setRequestProperty("cache-control", "no-cache")
            c.setRequestProperty("origin", "https://trensrj.com.br")
            c.setRequestProperty("referer", "https://trensrj.com.br/")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            c.disconnect()
        }
    }

    private fun api(path: String, body: String? = null): JSONObject {
        val (code, txt) = http(BASE + path, body)
        return try {
            JSONObject(txt)
        } catch (_: Exception) {
            error("HTTP $code ${txt.take(120)}")
        }
    }

    private fun showError(box: LinearLayout, e: Throwable) {
        box.removeAllViews()
        box.addView(label(getString(R.string.error_prefix, e.message.orEmpty()), R.style.TextUnavailable))
    }

    // ---------- stations (fetched once, cached forever) ----------
    private fun loadStations() {
        val cached = prefs.getString("stations", null)
        if (cached != null) {
            runCatching { applyStations(cached) }.onSuccess { return }
            prefs.edit { remove("stations") } // corrupted cache: fetch again
        }
        nextBox.addView(label(getString(R.string.loading_stations), R.style.TextBody))
        bg({
            val (code, txt) = http("$BASE/stations")
            if (code !in 200..299) error("HTTP $code")
            JSONArray(txt) // validates before caching
            txt
        }) { r ->
            r.onSuccess {
                prefs.edit { putString("stations", it) }
                applyStations(it)
            }.onFailure { showError(nextBox, it) }
        }
    }

    private fun applyStations(json: String) {
        val arr = JSONArray(json)
        stations = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Station(o.getString("id"), o.getString("name"), o.getString("slug"))
        }
        val names = stations.map { it.name }
        for (v in listOf(origin, dest)) {
            v.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names))
        }
        refreshAll()
    }

    private fun slug(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().trim()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun station(text: String): Station? {
        val s = slug(text)
        return stations.firstOrNull { it.slug == s || slug(it.name) == s }
    }

    // ---------- actions ----------
    private fun invert() {
        val t = origin.text.toString()
        origin.setText(dest.text.toString(), false)
        dest.setText(t, false)
    }

    private fun refreshAll() {
        date.setText(now("yyyy-MM-dd"))
        time.setText(now("HH:mm"))
        refreshNext()
        refreshPlan()
    }

    private fun refreshNext() {
        val my = ++nextSeq
        val o = station(origin.text.toString())
        val d = station(dest.text.toString())
        nextBox.removeAllViews()
        if (o == null || d == null) {
            nextBox.addView(label("Escolha estações válidas da lista.", R.style.TextCaption))
            return
        }
        prefs.edit { putString("o", o.name).putString("d", d.name) }
        nextBox.addView(label(getString(R.string.searching), R.style.TextBody))
        bg({ api("/trips/next?originId=${o.id}&destinationId=${d.id}") }) { r ->
            if (my != nextSeq) return@bg
            r.onSuccess { renderNext(it) }.onFailure { showError(nextBox, it) }
        }
    }

    private fun refreshPlan() {
        val my = ++planSeq
        val o = station(origin.text.toString())
        val d = station(dest.text.toString())
        planBox.removeAllViews()
        if (o == null || d == null) return
        planBox.addView(label(getString(R.string.searching), R.style.TextBody))
        val body = JSONObject()
            .put("originSlug", o.slug)
            .put("destinationSlug", d.slug)
            .put("date", date.text.toString().trim())
            .put("time", time.text.toString().trim())
            .toString()
        bg({ api("/trips/plan", body) }) { r ->
            if (my != planSeq) return@bg
            r.onSuccess { renderPlan(it) }.onFailure { showError(planBox, it) }
        }
    }

    // ---------- rendering ----------
    private fun renderNext(j: JSONObject) {
        nextBox.removeAllViews()
        if (j.has("error")) { // e.g. NO_SERVICE
            nextBox.addView(label(j.str("message") ?: "Sem dados em tempo real no momento.", R.style.TextUnavailable))
            return
        }
        val deps = j.optJSONArray("departures")
        if (j.optBoolean("noService") || deps == null || deps.length() == 0) {
            nextBox.addView(label(getString(R.string.no_trains_scheduled), R.style.TextUnavailable))
            return
        }
        val lineHeader = label("${j.str("lineName")} → ${j.str("directionLabel")}", R.style.TextSubheadBold).apply {
            color(j.str("lineColor"))?.let { setTextColor(it) }
        }
        nextBox.addView(lineHeader)

        for (i in 0 until deps.length()) {
            val d = deps.getJSONObject(i)
            val m = d.optInt("minutesUntil", -1)
            val eta = when {
                m < 0 -> ""
                m == 0 -> "agora"
                else -> "em $m min"
            }
            val conf = d.str("confidence")
            val parts = listOfNotNull(
                d.str("departureTime"), d.str("trainCode"), d.str("trainType"),
                d.str("platformLabel")?.let { "plataforma $it" },
                if (conf == "live") "ao vivo" else conf
            ).joinToString(" · ")
            if (i == 0) {
                nextBox.addView(label(eta, R.style.TextEta))
                nextBox.addView(label(parts, R.style.TextSubhead))
            } else {
                nextBox.addView(label("$eta · $parts", R.style.TextBodyItem))
            }
        }
        val t = j.opt("transferStation")
        if (t != null && t != JSONObject.NULL) {
            val name = if (t is JSONObject) t.optString("name", t.toString()) else t.toString()
            val next = j.str("nextLineName")?.let { " → linha $it" }.orEmpty()
            val transferBox = TextView(ContextThemeWrapper(this, R.style.TransferBox)).apply {
                text = getString(R.string.transfer_at, name, next)
            }
            nextBox.addView(transferBox)
        }
    }

    private fun renderPlan(j: JSONObject) {
        planBox.removeAllViews()
        if (j.has("error")) {
            planBox.addView(label(j.str("message") ?: j.optString("error"), R.style.TextUnavailable))
            return
        }
        val opts = j.optJSONArray("options")
        if (opts == null || opts.length() == 0) {
            planBox.addView(label(getString(R.string.no_trips_found), R.style.TextBody))
            return
        }
        for (i in 0 until opts.length()) {
            val x = opts.getJSONObject(i)
            val card = layoutInflater.inflate(R.layout.item_plan_card, planBox, false)
            val tvCardTitle = card.findViewById<TextView>(R.id.tvCardTitle)
            val legsBox = card.findViewById<LinearLayout>(R.id.legsBox)
            val tvLastTrip = card.findViewById<TextView>(R.id.tvLastTrip)
            val warningsBox = card.findViewById<LinearLayout>(R.id.warningsBox)

            tvCardTitle.text = "${x.getString("departureTime")} → ${x.getString("arrivalTime")}   ${x.getInt("totalDurationMin")} min"

            val legs = x.getJSONArray("legs")
            for (k in 0 until legs.length()) {
                val l = legs.getJSONObject(k)
                val line = l.getJSONObject("line")
                val txt = "${line.getString("shortName")}: " +
                        "${l.getJSONObject("fromStation").getString("name")} ${l.getString("departureTime")} → " +
                        "${l.getJSONObject("toStation").getString("name")} ${l.getString("arrivalTime")} · " +
                        "${l.getInt("stopsCount")} paradas"
                legsBox.addView(label(txt, R.style.TextLegItem).apply {
                    color(line.getString("color"))?.let { setTextColor(it) }
                })
            }
            if (x.optBoolean("isLastTripOfDay")) {
                tvLastTrip.visibility = View.VISIBLE
            }
            x.optJSONArray("warnings")?.let { w ->
                for (n in 0 until w.length()) {
                    warningsBox.addView(label("⚠ " + w.optString(n), R.style.TextWarning))
                }
            }
            planBox.addView(card)
        }
    }
}
