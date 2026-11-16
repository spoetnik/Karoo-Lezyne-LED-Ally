# Lezyne Lights – Karoo-extension

Bedien Lezyne Smart Connect-lampen vanaf een Hammerhead Karoo, zonder telefoon. Getest op een Karoo 2. Zie `PROTOCOL.md` voor het Bluetooth-protocol.

## Wat hij doet

- **Dataveld "Lezyne licht"**: links staan aan/uit en de batterij (tik = aan/uit), rechts de huidige modus (tik = volgende modus).
- **Automatisch aan bij schemer**: tijdens een rit gaat het licht aan zodra de zon onder 3° boven de horizon zakt, berekend uit je GPS-positie en de tijd. Bij daglicht (zon boven 5°) gaat hij weer uit. Dit gebeurt alleen bij de overgang tussen licht en donker; zet je zelf iets om, dan blijft dat zo (ook als de extension halverwege de rit herstart).
- **Uit bij afsluiten**: als je de rit afsluit, gaat de lamp uit.
- **Meldingen** bij een bijna lege batterij (1/5 of minder), een lege batterij of oververhitting.
- **Verbinding** alleen tijdens een rit, of zolang het dataveld of de app open is. Daarna sluit hij na 1 minuut. De status wordt elke 5 minuten en na elke actie opgevraagd.

## Bouwen

Push naar GitHub; de workflow **Build APK** bouwt `app-debug.apk` (artifact `lezyne-karoo-debug`).

karoo-ext staat op GitHub Packages, en dat vereist altijd een persoonlijk token (het standaard `GITHUB_TOKEN` geeft 401). Eenmalig instellen:

1. GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)** → *Generate new token (classic)*. Vink alleen `read:packages` aan.
2. In deze repo: Settings → Secrets and variables → Actions → *New repository secret*, met naam `GPR_TOKEN` en het token als waarde.

Lokaal bouwen: zet `gpr.user` en `gpr.key` (het token) in `~/.gradle/gradle.properties` en draai `./gradlew assembleDebug`.

## Installeren

```
adb install -r app-debug.apk
adb shell am start -n nl.nise.lezyne/.MainActivity
```

Bij de eerste start vraagt de app om locatierechten. Die zijn op Android 8 nodig om Bluetooth te scannen. De app zoekt dan je voorlamp en onthoudt hem. Met "Andere lamp zoeken" kies je opnieuw.

Daarna, op de Karoo: zet het dataveld **Lezyne licht** op een ritpagina (graphical veld; het liefst minstens een halve breedte).

## Problemen?

Het log staat op de Karoo in een bestand:

```
adb pull /sdcard/Android/data/nl.nise.lezyne/files/lezyne-log.txt
```

Live meekijken: `adb logcat -s LEZYNE`. Het log is ook zichtbaar in de app.

Zet tijdens het rijden Bluetooth op je telefoon uit of sluit LED Ally af. Mogelijk accepteert de lamp maar één verbinding tegelijk.
