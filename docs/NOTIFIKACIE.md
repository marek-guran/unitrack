# 🔔 Notifikačný systém

Tento dokument popisuje, ako fungujú notifikácie v UniTracku — aké kanály existujú, ako sa plánujú, ako sa detegujú zmeny známok a zrušené hodiny, a ako to celé funguje aj v offline režime.

---

## Prehľad

Notifikácie obsluhuje jediná trieda `NextClassAlarmReceiver` (v balíku `notification/`), ktorá je zaregistrovaná ako `BroadcastReceiver` v `AndroidManifest.xml`. Reaguje na dva typy akcií:

| Akcia | Interval | Priorita | Čo robí |
|---|---|---|---|
| `ACTION_NEXT_CLASS` | Každých 15 minút | Tichá (nízka) | Aktualizuje „živú" notifikáciu s aktuálnou/ďalšou hodinou |
| `ACTION_CHECK_CHANGES` | Každých 30 minút | Vysoká (zvuková) | Kontroluje zmeny známok a zrušené hodiny |

---

## Notifikačné kanály

Android 8+ vyžaduje kanály. UniTrack vytvára tri:

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

---

## Živá notifikácia rozvrhu

Toto je hlavná notifikácia, ktorá sa zobrazuje počas školského dňa a priebežne informuje o aktuálnom stave.

### Ako funguje

1. Každých 15 minút sa spustí `handleNextClass()`
2. Načíta sa rozvrh pre aktuálny deň (zohľadňujú sa parné/nepárne týždne)
3. Odfiltrujú sa hodiny, ktoré kolidujú s voľnými dňami
4. Podľa aktuálneho času sa určí stav:

| Stav | Správa v notifikácii | Príklad |
|---|---|---|
| Prebieha hodina | „Teraz: {predmet}" | „Teraz: Matematika 1" |
| Prestávka | „Prestávka" + „Ďalej: {predmet} o {čas}" | „Ďalej: Fyzika o 10:00" |
| Pred prvou hodinou | „Ďalej: {predmet} o {čas}" | „Ďalej: Informatika o 08:00" |
| Po poslednej hodine | „Voľno" | — |
| Žiadne hodiny | Notifikácia sa nezobrazí | — |

### Progress bar

Notifikácia obsahuje progress bar, ktorý ukazuje priebeh celého školského dňa — od začiatku prvej hodiny po koniec poslednej.

### Detekcia parity týždňa

Rozvrh môže mať hodiny pre párny týždeň, nepárny týždeň, alebo každý týždeň. `NextClassAlarmReceiver` zisťuje aktuálnu paritu podľa ISO kalendára:

```kotlin
val weekNumber = LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
val isOddWeek = weekNumber % 2 != 0
```

Hodiny sa filtrujú podľa tejto parity — zobrazujú sa len tie, ktoré zodpovedajú aktuálnemu týždňu (alebo majú paritu `"every"`).

---

## Detekcia zmien známok

Každých 30 minút sa kontroluje, či sa zmenili známky študenta.

### Mechanizmus

1. Načíta sa aktuálny „snapshot" všetkých známok zo všetkých predmetov
2. Porovná sa s predchádzajúcim snapshotom (uloženým v `SharedPreferences` pod kľúčom `grade_snapshot`)
3. Rozdiely sa identifikujú:

| Typ zmeny | Správa | Príklad |
|---|---|---|
| Nová známka | „Nová známka {grade} z {predmet}" | „Nová známka B z Matematika 1" |
| Upravená známka | „Upravená známka {grade} z {predmet}" | „Upravená známka A z Fyzika" |
| Odstránená známka | „Odstránená známka z {predmet}" | „Odstránená známka z Informatika" |

4. Aktualizovaný snapshot sa uloží

### Formát snapshotu

Snapshot je JSON objekt serializovaný do stringu, kde kľúče sú `{subjectKey}_{markId}` a hodnoty sú `{grade}`:

```json
{
  "mat1_abc123": "B",
  "fyz1_def456": "A"
}
```

---

## Detekcia zrušených hodín

Rovnako každých 30 minút sa kontrolujú voľné dni.

### Mechanizmus

1. Načítajú sa všetky voľné dni zo všetkých učiteľov
2. Porovnajú sa s predchádzajúcim snapshotom (`daysoff_snapshot`)
3. Pre každý nový voľný deň sa skontroluje, či koliduje s rozvrhom
4. Ak áno, vygeneruje sa notifikácia:

```
„Hodina zrušená: {predmet} na {dátum}"
```

### Kontrola kolízie

Voľný deň koliduje s rozvrhovou hodinou ak:
- Deň v rozvrhu padne na dátum voľného dňa (alebo do rozsahu dátumov)
- Ak voľný deň má časový rozsah, kontroluje sa aj prekrytie časov

---

## Offline podpora

Celý notifikačný systém funguje aj v offline režime. Namiesto Firebase sa dáta čítajú z `LocalDatabase`:

- Rozvrh: `LocalDatabase.getTimetableEntries()`
- Voľné dni: `LocalDatabase.getDaysOff()`
- Známky: `LocalDatabase.getMarks()`

UID používateľa v offline režime je konštanta `OfflineMode.LOCAL_USER_UID` (`"local_user"`).

---

## Plánovanie alarmov

### AlarmManager

Notifikácie sa plánujú cez `AlarmManager.setRepeating()`:

| Alarm | Request code | Interval | Oneskorenie po spustení |
|---|---|---|---|
| Next Class | 2001 | 15 minút | 5 sekúnd |
| Changes Check | 2002 | 30 minút | 10 sekúnd |

### Inicializácia

Alarmy sa nastavujú v `MainActivity.onCreate()`:

```kotlin
NextClassAlarmReceiver.createNotificationChannels(this)
NextClassAlarmReceiver.triggerNextClassCheck(this)   // Okamžitá kontrola
NextClassAlarmReceiver.scheduleNextClass(this)       // Každých 15 min
NextClassAlarmReceiver.scheduleChangesCheck(this)    // Každých 30 min
```

### Prežitie reštartu

`NextClassAlarmReceiver` je zaregistrovaný v manifeste s `RECEIVE_BOOT_COMPLETED` povolením, čo zabezpečuje, že alarmy sa nanovo naplánujú aj po reštarte zariadenia.

---

## Oprávnenia

| Oprávnenie | Prečo |
|---|---|
| `POST_NOTIFICATIONS` | Zobrazovanie notifikácií (povinné od Android 13) |
| `POST_PROMOTED_NOTIFICATIONS` | Rozšírené notifikácie |
| `FOREGROUND_SERVICE` | Beh notifikačnej služby na pozadí |
| `RECEIVE_BOOT_COMPLETED` | Plánovanie alarmov po reštarte |

Pri Android 13+ sa oprávnenie `POST_NOTIFICATIONS` vyžiada runtime dialógom v `MainActivity`.

---

[← Späť na README](../README.md)
