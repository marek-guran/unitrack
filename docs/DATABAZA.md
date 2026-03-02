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

### Firebase Disk Persistence a Cache-First Loading

V `UniTrackApplication.onCreate()` je zapnutá disk persistence:

```kotlin
FirebaseDatabase.getInstance().setPersistenceEnabled(true)
```

Toto umožňuje Firebase SDK lokálne cachovať všetky načítané dáta. Keď sa rovnaká cesta načíta znova, dáta sú dostupné okamžite z lokálnej cache bez čakania na server.

Pre maximálne využitie tejto cache aplikácia používa rozšírenie `getFromCache()` (definované v `data/FirebaseExtensions.kt`), ktoré nahrádza štandardné `Query.get()`:

```kotlin
fun Query.getFromCache(): Task<DataSnapshot> {
    val source = TaskCompletionSource<DataSnapshot>()
    addListenerForSingleValueEvent(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            source.trySetResult(snapshot)
        }
        override fun onCancelled(error: DatabaseError) {
            source.trySetException(error.toException())
        }
    })
    return source.task
}
```

**Prečo `getFromCache()` namiesto `get()`:**
- `Query.get()` vždy skúsi server ako prvý a na cache sa obráti len keď je zariadenie offline — čo spôsobuje viditeľné oneskorenie
- `getFromCache()` (obaľujúci `addListenerForSingleValueEvent`) číta najprv z lokálnej cache — ak dáta existujú, odpoveď je okamžitá
- Všetky Firebase čítacie operácie naprieč celou aplikáciou boli nahradené volaním `.getFromCache()`

### Ochrana zápisov pri strate spojenia

V online režime sú všetky Firebase zápisy chránené cez `requireOnline()` guard (definovaný v `data/ConnectivityGuard.kt`). Ak je zariadenie dočasne odpojené od Firebase:
- Zápis sa nevykoná
- Zobrazí sa štylizovaný Snackbar „Ste offline – môžete iba prezerať"
- Používateľ môže naďalej prehliadať dáta z lokálnej cache

Stav pripojenia monitoruje `FirebaseConnectionMonitor` (singleton), ktorý sleduje Firebase cestu `.info/connected` a poskytuje synchronný prístup (`isConnected`) aj reaktívne LiveData (`connected`).

### Štruktúra stromu

Firebase používa stromovú JSON štruktúru. Tu je prehľad hlavných ciest:

```
root/
├── admins/
│   └── {uid}: true                          # Zoznam admin používateľov
│
├── pending_users/                           # Používatelia čakajúci na schválenie
│   └── {uid}/
│       ├── email: "student@uni.sk"
│       ├── name: "Ján Novák"
│       ├── tempKey: "..."                   # Dočasné heslo (na odstránenie z Auth pri odmietnutí)
│       └── status: "rejected"               # Voliteľné — nastavené adminom pri odmietnutí
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
│           ├── subjects/
│           │   ├── zimny: ["subject_key_1", "subject_key_2"]
│           │   └── letny: ["subject_key_3"]
│           └── consultation_timetable/      # Rezervácie konzultácií študenta
│               └── {entryKey}/
│                   ├── bookingKey: "{bookingKey}"
│                   ├── consultingSubjectKey: "_consulting_{uid}"
│                   ├── date: "05.03.2026"
│                   ├── timeFrom: "10:00"
│                   └── timeTo: "10:30"
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
│               ├── qr_code: "abc123..."     # Aktívny QR kód (dočasný, počas relácie)
│               ├── qr_last_scan/            # Posledný úspešný sken
│               │   ├── uid: "{studentUid}"
│               │   ├── name: "Ján Novák"
│               │   └── time: 1708700000000  # ServerValue.TIMESTAMP
│               ├── qr_fail/                 # Posledný neúspešný pokus
│               │   ├── name: "Ján Novák"
│               │   ├── reason: "Študent nie je zapísaný"
│               │   └── time: 1708700000000
│               └── {studentUid}/
│                   └── {date}/              # napr. "23.02.2026"
│                       ├── time: "08:00"
│                       ├── note: ""
│                       └── absent: true
│
├── teachers/
│   └── {uid}: "ucitel@uni.sk, Meno Učiteľa" # Email a meno učiteľa (pridanie = povýšenie na učiteľa, odstránenie = degradácia na študenta)
│
├── days_off/                                # Voľné dni
│   └── {teacherUid}/
│       └── {dayOffKey}/
│           ├── date: "23.02.2026"           # Začiatok
│           ├── dateTo: "04.03.2026"         # Koniec (prázdne = jeden deň)
│           ├── timeFrom: "12:00"            # Voliteľné
│           ├── timeTo: "14:00"              # Voliteľné
│           └── note: "Konferencia"
│
├── consultation_bookings/                   # Rezervácie konzultácií
│   └── {consultingSubjectKey}/              # napr. "_consulting_{teacherUid}"
│       └── {bookingKey}/
│           ├── consultingEntryKey: "{entryKey}"  # Odkaz na konzultačnú hodinu
│           ├── consultingSubjectKey: "_consulting_{uid}"
│           ├── studentUid: "{uid}"
│           ├── studentName: "Ján Novák"
│           ├── studentEmail: "student@uni.sk"
│           ├── date: "05.03.2026"           # Konkrétny dátum rezervácie (DD.MM.YYYY)
│           ├── timeFrom: "10:00"            # Čas príchodu
│           ├── timeTo: "10:30"              # Koniec termínu
│           └── teacherUid: "{uid}"
│
└── notifications/                           # Notifikácie pre používateľov
    └── {uid}/
        └── {notificationKey}/
            ├── type: "consultation_cancelled"  # Typ notifikácie
            └── ...                             # Ďalšie polia podľa typu

└── settings/                                # Globálne nastavenia
    └── allowed_domains/                     # Povolené emailové domény (verejne čitateľné)
        └── {index}: "uni.sk"
```

### Konvencie pomenovania

- **`predmety`** = predmety (subjects)
- **`hodnotenia`** = hodnotenia/známky (grades/marks)
- **`pritomnost`** = prítomnosť/dochádzka (attendance)
- **`qr_code`** = aktívny QR kód pre dochádzku (dočasný, existuje len počas relácie)
- **`qr_last_scan`** = posledný úspešný sken študenta (používa sa na monitorovanie v reálnom čase)
- **`qr_fail`** = posledný neúspešný pokus o sken (nezapísaný študent, neplatný kód)
- **`days_off`** = voľné dni (days off)
- **`school_years`** = školské roky
- **`consultation_bookings`** = rezervácie konzultácií (consultation bookings)
- **`consultation_timetable`** = rozvrh konzultácií študenta (pod `students/{uid}/`)
- **`notifications`** = notifikácie pre používateľov (napr. zrušenie konzultácie)
- **`settings`** = globálne nastavenia aplikácie (napr. `allowed_domains` — zoznam povolených emailových domén)
- **`pending_users`** = používatelia čakajúci na schválenie administrátorom (po registrácii)

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

### ConsultationBooking
```kotlin
data class ConsultationBooking(
    val key: String = "",
    val consultingEntryKey: String = "",  // Odkaz na konzultačnú hodinu učiteľa
    val consultingSubjectKey: String = "", // Kľúč konzultačného predmetu (_consulting_{uid})
    val studentUid: String = "",
    val studentName: String = "",
    val studentEmail: String = "",
    val date: String = "",               // Konkrétny dátum rezervácie (DD.MM.YYYY)
    val timeFrom: String = "",           // Čas príchodu
    val timeTo: String = "",             // Koniec termínu
    val teacherUid: String = ""
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
