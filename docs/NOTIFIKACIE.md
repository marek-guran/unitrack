# 🔔 Notifikačný systém

Tento dokument popisuje, ako fungujú notifikácie v UniTracku — aké kanály existujú, ako sa plánujú, ako sa detekujú zmeny známok, neprítomnosti a zrušené hodiny, a ako to celé funguje aj v offline režime.

---

## Prehľad

Notifikácie obsluhuje jediná trieda `NextClassAlarmReceiver` (v balíku `notification/`), ktorá je zaregistrovaná ako `BroadcastReceiver` v `AndroidManifest.xml`. Reaguje na dva typy akcií:

| Akcia | Predvolený interval | Priorita | Čo robí |
|---|---|---|---|
| `ACTION_NEXT_CLASS` | Každé 2 minúty (konfigurovateľné) | Tichá (nízka) | Aktualizuje „živú" notifikáciu s aktuálnou/ďalšou hodinou |
| `ACTION_CHECK_CHANGES` | Každých 30 minút (konfigurovateľné) | Vysoká (zvuková) | Kontroluje zmeny známok, neprítomnosti a zrušené hodiny |

Oba intervaly si používateľ môže prispôsobiť v nastaveniach aplikácie a takisto oba kanály môže individuálne zapnúť alebo vypnúť.

---

## Notifikačné kanály

Android 8+ vyžaduje kanály. UniTrack vytvára štyri:

### 1. Rozvrh hodín (`next_class_channel`)
- **Názov:** Rozvrh hodín
- **Priorita:** Nízka (tichá)
- **Typ:** Live Update — priebežná notifikácia s progress barom
- **Obsah:** Aktuálna hodina, ďalšia hodina, prestávka alebo voľno

### 2. Zrušené hodiny (`class_cancelled_channel`)
- **Názov:** Zrušené hodiny
- **Priorita:** Vysoká (zvuk + vibrácie)
- **Obsah:** Keď učiteľ pridá voľný deň, ktorý koliduje s rozvrhom

### 3. Známky (`grades_channel`)
- **Názov:** Známky
- **Priorita:** Vysoká (zvuk + vibrácie)
- **Obsah:** Nová známka, upravená známka alebo odstránená známka

### 4. Neprítomnosť (`absence_channel`)
- **Názov:** Neprítomnosť
- **Priorita:** Vysoká (zvuk + vibrácie)
- **Obsah:** Keď sa študentovi zaznamená nová neprítomnosť na hodine

---

## Živá notifikácia rozvrhu

Toto je hlavná notifikácia, ktorá sa zobrazuje počas školského dňa a priebežne informuje o aktuálnom stave.

### Ako funguje

1. V nastavenom intervale (predvolene každé 2 minúty) sa spustí `handleNextClass()`
2. Načíta sa rozvrh pre aktuálny deň (zohľadňujú sa párne/nepárne týždne a aktuálny semester)
3. Odfiltrujú sa hodiny, ktoré kolidujú s voľnými dňami
4. Podľa aktuálneho času sa určí stav:

| Stav | Správa v notifikácii | Príklad |
|---|---|---|
| Pred prvou hodinou (v rámci okna) | „Vyučovanie začína čoskoro" | „Matematika 1 (A402) • Štart 08:00" |
| Prebieha hodina | „{predmet} ({učebňa})" | „Matematika 1 (A402)" |
| Prestávka (≤ 30 min) | „Prestávka" + „Ďalej: {predmet}" | „Ďalej: Fyzika • Štart 10:00" |
| Dlhšia pauza (> 30 min) | „Voľno" + „Ďalej: {predmet}" | — |
| Po poslednej hodine | Notifikácia sa automaticky zruší | — |
| Žiadne hodiny | Notifikácia sa nezobrazí | — |

### Konfigurovateľné nastavenia

V nastaveniach aplikácie si používateľ môže upraviť:
- **Interval živej aktualizácie** — 1, 2, 5, 10 alebo 15 minút (predvolene 2 min)
- **Minúty pred prvou hodinou** — 15, 30, 45, 60 alebo 90 minút (predvolene 30 min) — notifikácia sa zobrazí až keď zostáva menej ako nastavený počet minút do začiatku vyučovania
- **Zobrazenie učebne** — zapnuté/vypnuté (predvolene zapnuté)
- **Zobrazenie nasledujúcej hodiny** — zapnuté/vypnuté (predvolene zapnuté)

### Android 16 ProgressStyle (segmentovaný progress bar)

Na zariadeniach s Android 16 (API 36) notifikácia využíva natívny `Notification.ProgressStyle` so segmentmi. Každý segment reprezentuje jednu hodinu alebo prestávku a má priradenú farbu:

- **Hodina** — oranžová (svetlá v tmavom režime)
- **Prestávka** — zelená (svetlá v tmavom režime)

Progress ukazuje, koľko z celkového školského dňa už uplynulo. Na starších verziách Android sa zobrazuje klasický nesegmentovaný progress bar.

### Semester-aware filtrovanie

Rozvrh sa filtruje podľa aktuálneho semestra. Ak má predmet nastavený semester (zimný/letný), hodiny sa zobrazia len ak sa aktuálny semester zhoduje so semestrom predmetu. Predmety s nastavením „obidva" sa zobrazujú vždy.

### Detekcia parity týždňa

Rozvrh môže mať hodiny pre párny týždeň, nepárny týždeň, alebo každý týždeň. `NextClassAlarmReceiver` zisťuje aktuálnu paritu podľa ISO kalendára:

```kotlin
val weekNumber = LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
val isOddWeek = weekNumber % 2 != 0
```

Hodiny sa filtrujú podľa tejto parity — zobrazujú sa len tie, ktoré zodpovedajú aktuálnemu týždňu (alebo majú paritu `"every"`).

---

## Detekcia zmien známok

V nastavenom intervale (predvolene každých 30 minút) sa kontroluje, či sa zmenili známky študenta.

### Mechanizmus

1. Načíta sa aktuálny „snapshot" všetkých známok zo všetkých predmetov, rokov a semestrov
2. Porovná sa s predchádzajúcim snapshotom (uloženým v `SharedPreferences` pod kľúčom `grade_snapshot`)
3. Rozdiely sa identifikujú:

| Typ zmeny | Správa | Príklad |
|---|---|---|
| Nová známka | „Nová známka {grade} z {predmet}" | „Nová známka B z Matematika 1" |
| Upravená známka | „Upravená známka {grade} z {predmet}" | „Upravená známka A z Fyzika" |
| Odstránená známka | „Odstránená známka z {predmet}" | „Odstránená známka z Informatika" |

4. Aktualizovaný snapshot sa uloží

### Formát snapshotu

Snapshot je reťazec kľúč-hodnota pármi, kde kľúče sú `{year}/{semester}/{subjectKey}/{markKey}` a hodnoty sú `{grade}|{name}`:

```
2025_2026/zimny/mat1/abc123=B|Test 1;2025_2026/zimny/fyz1/def456=A|Skúška
```

---

## Detekcia neprítomnosti

Nová funkcia, ktorá kontroluje zmeny v dochádzke študenta. Prebieha súčasne s kontrolou známok.

### Mechanizmus

1. Načítajú sa všetky záznamy dochádzky pre prihláseného študenta
2. Porovnajú sa s predchádzajúcim snapshotom (`attendance_snapshot`)
3. Identifikujú sa nové neprítomnosti — záznamy kde `absent = true` a predtým buď neexistovali, alebo mali `absent = false`
4. Pre každú novú neprítomnosť sa vygeneruje notifikácia s názvom predmetu

Táto funkcia funguje len v online režime (rovnako ako detekcia známok a zrušených hodín).

---

## Detekcia zrušených hodín

V rovnakom intervale ako kontrola známok sa kontrolujú aj voľné dni.

### Mechanizmus

1. Načítajú sa všetky voľné dni zo všetkých učiteľov
2. Porovnajú sa s predchádzajúcim snapshotom (`daysoff_snapshot`)
3. Pre každý nový voľný deň sa skontroluje, či koliduje s rozvrhom na aktuálny deň
4. Ak áno, vygeneruje sa notifikácia:

```
„Hodina zrušená: {predmet} na {dátum}"
```

Notifikácia o zrušení hodiny sa zobrazí maximálne raz denne (ochrana proti duplicitám).

### Kontrola kolízie

Voľný deň koliduje s rozvrhovou hodinou ak:
- Deň v rozvrhu padne na dátum voľného dňa (alebo do rozsahu dátumov)
- Ak voľný deň má časový rozsah, kontroluje sa aj prekrytie časov
- Zohľadňuje sa parita týždňa a aktuálny semester

---

## Offline podpora

Živá notifikácia rozvrhu funguje aj v offline režime. Namiesto Firebase sa dáta čítajú z `LocalDatabase`:

- Rozvrh: `LocalDatabase.getTimetableEntries()`
- Voľné dni: `LocalDatabase.getDaysOff()`

UID používateľa v offline režime je konštanta `OfflineMode.LOCAL_USER_UID` (`"local_user"`).

Kontrola zmien známok, neprítomnosti a zrušených hodín (`handleChangesCheck`) je v offline režime vypnutá — prebieha len v online režime, kde sú zmeny vykonávané inými používateľmi.

---

## Plánovanie alarmov

### AlarmManager

Notifikácie sa plánujú cez `AlarmManager.setRepeating()`:

| Alarm | Request code | Predvolený interval | Oneskorenie po spustení |
|---|---|---|---|
| Next Class | 2001 | 2 minúty (konfigurovateľné: 1–15 min) | 5 sekúnd |
| Changes Check | 2002 | 30 minút (konfigurovateľné: 15–120 min) | 10 sekúnd |

### Inicializácia

Alarmy sa nastavujú v `MainActivity.onCreate()`:

```kotlin
NextClassAlarmReceiver.createNotificationChannels(this)
NextClassAlarmReceiver.triggerNextClassCheck(this)   // Okamžitá kontrola
NextClassAlarmReceiver.scheduleNextClass(this)       // Podľa nastaveného intervalu
NextClassAlarmReceiver.scheduleChangesCheck(this)    // Podľa nastaveného intervalu
```

### Zapínanie a vypínanie

Každý typ notifikácií (živá aktualizácia aj kontrola zmien) sa dá individuálne zapnúť alebo vypnúť v nastaveniach. Pri vypnutí sa príslušný alarm zruší cez `AlarmManager.cancel()`.

### Prežitie reštartu

`NextClassAlarmReceiver` je zaregistrovaný v manifeste s `RECEIVE_BOOT_COMPLETED` povolením, čo zabezpečuje, že alarmy sa nanovo naplánujú aj po reštarte zariadenia.

---

## Oprávnenia

| Oprávnenie | Prečo |
|---|---|
| `POST_NOTIFICATIONS` | Zobrazovanie notifikácií (povinné od Android 13) |
| `POST_PROMOTED_NOTIFICATIONS` | Rozšírené Live Update notifikácie (Android 16) |
| `FOREGROUND_SERVICE` | Beh notifikačnej služby na pozadí |
| `RECEIVE_BOOT_COMPLETED` | Plánovanie alarmov po reštarte |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Výnimka z optimalizácie batérie pre spoľahlivé doručovanie |

Pri Android 13+ sa oprávnenie `POST_NOTIFICATIONS` vyžiada runtime dialógom v `MainActivity`. V nastaveniach je k dispozícii tlačidlo na vypnutie optimalizácie batérie, čo zabraňuje systému obmedziť doručovanie notifikácií.

---

[← Späť na README](../README.md)
