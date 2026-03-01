# 🏗 Architektúra aplikácie

Tento dokument popisuje, ako je UniTrack navrhnutý z technického hľadiska — aké vzory sa používajú, ako tečú dáta medzi vrstvami a prečo sú niektoré veci riešené práve tak, ako sú.

---

## Prehľad vrstiev

UniTrack sa drží princípov **MVVM** (Model – View – ViewModel), aj keď nie dogmaticky. Niektoré jednoduchšie obrazovky komunikujú s Firebase priamo z Fragmentu, bez ViewModel medzivrstvy — tam, kde by ViewModel zbytočne komplikoval jednoduchú operáciu.

```
┌──────────────────────────────────────────────────┐
│                   UI vrstva                       │
│  (Activity, Fragmenty, Adaptéry, View Binding)   │
├──────────────────────────────────────────────────┤
│               ViewModel vrstva                    │
│  (LiveData, stavová logika, transformácie dát)    │
├──────────────────────────────────────────────────┤
│               Dátová vrstva                       │
│  (Firebase Realtime DB / LocalDatabase)           │
│  (LoginRepository, OfflineMode)                   │
└──────────────────────────────────────────────────┘
```

### UI vrstva

Všetko, čo používateľ vidí. Fragmenty sa starajú o zobrazenie dát, reakcie na kliknutia a volanie Firebase/lokálnej databázy. Layouty sú napojené cez **View Binding** — žiadne `findViewById()` volania.

### ViewModel vrstva

Používa sa tam, kde je to užitočné — napríklad `DashboardViewModel` drží stav dashboardu a `LoginViewModel` rieši validáciu formulára a stav prihlásenia. LiveData zabezpečuje, že UI sa automaticky aktualizuje pri zmene dát.

### Dátová vrstva

Duálny backend: buď Firebase Realtime Database (online), alebo `LocalDatabase` — vlastná JSON databáza uložená v súbore (offline). O tom, ktorý sa používa, rozhoduje `OfflineMode`.

---

## Životný cyklus aplikácie

### Spustenie (cold start)

1. `UniTrackApplication.onCreate()` — inicializácia Application triedy, aplikovanie tmavého režimu zo `SharedPreferences`, inicializácia Firebase App Check (Play Integrity pre release, Debug provider pre vývoj)
2. `SplashActivity.onCreate()`:
   - Aplikuje tmavý režim (záloha pre prípad, že Application ešte nestihla)
   - Skryje ActionBar a nastaví edge-to-edge zobrazenie
   - Zobrazí animovaný obsah (logo a názov aplikácie) — slide-up animácia zdola nahor (800ms, DecelerateInterpolator)
   - Po 2 sekundách presmeruje na `MainActivity` s fade prechodom
3. `MainActivity.onCreate()`:
   - Načíta preferenciu tmavého režimu zo `SharedPreferences` a nastaví tému
   - Skontroluje `OfflineMode.isOffline()`:
     - **Offline** → preskočí prihlásenie, rovno zobrazí navigáciu so všetkými tabmi
     - **Online** → skontroluje `FirebaseAuth.currentUser`:
       - Null → presmeruje na `LoginActivity`
       - Existuje → pokračuje na hlavnú obrazovku
   - Postaví navigáciu (`buildNavigation()`)
   - V online režime asynchrónne overí admin práva (`checkAdminAndRebuildNav()`)
   - Vytvorí notifikačné kanály a naplánuje alarmy
   - Vyžiada oprávnenie `POST_NOTIFICATIONS` (Android 13+)

### Prihlásenie (LoginActivity)

```
LoginActivity
  └─ LoginViewModel (validácia formulára)
       └─ FirebaseAuth.signInWithEmailAndPassword()
            └─ Úspech → finish() + MainActivity sa zobrazí
            └─ Chyba → zobrazí chybovú hlášku
```

`LoginActivity` nie je launcher — `SplashActivity` je launcher, ktorá po animácii spustí `MainActivity`. Ak `MainActivity` zistí, že nie je prihlásený, presmeruje na `LoginActivity`. Po úspešnom prihlásení sa `LoginActivity` zatvorí a `MainActivity` zostane v zásobníku.

### Detekcia admin práv

Pri online štarte `MainActivity` nastaví real-time `ValueEventListener` na Firebase cestu `teachers/{uid}`. Pri každej zmene tohto uzla sa asynchrónne overí aj cesta `admins/{uid}`. Podľa výsledku sa navigácia dynamicky prebuduje:

- Ak `admins/{uid}` existuje → admin taby (Účty, Predmety)
- Ak `teachers/{uid}` existuje → učiteľská navigácia (bez Konzultácií)
- Ak ani jedno → študentská navigácia (s Konzultáciami)

Prvé zavolanie listenera (počiatočné načítanie) len nastaví navigáciu. Každé ďalšie zavolanie (skutočná zmena role) navyše presmeruje používateľa na domovskú obrazovku, aby sa UI okamžite prispôsobilo novej role. Listener sa odregistruje v `onDestroy()`.

Toto zabezpečuje, že ak admin zmení rolu používateľa, dotknutý používateľ uvidí zmenu okamžite v reálnom čase bez nutnosti reštartu aplikácie.

---

## Štruktúra modulov

Projekt má jediný modul `app/`. Nie je rozdelený do feature modulov — je to monolitická aplikácia, čo pre jej rozsah dáva zmysel.

```
com.marekguran.unitrack/
├── SplashActivity.kt            # Animovaná splash obrazovka (launcher)
├── MainActivity.kt              # Vstupný bod po splashi, navigácia, internet check
├── UniTrackApplication.kt       # Application trieda (inicializácia témy + App Check)
├── BulkGradeActivity.kt         # Hromadné zadávanie známok (expand/collapse animácie)
├── ConsultingHoursActivity.kt   # Správa konzultačných hodín (učiteľ — pridávanie, správa, rezervácie)
├── TeacherBookingsActivity.kt   # Prehľad rezervácií študentov (učiteľ — alternatívne zobrazenie)
├── NewSemesterActivity.kt       # Vytvorenie nového školského roka/semestra (záložky: nastavenia, predmety, študenti)
├── QrAttendanceActivity.kt      # QR kód dochádzka — strana učiteľa (generovanie, monitorovanie)
├── QrScannerActivity.kt         # QR kód skener — strana študenta (skenovanie, overenie)
├── SubjectDetailFragment.kt     # Detail predmetu (ViewPager2 — známky, dochádzka, študenti)
├── data/                        # Dátová vrstva
│   ├── LocalDatabase.kt         # Offline JSON databáza s convenience a migračnými metódami
│   ├── LoginDataSource.kt       # Prihlásenie (scaffold, reálne cez Firebase)
│   ├── LoginRepository.kt       # Repository pre prihlásenie
│   ├── OfflineMode.kt           # Online/offline prepínač
│   ├── Result.kt                # Sealed class pre výsledky operácií
│   └── model/                   # Dátové modely + RecyclerView adaptéry
│       └── ConsultationBooking.kt  # Model rezervácie konzultácie
├── notification/                # Notifikačný systém
│   └── NextClassAlarmReceiver.kt
├── update/                      # Kontrola aktualizácií
│   └── UpdateChecker.kt         # Kontrola najnovšej verzie z GitHub
└── ui/                          # Obrazovky
    ├── PillNavigationBar.kt     # Vlastný navigačný komponent (glass-morphism)
    ├── SubjectDetailPagerAdapter.kt  # ViewPager2 pre detail predmetu (3 záložky)
    ├── SwipeableFrameLayout.kt  # Gestá pre swipe navigáciu
    ├── home/                    # Domov + dialóg známok
    ├── dashboard/               # Dashboard (ViewModel + Fragment)
    ├── login/                   # Prihlásenie (MVVM komplet)
    ├── consulting/              # Konzultačné hodiny (študentský pohľad)
    │   └── ConsultingHoursFragment.kt  # Prehliadanie učiteľov, rezervácia, moje rezervácie
    ├── timetable/               # Rozvrh
    │   ├── TimetableFragment.kt       # Hlavný kontrolér (dáta, CRUD, filtre)
    │   ├── ScheduleAdapter.kt         # Stavové karty hodín (PAST/CURRENT/NEXT/FUTURE)
    │   ├── DayChipAdapter.kt          # Chip navigátor dní s animáciami
    │   └── TimetablePagerAdapter.kt   # ViewPager2 adaptér pre stránky dní
    ├── students/                # Správa študentov / účtov
    ├── subjects/                # Správa predmetov
    └── settings/                # Nastavenia (téma, notifikácie, export, admin)
```

---

## Kľúčové architektonické rozhodnutia

### Prečo SplashActivity?

`SplashActivity` je vstupný bod aplikácie (launcher). Slúži na zobrazenie animovaného loga počas načítavania. Tmavý režim sa aplikuje už v `UniTrackApplication.onCreate()`, takže používateľ nikdy nevidí nesprávnu tému. Po dvoch sekundách sa plynulo presunie na `MainActivity`.

### Prečo duálny backend?

Offline režim existuje preto, aby učiteľ mohol spravovať známky a dochádzku aj bez internetu — napríklad v učebni bez Wi-Fi. `LocalDatabase` replikuje štruktúru Firebase, takže logika v UI vrstvách nemusí byť duplicitná — len sa mení zdroj dát.

### Prečo vlastná navigácia (PillNavigationBar)?

Štandardný `BottomNavigationView` z Material knižnice nepodporoval požadovaný dizajn — animovanú „pilulku" s tieňovým efektom, adaptívnu veľkosť pre tablety, a dynamické pridávanie/odoberanie tabov podľa role. `PillNavigationBar` je vlastný `View`, ktorý toto všetko rieši.

### Prečo JSON súbor namiesto Room/SQLite?

Pre offline režim sa používa jednoduchý JSON súbor (`local_db.json`) namiesto Room databázy. Dôvod: štruktúra dát v Firebase je stromová (JSON), a replikácia tejto štruktúry 1:1 do JSON súboru bola jednoduchšia a menej náchylná na chyby pri mapovaní. Navyše sa celý súbor dá jednoducho exportovať a importovať ako záloha.

### Prečo nie sú všetky fragmenty cez ViewModel?

Niektoré obrazovky (napr. `SettingsFragment`, `TimetableFragment`) sú relatívne jednoduché — načítajú dáta z Firebase/lokálnej DB a zobrazia ich. Pridávanie ViewModel vrstvy by tam neprinieslo výrazný benefit. ViewModel sa používa tam, kde je to naozaj užitočné — pri prihlásení (validácia, stav formulára) a dashboarde.

---

## Závislosti a build systém

Projekt používa **Gradle Kotlin DSL** s **Version Catalog** (`gradle/libs.versions.toml`). Všetky verzie závislostí sú definované na jednom mieste.

### Hlavné závislosti

| Závislosť | Účel |
|---|---|
| `androidx.core-ktx` | Kotlin rozšírenia pre Android |
| `androidx.appcompat` | Spätná kompatibilita UI komponentov |
| `material` | Material Design 3 komponenty |
| `androidx.constraintlayout` | Flexibilné layouty |
| `androidx.lifecycle` | ViewModel + LiveData |
| `androidx.navigation` | Fragment navigácia |
| `androidx.viewpager2` | ViewPager2 pre swipe navigáciu rozvrhu |
| `firebase-database` | Firebase Realtime Database |
| `firebase-auth` | Firebase Authentication |
| `firebase-appcheck` | Firebase App Check (Play Integrity + Debug provider) |
| `zxing-core` | Generovanie QR kódov (knižnica pre čiarové a QR kódy) |
| `zxing-android-embedded` | Skenovanie QR kódov fotoaparátom (JourneyApps wrapper) |
| `blurview` | BlurView pre glass-morphism efekty (PillNavigationBar) |


### Build konfigurácia

- **compileSdk:** 36
- **minSdk:** 31 (Android 12)
- **targetSdk:** 36
- **Java kompatibilita:** 17
- **Kotlin JVM target:** 17
- **View Binding:** zapnuté
- **ProGuard:** zapnutý (isMinifyEnabled = true)

---

## Bezpečnosť

- **Firebase App Check** overuje, že požiadavky na Firebase pochádzajú z legitimnej inštancie aplikácie (Play Integrity pre release, Debug provider pre vývoj) — bez platného atestačného tokenu nie je možné pristupovať k online databáze
- Firebase Authentication rieši prihlásenie — heslá sa neukladajú lokálne
- Offline režim nepoužíva autentifikáciu (dáta sú len na zariadení)
- `google-services.json` je súčasťou repozitára — obsahuje konfiguráciu Firebase projektu, nie tajné kľúče. Skutočná ochrana je zabezpečená cez App Check a Security Rules
- Admin práva sa overujú cez Firebase cestu `admins/{uid}` — Firebase Security Rules zabezpečujú, že prístup je kontrolovaný na strane servera
- Podrobnosti v [Bezpečnosť](BEZPECNOST.md)

---

[← Späť na README](../README.md)
