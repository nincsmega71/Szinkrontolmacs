package hu.tamas.szinkrontolmacs

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** A forrásnyelvek, amelyekből fordítunk (Whisper nyelvkódok = ML Kit nyelvkódok). */
val FORRAS_NYELVEK = linkedMapOf(
    "en" to "angol",
    "es" to "spanyol",
    "ru" to "orosz",
    "de" to "német",
    "fr" to "francia",
    "it" to "olasz",
)

/** Whisper beszédfelismerő modell letöltése és helye. */
object WhisperModell {

    fun nev(kulcs: String) = if (kulcs == "base") "Gyors (base, kb. 160 MB)" else "Pontos (small, kb. 360 MB)"

    fun mappa(ctx: Context, kulcs: String) = File(ctx.filesDir, "whisper-$kulcs")

    private fun fajlok(kulcs: String) =
        listOf("$kulcs-encoder.int8.onnx", "$kulcs-decoder.int8.onnx", "$kulcs-tokens.txt")

    fun encoder(ctx: Context, kulcs: String) = File(mappa(ctx, kulcs), "$kulcs-encoder.int8.onnx").absolutePath
    fun decoder(ctx: Context, kulcs: String) = File(mappa(ctx, kulcs), "$kulcs-decoder.int8.onnx").absolutePath
    fun tokens(ctx: Context, kulcs: String) = File(mappa(ctx, kulcs), "$kulcs-tokens.txt").absolutePath

    fun kesz(ctx: Context, kulcs: String): Boolean =
        fajlok(kulcs).all { File(mappa(ctx, kulcs), it).let { f -> f.exists() && f.length() > 0 } }

    /** Blokkoló letöltés (háttérszálon hívd). haladas(szöveg) */
    fun letolt(ctx: Context, kulcs: String, haladas: (String) -> Unit) {
        val dir = mappa(ctx, kulcs)
        dir.mkdirs()
        val lista = fajlok(kulcs)
        lista.forEachIndexed { i, nev ->
            val cel = File(dir, nev)
            if (cel.exists() && cel.length() > 0) return@forEachIndexed
            val url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$kulcs/resolve/main/$nev"
            val reszleges = File(dir, "$nev.part")
            letoltFajl(url, reszleges) { szazalek ->
                haladas("Beszédfelismerő letöltése: ${i + 1}/${lista.size} fájl – $szazalek%")
            }
            if (!reszleges.renameTo(cel)) throw IllegalStateException("Nem sikerült menteni: $nev")
        }
    }

    private fun letoltFajl(url: String, cel: File, haladas: (Int) -> Unit) {
        var cim = url
        var kapcsolat: HttpURLConnection
        var atiranyitas = 0
        while (true) {
            kapcsolat = URL(cim).openConnection() as HttpURLConnection
            kapcsolat.instanceFollowRedirects = false
            kapcsolat.connectTimeout = 20000
            kapcsolat.readTimeout = 60000
            val kod = kapcsolat.responseCode
            if (kod in 300..399) {
                val hova = kapcsolat.getHeaderField("Location") ?: throw IllegalStateException("Hibás átirányítás")
                cim = URL(URL(cim), hova).toString()
                kapcsolat.disconnect()
                if (++atiranyitas > 8) throw IllegalStateException("Túl sok átirányítás")
                continue
            }
            if (kod != 200) throw IllegalStateException("Letöltési hiba: HTTP $kod")
            break
        }
        val osszes = kapcsolat.contentLengthLong
        var olvasott = 0L
        var utolso = -1
        kapcsolat.inputStream.use { be ->
            FileOutputStream(cel).use { ki ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = be.read(buf)
                    if (n < 0) break
                    ki.write(buf, 0, n)
                    olvasott += n
                    val sz = if (osszes > 0) (olvasott * 100 / osszes).toInt() else 0
                    if (sz != utolso) {
                        utolso = sz
                        haladas(sz)
                    }
                }
            }
        }
        kapcsolat.disconnect()
        if (osszes > 0 && olvasott != osszes) throw IllegalStateException("Megszakadt a letöltés")
    }
}

/** ML Kit fordító modellek (offline, ingyenes). */
object Forditok {

    fun ugyfel(forras: String): Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(forras)
            .setTargetLanguage(TranslateLanguage.HUNGARIAN)
            .build()
    )

    fun mindKesz(): Boolean {
        return try {
            val letoltott = Tasks.await(
                RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),
                20, TimeUnit.SECONDS
            ).map { it.language }.toSet()
            letoltott.contains(TranslateLanguage.HUNGARIAN) &&
                FORRAS_NYELVEK.keys.all { letoltott.contains(it) }
        } catch (e: Exception) {
            false
        }
    }

    /** Blokkoló letöltés (háttérszálon hívd). */
    fun letoltMind(haladas: (String) -> Unit) {
        val felt = DownloadConditions.Builder().build()
        var i = 0
        for ((kod, nev) in FORRAS_NYELVEK) {
            i++
            haladas("Fordító letöltése: $nev → magyar ($i/${FORRAS_NYELVEK.size})")
            val t = ugyfel(kod)
            try {
                Tasks.await(t.downloadModelIfNeeded(felt), 10, TimeUnit.MINUTES)
            } finally {
                t.close()
            }
        }
    }
}
