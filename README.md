# 📚 UniTrack

**UniTrack** je Android aplikácia pre správu akademického života na vysokých školách. Vznikla ako praktická súčasť diplomovej práce — nástroj pre učiteľov, administrátorov a študentov, ktorý zjednodušuje evidenciu známok, dochádzky, rozvrhu a správu predmetov na jednom mieste.

Aplikácia funguje v dvoch režimoch: **online** (cez Firebase s App Check ochranou) aj **offline** (lokálna JSON databáza), takže ju je možné používať aj bez pripojenia na internet.

> **Poznámka k bezpečnosti:** Online verzia je chránená pomocou **Firebase App Check** — bez platného atestačného kľúča nie je možné pristupovať k databáze ani API. Offline režim je prístupný lokálne bez autentifikácie, pretože dáta zostávajú výhradne na zariadení používateľa.

---

## 🧭 Obsah

- [Hlavné funkcie](#-hlavné-funkcie)
- [Technológie](#-technológie)
- [Štruktúra projektu](#-štruktúra-projektu)
- [Inštalácia a spustenie](#-inštalácia-a-spustenie)
- [Firebase App Check](#-firebase-app-check)
- [Obrazovky aplikácie](#-obrazovky-aplikácie)
- [Konzultačné hodiny](#-konzultačné-hodiny)
- [Offline režim](#-offline-režim)
- [Migrácia databázy](#-migrácia-databázy)
- [Animácie a prechody](#-animácie-a-prechody)
- [Notifikácie](#-notifikácie)
- [Oprávnenia](#-oprávnenia)
- [Technická dokumentácia](#-technická-dokumentácia)
- [Verzia](#-verzia)

---

## ✨ Hlavné funkcie

- **Splash obrazovka** — animovaný vstup s logom, slide-up efektom a plynulým prechodom do aplikácie
- **Prihlásenie a autentifikácia** — Firebase Auth s emailom a heslom, alebo offline režim bez prihlásenia
- **Firebase App Check** — ochrana online databázy pomocou Play Integrity (release) a debug tokenov (vývoj), bez platného atestačného kľúča sa k dátam nedostane žiadna neautorizovaná aplikácia
- **Evidencia známok** — pridávanie, úprava a mazanie hodnotení (A až Fx) s názvom, popisom a váhou
- **Hromadné hodnotenie (Bulk Grading)** — zadávanie známok viacerým študentom naraz s výberom známky cez chip komponenty, spoločným dátumom a voliteľnými poznámkami pre každého študenta
- **Sledovanie dochádzky** — zaznamenávanie prítomnosti/neprítomnosti študentov podľa dátumu
- **QR kód dochádzka** — učiteľ zobrazí rotujúci QR kód, študenti ho naskenujú fotoaparátom a dochádzka sa zaznamená automaticky v reálnom čase
- **Správa rozvrhu** — týždenný rozvrh s filtrami (párny/nepárny týždeň, dnes), podpora voľných dní
- **Správa predmetov** — vytváranie, editácia a priradenie predmetov k semestrom (zimný/letný/obidva)
- **Správa študentov a účtov** — administrácia používateľov, priradenie rolí (učiteľ, admin, študent) s možnosťou zmeny role v reálnom čase (online režim) — pri zmene role sa UI okamžite aktualizuje a dáta zostávajú zachované
- **Akademická analytika** — priemery známok, percentá dochádzky, navrhovaná známka
- **Migrácia databázy** — automatická aj manuálna migrácia štruktúry databázy (globálne predmety → per-year, per-year študenti → globálna štruktúra, migrácia pri zmene semestra predmetu) pre online aj offline režim
- **Animácie a prechody** — paint-drop animácia pri prepínaní tmavého režimu (kruhový reveal), plynulé expand/collapse animácie, slide-up splash, fade prechody medzi obrazovkami
- **Tmavý režim** — prepínateľný v nastaveniach, zapamätá si voľbu používateľa (aplikuje sa už od spustenia, vrátane ikon stavového riadku)
- **Export a import databázy** — zálohovanie a obnova celej lokálnej databázy ako JSON súbor
- **Nastaviteľné notifikácie** — živá aktualizácia rozvrhu, upozornenia na zrušené hodiny, zmeny známok, nové neprítomnosti a pripomienky konzultačných hodín s konfigurovateľnými intervalmi
- **Android 16 Live Update** — segmentovaný progress bar s farebnými blokmi pre hodiny a prestávky (na podporovaných zariadeniach)
- **Reset hesla** — možnosť odoslať email na obnovu hesla priamo z nastavení
- **Konzultačné hodiny** — učitelia môžu nastaviť svoje konzultačné hodiny (deň, čas, učebňa), študenti si ich môžu prehliadať a rezervovať termíny; učitelia vidia prehľad rezervácií a môžu ich spravovať (úprava, zrušenie, kontaktovanie študenta)
- **Správa nového semestra** — samostatná obrazovka pre vytvorenie nového školského roka s výberom predmetov a študentov (záložky: nastavenia, predmety, študenti)
- **Kontrola aktualizácií** — automatická kontrola dostupnosti novej verzie z GitHub repozitára
- **Meno učiteľa** — v offline režime si učiteľ môže nastaviť a uložiť svoje meno
- **Responzívny dizajn** — prispôsobený pre telefóny aj tablety s vlastnou pill navigáciou
- **Plynulý rozvrh** — swipe navigácia medzi dňami s 1:1 peek animáciou (obsah sleduje prst), zobrazovanie voľných dní s prázdnym stavom, učebňa zobrazená v „pill" odznaku na kartách rozvrhu, fade-out na okrajoch navigátora dní, bez ghosting efektu pri prepínaní čipov

---

## 🛠 Technológie

| Oblasť | Technológia |
|---|---|
| Jazyk | Kotlin |
| Platforma | Android (minSdk 31, targetSdk 36) |
| Backend | Firebase Realtime Database + Firebase Auth |
| Bezpečnosť | Firebase App Check (Play Integrity / Debug provider) |
| UI | Material Design 3, AndroidX, View Binding |
| Architektúra | MVVM (ViewModel + LiveData + Fragmenty) |
| Navigácia | Android Navigation Component |
| UI efekty | Vlastný PillNavigationBar s glass-morphism efektom, magnifikáciou a tieňmi |
| Animácie | Programatické animácie (ValueAnimator, circular reveal, fade, slide-up) |
| Rozvrh | ViewPager2 s DayChipAdapter, ScheduleAdapter a stavovými kartami hodín |
| QR kódy | ZXing (generovanie a skenovanie QR kódov pre dochádzku) |
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
│       │   │   ├── timetable/          # Rozvrh hodín
│       │   │   │   ├── TimetableFragment.kt      # Hlavný kontrolér rozvrhu
│       │   │   │   ├── ScheduleAdapter.kt         # Stavové karty hodín (PAST/CURRENT/NEXT/FUTURE)
│       │   │   │   ├── DayChipAdapter.kt          # Animovaný chip navigátor dní
│       │   │   │   └── TimetablePagerAdapter.kt   # ViewPager2 adaptér pre stránky dní
│       │   │   ├── consulting/         # Konzultačné hodiny (študentský pohľad — prehliadanie a rezervácia)
│       │   │   │   └── ConsultingHoursFragment.kt # Zoznam učiteľov, rezervácia termínov, moje rezervácie
│       │   │   ├── subjects/           # Správa predmetov
│       │   │   ├── students/           # Správa študentov / účtov
│       │   │   ├── settings/           # Nastavenia (tmavý režim, notifikácie, export, admin, migrácia)
│       │   │   ├── PillNavigationBar.kt  # Vlastná navigačná lišta (glass-morphism)
│       │   │   ├── SubjectDetailPagerAdapter.kt  # ViewPager2 pre detail predmetu (známky/dochádzka/študenti)
│       │   │   └── SwipeableFrameLayout.kt       # Gestá pre swipe navigáciu
│       │   ├── data/                   # Dátová vrstva
│       │   │   ├── model/              # Dátové modely (Mark, Student, Timetable, ConsultationBooking...)
│       │   │   ├── LocalDatabase.kt    # Lokálna JSON databáza + migračné metódy
│       │   │   ├── LoginDataSource.kt  # Prihlásenie cez Firebase
│       │   │   ├── LoginRepository.kt  # Repository pre prihlásenie
│       │   │   └── OfflineMode.kt      # Prepínanie online/offline
│       │   ├── notification/           # Notifikácie (rozvrh, známky, zrušené hodiny, neprítomnosť, konzultácie)
│       │   ├── update/                 # Kontrola aktualizácií z GitHub
│       │   │   └── UpdateChecker.kt    # Sťahovanie a kontrola najnovšej verzie
│       │   ├── BulkGradeActivity.kt    # Hromadné zadávanie známok viacerým študentom
│       │   ├── ConsultingHoursActivity.kt  # Správa konzultačných hodín (učiteľ — pridávanie, správa, rezervácie)
│       │   ├── TeacherBookingsActivity.kt  # Prehľad rezervácií študentov (učiteľ)
│       │   ├── NewSemesterActivity.kt  # Vytvorenie nového školského roka/semestra
│       │   ├── QrAttendanceActivity.kt # QR kód dochádzka (strana učiteľa — generovanie a monitorovanie)
│       │   ├── QrScannerActivity.kt    # QR kód skener (strana študenta — skenovanie a overenie)
│       │   ├── SplashActivity.kt       # Animovaná splash obrazovka (slide-up + fade)
│       │   ├── MainActivity.kt         # Hlavná aktivita s navigáciou a paint-drop animáciou
│       │   └── UniTrackApplication.kt  # Application trieda (inicializácia tmavého režimu + App Check)
│       ├── res/                        # Zdroje (layouty, stringy, ikony, farby)
│       └── AndroidManifest.xml
├── database.rules.json                 # Firebase Security Rules (vzorová konfigurácia)
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
   - Zapnite **App Check** s provider-om Play Integrity (viď sekciu [Firebase App Check](#-firebase-app-check))
   - Stiahnite `google-services.json` a vložte ho do priečinka `app/`

4. **Spustite aplikáciu:**
   - Vyberte zariadenie alebo emulátor
   - Kliknite na ▶ Run

> 💡 Ak nemáte Firebase, jednoducho použite **offline režim** — na prihlasovacej obrazovke stlačte tlačidlo „Lokálny režim". Všetky funkcie (okrem cloudovej synchronizácie) sú plne dostupné.

---

## 🛡 Firebase App Check

UniTrack využíva **Firebase App Check** na ochranu backendových zdrojov (Realtime Database, Authentication) pred neoprávneným prístupom. Bez platného atestačného tokenu žiadna aplikácia ani skript nemôže pristupovať k online databáze — aj keď pozná URL alebo API kľúč.

### Ako to funguje

App Check overuje, že požiadavky na Firebase pochádzajú z legitimnej inštancie aplikácie UniTrack:

| Prostredie | Provider | Princíp |
|---|---|---|
| **Release (produkcia)** | Play Integrity | Google Play overí integritu zariadenia a aplikácie pomocou SHA-256 certifikátu |
| **Debug (vývoj)** | Debug App Check | Vývojár si zaregistruje debug token v Firebase Console |

### Implementácia

App Check sa inicializuje pri štarte aplikácie v `UniTrackApplication.kt`:

```kotlin
val factory = if (BuildConfig.DEBUG) {
    DebugAppCheckProviderFactory.getInstance()
} else {
    PlayIntegrityAppCheckProviderFactory.getInstance()
}
FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)
```

### Čo to znamená v praxi

- **Online verzia je uzamknutá** — bez platného kľúča (Play Integrity atestácie alebo debug tokenu) nie je možné čítať ani zapisovať dáta
- **Offline režim nie je ovplyvnený** — lokálne dáta sú uložené priamo na zariadení a App Check sa na ne nevzťahuje
- **Open source bezpečnosť** — aj keď je kód verejne dostupný, samotný prístup k databáze vyžaduje kombináciu Firebase Auth + App Check, čím sa predchádza zneužitiu

### Nastavenie pre vlastný Firebase projekt

1. V [Firebase Console](https://console.firebase.google.com/) → **App Check** → zapnite **Play Integrity** provider
2. Zaregistrujte SHA-256 odtlačok vášho podpisového certifikátu
3. Pre vývoj: spustite debug build, skopírujte debug token z logov a zaregistrujte ho v Firebase Console
4. V nastaveniach Realtime Database zapnite **Enforce App Check** — od tohto momentu sú povolené len overené požiadavky

---

## 📱 Obrazovky aplikácie

### 🎬 Splash obrazovka

Po spustení aplikácie sa zobrazí animovaná splash obrazovka s logom UniTrack. Logo sa plynule vysúva zdola nahor (slide-up, 800ms s DecelerateInterpolator) a po krátkej pauze sa obrazovka presunie na hlavnú aktivitu s fade prechodom. Tmavý režim sa aplikuje už počas zobrazenia splashu, takže používateľ nikdy nevidí nesprávnu tému.

### 🔐 Prihlásenie

Vstupná obrazovka s emailom a heslom. Prihlásenie prebieha cez Firebase Auth. Ak je používateľ už prihlásený, aplikácia ho automaticky presmeruje na domovskú obrazovku. Pre prácu bez internetu je k dispozícii tlačidlo **„Lokálny režim"**.

### 🏠 Domov

Hlavná obrazovka po prihlásení. Učitelia a admini vidia prehľad svojich predmetov — počet študentov, priemerné hodnotenie a dochádzku. Študenti vidia svoje zapísané predmety a známky. Filtrovanie podľa akademického roka a semestra.

Po kliknutí na predmet sa otvorí **detail predmetu** s ViewPager2 rozložením — tri záložky pre známky, dochádzku a zoznam študentov. Z detailu je možné spustiť aj **hromadné hodnotenie** pre rýchle zadanie známok celej skupine alebo **QR kód dochádzku** pre automatické zaznamenanie prítomnosti.

### 📷 QR kód dochádzka

Učiteľ môže spustiť QR kód dochádzku z detailu predmetu. Aplikácia vygeneruje rotujúci QR kód, ktorý sa zobrazí na obrazovke učiteľa. Študenti naskenujú QR kód svojím zariadením a dochádzka sa automaticky zaznamená. Učiteľ vidí v reálnom čase, kto sa prihlásil, a po ukončení sa výsledky uložia do databázy.

### 📝 Hromadné hodnotenie (Bulk Grading)

Samostatná obrazovka (`BulkGradeActivity`) umožňuje učiteľovi zadať známky viacerým študentom naraz:

- **Výber známky** cez Material chip komponenty (A, B, C, D, E, Fx) pre každého študenta
- **Spoločný dátum** pre celú skupinu cez date picker
- **Voliteľné poznámky** pre jednotlivých študentov s plynulou expand/collapse animáciou (350ms, DecelerateInterpolator)
- **Vyhľadávanie** a filtrovanie študentov v zozname
- **Podpora offline režimu** — funguje rovnako s lokálnou JSON databázou aj Firebase

### 📅 Rozvrh

Týždenný rozvrh s filtrami:
- **Všetky** — celý rozvrh
- **Dnes** — len dnešné hodiny
- **Nepárny/Párny týždeň** — podľa parity týždňa

Učitelia môžu pridávať **voľné dni** (dovolenky) s dátumom, časovým rozsahom a poznámkou. Rozvrh zobrazuje učebňu v dedikovanom „pill" odznaku na pravej strane karty predmetu pre rýchlu identifikáciu miestnosti. Navigácia medzi dňami podporuje swipe gesto s plynulým 1:1 peek náhľadom (obsah sleduje prst v reálnom čase) a zobrazuje všetky dni vrátane voľných dní s prázdnym stavom. Horizontálny navigátor dní má fade-out efekt na okrajoch pre indikáciu posúvateľnosti a prepínanie medzi čipmi prebieha bez vizuálneho „ghostingu".

### 👥 Študenti / Účty

V online režime (pre adminov) sa zobrazuje ako **„Účty"** — správa všetkých používateľov systému s filtrovaním podľa role (študent, učiteľ, admin). Admin môže zmeniť rolu používateľa (študent ↔ učiteľ) a zmena sa prejaví okamžite v reálnom čase — navigácia dotknutého používateľa sa automaticky prebuduje bez straty dát.

V offline režime sa zobrazuje ako **„Študenti"** — pridávanie a odstraňovanie študentov, správa zápisov predmetov.

### 📚 Predmety

Správa predmetov — vytváranie nových, úprava názvu, priradenie učiteľa a nastavenie semestra (zimný, letný, alebo obidva). Pri zmene semestra sa automaticky migrujú všetky známky a dochádzka.

### 🕐 Konzultačné hodiny

Učitelia môžu nastaviť svoje konzultačné hodiny cez obrazovku `ConsultingHoursActivity` s tromi záložkami:

- **Pridanie konzultačných hodín** — výber dňa v týždni, začiatok a koniec, typ miestnosti (Kabinet/Učebňa), číslo miestnosti, poznámka
- **Správa konzultačných hodín** — prehľad existujúcich hodín s možnosťou úpravy a mazania (pri mazaní sa kontrolujú aktívne rezervácie študentov)
- **Prehľad rezervácií** (len online) — zoznam študentov, ktorí si zarezervovali konzultáciu, s možnosťou úpravy, zrušenia a kontaktovania emailom

Študenti pristupujú ku konzultačným hodinám cez záložku **„Konzultácie"** v navigácii (`ConsultingHoursFragment`):

- **Prehľad učiteľov** — vyhľadávanie učiteľov s aktívnymi konzultačnými hodinami, zobrazenie dňa, času, učebne
- **Rezervácia termínu** — výber konkrétneho dátumu (podľa dňa v týždni), zadanie preferovaného času príchodu
- **Moje rezervácie** — prehľad vlastných aktívnych rezervácií s možnosťou úpravy a zrušenia; minulé rezervácie sa automaticky mažú

Konzultačné hodiny sú uložené v Firebase pod cestou `school_years/{year}/predmety/_consulting_{teacherUid}/timetable/` a rezervácie pod `consultation_bookings/{consultingSubjectKey}/`.

### 📆 Nový semester

Samostatná obrazovka (`NewSemesterActivity`) pre vytvorenie nového školského roka/semestra s ViewPager2 rozložením a tromi záložkami:

- **Nastavenia** — zadanie názvu roka, kopírovanie predmetov z predchádzajúceho roka
- **Predmety** — výber predmetov pre nový semester s vyhľadávaním a filtrami (Všetky/Vybrané/Nevybrané)
- **Študenti** — výber študentov pre nový semester s vyhľadávaním a filtrami

### ⚙️ Nastavenia

- **Vzhľad** — prepínanie tmavého režimu s paint-drop animáciou (kruhový reveal efekt)
- **Notifikácie** — zapínanie/vypínanie živej aktualizácie rozvrhu a upozornení na zmeny, nastavenie intervalov kontroly, zobrazenie učebne a nasledujúcej hodiny v notifikácii, konfigurácia počtu minút pred prvou hodinou, pripomienky konzultačných hodín, optimalizácia batérie
- **Admin funkcie** — správa akademických rokov a semestrov
- **Offline funkcie** — export/import databázy, vytváranie školských rokov, nastavenie mena učiteľa
- **Migrácia databázy** — manuálne spustenie migrácie štruktúry dát pre online aj offline režim (viď [Migrácia databázy](#-migrácia-databázy))
- **Účet** — odhlásenie, reset hesla (online), reset aplikácie (offline)

---

## 💾 Offline režim

UniTrack ponúka plnohodnotný offline režim bez potreby Firebase alebo internetu. Všetky dáta sa ukladajú lokálne v JSON formáte.

### Čo funguje offline:

- Kompletná správa predmetov, študentov a známok
- Hromadné hodnotenie (bulk grading) viacerých študentov naraz
- Konzultačné hodiny učiteľov (pridávanie, správa)
- Rozvrh a voľné dni
- Živá notifikácia rozvrhu (s podporou semester-aware filtrovania)
- Export celej databázy do JSON súboru (záloha)
- Import databázy zo súboru (obnova)
- Vytváranie akademických rokov a semestrov
- Migrácia databázy (automatická aj manuálna)
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

## 🔄 Migrácia databázy

Postupom vývoja sa menila štruktúra ukladania dát v UniTrack. Aplikácia obsahuje migračné mechanizmy, ktoré automaticky alebo manuálne prevedú staršie dátové štruktúry na aktuálny formát — pre online (Firebase) aj offline (lokálny JSON) režim.

Podrobná dokumentácia je v samostatnom dokumente: **[Migrácia databázy](docs/MIGRACIA.md)**

### Typy migrácií

| Migrácia | Čo rieši | Kedy sa spúšťa |
|---|---|---|
| **Globálne predmety → per-year** | Presun predmetov z globálneho uzla do štruktúry podľa školských rokov | Automaticky pri načítaní domovskej obrazovky, alebo manuálne z nastavení |
| **Per-year študenti → globálna štruktúra** | Zlúčenie študentov z per-year formátu do jednej globálnej kolekcie s vnorenou subjects mapou | Automaticky pri načítaní domovskej obrazovky, alebo manuálne z nastavení |
| **Migrácia semestra predmetu** | Presun známok, dochádzky a zápisov študentov pri zmene semestra predmetu (zimný ↔ letný ↔ obidva) | Automaticky pri zmene semestra predmetu |

### Manuálne spustenie

V nastaveniach aplikácie je k dispozícii tlačidlo **„Migrovať databázu"**, ktoré spustí všetky dostupné migrácie. Po dokončení sa zobrazí prehľad vykonaných zmien. Ak nie sú potrebné žiadne migrácie, aplikácia to oznámi.

---

## 🎨 Animácie a prechody

UniTrack kladie dôraz na plynulý a vizuálne príjemný používateľský zážitok. Všetky animácie sú implementované programaticky (nie cez XML), čo umožňuje presné riadenie parametrov.

### Paint-drop animácia (prepnutie tmavého režimu)

Pri prepnutí tmavého režimu sa spustí trojfázová animácia:
1. **Kvapka padá** — z pozície prepínača smerom nadol (520ms, AccelerateInterpolator)
2. **Splash efekt** — kvapka sa rozšíri a zmizne (250ms, DecelerateInterpolator)
3. **Kruhový reveal** — nová téma sa odkryje zdola nahor kruhovým prechodom (700ms)

Výsledkom je plynulý a vizuálne atraktívny prechod medzi svetlou a tmavou témou bez blikania alebo prerušenia.

### Splash obrazovka

- **Slide-up** — logo sa animovane vysúva zo spodku obrazovky do stredu (800ms, DecelerateInterpolator)
- **Fade prechod** — plynulé prepnutie zo splash obrazovky na hlavnú aktivitu

### Bulk Grading — Expand/Collapse

- Poznámkové pole pre každého študenta sa rozbalí/zbalí plynulou animáciou výšky (350ms, DecelerateInterpolator)
- Animácia meria skutočnú výšku obsahu (`wrap_content`) a interpoluje od 0 po cieľovú hodnotu

### Rozvrh

- **1:1 peek navigácia** — obsah nasledujúceho/predchádzajúceho dňa sleduje prst pri swipe geste
- **Fade-out okraje** — indikácia posúvateľnosti na krajoch chip navigátora
- **Chip prepínanie** bez ghosting efektu

---

## 🔔 Notifikácie

Aplikácia využíva päť notifikačných kanálov:

| Kanál | Popis | Priorita |
|---|---|---|
| **Rozvrh hodín** | Živá aktualizácia — ukazuje aktuálnu/ďalšiu hodinu, prestávku alebo voľno (segmentovaný progress bar na Android 16 s červenými segmentmi pre konzultačné hodiny učiteľov) | Tichá (nízka) |
| **Zrušené hodiny** | Upozornenie keď učiteľ označí hodinu ako zrušenú | Vysoká |
| **Známky** | Nová, upravená alebo odstránená známka | Vysoká |
| **Neprítomnosť** | Upozornenie na novú zaznamenanú neprítomnosť študenta | Vysoká |
| **Konzultačné hodiny** | Pripomienky pred konzultáciou (pre študentov aj učiteľov), notifikácie o nových rezerváciách a zrušení konzultácií | Vysoká |

Intervaly kontrol sú konfigurovateľné v nastaveniach — živá aktualizácia rozvrhu (predvolene každé 2 minúty) a kontrola zmien známok, neprítomnosti a zrušených hodín (predvolene každých 30 minút). Oba kanály je možné individuálne zapnúť alebo vypnúť. Notifikácie konzultačných hodín majú vlastný prepínač s nastaviteľným počtom minút pred pripomienkou. Notifikácie fungujú aj po reštarte zariadenia.

---

## 🔒 Oprávnenia

Aplikácia vyžaduje tieto Android oprávnenia:

- `INTERNET` — prístup na internet pre Firebase komunikáciu a kontrolu aktualizácií
- `POST_NOTIFICATIONS` — zobrazovanie notifikácií (Android 13+)
- `POST_PROMOTED_NOTIFICATIONS` — rozšírené notifikácie (Live Update na Android 16)
- `FOREGROUND_SERVICE` — beh notifikačnej služby na pozadí
- `RECEIVE_BOOT_COMPLETED` — plánovanie notifikácií po reštarte zariadenia
- `SCHEDULE_EXACT_ALARM` — plánovanie presných alarmov pre notifikácie
- `USE_EXACT_ALARM` — používanie presných alarmov
- `CAMERA` — prístup k fotoaparátu pre skenovanie QR kódov (dochádzka)
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — výnimka z optimalizácie batérie pre spoľahlivé doručovanie notifikácií

---

## 📖 Technická dokumentácia

Pre hlbšie pochopenie toho, ako UniTrack funguje pod kapotou, sú k dispozícii samostatné dokumenty:

| Dokument | Obsah |
|---|---|
| [Architektúra aplikácie](docs/ARCHITEKTURA.md) | Celková architektúra, MVVM vzor, priebeh dát medzi vrstvami, životný cyklus komponentov, SplashActivity |
| [Databáza a dátová vrstva](docs/DATABAZA.md) | Firebase Realtime Database cesty, lokálna JSON databáza, dátové modely, convenience metódy, migrácia semestrov |
| [Dochádzka a QR kódy](docs/DOCHADZKA.md) | Manuálna dochádzka, QR kód dochádzka (učiteľ/študent), formát QR kódu, Firebase pravidlá, bezpečnosť |
| [Migrácia databázy](docs/MIGRACIA.md) | Typy migrácií, kedy a prečo sa spúšťajú, ako fungujú pre online aj offline režim, bezpečnosť dát pri migrácii |
| [Navigácia a UI komponenty](docs/NAVIGACIA.md) | Navigation Component, PillNavigationBar, role-based navigácia, fragmenty a adaptéry, konzultačné hodiny |
| [Rozvrh hodín](docs/ROZVRH.md) | ViewPager2 navigácia, stavové karty (PAST/CURRENT/NEXT/FUTURE), živý progress bar, chip navigátor, voľné dni, filtrovanie parity, konzultačné hodiny |
| [Notifikačný systém](docs/NOTIFIKACIE.md) | Kanály, konfigurovateľné intervaly, Android 16 ProgressStyle, detekcia zmien známok, neprítomnosti, zrušených hodín a pripomienky konzultačných hodín |
| [Nastavenia aplikácie](docs/NASTAVENIA.md) | Podrobný popis všetkých nastavení — vzhľad, notifikácie, správa účtu, offline funkcie, SharedPreferences kľúče |
| [Tlač a export dát](docs/TLAC_A_EXPORT.md) | PDF reporty (predmet, študent, učiteľ), export/import lokálnej databázy, formát záloh |
| [Testovanie](docs/TESTOVANIE.md) | Metodika testovania, testovacie scenáre, matica zariadení, výsledky testovania |
| [Bezpečnosť](docs/BEZPECNOST.md) | Bezpečnostný model, Firebase autentifikácia, App Check, ochrana dát, oprávnenia, odporúčania |
| [Splnenie cieľa práce](docs/SPLNENIE_CIELA.md) | Mapovanie cieľa diplomovej práce na implementované funkcie, analýza splnenia |

---

## 🏷 Verzia

- **Verzia aplikácie:** 3.2.3
- **Kód verzie (Google):** 38
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
