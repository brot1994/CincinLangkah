package id.bram.cincinlangkah

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Wajib ada untuk izin Health Connect. */
class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        setContentView(TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 16f
            text = "Cincin Langkah hanya MEMBACA data langkah dari Health Connect untuk dibandingkan " +
                "dengan data cincin. Semua data tetap di HP ini, tidak dikirim ke mana pun."
        })
    }
}
