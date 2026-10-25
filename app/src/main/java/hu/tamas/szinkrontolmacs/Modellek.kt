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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
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

/** Beszédfelismerő modellek (Parakeet v3 vagy Whisper) letöltése és helye. */
object BeszedModell {

    const val PARAKEET = "parakeet"
    val KULCSOK = listOf(PARAKEET, "small", "base")

    fun nev(kulcs: String) = when (kulcs) {
        PARAKEET -> "Legjobb (Parakeet v3, kb. 490 MB) – amerikai angolra is"
        "base" -> "Gyors (Whisper base, kb. 160 MB)"
        else -> "Pontos (Whisper small, kb. 360 MB)"
    }

    fun mappa(ctx: Context, kulcs: String) =
        if (kulcs == PARAKEET) File(ctx.filesDir, "parakeet-v3") else File(ctx.filesDir, "whisper-$kulcs")

    private val PARAKEET_FAJLOK = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    private const val PARAKEET_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"

    private fun fajlok(kulcs: String) =
        if (kulcs == PARAKEET) PARAKEET_FAJLOK
        else listOf("$kulcs-encoder.int8.onnx", "$kulcs-decoder.int8.onnx", "$kulcs-tokens.txt")

    fun encoder(ctx: Context, kulcs: String) = File(mappa(ctx, kulcs), "$kulcs-encoder.int8.onnx").absolutePath
    fun decoder(ctx: Context, kulcs: String) = File(mappa(ctx, kulcs), "$kulcs-decoder.int8.onnx").absolutePath
    fun tokens(ctx: Context, kulcs: String) = File(mappa(ctx, kulcs), "$kulcs-tokens.txt").absolutePath

    fun kesz(ctx: Context, kulcs: String): Boolean =
        fajlok(kulcs).all { File(mappa(ctx, kulcs), it).let { f -> f.exists() && f.length() > 0 } }

    /** Blokkoló letöltés (háttérszálon hívd). haladas(szöveg) */
    fun letolt(ctx: Context, kulcs: String, haladas: (String) -> Unit) {
        if (kesz(ctx, kulcs)) return
        val dir = mappa(ctx, kulcs)
        dir.mkdirs()
        if (kulcs == PARAKEET) {
            letoltParakeet(dir, haladas)
            return
        }
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

    /** A .tar.bz2 csomagot letöltés közben bontja ki (nem kell kétszer annyi hely). */
    private fun letoltParakeet(dir: File, haladas: (String) -> Unit) {
        val kapcsolat = megnyit(PARAKEET_URL)
        val osszes = kapcsolat.contentLengthLong
        var olvasott = 0L
        var utolso = -1
        val szamlalo = object : java.io.FilterInputStream(BufferedInputStream(kapcsolat.inputStream, 1 shl 16)) {
            override fun read(): Int {
                val b = super.read()
                if (b >= 0) jelent(1)
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) jelent(n)
                return n
            }

            private fun jelent(n: Int) {
                olvasott += n
                val sz = if (osszes > 0) (olvasott * 100 / osszes).toInt() else 0
                if (sz != utolso) {
                    utolso = sz
                    haladas("Parakeet letöltése és kibontása – $sz%")
                }
            }
        }
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(szamlalo)).use { tar ->
                while (true) {
                    val bejegyzes = tar.nextEntry ?: break
                    if (bejegyzes.isDirectory) continue
                    val nev = bejegyzes.name.substringAfterLast('/')
                    if (nev !in PARAKEET_FAJLOK) continue
                    val reszleges = File(dir, "$nev.part")
                    FileOutputStream(reszleges).use { ki -> tar.copyTo(ki, 1 shl 16) }
                    val cel = File(dir, nev)
                    cel.delete()
                    if (!reszleges.renameTo(cel)) throw IllegalStateException("Nem sikerült menteni: $nev")
                }
            }
        } finally {
            kapcsolat.disconnect()
        }
        if (!PARAKEET_FAJLOK.all { File(dir, it).length() > 0 }) {
            throw IllegalStateException("Hiányos csomag, próbáld újra")
        }
    }

    private fun megnyit(url: String): HttpURLConnection {
        var cim = url
        var atiranyitas = 0
        while (true) {
            val kapcsolat = URL(cim).openConnection() as HttpURLConnection
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
            return kapcsolat
        }
    }

    private fun letoltFajl(url: String, cel: File, haladas: (Int) -> Unit) {
        val kapcsolat = megnyit(url)
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
