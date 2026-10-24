package id.bram.cincinlangkah

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log ke file (untuk dikirim ke Claude kalau ada masalah) + ke layar. */
object AppLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<String>()
    var listener: ((String) -> Unit)? = null

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.filesDir, "log.txt")
    }

    @Synchronized
    fun d(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        recent.addLast(line)
        while (recent.size > 300) recent.removeFirst()
        try {
            val f = file
            if (f != null) {
                if (f.length() > 3_000_000) {
                    val keep = f.readText().takeLast(1_000_000)
                    f.writeText(keep)
                }
                f.appendText(line + "\n")
            }
        } catch (_: Exception) { }
        val l = listener
        if (l != null) main.post { l(line) }
    }

    @Synchronized
    fun recentLines(): List<String> = recent.toList()

    fun tail(maxChars: Int = 150_000): String =
        try { file?.readText()?.takeLast(maxChars) ?: "" } catch (_: Exception) { "" }

    fun clear() {
        synchronized(this) { recent.clear() }
        try { file?.writeText("") } catch (_: Exception) { }
    }
}
