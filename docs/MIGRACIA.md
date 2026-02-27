# 🔄 Migrácia databázy

Tento dokument popisuje migračné mechanizmy v aplikácii UniTrack — prečo sú potrebné, aké typy migrácií existujú, ako fungujú a ako ich spustiť.

---

## Prečo je migrácia potrebná

Počas vývoja UniTrack sa menila štruktúra ukladania dát. Pôvodne boli predmety uložené globálne a študenti boli viazaní na konkrétny školský rok. Aktuálna verzia používa predmety priradené k školským rokom a študentov v jednej globálnej kolekcii.

Aby sa existujúce dáta nestratili, aplikácia obsahuje migračné funkcie, ktoré automaticky prevedú staršie štruktúry na aktuálny formát. Migrácie sú bezpečné — neprepíšu existujúce dáta a pri opakovanom spustení nespôsobia duplicity.

---

## Typy migrácií

### 1. Globálne predmety → per-year štruktúra

**Problém:** V staršej verzii boli predmety uložené v globálnom uzle `predmety/`. Aktuálna verzia očakáva predmety v rámci školských rokov: `school_years/{yearKey}/predmety/`.

**Čo migrácia robí:**
1. Načíta všetky predmety z globálneho uzla `predmety/`
2. Pre každý školský rok skontroluje, či už má vlastné predmety
3. Ak nie — skopíruje globálne predmety do daného školského roka
4. Po úspešnej migrácii odstráni globálny uzol `predmety/`

**Implementácia:**
- Offline: `LocalDatabase.migrateGlobalSubjectsToYears()`
- Online: `SettingsFragment.migrateOnlineDb()` (Firebase cez async callbacky)

### 2. Per-year študenti → globálna štruktúra

**Problém:** V staršej verzii boli študenti uložení per-year: `students/{yearKey}/{uid}/`. Aktuálna verzia používa globálnu štruktúru: `students/{uid}/` s vnorenou mapou predmetov podľa rokov.

**Čo migrácia robí:**
1. Detekuje, či existujú kľúče v `students/` zodpovedajúce formátu školského roka (napr. `2024_2025`)
2. Pre každého študenta zlúči údaje z rôznych rokov do jedného záznamu
3. Zachová meno a email (prioritou je neprázdna hodnota)
4. Predmety sa premiestnia do vnorenej štruktúry: `subjects/{yearKey}/{semester}: [subjectKeys]`
5. Nahradí celý uzol `students/` novou globálnou štruktúrou

**Stará štruktúra:**
```
students/
├── 2024_2025/
│   └── uid123/
│       ├── name: "Ján Novák"
│       ├── email: "jan@uni.sk"
│       └── subjects/
│           ├── zimny: ["mat1", "fyz1"]
│           └── letny: ["mat2"]
└── 2025_2026/
    └── uid123/
        └── ...
```

**Nová štruktúra:**
```
students/
└── uid123/
    ├── name: "Ján Novák"
    ├── email: "jan@uni.sk"
    └── subjects/
        ├── 2024_2025/
        │   ├── zimny: ["mat1", "fyz1"]
        │   └── letny: ["mat2"]
        └── 2025_2026/
            └── ...
```

**Implementácia:**
- Offline: `LocalDatabase.migrateStudentsToGlobal()`
- Online: `SettingsFragment.migrateStudentsOnline()` a `HomeFragment.migrateStudentsOnline()`

### 3. Migrácia semestra predmetu

**Problém:** Keď učiteľ zmení semester predmetu (napríklad z „zimný" na „letný"), všetky súvisiace dáta — známky, dochádzka a zápisy študentov — musia byť presunuté do nového semestra.

**Čo migrácia robí:**
1. Určí, ktoré semestre sa odobrali a ktoré pribudli
2. Pre každý školský rok a každý odobraný semester:
   - Presunie zápisy študentov do cieľového semestra
   - Presunie známky (`hodnotenia/{year}/{oldSem}/{subject}/` → `hodnotenia/{year}/{newSem}/{subject}/`)
   - Presunie dochádzku (`pritomnost/{year}/{oldSem}/{subject}/` → `pritomnost/{year}/{newSem}/{subject}/`)
3. Existujúce dáta v cieľovom semestri sa neprepíšu (bezpečná migrácia)
4. Po presune vymaže dáta z pôvodného semestra

**Príklady:**

| Zmena | Výsledok |
|---|---|
| `zimny` → `letny` | Všetky dáta sa presunú zo zimného do letného semestra |
| `both` → `zimny` | Dáta z letného semestra sa presunú do zimného; zimné zostanú |
| `zimny` → `both` | Nič sa nemigruje — predmet je teraz v oboch semestroch |

**Implementácia:**
- Offline: `LocalDatabase.migrateSubjectSemester(subjectKey, oldSemester, newSemester)`
- Spúšťa sa automaticky pri zmene semestra predmetu v `SubjectsManageFragment` a `SettingsFragment`

---

## Kedy sa migrácie spúšťajú

### Automaticky

- **Pri načítaní domovskej obrazovky** (`HomeFragment`) — aplikácia skontroluje, či existujú legacy dáta (globálne predmety alebo per-year študenti) a ak áno, spustí migráciu na pozadí
- **Pri zmene semestra predmetu** — okamžite sa spustí migrácia semestra pre daný predmet

### Manuálne

- **Z nastavení** — tlačidlo „Migrovať databázu" v sekcii admin funkcií spustí všetky dostupné migrácie a zobrazí výsledok:
  - Ak boli nájdené a migrované dáta → zobrazí sa prehľad (napr. „predmety migrované, študenti migrovaní")
  - Ak neboli nájdené žiadne legacy dáta → zobrazí sa správa „Žiadne dáta nevyžadovali migráciu"

---

## Bezpečnosť dát pri migrácii

- **Žiadne prepisovanie** — ak v cieľovom umiestnení už existujú dáta s rovnakým kľúčom, zachovajú sa pôvodné (target wins)
- **Idempotentnosť** — opakované spustenie migrácie nespôsobí duplicity ani stratu dát
- **Offline bezpečnosť** — lokálna databáza je `@Synchronized`, takže počas migrácie nedôjde k race condition
- **Online bezpečnosť** — Firebase operácie sú atomické na úrovni jednotlivých zápisov

---

## Detekcia legacy dát

Aplikácia automaticky detekuje, či sú potrebné migrácie:

| Kontrola | Metóda | Čo hľadá |
|---|---|---|
| Legacy globálne predmety | `LocalDatabase.hasLegacyGlobalSubjects()` | Existencia uzla `predmety/` na root úrovni |
| Legacy per-year študenti | `LocalDatabase.hasLegacyPerYearStudents()` | Kľúče v `students/` zodpovedajúce formátu `YYYY_YYYY` |

---

[← Späť na README](../README.md)
