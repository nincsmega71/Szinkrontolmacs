package hu.tamas.szinkrontolmacs

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private companion object {
        const val KERES_VETITES = 10
        const val KERES_ENGEDELY = 11
    }

    private lateinit var beall: Beallitasok
    private lateinit var modellAllapot: TextView
    private lateinit var letoltGomb: Button
    private lateinit var hangAllapot: TextView
    private lateinit var hangGomb: Button
    private lateinit var inditGomb: Button
    private lateinit var allapot: TextView
    private lateinit var sebessegFelirat: TextView
    private var radioSmall: RadioButton? = null
    private var radioBase: RadioButton? = null
    @Volatile private var letoltesFut = false
    private var probaTts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        beall = Beallitasok(this)
        setContentView(feluletEpites())
        modellEllenoriz()
        hangEllenoriz()
    }

    override fun onResume() {
        super.onResume()
        TolmacsService.figyelo = { uzenet ->
            allapot.text = uzenet
            gombFrissit()
        }
        if (TolmacsService.utolsoAllapot.isNotEmpty()) allapot.text = TolmacsService.utolsoAllapot
        gombFrissit()
    }

    override fun onPause() {
        TolmacsService.figyelo = null
        super.onPause()
    }

    override fun onDestroy() {
        probaTts?.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Felület

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cim(szoveg: String) = TextView(this).apply {
        text = szoveg
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun szoveg(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun feluletEpites(): View {
        val oszlop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        oszlop.addView(TextView(this).apply {
            text = "Szinkrontolmács"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        oszlop.addView(szoveg(
            "A telefonon lejátszott videó hangját (angol, spanyol, orosz, német, francia, olasz) " +
                "automatikusan felismeri, és magyarul mondja a fülhallgatódba."
        ))

        // 1. Beszédfelismerő
        oszlop.addView(cim("1. Beszédfelismerő modell"))
        val csoport = RadioGroup(this)
        radioSmall = RadioButton(this).apply {
            id = View.generateViewId()
            text = WhisperModell.nev("small")
            textSize = 15f
        }
        radioBase = RadioButton(this).apply {
            id = View.generateViewId()
            text = WhisperModell.nev("base")
            textSize = 15f
        }
        csoport.addView(radioSmall)
        csoport.addView(radioBase)
        if (beall.modell == "base") radioBase?.isChecked = true else radioSmall?.isChecked = true
        csoport.setOnCheckedChangeListener { _, checkedId ->
            beall.modell = if (checkedId == radioBase?.id) "base" else "small"
            modellEllenoriz()
        }
        oszlop.addView(csoport)
        modellAllapot = szoveg("")
        oszlop.addView(modellAllapot)
        letoltGomb = Button(this).apply {
            text = "Modellek letöltése (Wi-Fi ajánlott)"
            setOnClickListener { letoltes() }
        }
        oszlop.addView(letoltGomb, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 2. Magyar hang
        oszlop.addView(cim("2. Magyar felolvasó hang"))
        hangAllapot = szoveg("Ellenőrzés…")
        oszlop.addView(hangAllapot)
        hangGomb = Button(this).apply {
            text = "Felolvasó beállítások megnyitása"
            setOnClickListener { ttsBeallitasNyit() }
        }
        oszlop.addView(hangGomb, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 3. Beállítások
        oszlop.addView(cim("3. Beállítások"))
        sebessegFelirat = szoveg("")
        oszlop.addView(sebessegFelirat)
        val csuszka = SeekBar(this).apply {
            max = 80                                   // 0.8 … 1.6
            progress = ((beall.sebesseg - 0.8f) * 100).toInt().coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    beall.sebesseg = 0.8f + p / 100f
                    sebessegFeliratFrissit()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        oszlop.addView(csuszka, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sebessegFeliratFrissit()
        oszlop.addView(CheckBox(this).apply {
            text = "Eredeti hang halkítása, amíg a magyar szól"
            textSize = 15f
            isChecked = beall.halkitas
            setOnCheckedChangeListener { _, b -> beall.halkitas = b }
        })

        // Indítás
        inditGomb = Button(this).apply {
            textSize = 20f
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener { inditVagyLeallit() }
        }
        oszlop.addView(inditGomb, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(24)
        })
        allapot = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
        }
        oszlop.addView(allapot)
        oszlop.addView(szoveg(
            "Tipp: indítás után a rendszer engedélyt kér a képernyő/hang rögzítésére – " +
                "ott a „Teljes képernyő” lehetőséget válaszd. Ezután nyisd meg a videót (YouTube, böngésző, " +
                "videólejátszó). A Netflix, Disney+, HBO Max és hasonló appok tiltják a hangjuk rögzítését, ott nem működik."
        ).apply { setTextColor(Color.GRAY) })

        return ScrollView(this).apply { addView(oszlop) }
    }

    private fun sebessegFeliratFrissit() {
        sebessegFelirat.text = String.format(Locale.ROOT, "Felolvasás sebessége: %.2f×", beall.sebesseg)
    }

    private fun gombFrissit() {
        inditGomb.text = if (TolmacsService.fut) "■  LEÁLLÍTÁS" else "▶  INDÍTÁS"
    }

    // ------------------------------------------------------------ Modellek

    private fun modellEllenoriz() {
        val kulcs = beall.modell
        Thread {
            val whisper = WhisperModell.kesz(this, kulcs)
            val ford = Forditok.mindKesz()
            runOnUiThread {
                if (letoltesFut) return@runOnUiThread
                modellAllapot.text = when {
                    whisper && ford -> "✔ Minden modell letöltve, offline is működik."
                    else -> "Hiányzik: " + listOfNotNull(
                        if (!whisper) "beszédfelismerő" else null,
                        if (!ford) "fordítók (kb. 200 MB)" else null,
                    ).joinToString(", ")
                }
                letoltGomb.visibility = if (whisper && ford) View.GONE else View.VISIBLE
            }
        }.start()
    }

    private fun letoltes() {
        if (letoltesFut) return
        letoltesFut = true
        letoltGomb.isEnabled = false
        val kulcs = beall.modell
        Thread {
            try {
                WhisperModell.letolt(this, kulcs) { s -> runOnUiThread { modellAllapot.text = s } }
                Forditok.letoltMind { s -> runOnUiThread { modellAllapot.text = s } }
                runOnUiThread { modellAllapot.text = "✔ Kész." }
            } catch (e: Exception) {
                runOnUiThread {
                    modellAllapot.text = "Letöltési hiba: ${e.message}. Próbáld újra."
                }
            } finally {
                letoltesFut = false
                runOnUiThread {
                    letoltGomb.isEnabled = true
                    modellEllenoriz()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ Magyar TTS

    private fun hangEllenoriz() {
        probaTts?.shutdown()
        probaTts = TextToSpeech(this) { st ->
            val t = probaTts
            val ok = st == TextToSpeech.SUCCESS && t != null &&
                t.isLanguageAvailable(Locale("hu", "HU")) >= TextToSpeech.LANG_AVAILABLE
            runOnUiThread {
                if (ok) {
                    hangAllapot.text = "✔ Van magyar felolvasó hang."
                    hangGomb.visibility = View.GONE
                } else {
                    hangAllapot.text = "Nincs magyar hang. Nyisd meg a felolvasó beállításait, " +
                        "és tölts le magyar hangot (Samsung vagy Google szövegfelolvasó)."
                    hangGomb.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun ttsBeallitasNyit() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e2: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    // ------------------------------------------------------------ Indítás

    private fun inditVagyLeallit() {
        if (TolmacsService.fut) {
            startService(Intent(this, TolmacsService::class.java).setAction(TolmacsService.ACTION_LEALLIT))
            return
        }
        if (!WhisperModell.kesz(this, beall.modell)) {
            Toast.makeText(this, "Előbb töltsd le a modelleket (1. lépés).", Toast.LENGTH_LONG).show()
            return
        }
        val kell = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            kell += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            kell += Manifest.permission.POST_NOTIFICATIONS
        }
        if (kell.isNotEmpty()) {
            requestPermissions(kell.toTypedArray(), KERES_ENGEDELY)
            return
        }
        vetitesKer()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != KERES_ENGEDELY) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vetitesKer()
        } else {
            Toast.makeText(
                this,
                "A hangrögzítési engedély nélkül nem hallja a videó hangját.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun vetitesKer() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val szandek = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(szandek, KERES_VETITES)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != KERES_VETITES) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Engedély nélkül nem indul el.", Toast.LENGTH_SHORT).show()
            return
        }
        allapot.text = "Indul…"
        val i = Intent(this, TolmacsService::class.java)
            .putExtra(TolmacsService.EXTRA_KOD, resultCode)
            .putExtra(TolmacsService.EXTRA_ADAT, data)
        startForegroundService(i)
    }
}
