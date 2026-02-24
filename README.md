# 📚 UniTrack

**UniTrack** je Android aplikácia pre správu akademického života na vysokých školách. Vznikla ako praktický nástroj pre učiteľov, administrátorov a študentov — s cieľom zjednodušiť evidenciu známok, dochádzky, rozvrhu a správu predmetov na jednom mieste.

Aplikácia funguje v dvoch režimoch: **online** (cez Firebase) aj **offline** (lokálna JSON databáza), takže ju je možné používať aj bez pripojenia na internet.

---

## 🧭 Obsah

- [Hlavné funkcie](#-hlavné-funkcie)
- [Technológie](#-technológie)
- [Štruktúra projektu](#-štruktúra-projektu)
- [Inštalácia a spustenie](#-inštalácia-a-spustenie)
- [Obrazovky aplikácie](#-obrazovky-aplikácie)
- [Offline režim](#-offline-režim)
- [Notifikácie](#-notifikácie)
- [Oprávnenia](#-oprávnenia)
- [Technická dokumentácia](#-technická-dokumentácia)
- [Verzia](#-verzia)

---

## ✨ Hlavné funkcie

- **Splash obrazovka** — animovaný vstupný screen s logom a plynulým prechodom do aplikácie
- **Prihlásenie a autentifikácia** — Firebase Auth s emailom a heslom, alebo offline režim bez prihlásenia
- **Evidencia známok** — pridávanie, úprava a mazanie hodnotení (A až Fx) s názvom, popisom a váhou
- **Sledovanie dochádzky** — zaznamenávanie prítomnosti/neprítomnosti študentov podľa dátumu
- **Správa rozvrhu** — týždenný rozvrh s filtrami (párny/nepárny týždeň, dnes), podpora voľných dní
- **Správa predmetov** — vytváranie, editácia a priradenie predmetov k semestrom (zimný/letný/obidva)
- **Správa študentov a účtov** — administrácia používateľov, priradenie rolí (učiteľ, admin, študent)
- **Akademická analytika** — priemery známok, percentá dochádzky
- **Tmavý režim** — prepínateľný v nastaveniach, zapamätá si voľbu používateľa (aplikuje sa už od spustenia)
- **Export a import databázy** — zálohovanie a obnova celej lokálnej databázy ako JSON súbor
- **Nastaviteľné notifikácie** — živá aktualizácia rozvrhu, upozornenia na zrušené hodiny, zmeny známok a nové neprítomnosti s konfigurovateľnými intervalmi
- **Android 16 Live Update** — segmentovaný progress bar s farebnými blokmi pre hodiny a prestávky (na podporovaných zariadeniach)
- **Reset hesla** — možnosť odoslať email na obnovu hesla priamo z nastavení
- **Meno učiteľa** — v offline režime si učiteľ môže nastaviť a uložiť svoje meno
- **Responzívny dizajn** — prispôsobený pre telefóny aj tablety s vlastnou pill navigáciou

---

## 🛠 Technológie

| Oblasť | Technológia |
|---|---|
| Jazyk | Kotlin |
| Platforma | Android (minSdk 31, targetSdk 36) |
| Backend | Firebase Realtime Database + Firebase Auth |
| UI | Material Design 3, AndroidX, View Binding |
| Architektúra | MVVM (ViewModel + LiveData + Fragmenty) |
| Navigácia | Android Navigation Component |
| UI efekty | Vlastný PillNavigationBar s animáciami a tieňovým efektom |
| Build systém | Gradle (Kotlin DSL) s Version Catalog |

---

## 📁 Štruktúra projektu

```
UniTrack/
├── app/
│   └── src/main/
│       ├── java/com/marekguran/unitrack/
│       │   ├── ui/                     # Obrazovky aplikácie
│       │   │   ├── home/               # Domovská obrazovka (zoznam predmetov, detail)
│       │   │   ├── dashboard/          # Dashboard
│       │   │   ├── login/              # Prihlásenie
│       │   │   ├── timetable/          # Rozvrh a voľné dni
│       │   │   ├── subjects/           # Správa predmetov
│       │   │   ├── students/           # Správa študentov / účtov
│       │   │   ├── settings/           # Nastavenia (tmavý režim, notifikácie, export, admin)
│       │   │   └── PillNavigationBar.kt  # Vlastná navigačná lišta
│       │   ├── data/                   # Dátová vrstva
│       │   │   ├── model/              # Dátové modely (Mark, Student, Timetable...)
│       │   │   ├── LocalDatabase.kt    # Lokálna JSON databáza
│       │   │   ├── LoginDataSource.kt  # Prihlásenie cez Firebase
│       │   │   ├── LoginRepository.kt  # Repository pre prihlásenie
│       │   │   └── OfflineMode.kt      # Prepínanie online/offline
│       │   ├── notification/           # Notifikácie (rozvrh, známky, zrušené hodiny, neprítomnosť)
│       │   ├── SplashActivity.kt       # Animovaná splash obrazovka (launcher)
│       │   ├── MainActivity.kt         # Hlavná aktivita s navigáciou
│       │   └── UniTrackApplication.kt  # Application trieda (inicializácia tmavého režimu)
│       ├── res/                        # Zdroje (layouty, stringy, ikony, farby)
│       └── AndroidManifest.xml
├── build.gradle.kts                    # Root Gradle konfigurácia
├── gradle/libs.versions.toml           # Version Catalog závislostí
└── settings.gradle.kts
```

---

## 🚀 Inštalácia a spustenie

### Požiadavky

- **Android Studio** Ladybug alebo novšie
- **JDK 11** alebo vyššie
- **Android zariadenie alebo emulátor** s Android 12+ (API 31+)
- **Firebase projekt** (voliteľné — aplikácia funguje aj v offline režime)

### Kroky

1. **Naklonujte repozitár:**
   ```bash
   git clone https://github.com/bachelor-emgi/UniTrack.git
   cd UniTrack
   ```

2. **Otvorte projekt v Android Studiu**

3. **Firebase nastavenie** (pre online režim):
   - Vytvorte Firebase projekt na [Firebase Console](https://console.firebase.google.com/)
   - Zapnite Authentication (Email/Password) a Realtime Database
   - Stiahnite `google-services.json` a vložte ho do priečinka `app/`

4. **Spustite aplikáciu:**
   - Vyberte zariadenie alebo emulátor
   - Kliknite na ▶ Run

> 💡 Ak nemáte Firebase, jednoducho použite **offline režim** — na prihlasovacej obrazovke stlačte tlačidlo „Lokálny režim".

---

## 📱 Obrazovky aplikácie

### 🎬 Splash obrazovka

Po spustení aplikácie sa zobrazí animovaná splash obrazovka s logom, ktorá sa po dvoch sekundách plynulo (fade) presunie na hlavnú obrazovku. Tmavý režim sa aplikuje už počas zobrazenia splashu, takže používateľ nikdy nevidí nesprávnu tému.

### 🔐 Prihlásenie

Vstupná obrazovka s emailom a heslom. Prihlásenie prebieha cez Firebase Auth. Ak je používateľ už prihlásený, aplikácia ho automaticky presmeruje na domovskú obrazovku. Pre prácu bez internetu je k dispozícii tlačidlo **„Lokálny režim"**.

### 🏠 Domov

Hlavná obrazovka po prihlásení. Učitelia a admini vidia prehľad svojich predmetov — počet študentov, priemerné hodnotenie a dochádzku. Študenti vidia svoje zapísané predmety a známky. Filtrovanie podľa akademického roka a semestra.

Po kliknutí na predmet sa otvorí **detail predmetu** s kompletným zoznamom študentov, ich známkami, dochádzkou a grafmi výkonu.

### 📅 Rozvrh

Týždenný rozvrh s filtrami:
- **Všetky** — celý rozvrh
- **Dnes** — len dnešné hodiny
- **Nepárny/Párny týždeň** — podľa parity týždňa

Učitelia môžu pridávať **voľné dni** (dovolenky) s dátumom, časovým rozsahom a poznámkou. Rozvrh zobrazuje aj učebňu a poznámky k jednotlivým hodinám.

### 👥 Študenti / Účty

V online režime (pre adminov) sa zobrazuje ako **„Účty"** — správa všetkých používateľov systému s filtrovaním podľa role (študent, učiteľ, admin).

V offline režime sa zobrazuje ako **„Študenti"** — pridávanie a odstraňovanie študentov, správa zápisov predmetov.

### 📚 Predmety

Správa predmetov — vytváranie nových, úprava názvu, priradenie učiteľa a nastavenie semestra (zimný, letný, alebo obidva). Pri zmene semestra sa automaticky migrujú všetky známky a dochádzka.

### ⚙️ Nastavenia

- **Vzhľad** — prepínanie tmavého režimu
- **Notifikácie** — zapínanie/vypínanie živej aktualizácie rozvrhu a upozornení na zmeny, nastavenie intervalov kontroly, zobrazenie učebne a nasledujúcej hodiny v notifikácii, konfigurácia počtu minút pred prvou hodinou, optimalizácia batérie
- **Admin funkcie** — správa akademických rokov a semestrov
- **Offline funkcie** — export/import databázy, vytváranie školských rokov, nastavenie mena učiteľa
- **Účet** — odhlásenie, reset hesla (online), reset aplikácie (offline)

---

## 💾 Offline režim

UniTrack ponúka plnohodnotný offline režim bez potreby Firebase alebo internetu. Všetky dáta sa ukladajú lokálne v JSON formáte.

### Čo funguje offline:

- Kompletná správa predmetov, študentov a známok
- Rozvrh a voľné dni
- Živá notifikácia rozvrhu (s podporou semester-aware filtrovania)
- Export celej databázy do JSON súboru (záloha)
- Import databázy zo súboru (obnova)
- Vytváranie akademických rokov a semestrov
- Nastavenie mena učiteľa

### Štruktúra lokálnej databázy:

```json
{
  "predmety": { ... },
  "students": { ... },
  "hodnotenia": { ... },
  "pritomnost": { ... },
  "teachers": { ... },
  "admins": { ... },
  "days_off": { ... },
  "school_years": { ... },
  "settings": { ... }
}
```

> Offline režim je možné aktivovať na prihlasovacej obrazovke tlačidlom **„Lokálny režim"**. Po reštarte aplikácie sa používateľ vráti na prihlasovaciu obrazovku.

---

## 🔔 Notifikácie

Aplikácia využíva štyri notifikačné kanály:

| Kanál | Popis | Priorita |
|---|---|---|
| **Rozvrh hodín** | Živá aktualizácia — ukazuje aktuálnu/ďalšiu hodinu, prestávku alebo voľno (segmentovaný progress bar na Android 16) | Tichá (nízka) |
| **Zrušené hodiny** | Upozornenie keď učiteľ označí hodinu ako zrušenú | Vysoká |
| **Známky** | Nová, upravená alebo odstránená známka | Vysoká |
| **Neprítomnosť** | Upozornenie na novú zaznamenanú neprítomnosť študenta | Vysoká |

Intervaly kontrol sú konfigurovateľné v nastaveniach — živá aktualizácia rozvrhu (predvolene každé 2 minúty) a kontrola zmien známok, neprítomnosti a zrušených hodín (predvolene každých 30 minút). Oba kanály je možné individuálne zapnúť alebo vypnúť. Notifikácie fungujú aj po reštarte zariadenia.

---

## 🔒 Oprávnenia

Aplikácia vyžaduje tieto Android oprávnenia:

- `POST_NOTIFICATIONS` — zobrazovanie notifikácií (Android 13+)
- `POST_PROMOTED_NOTIFICATIONS` — rozšírené notifikácie (Live Update na Android 16)
- `FOREGROUND_SERVICE` — beh notifikačnej služby na pozadí
- `RECEIVE_BOOT_COMPLETED` — plánovanie notifikácií po reštarte zariadenia
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — výnimka z optimalizácie batérie pre spoľahlivé doručovanie notifikácií

---

## 📖 Technická dokumentácia

Pre hlbšie pochopenie toho, ako UniTrack funguje pod kapotou, sú k dispozícii samostatné dokumenty:

| Dokument | Obsah |
|---|---|
| [Architektúra aplikácie](docs/ARCHITEKTURA.md) | Celková architektúra, MVVM vzor, priebeh dát medzi vrstvami, životný cyklus komponentov, SplashActivity |
| [Databáza a dátová vrstva](docs/DATABAZA.md) | Firebase Realtime Database cesty, lokálna JSON databáza, dátové modely, convenience metódy, migrácia semestrov |
| [Navigácia a UI komponenty](docs/NAVIGACIA.md) | Navigation Component, PillNavigationBar, role-based navigácia, fragmenty a adaptéry |
| [Notifikačný systém](docs/NOTIFIKACIE.md) | Kanály, konfigurovateľné intervaly, Android 16 ProgressStyle, detekcia zmien známok, neprítomnosti a zrušených hodín |
| [Nastavenia aplikácie](docs/NASTAVENIA.md) | Podrobný popis všetkých nastavení — vzhľad, notifikácie, správa účtu, offline funkcie, SharedPreferences kľúče |
| [Tlač a export dát](docs/TLAC_A_EXPORT.md) | PDF reporty (predmet, študent, učiteľ), export/import lokálnej databázy, formát záloh |
| [Testovanie](docs/TESTOVANIE.md) | Metodika testovania, testovacie scenáre, matica zariadení, výsledky testovania |
| [Bezpečnosť](docs/BEZPECNOST.md) | Bezpečnostný model, Firebase autentifikácia, ochrana dát, oprávnenia, odporúčania |
| [Splnenie cieľa práce](docs/SPLNENIE_CIELA.md) | Mapovanie cieľa diplomovej práce na implementované funkcie, analýza splnenia |

---

## 🏷 Verzia

- **Verzia aplikácie:** 2.0.2
- **Kód verzie (Google):** 22
- **Min SDK:** 31 (Android 12)
- **Target SDK:** 36

---

## 👤 Autor a školiteľ

|  | Meno | Pozícia |
|---|---|---|
| **Autor** | Marek Guráň | Študent, odbor jednoodborové učiteľstvo informatiky |
| **Školiteľ** | doc. Ing. Ján Pillár, PhD. | Vedúci diplomovej práce |

**Katolícka univerzita v Ružomberku**

### Cieľ práce

> Návrh a kompletná realizácia mobilnej aplikácie na evidenciu prítomnosti a hodnotenia študentov.

Cieľ práce bol naplnený v plnom rozsahu. Aplikácia UniTrack implementuje kompletný systém evidencie prítomnosti (zaznamenávanie, úprava, mazanie, percentuálne prehľady, notifikácie o neprítomnosti) aj hodnotenia študentov (pridávanie známok A–Fx, úprava, mazanie, výpočet priemerov, navrhovaná známka, notifikácie o zmenách). Nad rámec stanoveného cieľa boli realizované ďalšie funkcie — správa rozvrhu, voľných dní, PDF reporty, duálny online/offline režim, nastaviteľné notifikácie s podporou Android 16 Live Update a responzívny dizajn pre telefóny aj tablety.

---

## 📄 Licencia

Tento projekt je súčasťou akademickej práce. Pre viac informácií kontaktujte autora.
