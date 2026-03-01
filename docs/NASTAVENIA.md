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
- **Aplikácia:** Okamžitá — `AppCompatDelegate.setDefaultNightMode()` sa volá priamo pri zmene, prepnutie spúšťa trojfázovú paint-drop animáciu (kvapka padá → splash efekt → kruhový reveal novej témy)
- **Inicializácia:** Tmavý režim sa načíta a aplikuje už v `UniTrackApplication.onCreate()`, čo zaručuje, že aj `SplashActivity` sa zobrazí v správnej téme
- **Stavový riadok:** Ikony stavového riadku sa automaticky aktualizujú podľa zvolenej témy pomocou `WindowInsetsControllerCompat.isAppearanceLightStatusBars`

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

### Konzultačné hodiny

| Nastavenie | Popis | Možnosti | Predvolené |
|---|---|---|---|
| **Zapnúť/Vypnúť** | Hlavný prepínač pripomienok konzultačných hodín | Switch | Zapnuté |
| **Minúty pred konzultáciou** | Koľko minút pred konzultáciou sa má pripomienka zobraziť | Konfigurovateľné | 10 min |

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

### Zmena role používateľa

V záložke **Účty** môže admin zmeniť rolu používateľa (študent ↔ učiteľ). Zmena role:
- Prebieha atomicky cez Firebase `updateChildren()`
- Pri povýšení na učiteľa sa dáta študenta zachovajú (predmety, školské roky, konzultácie)
- Pri degradácii na študenta sa aktualizuje len meno a email — existujúce dáta zostávajú netknuté
- Dotknutý používateľ uvidí zmenu okamžite v reálnom čase vďaka `ValueEventListener` v `MainActivity`

### Schvaľovanie registrácií

Používatelia, ktorí sa zaregistrujú cez prihlasovaciu obrazovku, sa pridajú do stavu **„Čaká na schválenie"** (`pending_users/{uid}`). V záložke **Účty** admin vidí čakajúcich používateľov cez filter chip „Čaká na schválenie" a môže:

- **Schváliť ako študenta** — atomicky presunie z `pending_users/` do `students/` s priradením aktuálneho školského roka
- **Schváliť ako učiteľa** — atomicky presunie z `pending_users/` do `teachers/`
- **Odmietnuť** — úplne odstráni používateľa z databázy aj z Firebase Authentication. Odmietnutý používateľ sa automaticky odhlási a môže sa znova zaregistrovať s rovnakou e-mailovou adresou.

Čakajúci používateľ vidí celostránkovú čakaciu obrazovku (`PendingApprovalActivity`) s logom aplikácie a informatívnou správou. Real-time listener automaticky detekuje zmenu stavu (schválenie alebo odmietnutie) a presmeruje používateľa.

### Nový semester

Tlačidlo pre spustenie samostatnej obrazovky (`NewSemesterActivity`) na vytvorenie nového školského roka/semestra s tromi záložkami:
- **Nastavenia** — zadanie názvu roka, kopírovanie predmetov z existujúceho roka
- **Predmety** — výber predmetov s vyhľadávaním a filtrami
- **Študenti** — výber študentov s vyhľadávaním a filtrami

> Správa používateľov a predmetov bola v novšej verzii presunutá z nastavení do samostatných navigačných záložiek „Účty" a „Predmety", ktoré sa zobrazia len adminovi.

---

## Migrácia databázy

V nastaveniach je k dispozícii tlačidlo **„Migrovať databázu"**, ktoré spustí migráciu štruktúry dát. Migrácia je dostupná v online aj offline režime a rieši:

- **Globálne predmety → per-year** — presun predmetov z globálneho uzla do štruktúry podľa školských rokov
- **Per-year študenti → globálna štruktúra** — zlúčenie študentov do jednej kolekcie

Po dokončení sa zobrazí prehľad vykonaných zmien. Ak nie sú potrebné žiadne migrácie, aplikácia to oznámi. Podrobná dokumentácia je v [Migrácia databázy](MIGRACIA.md).

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
| `notif_enabled_consultation` | Boolean | Zapnuté pripomienky konzultačných hodín |
| `notif_consultation_minutes_before` | Int | Minúty pred konzultáciou pre pripomienku |
| `teacher_name` | String | Meno učiteľa (offline) |
| `semester` | String | Aktuálne vybraný semester |

---

[← Späť na README](../README.md)
