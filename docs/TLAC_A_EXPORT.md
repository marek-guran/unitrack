# 🖨 Tlač a export dát

Tento dokument popisuje možnosti tlače PDF reportov a exportu/importu dát v aplikácii UniTrack.

---

## Prehľad

UniTrack umožňuje generovať PDF reporty priamo z aplikácie cez systémovú službu `PrintManager`. Reporty sa dajú vytlačiť na fyzickej tlačiarni alebo uložiť ako PDF súbor. Okrem toho je k dispozícii export a import celej lokálnej databázy vo formáte JSON.

---

## PDF reporty

Aplikácia ponúka tri typy PDF reportov, z ktorých každý má vlastný `PrintDocumentAdapter`:

### 1. Report predmetu (SubjectReportPrintAdapter)

**Kde:** Detail predmetu → záložka „Export" alebo tlačidlo exportu

**Obsah:**
- Logo UniTrack s hlavičkou
- Názov predmetu a meno učiteľa
- Dátum a čas tlače
- Tabuľka študentov so stĺpcami:
  - **Meno študenta** (tučné, s automatickým zalamovaním)
  - **Prítomnosť** (počet/celkom + percento, napr. „8/10 (80%)")
  - **Známky** (zoznam všetkých známok, s automatickým zalamovaním)
  - **Priemer** (vypočítaný priemer známok)
- Zhrnutie: celkom študentov, celkom udelených známok, priemerná dochádzka
- Podpora viacstránkových dokumentov (automatické stránkovanie pri dlhých zoznamoch)

**Názov súboru:** `{názov_predmetu}-report.pdf`

### 2. Výsledky študenta (StudentResultsPrintAdapter)

**Kde:** Domovská obrazovka (režim študenta) → tlačidlo exportu

**Obsah:**
- Logo UniTrack s hlavičkou
- Meno študenta
- Akademický rok a semester
- Dátum a čas tlače
- Tabuľka predmetov so stĺpcami:
  - **Predmet** (názov predmetu)
  - **Dochádzka** (percento prítomnosti)
  - **Známky** (zoznam udelených známok)
  - **Priemer** (priemer známok v predmete)
- Zhrnutie: celkom predmetov, celkový priemer, celková dochádzka

**Názov súboru:** `{meno_študenta}-vysledky.pdf`

### 3. Prehľad predmetov učiteľa (TeacherSubjectsPrintAdapter)

**Kde:** Domovská obrazovka (režim učiteľa) → tlačidlo exportu

**Obsah:**
- Logo UniTrack s hlavičkou
- Meno učiteľa
- Akademický rok a semester
- Dátum a čas tlače
- Tabuľka predmetov so stĺpcami:
  - **Predmet** (názov predmetu)
  - **Študenti** (počet zapísaných študentov)
  - **Priemerná dochádzka** (celková dochádzka v predmete)
  - **Priemerná známka** (celkový priemer hodnotení)
- Zhrnutie: celkom predmetov, celkom unikátnych študentov

**Názov súboru:** `{meno_učiteľa}-predmety.pdf`

---

## Spoločné vlastnosti PDF reportov

### Hlavička

Všetky reporty obsahujú na začiatku:
- Ikonu aplikácie (kruhové logo) vykreslené vo vysokom rozlíšení (4× veľkosť pre ostrý výstup)
- Text „UniTrack" vedľa loga
- Oddeľovaciu čiaru pod hlavičkou

### Tabuľky

- Dynamická šírka stĺpcov podľa obsahu (napr. šírka stĺpca mien sa prispôsobí najdlhšiemu menu)
- Automatické zalamovanie textu v bunkách
- Hlavička tabuľky so sivým pozadím a tučným písmom
- Ohraničenie buniek čiarami

### Stránkovanie

Report predmetu podporuje automatické stránkovanie — ak sa zoznam študentov nezmestí na jednu stranu, pokračuje na ďalšej stránke s opakovanou hlavičkou tabuľky.

---

## Export a import databázy (JSON)

Táto funkcia je dostupná len v **offline režime** a nachádza sa v nastaveniach aplikácie.

### Export

1. Používateľ klikne na tlačidlo „Exportovať databázu"
2. Otvorí sa systémový dialóg na výber umiestnenia súboru
3. Predvolený názov súboru: `unitrack_backup.json`
4. Celý obsah lokálnej databázy sa zapíše do vybraného súboru vo formáte JSON (prehľadne odsadený)

### Import

1. Používateľ klikne na tlačidlo „Importovať databázu"
2. Otvorí sa systémový dialóg na výber JSON súboru
3. Obsah súboru nahradí aktuálnu lokálnu databázu
4. Ak importovaná databáza obsahuje meno učiteľa (`settings/teacher_name`), automaticky sa synchronizuje do nastavení

### Formát zálohy

Exportovaný súbor obsahuje kompletný JSON strom databázy:

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

> ⚠️ Import prepíše celú existujúcu databázu. Pred importom sa odporúča vytvoriť zálohu aktuálnych dát.

---

[← Späť na README](../README.md)
