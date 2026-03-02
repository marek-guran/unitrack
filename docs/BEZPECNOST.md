# 🔒 Bezpečnosť

Tento dokument popisuje bezpečnostný model aplikácie UniTrack — ako je zabezpečená autentifikácia, ochrana dát, komunikácia so serverom a aké odporúčania platia pre produkčné nasadenie.

---

## Prehľad bezpečnostného modelu

UniTrack pracuje v dvoch režimoch, z ktorých každý má odlišný bezpečnostný profil:

| Aspekt | Online režim | Offline režim |
|---|---|---|
| **Autentifikácia** | Firebase Auth (email + heslo) | Žiadna (lokálny prístup) |
| **Úložisko dát** | Firebase Realtime Database (cloud) | Lokálny JSON súbor na zariadení |
| **Prenos dát** | HTTPS (šifrované cez TLS) | Žiadny prenos — dáta neopúšťajú zariadenie |
| **Riadenie prístupu** | Firebase Security Rules + admin detekcia | Plný prístup (lokálny používateľ) |
| **App Check** | Firebase App Check (Play Integrity / Debug) | Nepoužíva sa (lokálne dáta) |
| **Zálohovanie** | Firebase zabezpečuje redundanciu | Manuálny export do JSON súboru |

---

## Autentifikácia

### Firebase Authentication

V online režime sa autentifikácia rieši cez **Firebase Auth** s metódou email + heslo:

1. Používateľ zadá email a heslo na prihlasovacej obrazovke
2. `LoginViewModel` validuje formulár (neprázdne polia, platný formát emailu)
3. `FirebaseAuth.signInWithEmailAndPassword()` odošle požiadavku
4. Firebase overí prihlasovacie údaje na strane servera
5. Pri úspechu sa vráti `FirebaseUser` objekt s UID
6. Token sa automaticky obnoví (Firebase SDK to rieši interne)

### Čo Firebase Auth zabezpečuje

- Heslá sa nikdy neukladajú na zariadení — Firebase ukladá len autentifikačný token
- Komunikácia prebieha výhradne cez HTTPS
- Ochrana proti brute-force útokom (rate limiting na strane Firebase)
- Podpora resetu hesla cez email
- Automatická obnova tokenov

### Offline režim

V offline režime sa autentifikácia nepoužíva. Používateľ pristupuje k lokálnym dátam priamo bez hesla. Toto je zámerné — offline režim slúži pre individuálneho učiteľa na osobnom zariadení, kde sú dáta chránené samotným zariadením (PIN, odtlačok prsta, šifrovanie úložiska).

---

## Riadenie prístupu

### Role používateľov

UniTrack rozlišuje tri role a jeden prechodný stav:

| Rola | Detekcia | Oprávnenia |
|---|---|---|
| **Admin** | Firebase cesta `admins/{uid}` existuje | Správa účtov, predmetov, školských rokov, zmena rolí používateľov |
| **Učiteľ** | Firebase cesta `teachers/{uid}` existuje | Správa známok, dochádzky, rozvrhu, voľných dní |
| **Študent** | Žiadna z vyššie uvedených ciest | Zobrazenie vlastných známok a dochádzky |
| **Čaká na schválenie** | Firebase cesta `pending_users/{uid}` existuje | Žiadne — zobrazí sa čakacia obrazovka |

### Registrácia a schvaľovanie

Po registrácii cez prihlasovaciu obrazovku sa nový používateľ pridá do `pending_users/{uid}` (nie priamo do `students/`). Administrátor v záložke **Účty** vidí čakajúcich používateľov vo filtri „Čaká na schválenie" a môže:

- **Schváliť ako študenta** — atomicky presunie z `pending_users/` do `students/` s priradením aktuálneho školského roka
- **Schváliť ako učiteľa** — atomicky presunie z `pending_users/` do `teachers/`
- **Odmietnuť** — odstráni z `pending_users/` a zablokuje prístup do aplikácie

Čakajúci používateľ vidí celostránkovú čakaciu obrazovku (`PendingApprovalActivity`) s logom aplikácie. Real-time listener na `pending_users/{uid}` detekuje schválenie alebo odmietnutie:
- Pri schválení automaticky presmeruje do hlavnej aplikácie
- Pri odmietnutí automaticky odhlási a presmeruje na prihlasovaciu obrazovku

### Zmena role používateľa

Admin môže zmeniť rolu používateľa (študent ↔ učiteľ) cez obrazovku Účty. Zmena role je implementovaná bezpečne:

- **Atomická operácia:** Zmena prebieha cez Firebase `updateChildren()`, čo zaručuje konzistentnosť
- **Zachovanie dát:** Pri povýšení na učiteľa sa pridá `teachers/{uid}`, ale `students/{uid}` zostáva netknutý. Pri degradácii na študenta sa `teachers/{uid}` odstráni a `students/{uid}` sa aktualizuje len s menom a emailom — existujúce predmety, školské roky a konzultácie zostávajú zachované
- **Real-time aktualizácia:** Dotknutý používateľ má v `MainActivity` aktívny `ValueEventListener` na `teachers/{uid}`, takže zmenu role uvidí okamžite bez reštartu aplikácie
- **Oprávnenia:** Len admin môže meniť role — Firebase Security Rules zabezpečujú, že zápis do `teachers/` a `admins/` je povolený len pre existujúcich adminov

### Detekcia admin práv

Admin detekcia prebieha v reálnom čase pri spustení aplikácie cez `ValueEventListener`:

```kotlin
// MainActivity.kt
val teacherRef = dbRef.child("teachers").child(uid)
teacherRef.addValueEventListener(object : ValueEventListener {
    override fun onDataChange(teacherSnap: DataSnapshot) {
        dbRef.child("admins").child(uid).get().addOnSuccessListener { adminSnap ->
            when {
                adminSnap.exists() -> buildNavigation(includeAdminTabs = true)
                teacherSnap.exists() -> buildNavigation(includeAdminTabs = false, showConsulting = false)
                else -> buildNavigation(includeAdminTabs = false, showConsulting = true)
            }
        }
    }
})
```

Toto je client-side kontrola, ktorá ovplyvňuje zobrazenie UI. Skutočná ochrana dát musí byť zabezpečená na strane Firebase cez Security Rules a App Check. Listener reaguje na zmeny v reálnom čase — ak admin zmení rolu používateľa, jeho navigácia sa okamžite prebuduje.

---

## Firebase App Check

UniTrack implementuje **Firebase App Check** na ochranu backendových zdrojov pred neoprávneným prístupom. App Check overuje, že každá požiadavka na Firebase pochádza z legitimnej inštancie aplikácie — nie z neoprávneného skriptu, bota alebo modifikovanej aplikácie.

### Prečo je to dôležité

Keďže UniTrack je open source projekt, zdrojový kód (vrátane Firebase konfigurácie) je verejne dostupný. Samotné Firebase Security Rules chránia dáta podľa autentifikácie a rolí, ale bez App Check by mohol ktokoľvek s platnými prihlasovacími údajmi pristupovať k databáze z ľubovoľnej aplikácie alebo skriptu. App Check pridáva ďalšiu vrstvu ochrany — overuje, že požiadavka pochádza z originálnej, nemodifikovanej aplikácie.

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

### Provideri podľa prostredia

| Prostredie | Provider | Princíp |
|---|---|---|
| **Release (produkcia)** | Play Integrity | Google Play Services overí integritu zariadenia a aplikácie pomocou SHA-256 podpisového certifikátu |
| **Debug (vývoj)** | Debug App Check | Vývojár zaregistruje debug token z logcatu v Firebase Console |

### Čo App Check zabezpečuje

- **Online verzia je uzamknutá** — bez platného atestačného tokenu (Play Integrity alebo debug) nie je možné čítať ani zapisovať dáta do Firebase
- **Offline režim nie je ovplyvnený** — lokálne dáta sú uložené priamo na zariadení a App Check sa na ne nevzťahuje
- **Ochrana open source projektu** — aj keď je kód verejne dostupný, prístup k produkčnej databáze vyžaduje kombináciu Firebase Auth + App Check + Security Rules

### Nastavenie pre vlastný Firebase projekt

1. V [Firebase Console](https://console.firebase.google.com/) → **App Check** → zapnite **Play Integrity** provider
2. Zaregistrujte SHA-256 odtlačok vášho podpisového certifikátu
3. Pre vývoj: spustite debug build, skopírujte debug token z logcatu a zaregistrujte ho v Firebase Console → App Check → **Manage debug tokens**
4. V nastaveniach Realtime Database zapnite **Enforce App Check** — od tohto momentu sú povolené len overené požiadavky

---

## Ochrana zápisov pri strate spojenia (online režim)

V online režime môže používateľ dočasne stratiť pripojenie k Firebase (napr. nestabilné Wi-Fi, mobilné dáta). Aby sa predišlo tichým chybám pri zápisoch, aplikácia implementuje viacvrstvovú ochranu:

### FirebaseConnectionMonitor

Centralizovaný singleton, ktorý monitoruje stav pripojenia cez špeciálny Firebase uzol `.info/connected`:

```kotlin
object FirebaseConnectionMonitor {
    val connected: LiveData<Boolean>   // Reaktívne UI pozorovanie
    var isConnected: Boolean           // Synchronný prístup (@Volatile)

    fun start()  // Spustí monitoring (volané z MainActivity)
    fun stop()   // Zastaví monitoring
}
```

### requireOnline() guard

Rozšírenia pre `Fragment` a `Activity` (v `ConnectivityGuard.kt`), ktoré zabránia zápisom keď je zariadenie offline:

- Ak `FirebaseConnectionMonitor.isConnected == true` → vráti `true`, operácia pokračuje
- Ak je zariadenie offline → zobrazí štylizovaný Snackbar a vráti `false`
- V lokálnom offline režime (`OfflineMode`) vždy vráti `true` (zápisy do lokálnej DB sú bezpečné)

Snackbar je štylizovaný s theme-aware farbami, zaoblenými rohmi a je ukotvený nad navigačnou lištou.

### Offline banner

V `MainActivity` sa pri strate pripojenia zobrazí červený banner na vrchu obrazovky s textom „Režim offline: Zobrazujú sa uložené dáta." Banner je riadený cez LiveData pozorovanie `FirebaseConnectionMonitor.connected`.

### QR kód funkcie pri strate spojenia

QR kód dochádzka vyžaduje aktívne pripojenie k Firebase (atomické transakcie). Keď je zariadenie offline:
- **Učiteľský FAB** pre QR dochádzku je zašednutý a neaktívny
- **Študentský FAB** pre QR skener je zašednutý a neaktívny
- **QrAttendanceActivity** a **QrScannerActivity** zobrazujú celostránkový offline overlay, ak sa spojenie stratí počas relácie

### Čo ochrana zabezpečuje

- Používateľ v online režime môže bezpečne prehliadať dáta z Firebase cache aj bez aktívneho pripojenia
- Žiadne zápisy sa nevykonajú keď nie je aktívne pripojenie — nedochádza k tichým chybám
- Používateľ je vždy informovaný o stave pripojenia (banner + Snackbar)
- QR kód funkcie sú úplne zablokované pri offline stave (vyžadujú real-time Firebase transakcie)

---

## Ochrana dát

### Online režim — Firebase Realtime Database

Dáta uložené vo Firebase sú chránené:

- **Šifrovaním počas prenosu** — všetka komunikácia s Firebase prebieha cez HTTPS/TLS
- **Šifrovaním v pokoji** — Firebase automaticky šifruje dáta na svojich serveroch
- **Firebase Security Rules** — pravidlá na strane servera, ktoré definujú kto môže čítať a zapisovať na ktoré cesty

### Odporúčané Firebase Security Rules

Pre produkčné nasadenie UniTracku sa odporúča nastaviť nasledujúce pravidlá v Firebase Console:

```json
{
  "rules": {
    ".read": "auth != null && root.child('admins').child(auth.uid).exists()",
    ".write": "auth != null && root.child('admins').child(auth.uid).exists()",

    "admins": {
      ".read": "auth != null && root.child('admins').child(auth.uid).exists()",
      ".write": "auth != null && root.child('admins').child(auth.uid).exists()",
      "$uid": {
        ".read": "auth != null && $uid === auth.uid",
        ".validate": "newData.isBoolean()"
      }
    },

    "teachers": {
      ".read": "auth != null",
      ".write": "auth != null && root.child('admins').child(auth.uid).exists()",
      "$uid": {
        ".validate": "newData.isString()"
      }
    },

    "pending_users": {
      ".read": "auth != null && root.child('admins').child(auth.uid).exists()",
      ".write": "auth != null && root.child('admins').child(auth.uid).exists()",
      "$uid": {
        ".read": "auth != null && $uid === auth.uid",
        ".write": "auth != null && $uid === auth.uid && !data.exists()",
        ".validate": "newData.hasChildren(['email', 'name', 'tempKey'])",
        "status": {
          ".write": "auth != null && $uid === auth.uid && !newData.exists()",
          ".validate": "newData.val() === 'rejected' || !newData.exists()"
        }
      }
    },

    "students": {
      ".read": "auth != null",
      ".write": "auth != null && (root.child('admins').child(auth.uid).exists() || root.child('teachers').child(auth.uid).exists())",
      "$uid": {
        "consultation_timetable": {
          ".read": "auth != null && $uid === auth.uid",
          ".write": "auth != null && $uid === auth.uid"
        }
      }
    },

    "predmety": {
      ".read": "auth != null",
      "$subjectKey": {
        ".write": "auth != null && root.child('admins').child(auth.uid).exists()",
        "timetable": {
          "$entryKey": {
            "classroom": {
              ".write": "auth != null && root.child('teachers').child(auth.uid).exists() && data.parent().exists()"
            },
            "note": {
              ".write": "auth != null && root.child('teachers').child(auth.uid).exists() && data.parent().exists()"
            }
          }
        }
      }
    },

    "hodnotenia": {
      ".write": "auth != null && (root.child('admins').child(auth.uid).exists() || root.child('teachers').child(auth.uid).exists())",
      "$year": {
        "$semester": {
          "$subjectKey": {
            ".read": "auth != null && (root.child('admins').child(auth.uid).exists() || root.child('teachers').child(auth.uid).exists())",
            "$studentUid": {
              ".read": "auth != null && $studentUid === auth.uid"
            }
          }
        }
      }
    },

    "pritomnost": {
      ".write": "auth != null && (root.child('admins').child(auth.uid).exists() || root.child('teachers').child(auth.uid).exists())",
      "$year": {
        "$semester": {
          "$subjectKey": {
            ".read": "auth != null && (root.child('admins').child(auth.uid).exists() || root.child('teachers').child(auth.uid).exists())",
            "qr_code": {
              ".read": "auth != null",
              ".write": "auth != null && !newData.exists()"
            },
            "qr_last_scan": {
              ".write": "auth != null",
              ".validate": "newData.hasChildren(['uid', 'name', 'time']) && newData.child('uid').val() === auth.uid"
            },
            "qr_fail": {
              ".write": "auth != null",
              ".validate": "newData.hasChildren(['name', 'reason', 'time'])"
            },
            "$studentUid": {
              ".read": "auth != null && $studentUid === auth.uid"
            }
          }
        }
      }
    },

    "school_years": {
      ".read": "auth != null",
      ".write": "auth != null && (root.child('admins').child(auth.uid).exists() || root.child('teachers').child(auth.uid).exists())",
      "$yearKey": {
        "predmety": {
          "$subjectKey": {
            ".write": "auth != null && (root.child('admins').child(auth.uid).exists() || root.child('teachers').child(auth.uid).exists())"
          }
        }
      }
    },

    "days_off": {
      ".read": "auth != null",
      "$teacherUid": {
        ".write": "auth != null && ($teacherUid === auth.uid || root.child('admins').child(auth.uid).exists())"
      }
    },

    "consultation_bookings": {
      ".read": "auth != null",
      "$subjectKey": {
        ".write": "auth != null",
        "$bookingKey": {
          ".write": "auth != null && (!data.exists() || data.child('studentUid').val() === auth.uid || root.child('admins').child(auth.uid).exists() || root.child('teachers').child(auth.uid).exists())"
        }
      }
    },

    "notifications": {
      "$uid": {
        ".read": "auth != null && $uid === auth.uid",
        ".write": "auth != null"
      }
    },

    "settings": {
      ".read": "auth != null",
      ".write": "auth != null && root.child('admins').child(auth.uid).exists()",
      "allowed_domains": {
        ".read": true
      }
    }
  }
}
```

#### Ako pravidlá fungujú

Firebase Security Rules sa vyhodnocujú na strane servera pri každom čítaní alebo zápise. Pravidlá používajú stromovú štruktúru, kde každá úroveň zodpovedá ceste v databáze. Premenné začínajúce `$` (napr. `$uid`, `$teacherUid`) zachytávajú dynamické segmenty cesty. Pravidlá na vyššej úrovni sa kaskádovo prenášajú na nižšie úrovne — ak rodičovský uzol povolí prístup, potomkovia ho nemôžu odobrať.

#### Čo pravidlá zabezpečujú

- **admins** — Čítať aj zapisovať zoznam adminov môžu len existujúci admini. Jednotlivý používateľ si môže prečítať vlastný admin záznam (`$uid === auth.uid`), čo umožňuje aplikácii zistiť, či je prihlásený používateľ admin. Validácia vynucuje, že hodnota musí byť boolean.
- **teachers** — Čítať zoznam učiteľov môže každý prihlásený používateľ (potrebné pre výber učiteľa v UI). Zapisovať môžu len admini. Validácia vynucuje, že hodnota musí byť reťazec.
- **pending_users** — Čítať celý zoznam čakajúcich používateľov môžu len admini. Zapisovať (schvaľovať/odstraňovať) môžu len admini. Každý čakajúci používateľ si môže čítať vlastný záznam (`$uid === auth.uid`), čo umožňuje `PendingApprovalActivity` sledovať zmeny stavu v reálnom čase. Výnimka pre zápis: nový používateľ si môže vytvoriť vlastný záznam (`$uid === auth.uid && !data.exists()`) — len jedenkrát pri registrácii. Validácia vynucuje prítomnosť polí `email`, `name` a `tempKey`. Vnorené pole `status` má osobitné pravidlá: admin ho môže nastaviť na `"rejected"` (validácia povoľuje len túto hodnotu alebo zmazanie), a používateľ si ho môže len odstrániť (`!newData.exists()`) — čím požiada o opätovné posúdenie. Pole `tempKey` obsahuje dočasné heslo použité pri vytvorení účtu — admin ho pri odmietnutí použije na prihlásenie cez sekundárnu Firebase Auth inštanciu a úplné odstránenie účtu z Firebase Authentication. Po schválení sa `tempKey` vymaže spolu s celým `pending_users/{uid}` záznamom a používateľovi sa odošle e-mail na nastavenie vlastného hesla.
- **students** — Čítať údaje o študentoch môže každý prihlásený používateľ (potrebné pre rozvrh a zobrazenie zapísaných predmetov). Zapisovať môžu len admini a učitelia.
- **predmety** — Čítať môže každý prihlásený používateľ. Celé predmety (vytvorenie, mazanie, zmena štruktúry) môžu zapisovať len admini. Učitelia majú povolený zápis výlučne do polí `classroom` a `note` existujúcich rozvrhových záznamov (`timetable/$entryKey/classroom` a `timetable/$entryKey/note`), a to len ak daný záznam už existuje (`data.parent().exists()`). Nemôžu vytvárať ani mazať rozvrhové záznamy.
- **hodnotenia** — Čítanie známok je teraz granulárne: na úrovni `$subjectKey` môžu čítať len admini a učitelia (potrebné pre zobrazenie známok všetkých študentov v predmete). Na úrovni `$studentUid` si študent môže prečítať len vlastné známky (`$studentUid === auth.uid`). Žiaden študent nemôže čítať známky iného študenta. Zapisovať môžu len admini a učitelia.
- **pritomnost** — Čítanie dochádzky je teraz granulárne: na úrovni `$subjectKey` môžu čítať len admini a učitelia (potrebné pre správu dochádzky v predmete). Na úrovni `$studentUid` si študent môže prečítať len vlastnú dochádzku (`$studentUid === auth.uid`). Žiaden študent nemôže čítať dochádzku iného študenta. Zapisovať môžu len admini a učitelia. Špeciálne uzly pre QR kód dochádzku majú vlastné pravidlá:
  - **`qr_code`** — čítať môže každý prihlásený používateľ (študent potrebuje vidieť aktívny kód na overenie). Zapisovať môže každý prihlásený používateľ, ale len na mazanie (`!newData.exists()`) — tým sa zabezpečí, že študent po úspešnom skene „spotrebuje" kód a vymaže ho, čo spustí generovanie nového kódu na strane učiteľa.
  - **`qr_last_scan`** — zapisovať môže každý prihlásený používateľ, ale validácia vynucuje, že záznam musí obsahovať `uid`, `name` a `time`, a UID musí zodpovedať prihlásenému používateľovi (`newData.child('uid').val() === auth.uid`). Študent tak nemôže zaznamenať dochádzku za niekoho iného.
  - **`qr_fail`** — zapisovať môže každý prihlásený používateľ, validácia vynucuje prítomnosť polí `name`, `reason` a `time`.
- **school_years** — Čítať môže každý prihlásený používateľ. Vytvárať a upravovať školské roky môžu admini aj učitelia (učitelia potrebujú zápis na priraďovanie konzultačných hodín a predmetov ku školským rokom). Vnorené predmety (`predmety/$subjectKey`) môžu zapisovať admini aj učitelia.
- **days_off** — Čítať môže každý prihlásený používateľ. Zapisovať voľné dni môže učiteľ len pre seba (`$teacherUid === auth.uid`), alebo admin pre kohokoľvek.
- **consultation_bookings** — Čítať môže každý prihlásený používateľ (potrebné pre zobrazenie rezervácií). Na úrovni `$subjectKey` môže zapisovať každý prihlásený používateľ (študent vytvára novú rezerváciu). Na úrovni `$bookingKey` môže upraviť/zmazať rezerváciu len študent, ktorý ju vytvoril (`data.child('studentUid').val() === auth.uid`), admin alebo učiteľ. Tým sa zabezpečí, že študent nemôže manipulovať s cudzími rezerváciami.
- **students → consultation_timetable** — Študent si môže čítať a zapisovať len vlastný rozvrh konzultácií (`$uid === auth.uid`). Ostatní používatelia nemajú prístup k cudziemu rozvrhu konzultácií.
- **notifications** — Čítať notifikácie môže len ich adresát (`$uid === auth.uid`). Zapisovať (odosielať) notifikácie môže každý prihlásený používateľ — toto umožňuje učiteľom a systému posielať notifikácie študentom (napr. zrušenie konzultácie).
- **settings** — Čítať môže každý prihlásený používateľ. Zapisovať môžu len admini. Vnorený uzol `allowed_domains` má verejný prístup na čítanie (`.read: true`) — umožňuje aplikácii načítať zoznam povolených emailových domén ešte pred prihlásením (napr. na validáciu registrácie).

### Offline režim — lokálny JSON súbor

Lokálna databáza (`local_db.json`) je uložená v privátnom úložisku aplikácie:

```
/data/data/com.marekguran.unitrack/files/local_db.json
```

- Súbor je prístupný len aplikácii UniTrack (Android sandbox)
- Na zariadení so šifrovaním úložiska (predvolene od Android 10) sú dáta šifrované aj v pokoji
- Pri resete aplikácie (z nastavení) sa súbor úplne vymaže

---

## Oprávnenia aplikácie

UniTrack vyžaduje minimálnu sadu Android oprávnení:

| Oprávnenie | Typ | Účel | Riziko |
|---|---|---|---|
| `POST_NOTIFICATIONS` | Runtime (od Android 13) | Zobrazovanie notifikácií | Nízke — len informačné notifikácie |
| `POST_PROMOTED_NOTIFICATIONS` | Normálne | Live Update notifikácie (Android 16) | Nízke |
| `FOREGROUND_SERVICE` | Normálne | Beh notifikačnej služby na pozadí | Nízke |
| `RECEIVE_BOOT_COMPLETED` | Normálne | Plánovanie alarmov po reštarte | Nízke |
| `SCHEDULE_EXACT_ALARM` | Normálne | Plánovanie presných alarmov pre notifikácie | Nízke |
| `USE_EXACT_ALARM` | Normálne | Používanie presných alarmov | Nízke |
| `CAMERA` | Runtime | Skenovanie QR kódov pre dochádzku | Nízke — len na čítanie QR kódov |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Špeciálne | Výnimka z optimalizácie batérie | Stredné — zvýšená spotreba batérie |

Aplikácia **nevyžaduje** prístup ku kontaktom, mikrofónu, polohe ani iným citlivým zdrojom. Fotoaparát sa používa výhradne na skenovanie QR kódov.

---

## Konfigurácia Firebase projektu

### google-services.json

Súbor `google-services.json` je súčasťou repozitára v priečinku `app/`. Obsahuje konfiguráciu Firebase projektu (ID projektu, API kľúč, URL databázy). Nie je to tajný kľúč v pravom slova zmysle — API kľúč v tomto súbore slúži na identifikáciu projektu, nie na autorizáciu. Skutočná ochrana dát je zabezpečená kombináciou Firebase Security Rules a App Check.

Napriek tomu sa odporúča:
- Obmedziť API kľúč v Google Cloud Console na konkrétne Android aplikácie (package name + SHA-1)
- Nastaviť Firebase Security Rules (nie spoliehať sa len na client-side ochranu)

### Čo NIE je v repozitári

Repozitár neobsahuje:
- Firebase Security Rules — tieto sa nastavujú priamo vo Firebase Console
- Žiadne prihlasovacie údaje ani tokeny

---

## Bezpečnostné odporúčania pre nasadenie

### Pre administrátorov

1. **Nastaviť Firebase Security Rules** podľa odporúčaní vyššie
2. **Zapnúť Enforce App Check** v nastaveniach Realtime Database — aplikácia už obsahuje implementáciu App Check s Play Integrity (release) a Debug (vývoj) providermi
3. **Obmedziť API kľúč** v Google Cloud Console na konkrétny package name a SHA-1 certifikát
4. **Pravidelne kontrolovať** Firebase Authentication konzolu — odstraňovať neaktívne účty
5. **Zálohovať dáta** cez Firebase automatické zálohy

### Pre používateľov

1. **Používať silné heslo** pri registrácii
2. **Zabezpečiť zariadenie** PINom, odtlačkom prsta alebo face ID
3. **V offline režime** pravidelne exportovať zálohy databázy
4. **Neposkytovať zálohy** (JSON export) tretím stranám — obsahujú kompletné dáta

---

[← Späť na README](../README.md)
