# 🧭 Navigácia a UI komponenty

Tento dokument popisuje, ako funguje navigácia v aplikácii, čo robí vlastný PillNavigationBar, ako sa menia taby podľa role používateľa a aké fragmenty a adaptéry tvoria UI.

---

## Navigation Component

UniTrack používa **Android Jetpack Navigation Component** s jedným navigačným grafom `mobile_navigation.xml`.

### Navigačný graf

```
navigation_home (štart)
├── → subjectDetailFragment (slide animácia)
│      argumenty: subjectName, subjectKey
│
navigation_dashboard (fade)
navigation_timetable (fade)
navigation_settings (fade)
navigation_students (fade)
navigation_subjects (fade)
```

Domovská obrazovka (`navigation_home`) je štartovacia destinácia. Z nej vedie akcia `action_home_to_subject_detail` na detail predmetu — s argumentmi `subjectName` a `subjectKey`. Táto navigácia používa slide animáciu (zľava/sprava), kým všetky ostatné prechody používajú fade (200ms).

### Fragment hosting

`MainActivity` obsahuje `NavHostFragment` v layoute (`nav_host_fragment_activity_main`), ktorý hostí všetky fragmenty. Navigácia sa ovláda programaticky cez `NavController` — nie cez štandardný `setupWithNavController()`, ale cez vlastný `PillNavigationBar`.

---

## PillNavigationBar

Toto je vlastný `View` komponent, ktorý nahrádza štandardný `BottomNavigationView`. Je to srdce navigácie — animovaná „pilulka" s glass-morphism efektom.

### Dva režimy zobrazenia

| Zariadenie | Režim | Popis |
|---|---|---|
| **Telefón** (< 600dp) | Ikony | Zobrazuje len ikony, vybraná ikona je zväčšená |
| **Tablet** (≥ 600dp) | Text | Zobrazuje textové popisky s adaptívnou veľkosťou |

### Vizuálne vlastnosti

- **Glass-morphism** — sklenený translúcentný efekt s dynamickou priehľadnosťou pri interakcii
- **Pill efekt** — priesvitná „pilulka" s tieňom sa plynulo posúva za vybranou položkou
- **Magnifikácia** — položky v blízkosti prsta sa zväčšujú (až na 1.35×) s hladkým gradientom v rámci 90dp polomeru
- **Animácie** — plynulý presun pilulky s `DecelerateInterpolator` (350ms, bez bounce efektu)
- **Farebné miešanie** — farba textu/ikony sa plynulo mení podľa prekrytia s pilulkou
- **Okrajová deformácia** — položky blízko okraja sa stláčajú (squish efekt, max 55%)
- **Sklenené odlesky** — jemné farebné záblesky na bokoch pilulky simulujúce refrakciu svetla
- **Žiara** — halo efekt okolo pilulky, ktorý sa stráca pri interakcii
- **Vstupná animácia** — pri prvom zobrazení sa lišta vysunie zdola (telefón) alebo zhora (tablet)
- **Tieň** — drop shadow s 14dp rozmazaním a 4dp odsadením

### API

```kotlin
// Nastavenie položiek
pillNav.setItems(labels: List<String>)       // Textový režim
pillNav.setIconItems(icons: List<Drawable>)  // Ikonový režim

// Callbacks
pillNav.onItemSelected = { index -> ... }    // Kliknutie na novú položku
pillNav.onItemReselected = { index -> ... }  // Opätovné kliknutie na vybranú

// Programová zmena
pillNav.setSelectedIndex(index: Int)         // Bez triggerovania callbacku
```

---

## Role-based navigácia

Navigačná lišta sa dynamicky mení podľa role používateľa a režimu:

### Online režim — bežný učiteľ/študent
```
[ Domov ] [ Rozvrh ] [ Nastavenia ]
```

### Online režim — admin
```
[ Domov ] [ Rozvrh ] [ Účty ] [ Predmety ] [ Nastavenia ]
```

### Offline režim (vždy)
```
[ Domov ] [ Rozvrh ] [ Študenti ] [ Predmety ] [ Nastavenia ]
```

### Ako prebieha detekcia

1. Pri spustení sa najprv zobrazí navigácia bez admin tabov
2. `checkAdminAndRebuildNav()` asynchrónne preverí Firebase cestu `admins/{uid}`
3. Ak snapshot existuje → `buildNavigation()` sa zavolá znova s `includeAdminTabs = true`
4. Navigácia sa plynulo prebuduje s novými tabmi

V offline režime sa admin taby (s názvom „Študenti") zobrazujú vždy, pretože lokálna správa vždy vyžaduje prístup k študentom a predmetom.

---

## Prehľad fragmentov

### HomeFragment
**Účel:** Hlavná obrazovka — prehľad predmetov učiteľa/študenta.

- **Učiteľ/Admin:** Zobrazuje karty predmetov s prehľadom (počet študentov, priemer, dochádzka)
- **Študent:** Zobrazuje zapísané predmety a ich známky
- Filtre: akademický rok (Spinner) + semester (Spinner)
- Kliknutie na predmet → navigácia na `SubjectDetailFragment`

### SubjectDetailFragment
**Účel:** Detail predmetu — kompletná správa známok a dochádzky pre konkrétny predmet.

- **ViewPager2** s `SubjectDetailPagerAdapter` — tri záložky: Známky, Dochádzka, Študenti
- Zoznam študentov s priemermi a dochádzkou
- Dialógy: pridanie/úprava známky, zobrazenie všetkých známok, správa dochádzky
- Navrhovaná ďalšia známka na základe výkonu
- Spustenie hromadného hodnotenia (`BulkGradeActivity`) pre rýchle zadanie známok celej skupine

### TimetableFragment
**Účel:** Týždenný rozvrh hodín — najkomplexnejšia obrazovka aplikácie.

- **ViewPager2** — swipe navigácia medzi dňami s 1:1 peek náhľadom
- **Chip navigátor dní** — horizontálna lišta s animovanými čipmi (DayChipAdapter), dnešný deň označený ako „Dnes"
- **Stavové karty hodín** — štyri vizuálne stavy (PAST, CURRENT, NEXT, FUTURE) podľa aktuálneho času (ScheduleAdapter)
- **Živý progress bar** — prebieha hodina zobrazuje priebeh v reálnom čase (aktualizácia každých 5 sekúnd)
- **Glassmorfický box** — aktuálny čas alebo „Späť na Dnes" s animovanou zmenou šírky
- **Hlavička** — číslo a parita týždňa, pozdrav podľa času dňa
- Filtre: semester-aware filtrovanie, parita týždňa (nepárny/párny)
- Detekcia voľných dní — prečiarknutie kolidujúcich hodín
- Admin: pridávanie, mazanie a kompletná úprava rozvrhových záznamov
- Učiteľ: úprava učebne a poznámky existujúcich záznamov
- Učiteľ/Admin: správa voľných dní (dialógy s date range a time range)
- Detekcia časových konfliktov
- Učebňa zobrazená v dedikovanom „pill" odznaku na pravej strane karty predmetu
- Nekonečný scroll — lazy-loading ďalších týždňov (max ~2 roky)
- Prázdny stav — animovaný emoji pre dni bez hodín
- Podrobná dokumentácia: [Rozvrh hodín](ROZVRH.md)

### StudentsManageFragment
**Účel:** Správa študentov (offline) alebo účtov (online admin).

- **Online:** Filter podľa role (Všetci / Študenti / Učitelia / Admini), editácia emailu, priradenie rolí
- **Offline:** Pridávanie/mazanie študentov, správa zápisov predmetov podľa semestra
- Vyhľadávanie podľa mena/emailu

### SubjectsManageFragment
**Účel:** Správa predmetov.

- Vytváranie, editácia názvu, priradenie učiteľa
- Nastavenie semestra (zimný/letný/obidva) s automatickou migráciou
- Filtrovanie podľa názvu/učiteľa

### SettingsFragment
**Účel:** Nastavenia aplikácie.

- Tmavý režim (switch) — ukladá sa do SharedPreferences, prepnutie spúšťa paint-drop animáciu s kruhovým reveal efektom
- **Nastavenia notifikácií:**
  - Zapnutie/vypnutie živej aktualizácie rozvrhu
  - Interval živej aktualizácie (1, 2, 5, 10, 15 minút)
  - Minúty pred prvou hodinou (15, 30, 45, 60, 90 minút)
  - Zapnutie/vypnutie kontroly zmien (známky, neprítomnosti, zrušené hodiny)
  - Interval kontroly zmien (15, 30, 60, 120 minút)
  - Zobrazenie učebne v notifikácii
  - Zobrazenie nasledujúcej hodiny v notifikácii
  - Optimalizácia batérie — tlačidlo na vypnutie systémovej optimalizácie
- **Migrácia databázy** — manuálne spustenie migrácie štruktúry dát (online aj offline)
- Online: správa akademických rokov, reset hesla, odhlásenie
- Offline: export/import databázy, vytváranie školských rokov, nastavenie mena učiteľa, reset aplikácie

### DashboardFragment
**Účel:** Dashboard obrazovka (momentálne placeholder/uvítacia obrazovka).

- Používa `DashboardViewModel` s LiveData pre text
- Vstupná animácia (staggered fade)

### LoginActivity
**Účel:** Prihlasovacie okno.

- Email + heslo cez Firebase Auth
- Validácia formulára cez `LoginViewModel` + `LoginFormState`
- Tlačidlo „Lokálny režim" pre offline
- Vstupné animácie (staggered fade + bounce)
- Klávesnica „Done" spustí prihlásenie

### SplashActivity
**Účel:** Animovaná vstupná obrazovka (launcher).

- Zobrazí logo a názov aplikácie s slide-up animáciou zdola nahor (800ms, DecelerateInterpolator)
- Aplikuje tmavý režim zo SharedPreferences
- Po 2 sekundách presmeruje na `MainActivity` s fade prechodom
- Edge-to-edge zobrazenie bez ActionBaru

### BulkGradeActivity
**Účel:** Hromadné zadávanie známok viacerým študentom naraz.

- RecyclerView so zoznamom študentov a Material chip komponentmi pre výber známky (A–Fx)
- Spoločný dátum a názov hodnotenia pre celú skupinu
- Voliteľné poznámky pre jednotlivých študentov s plynulou expand/collapse animáciou (350ms)
- Vyhľadávanie a filtrovanie študentov
- Podpora online aj offline režimu

### QrAttendanceActivity
**Účel:** QR kód dochádzka — strana učiteľa.

- Generovanie rotujúceho QR kódu (nový kód po každom úspešnom skene)
- Zobrazenie QR kódu na obrazovke (512×512 px bitmap cez ZXing QRCodeWriter)
- Monitorovanie skenov v reálnom čase cez Firebase listenery (`qr_last_scan`, `qr_fail`)
- Log skenov s filtrovaním (Všetci / Prítomní / Chyby)
- Relatívne zobrazenie času skenov (Teraz, 30s, 5 min...)
- Po ukončení relácie uloženie dochádzky a vyčistenie dočasných Firebase uzlov
- Obrazovka zostáva zapnutá počas relácie (wake lock)

### QrScannerActivity
**Účel:** QR kód skener — strana študenta.

- Skenovanie QR kódu fotoaparátom cez ZXing `DecoratedBarcodeView`
- Validácia formátu QR kódu (`UNITRACK|{rok}|{semester}|{predmet}|{kód}`)
- Overenie zápisu študenta v predmete
- Atomické overenie kódu cez Firebase transakciu (ochrana pred replay útokmi)
- Zápis do `qr_last_scan` s UID prihláseného používateľa
- Vizuálna spätná väzba (úspech/chyba) s animáciou

---

## RecyclerView adaptéry

Všetky adaptéry sú v `data/model/` a obsluhujú zoznamy v rôznych fragmentoch:

| Adaptér | Kde sa používa | Zobrazuje |
|---|---|---|
| `TeacherSubjectSummaryAdapter` | HomeFragment | Karty predmetov s prehľadom |
| `SubjectAdapter` | HomeFragment (študent) | Zoznam predmetov študenta |
| `TeacherStudentAdapter` | SubjectDetailFragment | Študenti v predmete |
| `MarkAdapter` | SubjectDetailFragment | Zoznam známok študenta |
| `StudentMarkAdapter` | StudentMarksDialogFragment | Známky v dialógu |
| `AttendanceAdapter` | SubjectDetailFragment | Dochádzka detail |
| `AttendanceStudentAdapter` | SubjectDetailFragment | Dochádzka v zozname študentov |
| `AttendanceTableAdapter` | SubjectDetailFragment | Tabuľková dochádzka |
| `SubjectAdapterAdmin` | SubjectsManageFragment / Settings | Predmety pre admina |
| `EnrollStudentAdapter` | StudentsManageFragment | Zápis predmetov pre študenta |
| `ScheduleAdapter` | TimetableFragment | Stavové karty hodín (PAST/CURRENT/NEXT/FUTURE) s progress barom |
| `DayChipAdapter` | TimetableFragment | Animovaný chip navigátor dní |
| `TimetablePagerAdapter` | TimetableFragment | ViewPager2 stránky dní s prázdnym stavom |

---

## Animácie a prechody

### Fragment prechody

| Typ | Kde | Animácia |
|---|---|---|
| Hlavné taby | Medzi Home, Timetable, Settings... | Fade (200ms) |
| Detail predmetu | Home → SubjectDetail | Slide in/out (left/right) |
| Navigačná lišta | Vstup pri spustení | Slide + fade (600ms, decelerate) |
| Splash → Main | Po 2 sekundách | Fade in/out |

### Dialógy

Aplikácia hojne využíva `AlertDialog` s vlastnými layoutmi pre:
- Pridanie/úprava známky
- Zobrazenie všetkých známok
- Správa dochádzky
- Pridanie voľného dňa
- Zápis predmetov
- Editácia používateľa
- Potvrdenie resetu aplikácie

---

## Monitorovanie internetu

`MainActivity` spúšťa periodickú kontrolu pripojenia každých 10 sekúnd (len v online režime). Ak nie je internet:
- Zobrazí sa `AlertDialog` s možnosťou otvoriť Wi-Fi nastavenia
- Dialog je `setCancelable(false)` — používateľ ho musí explicitne zavrieť
- Po obnovení pripojenia sa dialog automaticky zatvára

---

[← Späť na README](../README.md)
