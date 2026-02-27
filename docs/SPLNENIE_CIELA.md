# 🎯 Splnenie cieľa práce

Tento dokument mapuje cieľ diplomovej práce na konkrétne implementované funkcie v aplikácii UniTrack a dokazuje jeho naplnenie.

---

## Cieľ práce

> **Návrh a kompletná realizácia mobilnej aplikácie na evidenciu prítomnosti a hodnotenia študentov.**

---

## Rozklad cieľa na časti

Cieľ práce pozostáva z piatich kľúčových častí. Každá je nižšie analyzovaná a mapovaná na konkrétnu implementáciu.

---

### 1. Návrh

| Aspekt návrhu | Realizácia | Dokumentácia |
|---|---|---|
| Architektúra aplikácie | MVVM vzor (ViewModel + LiveData + Fragmenty) | [ARCHITEKTURA.md](ARCHITEKTURA.md) |
| Návrh databázy | Stromová JSON štruktúra (Firebase + lokálna replika) | [DATABAZA.md](DATABAZA.md) |
| Návrh používateľského rozhrania | Material Design 3, responzívne layouty (telefón + tablet) | [NAVIGACIA.md](NAVIGACIA.md) |
| Návrh notifikačného systému | 4 kanály, konfigurovateľné intervaly, Android 16 podpora | [NOTIFIKACIE.md](NOTIFIKACIE.md) |
| Návrh bezpečnostného modelu | Firebase Auth, App Check (Play Integrity), role-based prístup, offline sandbox | [BEZPECNOST.md](BEZPECNOST.md) |

---

### 2. Kompletná realizácia

Aplikácia je plne funkčná a pripravená na reálne nasadenie. Všetky navrhnuté funkcie sú implementované v oboch režimoch (online aj offline):

| Metrika | Hodnota |
|---|---|
| Celkový počet Kotlin súborov | 30+ |
| Počet XML layoutov | 55 (vrátane 18 tabletových variantov) |
| Počet dátových modelov | 9 |
| Počet RecyclerView adaptérov | 10 |
| Počet notifikačných kanálov | 4 |
| Počet PDF reportov | 3 typy |
| Podporované Android verzie | 12 – 16 (API 31 – 36) |

---

### 3. Evidencia prítomnosti (dochádzka)

Toto je prvá kľúčová časť cieľa. Aplikácia implementuje kompletný systém evidencie prítomnosti:

| Funkcia | Popis | Umiestnenie v kóde |
|---|---|---|
| **Zaznamenanie dochádzky** | Učiteľ označí študentov ako prítomných/neprítomných | `SubjectDetailFragment.showMarkAttendanceDialog()` |
| **Hromadné zaznamenanie** | Dialóg so zoznamom všetkých študentov, chipové tlačidlá prítomný/neprítomný, tlačidlo „Označiť všetkých" | `SubjectDetailFragment.showMarkAttendanceDialog()` |
| **Výber dátumu** | DatePicker pre ľubovoľný dátum záznamu | `SubjectDetailFragment` — DatePickerDialog |
| **Úprava záznamu** | Zmena dátumu, času, poznámky a stavu prítomnosti | `SubjectDetailFragment.showEditAttendanceDialog()` |
| **Mazanie záznamu** | Odstránenie s potvrdením a možnosťou vrátenia (Undo) | `SubjectDetailFragment.removeAttendance()` |
| **Percentuálny prehľad** | Výpočet prítomných/celkom + percento (napr. „8/10 (80%)") | `SubjectDetailFragment` — výpočet priemeru |
| **Detail dochádzky** | Dialóg s kompletným zoznamom záznamov zoradených podľa dátumu | `SubjectDetailFragment.showAttendanceDetailDialog()` |
| **Per predmet** | Záznamy sú viazané na konkrétny predmet | Databázová cesta: `pritomnost/{rok}/{semester}/{predmet}/` |
| **Per semester a rok** | Filtrovanie podľa akademického roka a semestra | Spinnery v HomeFragment |
| **Online ukladanie** | Firebase Realtime Database | `db.child("pritomnost")...setValue()` |
| **Offline ukladanie** | Lokálna JSON databáza | `LocalDatabase.setAttendance()` |
| **Notifikácie** | Upozornenie študenta na novú zaznamenanú neprítomnosť | `NextClassAlarmReceiver.checkAbsenceChanges()` |
| **PDF export** | Dochádzka zahrnutá v reporte predmetu (stĺpec „Prítomnosť") | `SubjectReportPrintAdapter` |
| **Migrácia semestrov** | Automatický presun záznamov pri zmene semestra predmetu | `LocalDatabase.migrateSubjectSemester()` |

---

### 4. Hodnotenie študentov (známky)

Toto je druhá kľúčová časť cieľa. Aplikácia implementuje kompletný systém hodnotenia:

| Funkcia | Popis | Umiestnenie v kóde |
|---|---|---|
| **Pridanie známky** | Výber stupňa (A–Fx), názov, popis, poznámka, dátum | `SubjectDetailFragment.showAddMarkDialog()` |
| **Stupnica A–Fx** | 6-stupňová škála: A (1,0), B (2,0), C (3,0), D (4,0), E (5,0), Fx (6,0) | `CHIP_TO_GRADE` mapovanie |
| **Úprava známky** | Zmena všetkých atribútov existujúcej známky | `SubjectDetailFragment.editMark()` |
| **Mazanie známky** | Odstránenie s potvrdením | `SubjectDetailFragment.removeMark()` |
| **Výpočet priemeru** | Aritmetický priemer všetkých známok študenta v predmete | `SubjectDetailFragment.calculateAverage()` |
| **Navrhovaná známka** | Odporúčanie ďalšej známky na základe aktuálneho priemeru | `SubjectDetailFragment.suggestMark()` |
| **Zobrazenie pre študenta** | Študent vidí vlastné známky v prehľadnom dialógu | `HomeFragment.openStudentMarksDialogAsStudent()` |
| **Per predmet** | Známky sú viazané na konkrétny predmet | Databázová cesta: `hodnotenia/{rok}/{semester}/{predmet}/` |
| **Per semester a rok** | Filtrovanie podľa akademického roka a semestra | Spinnery v HomeFragment |
| **Online ukladanie** | Firebase Realtime Database | `db.child("hodnotenia")...push().setValue()` |
| **Offline ukladanie** | Lokálna JSON databáza | `LocalDatabase.addMark()` |
| **Notifikácie** | Upozornenie na novú, upravenú alebo odstránenú známku | `NextClassAlarmReceiver.checkGradeChanges()` |
| **PDF export** | Známky zahrnuté vo všetkých troch typoch reportov | `SubjectReportPrintAdapter`, `StudentResultsPrintAdapter` |
| **Migrácia semestrov** | Automatický presun známok pri zmene semestra predmetu | `LocalDatabase.migrateSubjectSemester()` |

---

### 5. Mobilná aplikácia

Aplikácia je natívna Android aplikácia napísaná v jazyku Kotlin:

| Aspekt | Realizácia |
|---|---|
| **Platforma** | Android (natívna aplikácia) |
| **Jazyk** | Kotlin |
| **Min. verzia** | Android 12 (API 31) |
| **Max. verzia** | Android 16 (API 36) |
| **Architektúra** | MVVM |
| **UI framework** | Material Design 3 |
| **Responzívnosť** | Telefón + tablet (vlastné layouty pre ≥ 600dp) |
| **Tmavý režim** | Podporovaný s okamžitým prepínaním |

---

## Nad rámec cieľa

Okrem stanovených požiadaviek boli implementované ďalšie funkcie, ktoré zvyšujú praktickú hodnotu aplikácie:

| Funkcia | Popis |
|---|---|
| **Správa rozvrhu** | Týždenný rozvrh s filtrami (párny/nepárny, dnes), podpora učební |
| **Voľné dni** | Učiteľ môže pridať voľné dni s dátumovým a časovým rozsahom |
| **Detekcia zrušených hodín** | Automatické upozornenie študenta na kolidujúce voľné dni |
| **Živá notifikácia rozvrhu** | Priebežná informácia o aktuálnej/ďalšej hodine, segmentovaný progress bar |
| **Android 16 ProgressStyle** | Farebné segmenty pre hodiny a prestávky na najnovšom Androide |
| **Duálny režim (online/offline)** | Plne funkčný offline režim s lokálnou JSON databázou |
| **Export/import databázy** | Zálohovanie a obnova celej databázy ako JSON súbor |
| **PDF reporty** | Tri typy: report predmetu, výsledky študenta, prehľad učiteľa |
| **Vlastná navigácia** | PillNavigationBar — animovaná „pilulka" s adaptívnym dizajnom |
| **Správa účtov** | Administrácia používateľov s priradením rolí (admin, učiteľ, študent) |
| **Správa predmetov** | Vytváranie, editácia, priradenie semestrov s automatickou migráciou dát |
| **Splash obrazovka** | Animovaný vstupný screen s logom a slide-up animáciou |
| **Reset hesla** | Odoslanie emailu na obnovu hesla |
| **Optimalizácia batérie** | Nastavenie výnimky pre spoľahlivé notifikácie |
| **Firebase App Check** | Ochrana backendových zdrojov pred neoprávneným prístupom (Play Integrity + Debug provider) |
| **Hromadné hodnotenie** | BulkGradeActivity — rýchle zadanie známok viacerým študentom naraz s chip komponentmi a expand/collapse animáciami |
| **Migrácia databázy** | Automatické aj manuálne migrácie štruktúry dát (globálne predmety → per-year, per-year študenti → globálna štruktúra) |
| **Pokročilé animácie** | Paint-drop efekt pri zmene tmavého režimu (kruhový reveal), plynulé expand/collapse animácie, 1:1 peek navigácia v rozvrhu |

---

## Záver

Cieľ diplomovej práce — **návrh a kompletná realizácia mobilnej aplikácie na evidenciu prítomnosti a hodnotenia študentov** — bol naplnený v plnom rozsahu. Obe kľúčové časti (evidencia prítomnosti a hodnotenie) sú implementované kompletne vrátane CRUD operácií, percentuálnych prehľadov, notifikácií, PDF exportov a duálneho online/offline režimu. Nad rámec cieľa bola aplikácia rozšírená o správu rozvrhu, voľných dní, účtov a predmetov, hromadné hodnotenie, pokročilé animácie, Firebase App Check ochranu a migráciu databázy, čo z nej robí ucelený a bezpečný nástroj pre akademickú správu.

---

[← Späť na README](../README.md)
