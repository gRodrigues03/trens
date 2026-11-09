package com.rdrgs.trens

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

private const val API = "https://api.trensrj.com.br/trips/plan"

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var origin: EditText
    private lateinit var dest: EditText
    private lateinit var date: EditText
    private lateinit var time: EditText
    private lateinit var out: LinearLayout

    private fun px(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun field(hint: String, value: String) = EditText(this).apply {
        setHint(hint); setText(value); setSingleLine()
    }

    private fun label(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("trens", MODE_PRIVATE)
        val now = Date()
        origin = field("Origem", prefs.getString("o", "Queimados")!!)
        dest = field("Destino", prefs.getString("d", "Edson Passos")!!)
        date = field("Data (AAAA-MM-DD)", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
        time = field("Hora (HH:MM)", SimpleDateFormat("HH:mm", Locale.US).format(now))
        out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val swap = Button(this).apply {
            text = "⇅ Inverter"
            setOnClickListener {
                val t = origin.text.toString()
                origin.text = dest.text
                dest.setText(t)
            }
        }
        val go = Button(this).apply {
            text = "Buscar"
            setOnClickListener { search() }
        }
        val row = LinearLayout(this).apply {
            addView(date, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(time, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(origin); addView(dest); addView(swap); addView(row); addView(go)
            addView(
                ScrollView(this@MainActivity).apply { addView(out) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            )
            // keeps content clear of status/navigation bars (edge-to-edge on new Android)
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(
                    px(16) + i.systemWindowInsetLeft, px(16) + i.systemWindowInsetTop,
                    px(16) + i.systemWindowInsetRight, px(16) + i.systemWindowInsetBottom
                )
                i
            }
        }
        setContentView(root)
        search()
    }

    private fun slug(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().trim()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun search() {
        getSharedPreferences("trens", MODE_PRIVATE).edit {
            putString("o", origin.text.toString())
                .putString("d", dest.text.toString())
        }
        out.removeAllViews()
        out.addView(label("Buscando…"))
        val body = JSONObject()
            .put("originSlug", slug(origin.text.toString()))
            .put("destinationSlug", slug(dest.text.toString()))
            .put("date", date.text.toString().trim())
            .put("time", time.text.toString().trim())
            .toString()
        thread {
            val r = runCatching { post(body) }
            runOnUiThread { r.fold({ render(it) }, { showError(it) }) }
        }
    }

    private fun post(body: String): JSONObject {
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("accept", "*/*")
            c.setRequestProperty("origin", "https://trensrj.com.br")
            c.setRequestProperty("referer", "https://trensrj.com.br/")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code ${txt.take(200)}")
            return JSONObject(txt)
        } finally {
            c.disconnect()
        }
    }

    private fun showError(e: Throwable) {
        out.removeAllViews()
        out.addView(label("Erro: ${e.message}"))
    }

    private fun render(j: JSONObject) {
        out.removeAllViews()
        val opts = j.optJSONArray("options")
        if (opts == null || opts.length() == 0) {
            out.addView(label("Nenhuma viagem encontrada."))
            return
        }
        for (i in 0 until opts.length()) {
            val x = opts.getJSONObject(i)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), px(12), px(12), px(12))
                setBackgroundColor(0x1A808080)
            }
            card.addView(
                label(
                    "${x.getString("departureTime")} → ${x.getString("arrivalTime")}   " +
                            "${x.getInt("totalDurationMin")} min", 20f, true
                )
            )
            val legs = x.getJSONArray("legs")
            for (k in 0 until legs.length()) {
                val l = legs.getJSONObject(k)
                val line = l.getJSONObject("line")
                val txt = "${line.getString("shortName")}: " +
                        "${l.getJSONObject("fromStation").getString("name")} ${l.getString("departureTime")} → " +
                        "${l.getJSONObject("toStation").getString("name")} ${l.getString("arrivalTime")} · " +
                        "${l.getInt("stopsCount")} paradas"
                card.addView(label(txt, 14f).apply {
                    runCatching { setTextColor(("#" + line.getString("color")).toColorInt()) }
                })
            }
            if (x.optBoolean("isLastTripOfDay")) card.addView(label("Último trem do dia", 14f, true))
            x.optJSONArray("warnings")?.let { w ->
                for (n in 0 until w.length()) card.addView(label("⚠ " + w.optString(n), 14f))
            }
            out.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(10) })
        }
    }
}