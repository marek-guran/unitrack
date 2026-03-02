# 🧪 Testovanie

Tento dokument popisuje prístup k testovaniu aplikácie UniTrack — akými spôsobmi bola aplikácia testovaná, na akých zariadeniach, aké scenáre boli overované a aké sú výsledky.

---

## Prístup k testovaniu

UniTrack bol testovaný primárne **manuálnym testovaním** na reálnych zariadeniach a emulátoroch. Vzhľadom na povahu aplikácie (úzke prepojenie s Firebase, bohatý UI s animáciami, notifikácie závislé od systémového času) bolo manuálne testovanie najefektívnejším spôsobom overenia správnosti.

### Typy testovania

| Typ | Popis | Pokrytie |
|---|---|---|
| **Funkčné testovanie** | Overenie správnosti jednotlivých funkcií (známky, dochádzka, rozvrh, notifikácie) | Kompletné |
| **Integračné testovanie** | Overenie spolupráce komponentov (Firebase ↔ UI, offline ↔ online prepínanie) | Kompletné |
| **UI/UX testovanie** | Testovanie používateľského rozhrania, animácií, responzivity | Kompletné |
| **Testovanie na zariadeniach** | Overenie na rôznych veľkostiach obrazoviek a verziách Androidu | Podrobná matica nižšie |
| **Regresné testovanie** | Kontrola, že nové zmeny nenarušili existujúcu funkcionalitu | Priebežné |
| **Testovanie hraničných stavov** | Prázdna databáza, chýbajúci internet, veľký počet záznamov, neplatný vstup | Vybrané scenáre |

---

## Matica testovacích zariadení

Aplikácia bola testovaná na nasledujúcich zariadeniach a emulátoroch:

| Zariadenie | Typ | Android verzia | API | Veľkosť displeja | Režim |
|---|---|---|---|---|---|
| Samsung Galaxy S25 Ultra | Fyzické zariadenie | Android 16 | 36 | 6.9" (telefón) | Online + Offline |
| Samsung Galaxy A35 5G | Fyzické zariadenie | Android 16 | 36 | 6.6" (telefón) | Online + Offline |
| Google Pixel 9 Pro | Emulátor | Android 16 | 36 | 6.3" (telefón) | Online + Offline |
| Pixel Tablet | Emulátor | Android 16 | 36 | 10.95" (tablet) | Online + Offline |

### Poznámky k testovaniu

- **Fyzické zariadenia:** Aplikácia bola priebežne testovaná na Samsung Galaxy S25 Ultra a Samsung Galaxy A35 5G, čo umožnilo overenie na reálnom hardvéri vrátane notifikácií, výkonu a Samsung One UI prostredia
- **Android 16 (API 36):** Testovanie segmentovaného `ProgressStyle` v notifikáciách, overenie `POST_PROMOTED_NOTIFICATIONS` oprávnenia
- **Tablet:** Overenie responzívneho dizajnu, textového režimu PillNavigationBar, tabletových layoutov (`layout-sw600dp`)

---

## Testovacie scenáre

### 1. Prihlásenie a autentifikácia

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 1.1 | Prihlásenie s platným emailom a heslom | Presmerovanie na domovskú obrazovku | ✅ |
| 1.2 | Prihlásenie s nesprávnym heslom | Zobrazenie chybovej hlášky | ✅ |
| 1.3 | Prihlásenie s neexistujúcim emailom | Zobrazenie chybovej hlášky | ✅ |
| 1.4 | Prihlásenie s prázdnym formulárom | Tlačidlo je neaktívne, validačná hláška | ✅ |
| 1.5 | Automatické prihlásenie (zapamätaný používateľ) | Preskočenie prihlasovacieho okna | ✅ |
| 1.6 | Spustenie offline režimu | Presmerovanie na domovskú obrazovku bez prihlásenia | ✅ |
| 1.7 | Reset hesla z nastavení | Odoslaný email na obnovu hesla | ✅ |

### 2. Správa známok

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 2.1 | Pridanie novej známky | Známka sa uloží a zobrazí v zozname | ✅ |
| 2.2 | Úprava existujúcej známky | Známka sa aktualizuje | ✅ |
| 2.3 | Odstránenie známky | Známka zmizne zo zoznamu | ✅ |
| 2.4 | Pridanie známky v offline režime | Známka sa uloží lokálne | ✅ |
| 2.5 | Overenie výpočtu priemeru | Priemer sa správne prepočíta po pridaní/úprave | ✅ |

### 3. Dochádzka

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 3.1 | Zaznamenanie prítomnosti | Záznam sa uloží s dátumom a časom | ✅ |
| 3.2 | Zaznamenanie neprítomnosti | Záznam s `absent = true` | ✅ |
| 3.3 | Percentuálny výpočet dochádzky | Správne percento (prítomní/celkom) | ✅ |
| 3.4 | Dochádzka v offline režime | Záznamy sa ukladajú lokálne | ✅ |

### 3a. QR kód dochádzka

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 3a.1 | Spustenie QR relácie učiteľom | QR kód sa zobrazí na obrazovke | ✅ |
| 3a.2 | Skenovanie platného QR kódu študentom | Dochádzka sa zaznamená, učiteľ vidí potvrdenie | ✅ |
| 3a.3 | Rotácia QR kódu po úspešnom skene | Nový QR kód sa vygeneruje do 1 sekundy | ✅ |
| 3a.4 | Skenovanie neplatného/expirovaného kódu | Zobrazenie chybovej správy | ✅ |
| 3a.5 | Študent nie je zapísaný v predmete | Odmietnutie so správou, záznam v qr_fail | ✅ |
| 3a.6 | Duplicitný sken toho istého študenta | Nový QR kód sa vygeneruje, študent je už zaznamenaný | ✅ |
| 3a.7 | Ukončenie relácie učiteľom | Dochádzka sa uloží, dočasné uzly sa vymažú | ✅ |
| 3a.8 | Oprávnenie fotoaparátu zamietnuté | Zobrazenie informácie o potrebe oprávnenia | ✅ |

### 4. Rozvrh a voľné dni

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 4.1 | Zobrazenie celého rozvrhu | Všetky hodiny pre aktuálny semester | ✅ |
| 4.2 | Filter „Dnes" | Len dnešné hodiny | ✅ |
| 4.3 | Filter párny/nepárny týždeň | Hodiny zodpovedajúce parite | ✅ |
| 4.4 | Pridanie voľného dňa (celý deň) | Zrušenie všetkých hodín v daný deň | ✅ |
| 4.5 | Pridanie voľného dňa (časový rozsah) | Zrušenie len kolidujúcich hodín | ✅ |
| 4.6 | Voľný deň s rozsahom dátumov | Správne pokrytie viacerých dní | ✅ |
| 4.7 | Detekcia časového konfliktu | Upozornenie pri prekrývajúcich sa hodinách | ✅ |

### 5. Notifikácie

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 5.1 | Živá notifikácia — prebieha hodina | Zobrazenie názvu predmetu a učebne | ✅ |
| 5.2 | Živá notifikácia — prestávka | Zobrazenie „Prestávka" + nasledujúca hodina | ✅ |
| 5.3 | Živá notifikácia — po skončení vyučovania | Notifikácia sa automaticky zruší | ✅ |
| 5.4 | Notifikácia — nová známka | Zvukové upozornenie s detailmi | ✅ |
| 5.5 | Notifikácia — zrušená hodina | Zvukové upozornenie s názvom predmetu | ✅ |
| 5.6 | Notifikácia — nová neprítomnosť | Zvukové upozornenie | ✅ |
| 5.7 | Notifikácie po reštarte zariadenia | Alarmy sa nanovo naplánujú | ✅ |
| 5.8 | Segmentovaný progress bar (Android 16) | Farebné segmenty pre hodiny a prestávky | ✅ |
| 5.9 | Vypnutie notifikačného kanálu | Alarm sa zruší, notifikácia sa nezobrazuje | ✅ |
| 5.10 | Pripomienka konzultácie — študent | Notifikácia X minút pred konzultáciou | ✅ |
| 5.11 | Pripomienka konzultácie — učiteľ | Notifikácia X minút pred konzultáciou s počtom študentov | ✅ |
| 5.12 | Notifikácia o novej rezervácii (učiteľ) | Učiteľ dostane notifikáciu keď študent zarezervuje termín | ✅ |
| 5.13 | Notifikácia o zrušení konzultácie (študent) | Študent dostane notifikáciu keď učiteľ zruší rezerváciu | ✅ |
| 5.14 | Vypnutie pripomienok konzultácií | Pripomienky sa nezobrazujú | ✅ |

### 6. Offline režim

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 6.1 | Spustenie v offline režime | Aplikácia funguje bez internetu | ✅ |
| 6.2 | Export databázy do JSON | Kompletná záloha sa uloží | ✅ |
| 6.3 | Import databázy zo súboru | Dáta sa obnovia, meno učiteľa sa synchronizuje | ✅ |
| 6.4 | Reset aplikácie | Všetky dáta sa vymažú, presmerovanie na login | ✅ |
| 6.5 | Živá notifikácia v offline | Rozvrh sa číta z lokálnej databázy | ✅ |

### 6a. Cache-first loading a ochrana zápisov (online režim)

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 6a.1 | Načítanie dát po prvom spustení | Dáta sa stiahnu zo servera a uložia do cache | ✅ |
| 6a.2 | Opakované načítanie dát (cache) | Dáta sa zobrazia okamžite z lokálnej cache | ✅ |
| 6a.3 | Strata spojenia počas prehliadania | Offline banner sa zobrazí, dáta z cache sú naďalej dostupné | ✅ |
| 6a.4 | Pokus o zápis pri offline stave | Snackbar „Ste offline – môžete iba prezerať", zápis sa nevykoná | ✅ |
| 6a.5 | Obnovenie spojenia po výpadku | Offline banner zmizne, zápisy sú opäť povolené | ✅ |
| 6a.6 | QR FAB tlačidlá pri offline stave | FAB sa zašedne a stane sa neaktívnym | ✅ |
| 6a.7 | QR aktivita pri strate spojenia | Celostránkový offline overlay blokuje interakciu | ✅ |
| 6a.8 | requireOnline() v lokálnom offline režime | Guard vždy vráti true (lokálne zápisy sú povolené) | ✅ |

### 6b. Hromadná dochádzka (BulkAttendanceActivity)

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 6b.1 | Otvorenie hromadnej dochádzky | Zobrazí sa zoznam študentov s prepínačom prítomný/neprítomný | ✅ |
| 6b.2 | Označenie všetkých ako prítomných | Chip „Označiť všetkých" nastaví všetkých na prítomných | ✅ |
| 6b.3 | Výber dátumu a času | Dátum a čas sa správne nastavia | ✅ |
| 6b.4 | Uloženie dochádzky (online) | Záznamy sa uložia do Firebase | ✅ |
| 6b.5 | Uloženie dochádzky (offline) | Záznamy sa uložia do lokálnej databázy | ✅ |
| 6b.6 | Zrušenie s neuloženými zmenami | Potvrdzovací dialóg pred zatvorením | ✅ |
| 6b.7 | requireOnline() guard | Zápis sa nevykoná pri offline stave v online režime | ✅ |

### 7. Správa predmetov a semestrov

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 7.1 | Vytvorenie nového predmetu | Predmet sa uloží s názvom, učiteľom, semestrom | ✅ |
| 7.2 | Zmena semestra predmetu | Automatická migrácia známok a dochádzky | ✅ |
| 7.3 | Priradenie učiteľa k predmetu | Email učiteľa sa uloží | ✅ |
| 7.4 | Odstránenie predmetu | Predmet a súvisiace dáta sa zmažú | ✅ |

### 8. Responzívny dizajn

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 8.1 | Zobrazenie na telefóne (< 600dp) | Ikonový režim navigácie, kompaktné layouty | ✅ |
| 8.2 | Zobrazenie na tablete (≥ 600dp) | Textový režim navigácie, rozšírené layouty | ✅ |
| 8.3 | Tmavý režim na všetkých obrazovkách | Správne farby a kontrasty | ✅ |
| 8.4 | Orientácia na výšku a šírku | Správne prekreslenie UI komponentov | ✅ |

### 9. PDF reporty a tlač

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 9.1 | Export reportu predmetu | PDF s tabuľkou študentov, známkami, dochádzkou | ✅ |
| 9.2 | Export výsledkov študenta | PDF s predmetmi a hodnoteniami | ✅ |
| 9.3 | Export prehľadu učiteľa | PDF s predmetmi a štatistikami | ✅ |
| 9.4 | Viacstránkový report | Automatické stránkovanie pri dlhom zozname | ✅ |

### 10. Konzultačné hodiny

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 10.1 | Pridanie konzultačnej hodiny učiteľom | Hodina sa uloží s dňom, časom, učebňou | ✅ |
| 10.2 | Úprava konzultačnej hodiny | Hodina sa aktualizuje | ✅ |
| 10.3 | Mazanie konzultačnej hodiny bez rezervácií | Hodina sa odstráni | ✅ |
| 10.4 | Mazanie konzultačnej hodiny s aktívnymi rezerváciami | Varovanie o zrušení rezervácií, potvrdenie | ✅ |
| 10.5 | Zobrazenie konzultačných hodín študentom | Zoznam učiteľov s ich hodinami | ✅ |
| 10.6 | Rezervácia termínu študentom | Rezervácia sa uloží v consultation_bookings aj consultation_timetable | ✅ |
| 10.7 | Zrušenie rezervácie študentom | Rezervácia sa odstráni z oboch miest | ✅ |
| 10.8 | Zrušenie rezervácie učiteľom | Rezervácia sa odstráni, študent dostane notifikáciu | ✅ |
| 10.9 | Úprava rezervácie (dátum, čas) | Dátum a čas sa aktualizujú | ✅ |
| 10.10 | Automatické mazanie minulých rezervácií | Minulé rezervácie sa pri načítaní zmažú | ✅ |
| 10.11 | Vyhľadávanie učiteľov/študentov | Filtrovanie podľa mena funguje správne | ✅ |

### 11. Nový semester

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 11.1 | Vytvorenie nového školského roka | Rok sa vytvorí s vybranými predmetmi a študentmi | ✅ |
| 11.2 | Kopírovanie predmetov z existujúceho roka | Predmety sa prekopírujú | ✅ |
| 11.3 | Výber a odznačenie predmetov/študentov | Filtre a hromadný výber fungujú | ✅ |

### 12. Zmena role používateľa (online admin)

| # | Scenár | Očakávaný výsledok | Stav |
|---|---|---|---|
| 12.1 | Povýšenie študenta na učiteľa | Rola sa zmení, dáta študenta zostávajú zachované | ✅ |
| 12.2 | Degradácia učiteľa na študenta | Rola sa zmení, existujúce predmety a školské roky zostávajú zachované | ✅ |
| 12.3 | Real-time aktualizácia navigácie po zmene role | Navigácia dotknutého používateľa sa okamžite prebuduje | ✅ |
| 12.4 | Presmerovanie na domovskú obrazovku po zmene role | Používateľ je automaticky presmerovaný na Home | ✅ |
| 12.5 | Zachovanie dát po opakovanej zmene role (študent → učiteľ → študent) | Žiadne dáta sa nestratia pri opakovaných zmenách | ✅ |

---

## Overenie kompatibility

### Android verzie

| Android verzia | API | Výsledok | Poznámka |
|---|---|---|---|
| Android 16 | 36 | ✅ Plne funkčné | ProgressStyle segmenty v notifikáciách, POST_PROMOTED_NOTIFICATIONS |

### Veľkosti obrazoviek

| Kategória | Šírka | Výsledok | Navigačný režim |
|---|---|---|---|
| Kompaktný telefón | < 360dp | ✅ Funkčné | Ikony |
| Štandardný telefón | 360–599dp | ✅ Funkčné | Ikony |
| Tablet | ≥ 600dp | ✅ Funkčné | Textové popisky |

---

## Poznámky k metodike testovania

- **Manuálne testovanie** bolo zvolené ako primárna metóda, pretože umožňuje dôkladne overiť celý používateľský zážitok vrátane animácií, notifikácií a interakcie s Firebase v reálnom čase.
- **Testovanie prebiehalo s realistickým objemom dát** — desiatky študentov a stovky známok, čo zodpovedá typickému nasadeniu v akademickom prostredí.
- **Firebase Security Rules** sa nastavujú priamo vo Firebase Console — odporúčaná konfigurácia je popísaná v dokumente [Bezpečnosť](BEZPECNOST.md).

---

[← Späť na README](../README.md)
