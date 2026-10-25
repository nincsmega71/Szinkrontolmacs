# Szinkrontolmács (Android)

A telefonon lejátszott videó hangját élőben magyarra fordítja és a fülhallgatóba mondja.

- Forrásnyelvek: angol, spanyol, orosz, német, francia, olasz – automatikus nyelvfelismeréssel
- Minden a telefonon fut (offline), ingyenes: Whisper (sherpa-onnx) + Google ML Kit fordító + rendszer magyar TTS
- Android 10+ (fejlesztve: Samsung Galaxy S24 Ultra)

## Működés

```
videó hangja (belső rögzítés) → mondatokra vágás (Silero VAD) → Whisper: nyelv + szöveg
   → ML Kit fordítás magyarra → magyar felolvasás → fülhallgató
```

## APK

Minden `main` ágra küldés után a GitHub Actions lefordítja az APK-t,
és a **Releases** oldalon `Szinkrontolmacs.apk` néven letölthető.

## Korlátok

- Kb. 2–4 mp késés (ki kell várni a mondat végét)
- Netflix, Disney+, HBO Max stb. tiltja a hangja rögzítését
- Gépi felolvasó hang; egyszerre beszélő szereplőket nem választ szét
