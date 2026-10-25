package id.bram.cincinlangkah

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

class CoachActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, CoachActivity::class.java))
        /** percakapan "Tanya" selama aplikasi hidup */
        private val chat = ArrayList<Pair<String, String>>()
        private var chatDate: LocalDate? = null
    }

    private val night by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val pal by lazy { Pal(night) }
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).roundToInt()
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private val cprefs by lazy { getSharedPreferences("coach", MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var day: Coach.Day? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pal.bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(14), px(8), px(14), px(28)); setBackgroundColor(pal.bg)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "‹"; textSize = 28f; setTextColor(pal.ring); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(px(36), px(44)); setOnClickListener { finish() }
        })
        head.addView(TextView(this).apply { text = "🧭 Pelatih"; textSize = 19f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD })
        root.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(8) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        content.addView(note("Menghitung… (pertama kali bisa agak lama, membaca 4 minggu data)"))
        scroll = ScrollView(this).apply { addView(root); setBackgroundColor(pal.bg) }
        setContentView(scroll)
        if (chatDate != LocalDate.now()) { chat.clear(); chatDate = LocalDate.now() }
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        lifecycleScope.launch {
            val d = try { Coach.day(this@CoachActivity) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { content.removeAllViews(); content.addView(note("Gagal menghitung: ${e.message}")); return@launch }
            day = d
            render(d)
        }
    }

    private fun render(d: Coach.Day) {
        content.removeAllViews()
        // ===== tiga skor =====
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val zc = when (d.zone) { 2 -> Color.parseColor("#2ECC71"); 1 -> Color.parseColor("#F2B84B"); 0 -> Color.parseColor("#E0475B"); else -> pal.muted }
        r.addView(gauge(d.recovery?.div(100f), d.recovery?.let { "$it%" } ?: "–", "Pemulihan", zc))
        r.addView(gauge(d.sleepScore?.div(100f), d.sleepScore?.let { "$it%" } ?: "–", "Tidur", pal.deep))
        r.addView(gauge((d.strain / 21).toFloat(), "%.1f".format(Locale("id", "ID"), d.strain), "Beban /21", Color.parseColor("#2F80ED")))
        content.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(10) })

        lines("☀ Pagi ini", d.morning, zc)
        lines("⏱ Sekarang", d.now, null)
        lines("📅 7 hari terakhir", d.week, null)
        renderAi(d)
        renderAsk(d)
        renderSettings()
        val c = card(null)
        c.addView(title("Cara skor dihitung"))
        c.addView(note("Pemulihan = HRV semalam (45%), detak jantung istirahat (25%) dan skor tidur (30%), dibanding rata-rata 28 malam terakhirmu. " +
            "🟢 ≥67%, 🟡 34–66%, 🔴 <34%. Tidur = durasi vs kebutuhan (60%), porsi nyenyak & REM (20%), gangguan (10%), konsistensi jam tidur (10%). " +
            "Kebutuhan tidur 7j30m + tambahan kalau kemarin bebannya berat atau ada utang tidur. Beban 0–21 dari kalori aktif & menit olahraga. " +
            "Cincin mengukur HRV beberapa kali per malam saja, jadi skornya perkiraan — lihat trennya, bukan satu hari.").apply { setPadding(0, 0, 0, 0) })
    }

    // ---------------- AI ----------------

    private fun aiKey(d: LocalDate) = "ai_${d}_${Ai.provider(this)}"

    private fun renderAi(d: Coach.Day) {
        val c = card(Color.parseColor("#9B59B6"))
        c.addView(title("✨ Komentar ${Ai.name(this)}"))
        if (!Ai.enabled(this)) {
            c.addView(note("Belum aktif. Atur di \"Pengaturan AI\" di bawah (Gemini ada versi gratis). Komentar di atas tetap jalan tanpa AI.").apply { setPadding(0, 0, 0, 0) })
            return
        }
        val body = TextView(this).apply { textSize = 14f; setTextColor(pal.fg); setLineSpacing(0f, 1.15f); setTextIsSelectable(true) }
        c.addView(body)
        val info = note("")
        c.addView(info)
        val btn = smallBtn("↻ Perbarui komentar") {}
        c.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(8), 0, 0); addView(btn) })
        fun fetch() {
            body.text = "Meminta komentar…"; btn.isEnabled = false
            lifecycleScope.launch {
                try {
                    val t = Ai.ask(this@CoachActivity, Coach.aiContext(this@CoachActivity, d) +
                        "\nTulis komentar & saran untuk hari ini, maksimal sekitar 150 kata. Mulai dari hal terpenting.")
                    cprefs.edit().putString(aiKey(d.date), t).putLong(aiKey(d.date) + "_t", System.currentTimeMillis()).apply()
                    body.text = t; info.text = "dibuat barusan · ${Ai.model(this@CoachActivity)}"
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { body.text = "Gagal: ${e.message}\n(Komentar bawaan di atas tetap berlaku.)" }
                btn.isEnabled = true
            }
        }
        btn.setOnClickListener { fetch() }
        val cached = cprefs.getString(aiKey(d.date), null)
        if (cached != null) {
            body.text = cached
            val t = cprefs.getLong(aiKey(d.date) + "_t", 0)
            info.text = "dibuat ${java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(t))} · ketuk Perbarui untuk data terbaru"
        } else fetch()
    }

    private fun renderAsk(d: Coach.Day) {
        val c = card(null)
        c.addView(title("💬 Tanya pelatih"))
        if (!Ai.enabled(this)) { c.addView(note("Butuh AI aktif (Pengaturan AI di bawah).").apply { setPadding(0, 0, 0, 0) }); return }
        val log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(log)
        fun bubble(t: String, me: Boolean) = log.addView(TextView(this).apply {
            text = t; textSize = 14f; setTextColor(pal.fg); setTextIsSelectable(true)
            setPadding(px(10), px(8), px(10), px(8))
            background = GradientDrawable().apply { cornerRadius = px(12).toFloat(); setColor(if (me) tinted(pal.ring) else tinted(Color.parseColor("#9B59B6"))) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(6); if (me) marginStart = px(40) else marginEnd = px(20) }
        })
        for ((q, a) in chat) { bubble(q, true); bubble(a, false) }
        if (chat.isEmpty()) c.addView(note("Contoh: \"Kenapa tidurku jelek minggu ini?\" · \"Malam ini makan apa yang pas?\" · \"Boleh main pingpong 2 jam hari ini?\"").apply { setPadding(0, 0, 0, px(6)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val e = EditText(this).apply {
            hint = "Tulis pertanyaan…"; textSize = 15f; setTextColor(pal.fg); setHintTextColor(pal.muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val send = Button(this).apply {
            text = "➤"; textSize = 18f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.ring) }
            layoutParams = LinearLayout.LayoutParams(px(44), px(44)).apply { marginStart = px(6) }
        }
        row.addView(e); row.addView(send)
        c.addView(row)
        send.setOnClickListener {
            val q = e.text.toString().trim(); if (q.isEmpty()) return@setOnClickListener
            e.setText(""); bubble(q, true); bubble("…", false)
            val wait = log.getChildAt(log.childCount - 1) as TextView
            send.isEnabled = false
            lifecycleScope.launch {
                try {
                    // pesan pertama membawa data; berikutnya cukup pertanyaannya
                    val msg = if (chat.isEmpty()) Coach.aiContext(this@CoachActivity, d) + "\nPertanyaan: $q" else q
                    val hist = chat.mapIndexed { i, p -> (if (i == 0) Coach.aiContext(this@CoachActivity, d) + "\nPertanyaan: " + p.first else p.first) to p.second }
                    val a = Ai.ask(this@CoachActivity, msg, hist)
                    chat += q to a; wait.text = a
                } catch (ex: kotlinx.coroutines.CancellationException) { throw ex
                } catch (ex: Exception) { wait.text = "Gagal: ${ex.message}" }
                send.isEnabled = true
                scroll.post { scroll.smoothScrollTo(0, row.bottom + c.top) }
            }
        }
    }

    private fun renderSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val tg = TextView(this).apply {
            text = "▸  Pengaturan AI & notifikasi"; textSize = 15f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD
            setPadding(px(4), px(12), px(4), px(10))
            setOnClickListener {
                box.visibility = if (box.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = (if (box.visibility == View.VISIBLE) "▾  " else "▸  ") + "Pengaturan AI & notifikasi"
            }
        }
        content.addView(tg); content.addView(box)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = px(20).toFloat(); setColor(pal.card) }
        }
        box.addView(c)
        var prov = Ai.provider(this)
        c.addView(note("Penyedia AI").apply { setPadding(0, 0, 0, 0) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        c.addView(chips)
        val keyE = EditText(this).apply {
            textSize = 14f; setTextColor(pal.fg); setHintTextColor(pal.muted); typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelE = EditText(this).apply { textSize = 14f; setTextColor(pal.fg); typeface = Typeface.MONOSPACE; inputType = InputType.TYPE_CLASS_TEXT }
        val keyL = note(""); val modelL = note("Model (biarkan default kalau ragu)")
        val help = TextView(this).apply { textSize = 13f; setTextColor(pal.ring); setPadding(0, px(6), 0, 0) }
        val priv = note("")
        val opts = listOf(Ai.OFF to "Mati", Ai.GEMINI to "Gemini", Ai.CLAUDE to "Claude")
        val btns = opts.map { (k, t) ->
            Button(this).apply {
                text = t; isAllCaps = false; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, px(38), 1f).apply { marginEnd = px(6) }
            }.also { chips.addView(it) } to k
        }
        fun refresh() {
            for ((b, k) in btns) {
                val sel = k == prov
                b.setTextColor(if (sel) Color.WHITE else pal.ring)
                b.background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); if (sel) setColor(pal.ring) else setStroke(px(1), pal.ring) }
            }
            val on = prov != Ai.OFF
            listOf(keyL, keyE, modelL, modelE, help, priv).forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
            if (!on) return
            keyL.text = "API key ${if (prov == Ai.GEMINI) "Gemini" else "Claude"}"
            keyE.setText(Ai.key(this, prov)); keyE.hint = if (prov == Ai.GEMINI) "AIza…" else "sk-ant-…"
            modelE.setText(Ai.model(this, prov))
            help.text = if (prov == Ai.GEMINI) "Buat API key gratis di Google AI Studio ›" else "Buat API key di Anthropic Console (perlu isi saldo) ›"
            help.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    if (prov == Ai.GEMINI) "https://aistudio.google.com/apikey" else "https://console.anthropic.com/settings/keys")))
            }
            priv.text = if (prov == Ai.GEMINI) "Free tier: Google boleh memakai data yang dikirim untuk mengembangkan produknya (termasuk ditinjau manusia). " +
                "Yang dikirim: ringkasan tidur, HRV, detak, langkah, berat, umur & tinggi — tanpa nama. Aktifkan billing di AI Studio kalau tidak mau."
                else "Berbayar per pemakaian (±beberapa ratus rupiah per komentar). Anthropic tidak memakai data API untuk melatih model secara default."
        }
        btns.forEach { (b, k) -> b.setOnClickListener { prov = k; refresh() } }
        c.addView(keyL); c.addView(keyE); c.addView(help); c.addView(modelL); c.addView(modelE); c.addView(priv)
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(8), 0, 0) }
        r.addView(smallBtn("Simpan & tes") {
            Ai.save(this, prov, if (prov == Ai.OFF) null else keyE.text.toString(), if (prov == Ai.OFF) null else modelE.text.toString())
            if (prov == Ai.OFF) { toast("AI dimatikan"); load(); return@smallBtn }
            lifecycleScope.launch {
                try {
                    val t = Ai.ask(this@CoachActivity, "Balas dengan satu kalimat pendek: siap membantu.", maxTokens = 60)
                    toast("✅ Tersambung: $t"); load()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { toast("Gagal: ${e.message}") }
            }
        })
        c.addView(r)
        c.addView(Switch(this).apply {
            text = "Notifikasi skor pagi (setelah data tidur masuk) + rangkuman tiap Senin"
            setTextColor(pal.fg); textSize = 13f; isChecked = prefs.getBoolean("coach_notif", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("coach_notif", v).apply() }
            setPadding(0, px(10), 0, px(4))
        })
        refresh()
    }

    // ---------------- helper ----------------

    private fun gauge(frac: Float?, center: String, label: String, color: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(px(4), px(8), px(4), px(8))
            background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(tinted(color)) }
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = px(8) }
        }
        box.addView(GaugeView(this, pal).apply { this.frac = frac; this.center = center; this.label = label; this.color = color })
        return box
    }

    private fun lines(t: String, l: List<String>, tint: Int?) {
        val c = card(tint)
        c.addView(title(t))
        l.forEachIndexed { i, s ->
            c.addView(TextView(this).apply {
                text = s; textSize = if (i == 0 && tint != null) 15f else 14f; setTextColor(pal.fg); setLineSpacing(0f, 1.12f)
                if (i == 0 && tint != null) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, px(3), 0, px(3))
            })
        }
    }

    private fun tinted(color: Int?) = if (color == null) pal.card
        else androidx.core.graphics.ColorUtils.blendARGB(pal.card, color, if (night) 0.16f else 0.10f)
    private fun card(tint: Int?): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = px(20).toFloat(); setColor(tinted(tint)) }
        }
        content.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(10) })
        return c
    }
    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(pal.fg); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, px(4))
    }
    private fun note(t: String) = TextView(this).apply { text = t; textSize = 11.5f; setTextColor(pal.muted); setPadding(0, px(6), 0, 0) }
    private fun smallBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(pal.ring)
        background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setStroke(px(1), pal.ring) }
        layoutParams = LinearLayout.LayoutParams(0, px(44), 1f)
        setOnClickListener { onClick() }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
