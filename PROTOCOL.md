# Lezyne Smart Connect – BLE-protocol

Afgeleid uit de gedecompileerde LED Ally-app (`com.lezyne.lezyneledapp` v1.137). Nog **niet getest op echte hardware**: alles hieronder is wat de app doet, niet wat bevestigd werkt vanaf een Karoo.

## 1. Architectuur: dual-role (belangrijk!)

De verbinding werkt in twee richtingen over één BLE-link:

| Richting | Hoe | UUID |
|---|---|---|
| Lamp → telefoon (status) | Telefoon is GATT-**client**, abonneert op notificaties van de lamp | service `29580001-d281-83bd-bc46-ad9b5bb78380`, char `29580003-d281-83bd-bc46-ad9b5bb78380` |
| Telefoon → lamp (commando's) | Telefoon draait een eigen GATT-**server** met dezelfde service/char. De lamp abonneert zich daarop (CCCD-write), en de telefoon stuurt commando's als **notify** (`notifyCharacteristicChanged`) | idem, gehost op de telefoon |

Gevolg voor de Karoo-extension: die moet ook `BluetoothManager.openGattServer()` doen en de service hosten **voordat** hij verbindt. Er wordt niet naar de lamp geschreven (geen `writeCharacteristic`).

Server-characteristic zoals de app hem aanmaakt:
- `BluetoothGattCharacteristic(29580003-…, PROPERTY_NOTIFY (0x10), PERMISSION_WRITE (0x10))`
- descriptor `00002902-…` (CCCD), permission `WRITE`
- service type `SERVICE_TYPE_PRIMARY`

Overige services op de lamp:
- `0000180a-…` Device Information. De app leest `00002a29` (Manufacturer Name) en een versiestring `"<model hex>.<major>.<minor>"`.
- `00001530-1212-efde-1523-785feabcd123` Nordic legacy DFU (firmware-update, niet aankomen).

Geen bonding/pairing, geen encryptie, geen checksum, geen handshake gevonden.

## 2. Verbindingsvolgorde (zoals de app)

1. GATT-server openen en de service toevoegen.
2. Scannen met filter op service-UUID `29580001-…` (scanmode LOW_LATENCY).
3. `connectGatt(ctx, autoConnect=false, callback)`.
4. `discoverServices()` → notificaties aanzetten op `29580003` (CCCD `0x0100`).
5. Wachten tot óók de lamp zich op onze server heeft geabonneerd (`onDescriptorWriteRequest` met `ENABLE_NOTIFICATION_VALUE`; altijd `sendResponse` sturen als `responseNeeded`).
6. Pas als beide kanalen open zijn: ~1 s wachten en dan `GetStatusFront` (0x00) sturen.
7. Status pollen: de app doet dit elke **600 s**. Statuswijzigingen komen ook als notificatie binnen.

Als de lamp de verbinding met onze GATT-server verbreekt, verbreekt de app ook de client-verbinding.

## 3. Uitgaande commando's (byte 0)

Enkelbyte, tenzij anders vermeld.

| Byte | Naam | Opmerking |
|---|---|---|
| 0x00 | GetStatusFront | |
| 0x01 | GetStatusRear | via voorlamp als combo gekoppeld |
| 0x02 | ModeSetFront | 20-byte pakket, zie §5 – zet ook aan |
| 0x03 | ModeSetRear | idem |
| 0x04 | ModeTestFront | preview van één modus (payload onbekend) |
| 0x05 | ModeTestRear | |
| 0x06 | RunFront | waarschijnlijk "aan in huidige modus" (de app gebruikt 0x07 zo voor de achterlamp) – **testen** |
| 0x07 | RunRear | achterlamp aan |
| 0x08 | NextModeFront | volgende modus – interessant voor de Karoo-knop |
| 0x09 | NextModeRear | |
| 0x0A | RearOff | |
| 0x0B | FrontOff | |
| 0x0C | PairRear | koppelen voor/achter |
| 0x0D | KillLights | alles uit |
| 0x0E / 0x0F | SetFront/RearName | `[cmd, len≤18, utf8…]` in 20 bytes |
| 0x10 / 0x11 | StartRear/FrontDfu | **niet gebruiken** |
| 0x13 | FactoryReset | **niet gebruiken** |
| 0x14 | Disconnect | |

## 4. Inkomende berichten (byte 0)

| Byte | Naam | Payload |
|---|---|---|
| 0x00 | FrontStatus | zie hieronder |
| 0x01 | RearStatus | zie hieronder |
| 0x05 | EnteringDeadMode | `[cmd, ?, serial u32 LE]` |
| 0x06 | BatteryDead | idem |
| 0x07 | OverTemperature | idem |
| 0x08 | ExitOverTemperature | idem |

Statuspakket (little-endian, 20 bytes):

| Offset | Veld |
|---|---|
| 0 | cmd (0x00/0x01) |
| 1–2 | batterij u16 – **spanning in mV** (0xFFFF = geen data). Met tweede accu: hi-byte×100 = accu 1, lo-byte×100 = accu 2 |
| 3 | geselecteerde combo-modus-index |
| 4 | geselecteerde single-modus-index |
| 5 | flags (zie hieronder) |
| 6 | aantal combo-modi (1–4) |
| 7–10 | combo-modi (LedMode) |
| 11 | aantal single-modi (0xFF = fabrieksinstellingen) |
| 12–15 | single-modi (LedMode) |
| 16 | RSSI van de gekoppelde lamp |
| 17 | firmware major |
| 18 | firmware minor |
| 19 | model-ID |

Flags (byte 5): `0x01` = **niet** in combo · `0x02` gekoppeld · `0x04` peer verbonden · `0x08` = **uit** (bit 0 = aan) · `0x10` dead mode · `0x20` oververhit · `0x40` tweede accu · `0x80` aan tijdens laden.

Batterijdrempels (mV, 5 balkjes): de app gebruikt een tabel per model, bijv. `{3374, 3539, 3607, 3644, 3667}` en `{3000, 3200, 3500, 3800, 4000}`. Onder 2000 mV toont de app niets.

## 5. ModeSet-pakket (0x02 / 0x03), 20 bytes

| Offset | Veld |
|---|---|
| 0 | 0x02 (front) / 0x03 (rear) |
| 1 | 1 = solo, 0 = onderdeel van combo |
| 2 | aantal combo-modi |
| 3 | geselecteerde combo-index |
| 4–7 | 4× combo-modus |
| 8 | aantal single-modi |
| 9 | geselecteerde single-index |
| 10–13 | 4× single-modus |
| 14–19 | 0x00 |

De app zet een lamp aan door dit pakket te sturen, en kiest een modus door de index aan te passen en het pakket opnieuw te sturen.

## 6. LedMode-waarden

0 Femto · 1 Econ · 2 Enduro · 3 Blast · 4 Overdrive · 5–10 Flash1–Flash6 · 11 Pulse · 12 DayTimeFlash1 · 13 DayTimeFlash2 · 14 Off

## 7. Modellen

| ID | Model | Voor/achter |
|---|---|---|
| 6 | KTV Front | voor |
| 7 | KTV Rear | achter |
| 8 | Connect Drive | voor |
| 9 | Super Drive | voor |
| 10 | Mega Drive | voor |
| 11 | Super Drive (Y17) | voor |

## 8. Advertising (manufacturer specific data, eerste entry)

| Offset | Veld |
|---|---|
| 0 | hoog nibble = model-ID, laag nibble = model-ID van de gekoppelde lamp |
| 1–4 | serienummer u32 LE |
| 5 | flags (zelfde bits als status byte 5; `0x08` = uit) |
| 6–9 | serienummer van de gekoppelde lamp u32 LE |

## 9. Open punten om eerst te testen

1. Kan een Karoo-extension een GATT-server openen, en abonneert de lamp zich daarop? Dit is het grootste risico.
2. Accepteert de lamp een tweede centrale, of moet de telefoon ontkoppeld zijn?
3. Wat doet 0x06 RunFront precies, en werkt 0x08 NextModeFront zonder eerst een ModeSet?
4. Krijgen we de RearStatus door via de voorlamp als ze als combo gekoppeld zijn?
