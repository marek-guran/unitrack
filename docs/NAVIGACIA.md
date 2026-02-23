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

Toto je vlastný `View` komponent, ktorý nahrádza štandardný `BottomNavigationView`. Je to srdce navigácie — animovaná „pilulka" so skleneným efektom.

### Dva režimy zobrazenia

| Zariadenie | Režim | Popis |
|---|---|---|
| **Telefón** (< 600dp) | Ikony | Zobrazuje len ikony, vybraná ikona je zväčšená |
| **Tablet** (≥ 600dp) | Text | Zobrazuje textové popisky s adaptívnou veľkosťou |

### Vizuálne vlastnosti

- **Pill efekt** — priesvitná „pilulka" s rozmazaným pozadím (BlurView) sa plynulo posúva za vybranou položkou
- **Animácie** — plynulý presun pilulky s `DecelerateInterpolator` (bez bounce efektu)
- **Farebné miešanie** — farba textu/ikony sa plynulo mení pri presúvaní pilulky
- **Okrajová deformácia** — položky blízko okraja sa mierne zmenšujú
- **Vstupná animácia** — pri prvom zobrazení sa lišta vysunie zdola (telefón) alebo zhora (tablet)

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

- Zoznam študentov s priemermi a dochádzkou
- Dialógy: pridanie/úprava známky, zobrazenie všetkých známok, správa dochádzky
- Navrhovaná ďalšia známka na základe výkonu

### TimetableFragment
**Účel:** Týždenný rozvrh hodín.

- Filtre: Všetky / Dnes / Nepárny týždeň / Párny týždeň
- Zobrazenie čísla a parity aktuálneho týždňa (ISO kalendár)
- Učiteľ: pridávanie/správa voľných dní (dialógy s date range a time range)
- Detekcia časových konfliktov

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

- Tmavý režim (switch) — ukladá sa do SharedPreferences
- Online: správa predmetov pre admina, odhlásenie, zoznam adminov
- Offline: export/import databázy, vytváranie školských rokov, reset aplikácie

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
- Klávesnica „Done" triggeruje prihlásenie

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

---

## Animácie a prechody

### Fragment prechody

| Typ | Kde | Animácia |
|---|---|---|
| Hlavné taby | Medzi Home, Timetable, Settings... | Fade (200ms) |
| Detail predmetu | Home → SubjectDetail | Slide in/out (left/right) |
| Navigačná lišta | Vstup pri spustení | Slide + fade (600ms, decelerate) |

### Dialógy

Aplikácia hojne využíva `AlertDialog` s vlastnými layoutmi pre:
- Pridanie/úprava známky
- Zobrazenie všetkých známok
- Správa dochádzky
- Pridanie voľného dňa
- Zápis predmetov
- Editácia používateľa

---

## Monitorovanie internetu

`MainActivity` spúšťa periodickú kontrolu pripojenia každých 10 sekúnd (len v online režime). Ak nie je internet:
- Zobrazí sa `AlertDialog` s možnosťou otvoriť Wi-Fi nastavenia
- Dialog je `setCancelable(false)` — používateľ ho musí explicitne zavrieť
- Po obnovení pripojenia sa dialog automaticky zatvára

---

[← Späť na README](../README.md)
