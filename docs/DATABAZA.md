# 🗄 Databáza a dátová vrstva

Tento dokument vysvetľuje, ako UniTrack pracuje s dátami — aké sú Firebase cesty, ako funguje lokálna databáza, aké modely sa používajú a ako prebieha migrácia pri zmene semestra.

---

## Duálny backend

UniTrack podporuje dva režimy a každý má vlastný dátový zdroj:

| Režim | Dátový zdroj | Autentifikácia |
|---|---|---|
| **Online** | Firebase Realtime Database | Firebase Auth (email + heslo) |
| **Offline** | `local_db.json` (lokálny JSON súbor) | Žiadna (lokálny používateľ) |

Prepínanie medzi režimami rieši `OfflineMode` — jednoduchý wrapper nad `SharedPreferences`, ktorý ukladá boolean hodnotu `offline_mode`.

```kotlin
// Kontrola režimu
if (OfflineMode.isOffline(context)) {
    // Použiť LocalDatabase
} else {
    // Použiť Firebase
}
```

---

## Firebase Realtime Database

### Štruktúra stromu

Firebase používa stromovú JSON štruktúru. Tu je prehľad hlavných ciest:

```
root/
├── admins/
│   └── {uid}: true                          # Zoznam admin používateľov
│
├── predmety/
│   └── {subjectKey}/
│       ├── name: "Matematika 1"
│       ├── teacherEmail: "ucitel@uni.sk"
│       ├── semester: "zimny"                # "zimny" | "letny" | "both"
│       └── timetable/
│           └── {entryKey}/
│               ├── day: "monday"
│               ├── startTime: "08:00"
│               ├── endTime: "09:30"
│               ├── weekParity: "every"      # "every" | "odd" | "even"
│               ├── classroom: "A402"
│               └── note: ""
│
├── school_years/
│   └── {yearKey}/                           # napr. "2025_2026"
│       └── name: "2025/2026"
│
├── students/
│   └── {yearKey}/
│       └── {uid}/
│           ├── email: "student@uni.sk"
│           ├── name: "Ján Novák"
│           └── subjects/
│               ├── zimny: ["subject_key_1", "subject_key_2"]
│               └── letny: ["subject_key_3"]
│
├── hodnotenia/                              # Známky
│   └── {yearKey}/
│       └── {semester}/                      # "zimny" | "letny"
│           └── {subjectKey}/
│               └── {studentUid}/
│                   └── {markId}/
│                       ├── grade: "B"       # A, B, C, D, E, Fx
│                       ├── name: "Test 1"
│                       ├── desc: "Prvý test"
│                       ├── note: ""
│                       └── timestamp: 1708700000000
│
├── pritomnost/                              # Dochádzka
│   └── {yearKey}/
│       └── {semester}/
│           └── {subjectKey}/
│               └── {studentUid}/
│                   └── {date}/              # napr. "23.02.2026"
│                       ├── time: "08:00"
│                       ├── note: ""
│                       └── absent: true
│
├── teachers/
│   └── {uid}: "ucitel@uni.sk, Meno Učiteľa" # Email a meno učiteľa
│
└── days_off/                                # Voľné dni
    └── {teacherUid}/
        └── {dayOffKey}/
            ├── date: "23.02.2026"           # Začiatok
            ├── dateTo: "04.03.2026"         # Koniec (prázdne = jeden deň)
            ├── timeFrom: "12:00"            # Voliteľné
            ├── timeTo: "14:00"              # Voliteľné
            └── note: "Konferencia"
```

### Konvencie pomenovania

- **`predmety`** = predmety (subjects)
- **`hodnotenia`** = hodnotenia/známky (grades/marks)
- **`pritomnost`** = prítomnosť/dochádzka (attendance)
- **`days_off`** = voľné dni (days off)
- **`school_years`** = školské roky

Kľúče školských rokov používajú formát s podčiarkovníkom (`2025_2026`), zatiaľ čo zobrazený názov používa lomku (`2025/2026`).

---

## Lokálna JSON databáza (LocalDatabase)

### Ako funguje

`LocalDatabase` je singleton trieda, ktorá replikuje Firebase stromovú štruktúru do JSON súboru `local_db.json` v privátnom úložisku aplikácie.

```
context.filesDir/local_db.json
```

### Path-based API

Pristupuje sa k nej cez cestu (path), rovnako ako k Firebase:

```kotlin
val db = LocalDatabase.getInstance(context)

// Čítanie
db.getJson("predmety/mat1")              // → JSONObject alebo null
db.getJsonArray("predmety/mat1/subjects") // → JSONArray alebo null
db.getString("predmety/mat1/name")        // → "Matematika 1" alebo null
db.getAny("predmety/mat1/semester")       // → Any alebo null
db.exists("admins/local_user")            // → true/false
db.getChildren("predmety")               // → List<String> kľúčov

// Zápis
db.put("predmety/mat1/name", "Nový názov")  // Nastaví hodnotu
db.remove("predmety/mat1")                   // Odstráni celý uzol
db.push("hodnotenia/2025_2026/zimny/mat1/uid1")  // Vygeneruje UUID kľúč

// Export / Import
db.exportToStream(outputStream)
db.importFromStream(inputStream)
db.exportToJson()                   // → String (JSON)
db.importFromJson(jsonString)       // Importuje z reťazca
```

### Convenience metódy

Pre bežné operácie poskytuje `LocalDatabase` vyššiu úroveň abstrakcie:

```kotlin
// Predmety
db.getSubjects()                           // → Map<String, JSONObject>
db.addSubject(key, name, teacherEmail, semester)
db.removeSubject(key)

// Študenti
db.getStudents(year)                       // → Map<String, JSONObject>
db.addStudent(year, uid, email, name, subjects)
db.removeStudent(year, uid)
db.updateStudentName(year, uid, name)
db.updateStudentSubjects(year, uid, semester, subjectKeys)

// Známky
db.addMark(year, semester, subjectKey, studentUid, mark)  // → markId
db.updateMark(year, semester, subjectKey, studentUid, markId, mark)
db.removeMark(year, semester, subjectKey, studentUid, markId)
db.getMarks(year, semester, subjectKey, studentUid)  // → Map<String, JSONObject>

// Dochádzka
db.setAttendance(year, semester, subjectKey, studentUid, date, entry)
db.removeAttendance(year, semester, subjectKey, studentUid, date)
db.getAttendance(year, semester, subjectKey, studentUid)  // → Map<String, JSONObject>

// Rozvrh
db.getTimetableEntries(subjectKey)         // → Map<String, JSONObject>
db.addTimetableEntry(subjectKey, entry)    // → entryId
db.removeTimetableEntry(subjectKey, entryKey)

// Voľné dni
db.getDaysOff(teacherUid)                  // → Map<String, JSONObject>
db.addDayOff(teacherUid, dayOff)           // → key
db.removeDayOff(teacherUid, key)

// Školské roky
db.getSchoolYears()                        // → Map<String, String>
db.addSchoolYear(key, name)

// Učitelia
db.addTeacher(uid, info)
db.getTeachers()                           // → Map<String, String>

// Meno učiteľa (offline)
db.setTeacherName(name)
db.getTeacherName()                        // → String?

// Ostatné
db.clearAll()                              // Vymaže a vytvorí predvolenú databázu
```

### Thread safety

Všetky operácie čítania aj zápisu sú `@Synchronized`. Po každom zápise sa automaticky volá `save()`, čo zapíše celý JSON strom do súboru. Toto je jednoduché a spoľahlivé pre veľkosť dát, s akou UniTrack pracuje.

### Predvolená databáza

Pri prvom spustení (alebo ak súbor neexistuje) sa vytvorí predvolená databáza s prázdnou štruktúrou a jedným školským rokom `2025/2026`.

---

## Dátové modely

Všetky modely sú v balíku `com.marekguran.unitrack.data.model`:

### Mark
```kotlin
data class Mark(
    val grade: String = "",      // "A", "B", "C", "D", "E", "Fx"
    val name: String = "",       // Názov hodnotenia ("Test 1")
    val desc: String = "",       // Popis
    val note: String = "",       // Poznámka / váha
    val timestamp: Long = 0      // Čas vytvorenia (milisekundy)
)
```

### MarkWithKey
```kotlin
data class MarkWithKey(
    val key: String,             // Firebase/lokálny kľúč
    val mark: Mark
)
```

### AttendanceEntry
```kotlin
data class AttendanceEntry(
    val date: String = "",       // "23.02.2026" (DD.MM.YYYY)
    val time: String = "",       // "08:00"
    val note: String = "",
    val absent: Boolean = false  // true = neprítomný
)
```

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

### DayOff
```kotlin
data class DayOff(
    val key: String = "",
    val date: String = "",       // Začiatok (DD.MM.YYYY)
    val dateTo: String = "",     // Koniec (prázdne = jeden deň)
    val timeFrom: String = "",   // Voliteľný začiatok času
    val timeTo: String = "",     // Voliteľný koniec času
    val note: String = "",
    val teacherUid: String = ""
)
```

### StudentDetail
```kotlin
data class StudentDetail(
    val studentUid: String,
    val studentName: String,
    val marks: List<MarkWithKey>,
    val attendanceMap: Map<String, AttendanceEntry> = emptyMap(),
    val average: String,         // Vypočítaný priemer ("1.5")
    val suggestedMark: String,   // Navrhovaná ďalšia známka
    val attendance: String = "", // Formátovaný text dochádzky
    val attRaw: String = ""      // Surové dáta dochádzky
)
```

### TeacherSubjectSummary
```kotlin
data class TeacherSubjectSummary(
    val subjectKey: String,
    val subjectName: String,
    val studentCount: Int,
    val averageMark: String,          // Priemerná známka predmetu
    val averageAttendance: String = "-"  // Priemerná dochádzka predmetu
)
```

### SubjectInfo
```kotlin
data class SubjectInfo(
    val key: String = "",
    val name: String,
    val marks: List<String>,     // Zoznam známok
    val average: String,
    val attendance: String,
    val attendanceCount: Map<String, AttendanceEntry>,
    val markDetails: List<Mark> = emptyList(),
    val teacherEmail: String = ""
)
```

### LoggedInUser
```kotlin
data class LoggedInUser(
    val userId: String,
    val displayName: String
)
```

---

## Migrácia semestrov

Keď učiteľ zmení semester predmetu (napríklad z „zimný" na „obidva"), `LocalDatabase.migrateSubjectSemester()` automaticky presunie všetky súvisiace dáta.

> Pre kompletný prehľad všetkých typov migrácií (globálne predmety → per-year, per-year študenti → globálna štruktúra, migrácia semestrov) vrátane príkladov a bezpečnostných záruk pozri **[Migrácia databázy](MIGRACIA.md)**.

### Ako to funguje

1. **Určí sa, čo sa mení** — aké semestre mal predmet predtým a aké bude mať teraz
2. **Identifikujú sa „odobraté" semestre** — tie, kde predmet už nebude
3. **Pre každý školský rok** a každý odobraný semester:
   - Presunie zápisy študentov do cieľového semestra
   - Presunie známky (`hodnotenia`) — bez prepisovania existujúcich
   - Presunie dochádzku (`pritomnost`) — bez prepisovania existujúcich
4. **Vymaže dáta z pôvodného semestra**

### Príklad

Predmet „Informatika" sa zmení zo `zimny` na `letny`:
- Všetky známky z `hodnotenia/2025_2026/zimny/informatika/` sa presunú do `hodnotenia/2025_2026/letny/informatika/`
- Dochádzka sa presunie rovnako
- Zápisy študentov sa aktualizujú

Ak sa predmet zmení na `both`, nič sa nemigruje — predmet je teraz v oboch semestroch a existujúce dáta zostanú kde sú.

---

## Result wrapper

Pre spracovanie výsledkov operácií sa používa sealed class:

```kotlin
sealed class Result<out T : Any> {
    data class Success<out T : Any>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
}
```

Používa sa hlavne v login flow — `LoginDataSource` → `LoginRepository` → `LoginViewModel`.

---

[← Späť na README](../README.md)
