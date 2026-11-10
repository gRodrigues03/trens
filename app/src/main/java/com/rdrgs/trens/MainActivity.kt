package com.rdrgs.trens

import android.R
import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    // ---------- UI helpers ----------
    private fun px(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun now(fmt: String) = SimpleDateFormat(fmt, Locale.US).format(Date())

    private fun label(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        setHint(hint); setText(value); setSingleLine()
    }

    private fun stationField(hint: String, value: String) = AutoCompleteTextView(this).apply {
        setHint(hint); setSingleLine(); threshold = 1; setSelectAllOnFocus(true)
        setText(value, false)
        onItemClickListener = AdapterView.OnItemClickListener { _, _, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(
                windowToken,
                0
            )
            clearFocus()
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
        origin = stationField("Origem", prefs.getString("o", "Queimados")!!)
        dest = stationField("Destino", prefs.getString("d", "Edson Passos")!!)
        date = field("Data (AAAA-MM-DD)", now("yyyy-MM-dd"))
        time = field("Hora (HH:MM)", now("HH:mm"))
        nextBox = vbox().apply {
            setPadding(px(12), px(12), px(12), px(12))
            setBackgroundColor(0x1A808080)
        }
        planBox = vbox()

        val btnInvert = Button(this).apply {
            text = "⇅ Inverter"
            setOnClickListener { invert(); refreshAll() }
        }
        val btnRefresh = Button(this).apply {
            text = "Atualizar"
            setOnClickListener { refreshAll() }
        }
        val btnPlan = Button(this).apply {
            text = "Buscar plano"
            setOnClickListener { refreshPlan() }
        }
        val buttons = LinearLayout(this).apply {
            addView(btnInvert, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(btnRefresh, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val dateTime = LinearLayout(this).apply {
            addView(date, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(time, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val content = vbox().apply {
            addView(label("Próximo trem", 18f, true))
            addView(nextBox)
            addView(label("Planejar viagem", 18f, true).apply { setPadding(0, px(20), 0, 0) })
            addView(dateTime)
            addView(btnPlan)
            addView(planBox)
        }
        val root = vbox().apply {
            addView(origin)
            addView(dest)
            addView(buttons)
            addView(
                ScrollView(this@MainActivity).apply { addView(content) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            )

            // Modern edge-to-edge insets handling
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
                val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(
                    px(16) + bars.left,
                    px(16) + bars.top,
                    px(16) + bars.right,
                    px(16) + bars.bottom
                )
                windowInsets
            }
        }
        setContentView(root)
        loadStations()
    }

    override fun onResume() {
        super.onResume()
        // reopening the app refreshes the live departures (first launch is handled by loadStations)
        if (resumed && stations.isNotEmpty()) refreshNext()
        resumed = true
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
        box.addView(label("Erro: ${e.message}", 14f))
    }

    // ---------- stations (fetched once, cached forever) ----------
    private fun loadStations() {
        val cached = prefs.getString("stations", null)
        if (cached != null) {
            runCatching { applyStations(cached) }.onSuccess { return }
            prefs.edit { remove("stations") } // corrupted cache: fetch again
        }
        nextBox.addView(label("Carregando estações…", 14f))
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
            v.setAdapter(ArrayAdapter(this, R.layout.simple_dropdown_item_1line, names))
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
            nextBox.addView(label("Escolha estações válidas da lista.", 15f))
            return
        }
        prefs.edit { putString("o", o.name).putString("d", d.name) }
        nextBox.addView(label("Buscando…", 14f))
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
        planBox.addView(label("Buscando…", 14f))
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
            nextBox.addView(label(j.str("message") ?: "Sem dados em tempo real no momento.", 15f))
            return
        }
        val deps = j.optJSONArray("departures")
        if (j.optBoolean("noService") || deps == null || deps.length() == 0) {
            nextBox.addView(label("Sem trens previstos no momento.", 15f))
            return
        }
        nextBox.addView(label("${j.str("lineName")} → ${j.str("directionLabel")}", 14f).apply {
            color(j.str("lineColor"))?.let { setTextColor(it) }
        })
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
                nextBox.addView(label(eta, 40f, true))
                nextBox.addView(label(parts, 16f))
            } else {
                nextBox.addView(label("$eta · $parts", 14f).apply { setPadding(0, px(8), 0, 0) })
            }
        }
        val t = j.opt("transferStation")
        if (t != null && t != JSONObject.NULL) {
            val name = if (t is JSONObject) t.optString("name", t.toString()) else t.toString()
            val next = j.str("nextLineName")?.let { " → linha $it" }.orEmpty()
            nextBox.addView(label("Baldeação em $name$next", 14f, true).apply {
                setPadding(
                    0,
                    px(8),
                    0,
                    0
                )
            })
        }
    }

    private fun renderPlan(j: JSONObject) {
        planBox.removeAllViews()
        if (j.has("error")) {
            planBox.addView(label(j.str("message") ?: j.optString("error"), 14f))
            return
        }
        val opts = j.optJSONArray("options")
        if (opts == null || opts.length() == 0) {
            planBox.addView(label("Nenhuma viagem encontrada.", 14f))
            return
        }
        for (i in 0 until opts.length()) {
            val x = opts.getJSONObject(i)
            val card = vbox().apply {
                setPadding(px(12), px(12), px(12), px(12))
                setBackgroundColor(0x1A808080)
            }
            card.addView(
                label(
                    "${x.getString("departureTime")} → ${x.getString("arrivalTime")}   ${
                        x.getInt(
                            "totalDurationMin"
                        )
                    } min", 20f, true
                )
            )
            val legs = x.getJSONArray("legs")
            for (k in 0 until legs.length()) {
                val l = legs.getJSONObject(k)
                val line = l.getJSONObject("line")
                val txt = "${line.getString("shortName")}: " +
                        "${
                            l.getJSONObject("fromStation").getString("name")
                        } ${l.getString("departureTime")} → " +
                        "${
                            l.getJSONObject("toStation").getString("name")
                        } ${l.getString("arrivalTime")} · " +
                        "${l.getInt("stopsCount")} paradas"
                card.addView(label(txt, 14f).apply {
                    color(line.getString("color"))?.let { setTextColor(it) }
                })
            }
            if (x.optBoolean("isLastTripOfDay")) card.addView(
                label(
                    "Último trem do dia",
                    14f,
                    true
                )
            )
            x.optJSONArray("warnings")?.let { w ->
                for (n in 0 until w.length()) card.addView(label("⚠ " + w.optString(n), 14f))
            }
            planBox.addView(
                card,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .apply { bottomMargin = px(10) })
        }
    }
}
