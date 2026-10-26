package id.bram.cincinlangkah

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Komentar AI opsional: Gemini (ada free tier) atau Claude (berbayar), dengan API key milik pengguna. */
object Ai {
    const val OFF = "off"; const val GEMINI = "gemini"; const val CLAUDE = "claude"
    const val DEF_GEMINI = "gemini-flash-latest"
    const val DEF_CLAUDE = "claude-haiku-5-5"
    /** cadangan kalau model utama sedang penuh (503/429) */
    private val FALLBACK_GEMINI = listOf("gemini-flash-lite-latest", "gemini-2.5-flash")
    @Volatile var lastModel = ""
    @Volatile private var readTimeoutMs = 60_000

    /** Penjelasan singkat untuk pesan error yang umum. */
    fun explain(msg: String): String = when {
        msg.contains("503") || msg.contains("high demand", true) || msg.contains("overloaded", true) ->
            "Server AI sedang penuh — API key-mu SUDAH BENAR, cuma servernya sibuk. Coba lagi beberapa menit lagi; " +
                "aplikasi juga otomatis mencoba ulang & pindah ke model cadangan."
        msg.contains("429") -> "Kuota gratis per menit/hari sedang habis. Tunggu sebentar (atau besok untuk kuota harian)."
        msg.contains("401") || msg.contains("403") || msg.contains("API key", true) || msg.contains("API_KEY", true) ->
            "API key ditolak — kemungkinan salah salin. Salin ulang dengan tombol Copy key di AI Studio."
        msg.contains("404") || msg.contains("not found", true) -> "Nama model tidak dikenal. Kosongkan kolom Model supaya kembali ke default."
        msg.contains("Unable to resolve host", true) || msg.contains("timeout", true) -> "Tidak ada koneksi internet / timeout."
        else -> "Pesan lengkap tercatat juga di Pengaturan & log (dashboard) — bisa dibagikan."
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun provider(ctx: Context) = p(ctx).getString("ai_provider", OFF) ?: OFF
    fun key(ctx: Context, prov: String = provider(ctx)) = p(ctx).getString("ai_key_$prov", "") ?: ""
    fun model(ctx: Context, prov: String = provider(ctx)) =
        p(ctx).getString("ai_model_$prov", null)?.takeIf { it.isNotBlank() } ?: if (prov == CLAUDE) DEF_CLAUDE else DEF_GEMINI
    fun enabled(ctx: Context) = provider(ctx) != OFF && key(ctx).isNotBlank()
    fun name(ctx: Context) = when (provider(ctx)) { GEMINI -> "Gemini"; CLAUDE -> "Claude"; else -> "AI" }

    fun save(ctx: Context, prov: String, key: String?, model: String?) {
        val e = p(ctx).edit().putString("ai_provider", prov)
        if (key != null) e.putString("ai_key_$prov", key.trim())
        if (model != null) e.putString("ai_model_$prov", model.trim())
        e.apply()
    }

    val SYSTEM = """
Kamu adalah pelatih kesehatan pribadi di aplikasi "Cincin Langkah", gayanya seperti pelatih di aplikasi Whoop: jujur, hangat, to the point.
Data berasal dari cincin pintar R11M (HRV/tensi/SpO2 diukur beberapa kali per malam, bukan kontinu), step counter HP, dan kadang jam/gelang.
Aturan:
- Bahasa Indonesia santai (pakai "kamu"), tanpa basa-basi pembuka.
- Bandingkan dengan pola normal penggunanya sendiri, sebut angka penting.
- Beri 2–4 saran konkret yang bisa dilakukan HARI INI (jam, menit, porsi), sesuaikan dengan program turun berat & jadwal olahraganya bila ada.
- Jangan mendiagnosis. Kalau ada tanda yang mengkhawatirkan (SpO2 malam < 90%, detak istirahat naik tajam beberapa hari, dsb.) sarankan cek ke dokter dengan tenang.
- Jangan menyarankan makan di bawah target kalori programnya.
- Ingat data cincin bisa bolong; kalau data kurang, katakan saja.
- Format: teks biasa, boleh emoji di awal poin, tanpa tabel, tanpa markdown tebal/judul.
""".trimIndent()

    /** Kirim pesan; history = pasangan (user, assistant) sebelumnya. */
    suspend fun ask(ctx: Context, user: String, history: List<Pair<String, String>> = emptyList(), maxTokens: Int = 700): String =
        withContext(Dispatchers.IO) {
            val prov = provider(ctx); val k = key(ctx); val m = model(ctx)
            require(prov != OFF && k.isNotBlank()) { "AI belum diatur" }
            val models = if (prov == GEMINI) (listOf(m) + FALLBACK_GEMINI).distinct() else listOf(m)
            var last: Exception? = null
            for (model in models) {
                // coba ulang dengan jeda kalau server sibuk
                for (attempt in 0..2) {
                    try {
                        // model utama diberi 40 dtk; cadangan (lebih cepat) 60 dtk
                        readTimeoutMs = if (model == models.first() && models.size > 1) 40_000 else 60_000
                        val t = if (prov == GEMINI) gemini(k, model, user, history, maxTokens) else claude(k, model, user, history, maxTokens)
                        lastModel = model
                        return@withContext t
                    } catch (e: Exception) {
                        last = e
                        AppLog.d("✨ AI gagal ($model, coba ${attempt + 1}): ${e.message}")
                        val m2 = e.message ?: ""
                        // model lambat (timeout): jangan diulang, langsung pindah ke model cadangan yang lebih cepat
                        if (m2.contains("timeout", true) || e is java.net.SocketTimeoutException) break
                        val busy = m2.contains("HTTP 503") || m2.contains("HTTP 429") || m2.contains("HTTP 529") || m2.contains("HTTP 500")
                        if (!busy) break
                        if (attempt < 2) kotlinx.coroutines.delay(if (attempt == 0) 3_000L else 8_000L)
                    }
                }
                val msg = last?.message ?: ""
                if (!(msg.contains("HTTP 503") || msg.contains("HTTP 429") || msg.contains("HTTP 404") ||
                        msg.contains("timeout", true) || last is java.net.SocketTimeoutException)) break // key salah dsb: jangan ganti model
            }
            throw last ?: RuntimeException("gagal")
        }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20_000; c.readTimeout = readTimeoutMs
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (a, b) -> c.setRequestProperty(a, b) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try { JSONObject(txt).getJSONObject("error").getString("message") } catch (_: Exception) { txt.take(200) }
            throw RuntimeException("HTTP $code: $msg")
        }
        return txt
    }

    private fun gemini(key: String, model: String, user: String, hist: List<Pair<String, String>>, maxTokens: Int): String {
        val contents = JSONArray()
        fun add(role: String, t: String) = contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", t))))
        for ((u, a) in hist) { add("user", u); add("model", a) }
        add("user", user)
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.6).put("maxOutputTokens", maxTokens * 3))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent"
        val r = JSONObject(post(url, mapOf("x-goog-api-key" to key), body.toString()))
        val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optString("text").takeIf { t -> t.isNotBlank() && !parts.getJSONObject(it).optBoolean("thought") } }
            .joinToString("").trim()
    }

    private fun claude(key: String, model: String, user: String, hist: List<Pair<String, String>>, maxTokens: Int): String {
        val msgs = JSONArray()
        for ((u, a) in hist) { msgs.put(JSONObject().put("role", "user").put("content", u)); msgs.put(JSONObject().put("role", "assistant").put("content", a)) }
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", SYSTEM).put("messages", msgs)
        val r = JSONObject(post("https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString()))
        val c = r.getJSONArray("content")
        return (0 until c.length()).map { c.getJSONObject(it) }.filter { it.optString("type") == "text" }
            .joinToString("") { it.getString("text") }.trim()
    }
}
