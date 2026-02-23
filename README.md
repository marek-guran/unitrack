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

- **Prihlásenie a autentifikácia** — Firebase Auth s emailom a heslom, alebo offline režim bez prihlásenia
- **Evidencia známok** — pridávanie, úprava a mazanie hodnotení (A až Fx) s názvom, popisom a váhou
- **Sledovanie dochádzky** — zaznamenávanie prítomnosti/neprítomnosti študentov podľa dátumu
- **Správa rozvrhu** — týždenný rozvrh s filtrami (párny/nepárny týždeň, dnes), podpora voľných dní
- **Správa predmetov** — vytváranie, editácia a priradenie predmetov k semestrom (zimný/letný/obidva)
- **Správa študentov a účtov** — administrácia používateľov, priradenie rolí (učiteľ, admin, študent)
- **Akademická analytika** — priemery známok, percentá dochádzky
- **Tmavý režim** — prepínateľný v nastaveniach, zapamätá si voľbu používateľa
- **Export a import databázy** — zálohovanie a obnova celej lokálnej databázy ako JSON súbor
- **Notifikácie** — upozornenia na ďalšiu hodinu, zrušené hodiny a zmeny známok
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
| UI efekty | BlurView (sklenený efekt navigácie) |
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
│       │   │   ├── settings/           # Nastavenia (tmavý režim, export, admin)
│       │   │   └── PillNavigationBar.kt  # Vlastná navigačná lišta
│       │   ├── data/                   # Dátová vrstva
│       │   │   ├── model/              # Dátové modely (Mark, Student, Timetable...)
│       │   │   ├── LocalDatabase.kt    # Lokálna JSON databáza
│       │   │   ├── LoginDataSource.kt  # Prihlásenie cez Firebase
│       │   │   ├── LoginRepository.kt  # Repository pre prihlásenie
│       │   │   └── OfflineMode.kt      # Prepínanie online/offline
│       │   ├── notification/           # Notifikácie (rozvrh, známky, zrušené hodiny)
│       │   ├── MainActivity.kt         # Hlavná aktivita s navigáciou
│       │   └── UniTrackApplication.kt  # Application trieda
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
- **Admin funkcie** — správa akademických rokov, semestrov, zoznam adminov
- **Offline funkcie** — export/import databázy, vytváranie školských rokov
- **Účet** — odhlásenie, reset aplikácie

---

## 💾 Offline režim

UniTrack ponúka plnohodnotný offline režim bez potreby Firebase alebo internetu. Všetky dáta sa ukladajú lokálne v JSON formáte.

### Čo funguje offline:

- Kompletná správa predmetov, študentov a známok
- Rozvrh a voľné dni
- Export celej databázy do JSON súboru (záloha)
- Import databázy zo súboru (obnova)
- Vytváranie akademických rokov a semestrov

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
  "school_years": { ... }
}
```

> Offline režim je možné aktivovať na prihlasovacej obrazovke tlačidlom **„Lokálny režim"**. Po reštarte aplikácie sa používateľ vráti na prihlasovaciu obrazovku.

---

## 🔔 Notifikácie

Aplikácia využíva tri notifikačné kanály:

| Kanál | Popis | Priorita |
|---|---|---|
| **Rozvrh hodín** | Živá aktualizácia — ukazuje aktuálnu/ďalšiu hodinu, prestávku alebo voľno | Tichá (nízka) |
| **Zrušené hodiny** | Upozornenie keď učiteľ označí hodinu ako zrušenú | Vysoká |
| **Známky** | Nová, upravená alebo odstránená známka | Vysoká |

Notifikácie sa kontrolujú pravidelne (každých 15–30 minút) a fungujú aj po reštarte zariadenia.

---

## 🔒 Oprávnenia

Aplikácia vyžaduje tieto Android oprávnenia:

- `POST_NOTIFICATIONS` — zobrazovanie notifikácií (Android 13+)
- `FOREGROUND_SERVICE` — beh notifikačnej služby na pozadí
- `RECEIVE_BOOT_COMPLETED` — plánovanie notifikácií po reštarte zariadenia

---

## 📖 Technická dokumentácia

Pre hlbšie pochopenie toho, ako UniTrack funguje pod kapotou, sú k dispozícii samostatné dokumenty:

| Dokument | Obsah |
|---|---|
| [Architektúra aplikácie](docs/ARCHITEKTURA.md) | Celková architektúra, MVVM vzor, priebeh dát medzi vrstvami, životný cyklus komponentov |
| [Databáza a dátová vrstva](docs/DATABAZA.md) | Firebase Realtime Database cesty, lokálna JSON databáza, dátové modely, migrácia semestrov |
| [Navigácia a UI komponenty](docs/NAVIGACIA.md) | Navigation Component, PillNavigationBar, role-based navigácia, fragmenty a adaptéry |
| [Notifikačný systém](docs/NOTIFIKACIE.md) | Kanály, AlarmManager plánovanie, detekcia zmien známok a zrušených hodín, offline podpora |

---

## 🏷 Verzia

- **Verzia aplikácie:** 2.0.1
- **Kód verzie (Google):** 21
- **Min SDK:** 31 (Android 12)
- **Target SDK:** 36

---

## 👤 Autor

Vytvoril **Marek Guran** ako súčasť diplomovej práce na **Katolíckej univerzite v Ružomberku**.

---

## 📄 Licencia

Tento projekt je súčasťou akademickej práce. Pre viac informácií kontaktujte autora.
