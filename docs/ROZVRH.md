# 📅 Rozvrh hodín

Tento dokument podrobne popisuje obrazovku rozvrhu — jednu z najkomplexnejších častí aplikácie UniTrack. Rozvrh zobrazuje týždenný harmonogram hodín s pokročilou navigáciou medzi dňami, stavovými kartami, živým sledovaním priebehu hodín, detekciou voľných dní a filtrovaním podľa parity týždňa.

---

## Prehľad

Rozvrh je postavený na **ViewPager2** s horizontálnym swipom medzi dňami. Každá stránka reprezentuje jeden deň a obsahuje zoznam rozvrhových kariet s rôznymi vizuálnymi stavmi. Nad pagerom je horizontálny navigátor dní (chip lišta) a hlavička s informáciami o aktuálnom týždni.

### Kľúčové vlastnosti

- **Swipe navigácia** — plynulý prechod medzi dňami s 1:1 peek náhľadom (obsah sleduje prst)
- **Stavové karty** — štyri vizuálne stavy (PAST, CURRENT, NEXT, FUTURE) podľa aktuálneho času
- **Živý progress bar** — aktuálna hodina zobrazuje priebeh v reálnom čase (aktualizácia každých 5 sekúnd)
- **Chip navigátor dní** — animovaná horizontálna lišta s čipmi pre rýchlu navigáciu
- **Glassmorfický box** — zobrazenie aktuálneho času alebo tlačidlo „Späť na Dnes"
- **Detekcia voľných dní** — prečiarknutie hodín kolidujúcich s voľnými dňami učiteľa
- **Filtrovanie parity** — zohľadnenie párnych/nepárnych týždňov
- **Semester-aware filtrovanie** — zobrazenie len hodín patriacich do aktuálneho semestra
- **Prázdny stav** — animovaný emoji pre dni bez hodín
- **CRUD operácie** — pridávanie a mazanie rozvrhových záznamov (len admin), editácia učebne a poznámky (učiteľ/admin)
- **Správa voľných dní** — dialógy pre vytvorenie, úpravu a zmazanie voľných dní
- **Nekonečný scroll** — lazy-loading ďalších týždňov pri posúvaní doprava (max ~2 roky)

---

## Architektonické komponenty

Rozvrh je rozdelený do štyroch hlavných tried:

| Trieda | Účel |
|---|---|
| `TimetableFragment` | Hlavný kontrolér — načítanie dát, CRUD, synchronizácia stavu, filtre |
| `ScheduleAdapter` | Adaptér pre karty hodín — stavy, expand/collapse, progress bar |
| `DayChipAdapter` | Adaptér pre chip navigátor dní — animované prechody výberu |
| `TimetablePagerAdapter` | ViewPager2 adaptér — väzba stránok, prázdny stav, caching adaptérov |

### Tok dát

```
TimetableFragment
  ├── načíta dáta z Firebase / LocalDatabase
  ├── filtruje podľa role, semestra, parity
  ├── buduje zoznam ScheduleCardItem pre každý deň
  │
  ├── DayChipAdapter ← chip navigátor
  │     └── selectDate() → animovaný prechod
  │
  ├── TimetablePagerAdapter ← ViewPager2
  │     ├── buildItemsForDate() → zoznam kariet pre deň
  │     └── ScheduleAdapter ← karty hodín
  │           ├── stavové vizuály (PAST/CURRENT/NEXT/FUTURE)
  │           ├── expand/collapse poznámok
  │           └── updateProgress() → živý progress bar
  │
  └── updateHeader() → hlavička + glassmorfický box
```

---

## ViewPager2 a navigácia medzi dňami

### Swipe navigácia

`ViewPager2` umožňuje plynulý swipe medzi dňami. Každá stránka zodpovedá jednému dňu a obsahuje vertikálny `RecyclerView` s kartami hodín. Pri swipe geste sa obsah presúva 1:1 s prstom (peek náhľad), čo vytvára plynulý pocit priameho ovládania.

### Chip navigátor dní

Nad ViewPagerom je horizontálny `RecyclerView` s dennými čipmi (Pon, Uto, Str...). Čipy sú interaktívne:

- **Vybraný čip** — biely, rozšírený, tmavý text
- **Nevybraný čip** — polopriehľadný, kompaktnejší, biely text
- **Dnešný deň** — zobrazuje text „Dnes" namiesto skratky dňa
- **Animovaný prechod** — 200ms farebná interpolácia (pozadie, text, šírka) cez `ArgbEvaluator`

```
Aktívny čip:    [████ Dnes 26.02 ████]     biely, široký
Neaktívny čip:  [ Pon 02.03 ]               polopriehľadný, kompaktný
```

### Synchronizácia

Chip navigátor a ViewPager sú obojsmerne synchronizované:
- **Swipe ViewPager** → aktualizuje sa výber čipu + hlavička
- **Tap na čip** → ViewPager sa presunie na zodpovedajúci deň + hlavička sa aktualizuje

### Nekonečný scroll

Čipy sa lazy-loadujú po týždňoch:
- Štart: 12 týždňov dopredu
- Rozšírenie: +8 týždňov keď používateľ dosiahne posledné 3 čipy
- Maximum: 104 týždňov (~2 roky) pre ochranu pred preťažením pamäte

---

## Stavové karty hodín

Každá rozvrhová karta má jeden zo štyroch stavov podľa aktuálneho času:

### PAST (Uplynulá)

- **Kedy:** Hodina sa už skončila, alebo je voľný deň / nesprávna parita
- **Vizuál:** 98% mierka, 40–50% priehľadnosť, sivý „✓" odznak
- **Voľný deň:** Prečiarknutý názov predmetu + 40% priehľadnosť
- **Nesprávna parita:** Stlmený na 50% priehľadnosť

### CURRENT (Prebieha)

- **Kedy:** Aktuálny čas je medzi `startTime` a `endTime` hodiny
- **Vizuál:** Plná mierka a viditeľnosť, zvýraznený farebný okraj (3dp), odznak „TERAZ"
- **Progress bar:** Animovaný priebeh od 0 do 100% podľa uplynulého času
- **Zostávajúci čas:** Text „zostáva X min" aktualizovaný každých 5 sekúnd
- **Farebný okraj:** `colorPrimary` v svetlom režime, `hero_accent` v tmavom režime

### NEXT (Ďalšia)

- **Kedy:** Prvá nadchádzajúca hodina v daný deň (nie voľný deň ani nesprávna parita)
- **Vizuál:** Plná mierka a viditeľnosť, farebný odznak „ĎALŠIA"
- **Čas do začiatku:** Text „za Xm" (alebo „za XhYm" ak > 60 minút)

### FUTURE (Budúca)

- **Kedy:** Všetky ostatné platné hodiny po ďalšej
- **Vizuál:** Plná mierka a viditeľnosť, bez odznaku

### Určenie stavu

```
Ak je dnešný deň AND nie je voľný deň AND nie je nesprávna parita:
  → určí sa časovo (PAST/CURRENT/NEXT/FUTURE)
Ak je voľný deň ALEBO nesprávna parita:
  → vynútene PAST
Inak (budúci/minulý deň):
  → FUTURE
```

---

## Expand / Collapse kariet

Karty hodín podporujú rozbaľovanie pre zobrazenie poznámok a akčných tlačidiel:

- **Jedno rozbalenie** — v daný moment môže byť rozbalená maximálne jedna karta
- **Tapnutie** — rozbalí aktuálnu kartu a zbalí predchádzajúcu
- **Animácia** — plynulá výšková animácia (350ms, `DecelerateInterpolator`)
- **Obsah rozbalenia:**
  - Poznámka hodiny (ak existuje)
  - Tlačidlo Upraviť (ak má používateľ práva učiteľa alebo admina)
  - Tlačidlo Zmazať (len ak má používateľ admin práva)

---

## Glassmorfický box

V pravom hornom rohu obrazovky je špeciálny box s dvoma režimami:

| Stav | Obsah | Správanie |
|---|---|---|
| **Dnes** | „Aktuálny čas" + HH:mm | Aktualizuje sa každých 30 sekúnd |
| **Iný deň** | „Späť na Dnes" | Kliknutím sa vráti na dnešný deň |

Prechod medzi režimami je animovaný — box mení šírku s `ChangeBounds` animáciou (350ms, `DecelerateInterpolator`). Kliknutie na box vyvolá scale-pulse spätnú väzbu (0.9× → 1× s `OvershootInterpolator`).

---

## Detekcia voľných dní

### Logika kolízie

Voľný deň koliduje s rozvrhovou hodinou ak:

1. **Deň** — dátum voľného dňa padne na deň v týždni, v ktorom je hodina (alebo je v rozsahu dátumov)
2. **Čas** — ak voľný deň má časový rozsah, kontroluje sa prekrytie s časom hodiny
3. **Celý deň** — ak `timeFrom` a `timeTo` sú prázdne, zrušený je celý deň
4. **Viacdňový rozsah** — na prvom dni sa aplikuje len `timeFrom`, na poslednom len `timeTo`

### Vizuálny dopad

- Prečiarknutý názov predmetu (`STRIKE_THRU_TEXT_FLAG`)
- 40% priehľadnosť karty
- Stav vynútený na PAST (bez ohľadu na aktuálny čas)
- Karta je stále viditeľná, ale vizuálne stlmená

---

## Filtrovanie parity týždňa

### ISO parita

```kotlin
val weekNumber = LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
val isOddWeek = weekNumber % 2 != 0
```

### Filtrovanie

Rozvrhové záznamy majú pole `weekParity`:
- `"every"` — každý týždeň (vždy zobrazené)
- `"odd"` — nepárny týždeň
- `"even"` — párny týždeň

Hodiny s nesprávnou paritou sa zobrazujú stlmené (50% priehľadnosť) so stavom PAST, aby používateľ videl celkovú štruktúru rozvrhu, ale jasne rozlíšil aktívne hodiny.

---

## Semester-aware filtrovanie

Rozvrh zohľadňuje aktuálny semester:

- **Automatická detekcia:** Január–Jún = „letný", Júl–December = „zimný"
- **Manuálne prepísanie:** Cez `SharedPreferences` kľúč `semester`
- **Filtrovanie:** Predmety so semestrom `"zimny"` / `"letny"` sa zobrazia len v zodpovedajúcom semestre
- **Predmety s `"both"`** sa zobrazujú vždy

---

## Hlavička rozvrhu

Hlavička nad chip navigátorom zobrazuje:

- **Číslo týždňa** a paritu (napr. „TÝŽDEŇ 34 • Nepárny týždeň")
- **Pozdrav podľa času dňa:**
  - 5:00–8:59 → „Dobré ráno!"
  - 9:00–11:59 → „Dobrý deň!"
  - 12:00–17:59 → „Dobré popoludnie!"
  - 18:00–4:59 → „Dobrý večer!"

---

## CRUD operácie

### Pridanie / Úprava záznamu

Dialóg s poliami:
- **Deň** — Spinner (Pondelok–Nedeľa) — len admin
- **Začiatok / Koniec** — TimePicker (HH:mm) — len admin
- **Parita** — Spinner (Každý týždeň / Nepárny / Párny) — len admin
- **Učebňa** — Textové pole — admin aj učiteľ
- **Poznámka** — Textové pole — admin aj učiteľ

Pridávanie nových záznamov je dostupné len pre admina. Učitelia môžu upraviť iba učebňu a poznámku existujúcich záznamov — ostatné polia (deň, čas, parita) sú pre nich uzamknuté.

Pri ukladaní sa vykonáva **detekcia časového konfliktu** (len pri admin úpravách) — ak nový záznam prekrýva existujúcu hodinu v rovnaký deň a s kompatibilnou paritou, zobrazí sa varovanie.

### Mazanie záznamu

Potvrdzovacie dialóg pred zmazaním. Mazanie je dostupné len pre admina. Záznam sa odstráni z Firebase alebo lokálnej databázy.

### Prístupové práva

| Rola | Pridanie | Úprava | Mazanie |
|---|---|---|---|
| **Admin** | ✅ Všetky predmety | ✅ Všetky polia | ✅ Všetky |
| **Učiteľ** | ❌ | ✅ Len učebňa a poznámka | ❌ |
| **Študent** | ❌ | ❌ | ❌ |
| **Offline** | ✅ (admin práva) | ✅ Všetky polia | ✅ |

---

## Správa voľných dní

### Pridanie voľného dňa

Učitelia a admini môžu pridávať voľné dni cez FAB tlačidlo:

- **Dátum** — DatePicker (DD.MM.YYYY), voliteľný koncový dátum pre rozsah
- **Čas** — Voliteľný časový rozsah (časť dňa)
- **Poznámka** — Popis voľného dňa

### Správa existujúcich

Dialóg „Moje voľné dni" zobrazuje zoznam všetkých voľných dní používateľa zoradených podľa dátumu, s možnosťou inline editácie a zmazania. Dialóg sa automaticky obnoví po každej operácii.

---

## Zobrazenie kariet

### Vždy viditeľné

- **Názov predmetu** (môže byť prečiarknutý pri voľnom dni)
- **Časový rozsah** (napr. „08:00 – 09:30")

### Podmienene viditeľné

| Prvok | Podmienka zobrazenia |
|---|---|
| **Učebňa** (pill odznak) | Ak `classroom` nie je prázdna |
| **Meno učiteľa** | Ak je priradený učiteľ (ikona + meno) |
| **Parita týždňa** | Ak `weekParity` nie je `"every"` |
| **Stavový odznak** | Ak stav je PAST, CURRENT alebo NEXT |
| **Zostávajúci čas** | Ak stav je CURRENT alebo NEXT |
| **Progress bar** | Ak stav je CURRENT |

### Vizuálny štýl kariet

- **Striedavé pozadie** — párne/nepárne riadky majú mierne odlišné farby
- **Téma-aware farby** — automatické prispôsobenie svetlému/tmavému režimu
- **Farebný akcent** — `colorPrimary` v svetlom režime, `hero_accent` v tmavom režime pre lepší kontrast

---

## Animácie a vizuálne efekty

### Vstupná animácia fragmentu

Pri zobrazení fragmentu sa celý obsah plynulo animuje:
- Fade-in (0 → 1 priehľadnosť)
- Slide-up (40px posun → 0)
- Trvanie: 500ms, `DecelerateInterpolator(2f)`

### Vstupná animácia kariet

Pri zobrazení stránky sa karty postupne vysúvajú (staggered slide-up animácia) — každá karta sa objaví s mierne oneskorením za predchádzajúcou.

### Chip prechody

Pri zmene vybraného dňa sa animuje:
- Farba pozadia čipu (200ms interpolácia)
- Farba textu (deň + dátum)
- Šírka čipu (padding interpolácia)
- Interpolátor: `AccelerateDecelerateInterpolator`

### Expand/Collapse kariet

- Výšková animácia: 350ms, `DecelerateInterpolator(1.5f)`
- Rozbalenie: výška 0 → cieľová výška (meraná v pozadí)
- Zbalenie: aktuálna výška → 0

### Prázdny stav

Dni bez hodín zobrazujú animovaný emoji:
- Pop-in: mierka 0 → 1 s `OvershootInterpolator(2f)` (500ms)
- Nepretržitý bounce: -24px oscilácia, 1000ms cyklus, nekonečné opakovanie
- Emoji sa animuje až po načítaní dát (ochrana pred predčasnou animáciou)

### Spätná väzba pri dotyku

Tapnutie na kartu alebo čip vyvolá:
- Zmenšenie na 0.97× (80ms)
- Návrat na 1.0× s `OvershootInterpolator(2f)` (200ms)

---

## Online vs Offline režim

### Online režim

1. Overí sa rola používateľa cez Firebase (`teachers/{uid}`, `admins/{uid}`)
2. Načítajú sa predmety podľa role:
   - **Učiteľ** — predmety s zodpovedajúcim `teacherEmail`
   - **Študent** — zapísané predmety (cez `students/{yearKey}/{uid}/subjects`)
3. Načítajú sa voľné dni všetkých učiteľov (`days_off/`)
4. Načíta sa cache mien učiteľov (`teachers/`)
5. Zavolá sa `buildSchedule()` pre zostavenie UI

### Offline režim

1. Používateľ má automaticky práva učiteľa aj admina
2. Predmety sa načítajú z `LocalDatabase.getSubjects()` (filtrované podľa semestra)
3. Rozvrhové záznamy z `LocalDatabase.getTimetableEntries()`
4. Voľné dni z `LocalDatabase.getDaysOff("offline_admin")`
5. UID používateľa je konštanta `OfflineMode.LOCAL_USER_UID` (`"local_user"`)

---

## Periodická aktualizácia

### Progress bar

Pre hodiny v stave CURRENT sa každých 5 sekúnd aktualizuje:
- Progress bar (percentuálny priebeh)
- Text zostávajúceho času („zostáva X min")

Aktualizácia prebieha cez `Handler.postDelayed()` a je aktívna len keď aktuálna stránka obsahuje CURRENT kartu.

### Glassmorfický box

Aktuálny čas v glassmorfickom boxe sa aktualizuje každých 30 sekúnd.

---

## Dátový model

### TimetableEntry

```kotlin
data class TimetableEntry(
    val key: String = "",
    val day: String = "",            // "monday" ... "sunday"
    val startTime: String = "",      // "13:50"
    val endTime: String = "",        // "14:50"
    val weekParity: String = "every", // "every" | "odd" | "even"
    val classroom: String = "",      // "A402"
    val note: String = "",
    val subjectKey: String = "",
    val subjectName: String = ""
)
```

### ScheduleCardItem

```kotlin
data class ScheduleCardItem(
    val entry: TimetableEntry,
    val state: ScheduleCardState,    // PAST / CURRENT / NEXT / FUTURE
    val isDayOff: Boolean,
    val isWrongParity: Boolean,
    val teacherName: String?
)
```

### ScheduleCardState

```kotlin
enum class ScheduleCardState {
    PAST,      // Uplynulá hodina
    CURRENT,   // Prebieha
    NEXT,      // Ďalšia nadchádzajúca
    FUTURE     // Budúca
}
```

### DayChipItem

```kotlin
data class DayChipItem(
    val dayKey: String,      // "monday", "tuesday", ...
    val shortName: String,   // "Dnes", "Pon", "Uto", ...
    val date: LocalDate,
    val isSelected: Boolean
)
```

---

[← Späť na README](../README.md)
