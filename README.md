# Lezyne test-app voor Karoo 2

Minimale testapp om te controleren of de Karoo een Lezyne Smart Connect-lamp kan bedienen. Het is nog geen Karoo-extension: alleen een scherm met knoppen en een log. Zie `PROTOCOL.md` voor het protocol.

## Bouwen

Via GitHub Actions, zonder dat je Android Studio nodig hebt:

1. Maak een (privé) repo en push deze map.
2. De workflow **Build APK** draait automatisch. Download het artifact `lezyne-karoo-debug` (een zip met `app-debug.apk`).

Lokaal kan ook: `./gradlew assembleDebug` (vereist JDK 17 en de Android SDK).

## Installeren op de Karoo 2

1. Zet ontwikkelaarsopties en USB-debugging aan op de Karoo. Verbind hem met USB en controleer `adb devices`.
2. `adb install -r app-debug.apk`
3. Starten: `adb shell am start -n nl.nise.lezyne/.MainActivity`
4. Accepteer de vraag om locatierechten. Op Android 8 is die nodig om Bluetooth te scannen.

## Testen

1. **Zet Bluetooth op je telefoon uit** (of sluit LED Ally geforceerd af), zodat de lamp vrij is.
2. Zet de voorlamp aan.
3. Tik **Zoek & verbind** en kijk mee in het log. Het verloop zou moeten zijn:
   - `Service toegevoegd aan onze GATT-server`
   - `Gevonden: …`
   - `Verbonden (client)`
   - `Notificaties van de lamp aan`
   - `Lamp heeft zich op onze server geabonneerd` ← **dit is het spannende punt**
   - `Beide kanalen open` en daarna een statusregel met batterij en modus
4. Probeer daarna **Volgende (08)**, **Uit (0B)**, **Aan (06)** en **Status**.
5. Via het hex-veld kun je ook andere commando's proberen (zie `PROTOCOL.md` §3). **Stuur nooit 10, 11 of 13**: dat zijn DFU en factory reset.

## Log terugsturen

```
adb pull /sdcard/Android/data/nl.nise.lezyne/files/lezyne-log.txt
```

Of live meekijken: `adb logcat -s LEZYNE`
