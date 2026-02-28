# 📋 Dochádzka a QR kódy

Tento dokument popisuje systém evidencie dochádzky v aplikácii UniTrack — manuálne zaznamenávanie prítomnosti aj automatickú QR kód dochádzku.

---

## Prehľad

UniTrack ponúka dva spôsoby zaznamenávania dochádzky:

| Spôsob | Kto spúšťa | Ako funguje | Režim |
|---|---|---|---|
| **Manuálna dochádzka** | Učiteľ / Admin | Učiteľ označí študentov ako prítomných/neprítomných v dialógu | Online + Offline |
| **QR kód dochádzka** | Učiteľ spustí, študenti skenujú | Učiteľ zobrazí rotujúci QR kód, študenti ho naskenujú fotoaparátom | Len Online |

---

## Manuálna dochádzka

### Zaznamenanie dochádzky

Učiteľ otvorí detail predmetu → záložka „Dochádzka" → tlačidlo pre zaznamenanie:

1. Zobrazí sa dialóg so zoznamom všetkých študentov zapísaných v predmete
2. Pri každom študentovi sú chipové tlačidlá **Prítomný** / **Neprítomný**
3. Tlačidlo **„Označiť všetkých"** nastaví všetkých na prítomných
4. Výber dátumu cez DatePicker (predvolene aktuálny dátum)
5. Po potvrdení sa záznamy uložia do databázy

### Úprava záznamu

Existujúci záznam dochádzky je možné upraviť — zmeniť dátum, čas, poznámku a stav prítomnosti.

### Mazanie záznamu

Záznam sa dá odstrániť s potvrdením a možnosťou vrátenia (Undo).

### Percentuálny prehľad

Pre každého študenta sa automaticky počíta:
- Počet prítomných / celkový počet záznamov
- Percento dochádzky (napr. „8/10 (80%)")

### Databázová cesta

```
pritomnost/{školský_rok}/{semester}/{predmet}/{študent_uid}/{dátum}
```

Každý záznam obsahuje:
- `time` — čas záznamu
- `note` — poznámka
- `absent` — boolean (true = neprítomný)

---

## QR kód dochádzka

QR kód dochádzka je automatizovaný spôsob zaznamenávania prítomnosti, ktorý funguje len v online režime. Učiteľ zobrazí na svojom zariadení rotujúci QR kód a študenti ho naskenujú svojimi zariadeniami.

### Princíp fungovania

```
Učiteľ                           Študent
┌──────────────┐                ┌──────────────┐
│ Generuje     │                │ Otvorí       │
│ QR kód       │───QR kód───▶  │ skener       │
│              │                │              │
│ Zobrazí      │                │ Naskenuje    │
│ na obrazovke │                │ fotoaparátom │
│              │                │              │
│ ◄──Firebase──│                │──Firebase──▶ │
│ Vidí sken    │                │ Overí kód    │
│ v reálnom    │                │ a zapíše     │
│ čase         │                │ dochádzku    │
│              │                │              │
│ Nový QR kód  │                │ Zobrazí      │
│ sa generuje  │                │ potvrdenie   │
└──────────────┘                └──────────────┘
```

### Formát QR kódu

```
UNITRACK|{školský_rok}|{semester}|{kľúč_predmetu}|{16_znakový_kód}
```

- **Prefix:** `UNITRACK` — identifikátor aplikácie
- **Školský rok:** napr. `2025_2026`
- **Semester:** `zimny` alebo `letny`
- **Kľúč predmetu:** Firebase kľúč predmetu
- **Kód:** 16-znakový alfanumerický kód (generovaný z UUID)

Príklad: `UNITRACK|2025_2026|zimny|mat1|a1b2c3d4e5f6g7h8`

### Rotácia QR kódov

QR kód sa automaticky mení po každom úspešnom skene:

1. Učiteľ vygeneruje prvý QR kód a zapíše ho do Firebase (`qr_code`)
2. Študent naskenuje kód a atomicky ho „spotrebuje" (vymaže z Firebase)
3. Učiteľ detekuje vymazanie a vygeneruje nový kód (oneskorenie ~1 sekunda)
4. Ďalší študent skenuje nový kód

Táto rotácia zabezpečuje, že každý QR kód je jednorazový — študent nemôže odfotografovať kód a zdieľať ho s niekým, kto nie je prítomný.

---

## Strana učiteľa (QrAttendanceActivity)

### Spustenie relácie

Učiteľ spustí QR kód dochádzku z detailu predmetu. Aplikácia:

1. Vytvorí prvý QR kód (UUID → 16 znakov → QR bitmap 512×512 px)
2. Zapíše kód do Firebase cesty `pritomnost/{rok}/{semester}/{predmet}/qr_code`
3. Zobrazí QR kód na obrazovke
4. Začne počúvať na Firebase zmeny (`qr_last_scan` a `qr_fail`)

### Monitorovanie v reálnom čase

Učiteľ vidí v reálnom čase:

- **Log skenov** — zoznam študentov, ktorí sa prihlásili, s relatívnym časom (Teraz, 30s, 5 min...)
- **Filtrovanie** — Všetci / Prítomní / Chyby
- **Počítadlo** — koľko študentov sa prihlásilo z celkového počtu

### Spracovanie skenov

| Udalosť | Reakcia učiteľa |
|---|---|
| Úspešný sken nového študenta | Pridanie do logu, generovanie nového QR kódu |
| Duplicitný sken (študent už prihlásený) | Generovanie nového QR kódu, bez duplicitného záznamu |
| Neúspešný pokus (qr_fail) | Zobrazenie chyby v logu (napr. „Študent nie je zapísaný") |

### Ukončenie relácie

Po stlačení tlačidla „Ukončiť dochádzku":

1. Uložia sa záznamy dochádzky pre všetkých prihlásených študentov
2. Neprítomní študenti (tí, čo neskenovali) sa zaznamenajú ako neprítomní
3. Dočasné Firebase uzly (`qr_code`, `qr_last_scan`, `qr_fail`) sa vymažú
4. Obrazovka sa zatvorí

### Technické detaily

- Obrazovka zostáva zapnutá počas relácie (wake lock)
- QR kód sa generuje cez ZXing `QRCodeWriter` (512×512 px bitmap)
- Firebase listenery na `qr_last_scan` a `qr_fail` reagujú okamžite na zmeny

---

## Strana študenta (QrScannerActivity)

### Skenovanie

1. Študent otvorí skener (prístupný z detailu predmetu alebo z domovskej obrazovky)
2. Aplikácia vyžiada oprávnenie `CAMERA` (ak ešte nebolo udelené)
3. Zobrazí sa viewfinder fotoaparátu cez ZXing `DecoratedBarcodeView`
4. Študent namieri fotoaparát na QR kód učiteľa

### Validácia

Po naskenovaní QR kódu sa vykonajú tieto kontroly:

1. **Formát** — kontrola, či QR kód začína prefixom `UNITRACK` a obsahuje 5 častí oddelených `|`
2. **Bezpečnosť cesty** — kontrola, či žiadna časť neobsahuje nepovolené znaky (`.`, `$`, `#`, `[`, `]`, `/`)
3. **Zápis študenta** — overenie, či je prihlásený študent zapísaný v danom predmete
4. **Platnosť kódu** — atomická Firebase transakcia overí, že kód v databáze zodpovedá naskenovanému kódu

### Atomická transakcia

Overenie a spotrebovanie QR kódu prebieha cez Firebase transakciu:

```
1. Prečítaj aktuálnu hodnotu qr_code
2. Ak sa zhoduje s naskenovaným kódom → vymaž (nastav null)
3. Ak sa nezhoduje → transakcia zlyhá (kód bol medzitým spotrebovaný)
```

Toto zabezpečuje, že rovnaký QR kód nemôže byť použitý dvoma študentmi súčasne.

### Po úspešnom skene

1. Zapíše sa záznam do `qr_last_scan` s UID študenta, menom a serverovým časom
2. Zobrazí sa potvrdenie (meno študenta + názov predmetu)
3. Študent môže zavrieť skener

### Chybové stavy

| Chyba | Správanie |
|---|---|
| Neplatný formát QR kódu | Ignorovanie (čaká sa na platný kód) |
| Študent nie je zapísaný | Záznam do `qr_fail`, zobrazenie chybovej správy |
| Kód bol medzitým spotrebovaný | Zobrazenie správy o expirovanom kóde |
| Chýba oprávnenie fotoaparátu | Zobrazenie informácie o potrebe oprávnenia |

---

## Firebase pravidlá pre QR dochádzku

QR kód dochádzka vyžaduje špeciálne Firebase Security Rules, ktoré umožňujú študentom interagovať s databázou počas skenovania:

```json
"pritomnost": {
  ".write": "auth != null && (...admins... || ...teachers...)",
  "$year": {
    "$semester": {
      "$subjectKey": {
        ".read": "auth != null && (...admins... || ...teachers...)",
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
}
```

### Vysvetlenie pravidiel

- **`qr_code`** — čítať môže každý prihlásený (študent vidí aktívny kód). Zapisovať môže každý, ale len na mazanie (`!newData.exists()`) — študent „spotrebuje" kód vymazaním.
- **`qr_last_scan`** — zapisovať môže každý prihlásený, ale validácia vynucuje, že UID v zázname musí zodpovedať prihlásenému používateľovi. Študent nemôže zaznamenať dochádzku za niekoho iného.
- **`qr_fail`** — zapisovať môže každý prihlásený, validácia vynucuje povinné polia.
- **`$studentUid`** — študent si môže prečítať len vlastnú dochádzku.

---

## Bezpečnostné aspekty

### Ochrana pred zdieľaním QR kódov

- **Rotácia kódov** — každý QR kód je jednorazový, po skene sa vygeneruje nový
- **Atomická transakcia** — kód sa spotrebuje pri skene, takže ho nemôže použiť niekto druhý
- **UID validácia** — záznam o skene musí obsahovať UID prihláseného študenta

### Ochrana pred manipuláciou

- **Firebase Security Rules** — študent nemôže priamo zapisovať dochádzku (len cez QR mechanizmus)
- **Validácia zápisu** — pred skenom sa overí, či je študent zapísaný v predmete
- **Path traversal ochrana** — validácia zakázaných znakov v kľúčoch cesty

### Obmedzenia

- QR kód dochádzka funguje len v online režime (vyžaduje Firebase)
- Študent musí mať nainštalovanú aplikáciu UniTrack a byť prihlásený
- Vyžaduje oprávnenie fotoaparátu

---

## Databázové cesty

### Dočasné uzly (len počas relácie)

| Cesta | Popis | Kto zapisuje |
|---|---|---|
| `pritomnost/{rok}/{sem}/{predmet}/qr_code` | Aktívny QR kód | Učiteľ (zápis), Študent (mazanie) |
| `pritomnost/{rok}/{sem}/{predmet}/qr_last_scan` | Posledný úspešný sken | Študent |
| `pritomnost/{rok}/{sem}/{predmet}/qr_fail` | Posledný neúspešný pokus | Študent |

### Trvalé záznamy

| Cesta | Popis | Kto zapisuje |
|---|---|---|
| `pritomnost/{rok}/{sem}/{predmet}/{uid}/{dátum}` | Záznam dochádzky (prítomný/neprítomný) | Učiteľ / Admin |

---

## Porovnanie s manuálnou dochádzkou

| Aspekt | Manuálna dochádzka | QR kód dochádzka |
|---|---|---|
| **Rýchlosť** | Učiteľ musí manuálne označiť každého študenta | Študenti sa prihlasujú sami |
| **Presnosť** | Závisí od učiteľa | Automatická — študent musí byť fyzicky prítomný |
| **Offline podpora** | ✅ Áno | ❌ Len online |
| **Potreba zariadenia** | Len učiteľ | Učiteľ + každý študent |
| **Ochrana** | Učiteľ kontroluje prítomnosť vizuálne | Rotujúci kód + atomická transakcia |

---

[← Späť na README](../README.md)
