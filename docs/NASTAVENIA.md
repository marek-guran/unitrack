# ⚙️ Nastavenia aplikácie

Tento dokument podrobne popisuje všetky konfiguračné možnosti v obrazovke nastavení — od vzhľadu cez notifikácie až po správu účtu a dát.

---

## Prehľad

Nastavenia sú prístupné cez navigačnú lištu (posledná záložka). Obsah sa dynamicky mení podľa režimu (online/offline) a role používateľa (admin/učiteľ/študent).

---

## Vzhľad

### Tmavý režim

- **Typ ovládania:** Switch (zapnuté/vypnuté)
- **Uloženie:** `SharedPreferences` (kľúč `dark_mode`)
- **Aplikácia:** Okamžitá — `AppCompatDelegate.setDefaultNightMode()` sa volá priamo pri zmene
- **Inicializácia:** Tmavý režim sa načíta a aplikuje už v `UniTrackApplication.onCreate()`, čo zaručuje, že aj `SplashActivity` sa zobrazí v správnej téme

---

## Notifikácie

Nastavenia notifikácií umožňujú detailne prispôsobiť správanie celého notifikačného systému.

### Živá aktualizácia rozvrhu

| Nastavenie | Popis | Možnosti | Predvolené |
|---|---|---|---|
| **Zapnúť/Vypnúť** | Hlavný prepínač živej notifikácie rozvrhu | Switch | Zapnuté |
| **Interval aktualizácie** | Ako často sa kontroluje a aktualizuje notifikácia | 1, 2, 5, 10, 15 min | 2 min |
| **Minúty pred prvou hodinou** | Koľko minút pred prvou hodinou sa má notifikácia zobraziť | 15, 30, 45, 60, 90 min | 30 min |
| **Zobrazenie učebne** | Či sa má v notifikácii zobraziť názov učebne | Switch | Zapnuté |
| **Zobrazenie nasledujúcej hodiny** | Či sa má v notifikácii zobraziť informácia o ďalšej hodine | Switch | Zapnuté |

### Kontrola zmien

| Nastavenie | Popis | Možnosti | Predvolené |
|---|---|---|---|
| **Zapnúť/Vypnúť** | Hlavný prepínač kontroly zmien (známky, neprítomnosti, zrušené hodiny) | Switch | Zapnuté |
| **Interval kontroly** | Ako často sa kontrolujú zmeny na serveri | 15, 30, 60, 120 min | 30 min |

### Optimalizácia batérie

Tlačidlo „Vypnúť optimalizáciu batérie" otvorí systémový dialóg na povolenie výnimky z batériovej optimalizácie. Toto zabezpečí, že systém nebude obmedziť doručovanie notifikácií na pozadí. Ak je výnimka už povolená, zobrazí sa informatívna správa.

### Technické detaily

Pri každej zmene nastavení notifikácií sa automaticky volá `rescheduleNotifications()`, čo zruší existujúce alarmy a naplánuje nové s aktualizovanými intervalmi. Pri vypnutí kanálu sa príslušný alarm úplne zruší.

---

## Správa účtu

### Online režim

| Funkcia | Popis |
|---|---|
| **Reset hesla** | Odošle email na obnovu hesla na adresu prihláseného používateľa cez Firebase Auth |
| **Odhlásenie** | Odhlási používateľa cez `FirebaseAuth.signOut()` a presmeruje na prihlasovaciu obrazovku |

### Offline režim

| Funkcia | Popis |
|---|---|
| **Reset aplikácie** | Po potvrdení vymaže celú lokálnu databázu, resetuje offline režim a presmeruje na prihlasovaciu obrazovku |

---

## Správa dát (offline)

Tieto funkcie sú dostupné len v offline režime:

### Export a import databázy

- **Export** — uloží celú lokálnu databázu ako JSON súbor (`unitrack_backup.json`)
- **Import** — načíta databázu zo JSON súboru a nahradí existujúce dáta

Podrobnosti sú v dokumente [Tlač a export dát](TLAC_A_EXPORT.md).

### Meno učiteľa

V offline režime si učiteľ môže nastaviť svoje meno, ktoré sa používa v PDF reportoch a v zobrazení rozvrhu. Meno sa ukladá dvojito — do `SharedPreferences` (pre rýchly prístup) aj do lokálnej databázy (cesta `settings/teacher_name`), takže sa zachová pri exporte a importe.

### Vytváranie školských rokov

Administrátor môže vytvárať nové akademické roky. Kľúč používa formát s podčiarkovníkom (`2025_2026`), zobrazený názov používa lomku (`2025/2026`).

---

## Admin funkcie (online)

Tieto funkcie sa zobrazujú len prihlásenému adminovi:

### Správa akademických rokov

Rovnaká funkcionalita ako v offline režime — vytváranie nových školských rokov.

> Správa používateľov a predmetov bola v novšej verzii presunutá z nastavení do samostatných navigačných záložiek „Účty" a „Predmety", ktoré sa zobrazia len adminovi.

---

## Uloženie nastavení

Všetky nastavenia sa ukladajú v `SharedPreferences` pod názvom `app_settings`:

| Kľúč | Typ | Popis |
|---|---|---|
| `dark_mode` | Boolean | Stav tmavého režimu |
| `offline_mode` | Boolean | Či je aplikácia v offline režime |
| `notif_enabled_live` | Boolean | Zapnutá živá aktualizácia rozvrhu |
| `notif_interval_live` | Int | Interval živej aktualizácie (v minútach) |
| `notif_minutes_before` | Int | Minúty pred prvou hodinou |
| `notif_enabled_changes` | Boolean | Zapnutá kontrola zmien |
| `notif_interval_changes` | Int | Interval kontroly zmien (v minútach) |
| `notif_show_classroom` | Boolean | Zobrazenie učebne v notifikácii |
| `notif_show_upcoming` | Boolean | Zobrazenie nasledujúcej hodiny |
| `teacher_name` | String | Meno učiteľa (offline) |
| `semester` | String | Aktuálne vybraný semester |

---

[← Späť na README](../README.md)
