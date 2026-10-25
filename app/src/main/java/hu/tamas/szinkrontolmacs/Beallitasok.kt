package hu.tamas.szinkrontolmacs

import android.content.Context

/** Egyszerű tartós beállítások. */
class Beallitasok(ctx: Context) {
    private val sp = ctx.getSharedPreferences("beallitasok", Context.MODE_PRIVATE)

    /** "small" = pontosabb, "base" = gyorsabb */
    var modell: String
        get() = sp.getString("modell", "small") ?: "small"
        set(v) = sp.edit().putString("modell", v).apply()

    /** Felolvasási sebesség szorzó (1.0 = normál). */
    var sebesseg: Float
        get() = sp.getFloat("sebesseg", 1.15f)
        set(v) = sp.edit().putFloat("sebesseg", v).apply()

    /** Halkítsa-e az eredeti hangot, amíg a magyar fordítás szól. */
    var halkitas: Boolean
        get() = sp.getBoolean("halkitas", true)
        set(v) = sp.edit().putBoolean("halkitas", v).apply()
}
