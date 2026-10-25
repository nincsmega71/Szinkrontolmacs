package hu.tamas.szinkrontolmacs

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translator
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A szinkrontolmács motorja:
 * belső hang (más appok lejátszása) → VAD mondatokra vágás → Whisper (nyelvfelismerés + leirat)
 * → ML Kit fordítás magyarra → magyar TTS a fülhallgatóba.
 */
class TolmacsService : Service() {

    companion object {
        const val TAG = "Tolmacs"
        const val EXTRA_KOD = "kod"
        const val EXTRA_ADAT = "adat"
        const val ACTION_LEALLIT = "hu.tamas.szinkrontolmacs.LEALLIT"
        private const val CSATORNA = "tolmacs"
        private const val ERTESITES_ID = 1
        private const val MINTAVETEL = 16000

        @Volatile
        var fut = false
            private set

        /** A főképernyő ezen keresztül kap állapotot (főszálon hívódik). */
        @Volatile
        var figyelo: ((String) -> Unit)? = null

        @Volatile
        var utolsoAllapot: String = ""
            private set

        private val fo = Handler(Looper.getMainLooper())

        fun jelez(uzenet: String) {
            utolsoAllapot = uzenet
            fo.post { figyelo?.invoke(uzenet) }
        }

        /** Whisper tipikus "hallucinációi" csendre / zenére. */
        private val SZEMET = listOf(
            "thank you for watching", "thanks for watching", "subtitles by", "amara.org",
            "please subscribe", "like and subscribe", "untertitel", "sous-titres",
            "sottotitoli", "subtítulos", "продолжение следует", "субтитры", "редактор субтитров",
        )
    }

    private lateinit var beall: Beallitasok
    private var vetites: MediaProjection? = null
    private var felvevo: AudioRecord? = null
    private var vad: Vad? = null
    private var felismero: OfflineRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsKesz = false
    private val forditok = HashMap<String, Translator>()
    private val szegmensek = LinkedBlockingDeque<FloatArray>()
    private val varakozoBeszed = ConcurrentHashMap.newKeySet<String>()
    private val beszedSzamlalo = AtomicLong(0)
    private var rogzitoSzal: Thread? = null
    private var feldolgozoSzal: Thread? = null
    @Volatile private var leall = false
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var hangkezelo: AudioManager
    private var fokusz: AudioFocusRequest? = null
    private var fokuszNalunk = false
    private val fokuszHandler = Handler(Looper.getMainLooper())

    private val tolmacsHangAttr = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)          // nem MEDIA → nem rögzítjük vissza
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEALLIT) {
            leallit()
            return START_NOT_STICKY
        }
        if (fut) return START_NOT_STICKY

        beall = Beallitasok(this)
        hangkezelo = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        csatornaLetrehozas()
        val ertesites = ertesites("Indul…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ERTESITES_ID, ertesites, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ERTESITES_ID, ertesites)
        }

        val kod = intent?.getIntExtra(EXTRA_KOD, 0) ?: 0
        val adat: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_ADAT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_ADAT)
        }
        if (adat == null) {
            jelez("Hiba: nincs engedély a hang rögzítéséhez.")
            leallit()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val v = mpm.getMediaProjection(kod, adat)
        if (v == null) {
            jelez("Hiba: a rögzítés engedélyezése sikertelen.")
            leallit()
            return START_NOT_STICKY
        }
        vetites = v
        v.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                jelez("A rendszer leállította a rögzítést.")
                leallit()
            }
        }, Handler(Looper.getMainLooper()))

        fut = true
        leall = false
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Szinkrontolmacs:fut").also {
            it.setReferenceCounted(false)
            it.acquire(6 * 60 * 60 * 1000L)
        }

        ttsIndit()
        feldolgozoSzal = Thread({ motorIndit() }, "tolmacs-motor").also { it.start() }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- TTS

    private fun ttsIndit() {
        tts = TextToSpeech(this) { allapot ->
            if (allapot != TextToSpeech.SUCCESS) {
                jelez("Hiba: a szövegfelolvasó nem indult el.")
                return@TextToSpeech
            }
            val t = tts ?: return@TextToSpeech
            val r = t.setLanguage(Locale("hu", "HU"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                jelez("Hiba: nincs magyar felolvasó hang. Telepítsd a beállításokban (Szövegfelolvasás).")
                return@TextToSpeech
            }
            t.setAudioAttributes(tolmacsHangAttr)
            t.setSpeechRate(beall.sebesseg)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    fokuszHandler.post { fokuszKer() }
                }

                override fun onDone(utteranceId: String?) = vege(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = vege(utteranceId)

                override fun onError(utteranceId: String?, errorCode: Int) = vege(utteranceId)

                override fun onStop(utteranceId: String?, interrupted: Boolean) = vege(utteranceId)

                private fun vege(id: String?) {
                    if (id != null) varakozoBeszed.remove(id)
                    if (varakozoBeszed.isEmpty()) {
                        fokuszHandler.removeCallbacksAndMessages(null)
                        fokuszHandler.postDelayed({ if (varakozoBeszed.isEmpty()) fokuszElenged() }, 700)
                    }
                }
            })
            ttsKesz = true
        }
    }

    private fun beszel(szoveg: String) {
        val t = tts ?: return
        if (!ttsKesz) return
        val lemaradas = varakozoBeszed.size
        // Ha lemaradtunk, a régit eldobjuk, hogy szinkronban maradjunk a képpel
        val mod = if (lemaradas >= 2) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (mod == TextToSpeech.QUEUE_FLUSH) varakozoBeszed.clear()
        val szorzo = when {
            lemaradas >= 2 -> 1.35f
            lemaradas == 1 -> 1.2f
            else -> 1.0f
        }
        t.setSpeechRate((beall.sebesseg * szorzo).coerceAtMost(2.2f))
        val id = "m" + beszedSzamlalo.incrementAndGet()
        varakozoBeszed.add(id)
        val p = Bundle()
        t.speak(szoveg, mod, p, id)
    }

    private fun fokuszKer() {
        if (!beall.halkitas || fokuszNalunk) return
        val req = fokusz ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(tolmacsHangAttr)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build().also { fokusz = it }
        fokuszNalunk = hangkezelo.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun fokuszElenged() {
        val req = fokusz ?: return
        if (fokuszNalunk) {
            hangkezelo.abandonAudioFocusRequest(req)
            fokuszNalunk = false
        }
    }

    // ---------------------------------------------------------------- Motor

    private fun motorIndit() {
        try {
            jelez("Beszédfelismerő betöltése…")
            val kulcs = beall.modell
            if (!WhisperModell.kesz(this, kulcs)) {
                jelez("Hiba: a beszédfelismerő modell nincs letöltve.")
                fo.post { leallit() }
                return
            }
            vad = Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 5.0f,
                    ),
                    sampleRate = MINTAVETEL,
                    numThreads = 1,
                ),
            )
            felismero = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = MINTAVETEL, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = WhisperModell.encoder(this, kulcs),
                            decoder = WhisperModell.decoder(this, kulcs),
                            language = "",          // üres = automatikus nyelvfelismerés
                            task = "transcribe",
                        ),
                        tokens = WhisperModell.tokens(this, kulcs),
                        modelType = "whisper",
                        numThreads = 6,
                    ),
                ),
            )
            jelez("Fordítók betöltése…")
            for (kod in FORRAS_NYELVEK.keys) forditok[kod] = Forditok.ugyfel(kod)

            if (leall) return
            rogzitoSzal = Thread({ rogzites() }, "tolmacs-rogzito").also { it.start() }
            jelez("Fut – indítsd el a videót. A magyar hang a fülhallgatóban szól.")
            ertesitesFrissit("Fut – figyeli a lejátszott hangot")
            feldolgozas()
        } catch (e: Throwable) {
            Log.e(TAG, "Motor hiba", e)
            jelez("Hiba: ${e.message ?: e.javaClass.simpleName}")
            fo.post { leallit() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun rogzites() {
        val v = vetites ?: return
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(v)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()
            val formatum = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(MINTAVETEL)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                MINTAVETEL, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(formatum)
                .setBufferSizeInBytes(maxOf(minBuf, MINTAVETEL * 2))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                jelez("Hiba: a belső hang rögzítése nem indult el.")
                fo.post { leallit() }
                return
            }
            felvevo = rec
            rec.startRecording()

            val ablak = 512
            val nyers = ShortArray(ablak)
            val minta = FloatArray(ablak)
            val vadP = vad ?: return
            while (!leall) {
                val n = rec.read(nyers, 0, ablak)
                if (n <= 0) continue
                for (i in 0 until n) minta[i] = nyers[i] / 32768f
                vadP.acceptWaveform(if (n == ablak) minta else minta.copyOf(n))
                while (!vadP.empty()) {
                    val seg = vadP.front().samples
                    vadP.pop()
                    // Max. 2 várakozó mondat – a régit eldobjuk, hogy ne csússzon el
                    while (szegmensek.size >= 2) szegmensek.pollFirst()
                    szegmensek.offerLast(seg)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Rögzítési hiba", e)
            if (!leall) {
                jelez("Hiba a rögzítésben: ${e.message ?: e.javaClass.simpleName}")
                fo.post { leallit() }
            }
        } finally {
            try { felvevo?.stop() } catch (_: Throwable) {}
            felvevo?.release()
            felvevo = null
        }
    }

    private fun feldolgozas() {
        val fel = felismero ?: return
        while (!leall) {
            val seg = szegmensek.pollFirst(300, TimeUnit.MILLISECONDS) ?: continue
            if (seg.size < MINTAVETEL * 4 / 10) continue          // 0,4 s alatt: zaj

            val t0 = System.currentTimeMillis()
            val stream = fel.createStream()
            val eredmeny = try {
                stream.acceptWaveform(seg, MINTAVETEL)
                fel.decode(stream)
                fel.getResult(stream)
            } finally {
                stream.release()
            }
            val nyelv = eredmeny.lang.trim().removePrefix("<|").removeSuffix("|>").lowercase(Locale.ROOT)
            val szoveg = eredmeny.text.trim()
            if (!ertelmes(szoveg)) continue
            val ford = forditok[nyelv]
            if (ford == null) {
                // Magyar vagy nem támogatott nyelv: nem fordítjuk
                jelez("Fut – (${nyelv.ifEmpty { "?" }} nyelv, kihagyva)")
                continue
            }
            val magyar = try {
                Tasks.await(ford.translate(szoveg), 8, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Fordítási hiba", e)
                continue
            }
            if (leall) break
            if (magyar.isNullOrBlank()) continue
            beszel(magyar)
            val feldolg = (System.currentTimeMillis() - t0) / 1000f
            val hossz = seg.size / MINTAVETEL.toFloat()
            jelez(String.format(Locale.ROOT,
                "Fut – %s → magyar\nMondat: %.1f s, feldolgozás: %.1f s\n„%s”",
                FORRAS_NYELVEK[nyelv], hossz, feldolg, magyar))
        }
    }

    private fun ertelmes(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.none { it.isLetter() }) return false                  // pl. "♪", "..."
        val k = s.lowercase(Locale.ROOT)
        if (SZEMET.any { k.contains(it) }) return false
        return true
    }

    // ---------------------------------------------------------------- Leállítás

    private fun leallit() {
        if (leall && !fut) {
            stopSelf()
            return
        }
        leall = true
        fut = false
        try { felvevo?.stop() } catch (_: Throwable) {}
        rogzitoSzal?.join(1500)
        feldolgozoSzal?.join(3000)
        try { vetites?.stop() } catch (_: Throwable) {}
        vetites = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsKesz = false
        fokuszElenged()
        forditok.values.forEach { it.close() }
        forditok.clear()
        felismero?.release()
        felismero = null
        vad?.release()
        vad = null
        szegmensek.clear()
        wakeLock?.release()
        wakeLock = null
        jelez("Leállítva.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (fut) leallit()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Értesítés

    private fun csatornaLetrehozas() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CSATORNA, "Szinkrontolmács", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun ertesites(szoveg: String): Notification {
        val megnyit = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val leallitI = PendingIntent.getService(
            this, 1, Intent(this, TolmacsService::class.java).setAction(ACTION_LEALLIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CSATORNA)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Szinkrontolmács")
            .setContentText(szoveg)
            .setContentIntent(megnyit)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Leállítás", leallitI).build())
            .build()
    }

    private fun ertesitesFrissit(szoveg: String) {
        getSystemService(NotificationManager::class.java).notify(ERTESITES_ID, ertesites(szoveg))
    }
}
