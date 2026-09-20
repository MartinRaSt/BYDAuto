# Změny v aplikaci BYD Logger

Soubor se pouze doplňuje na konec. Nejnovější změna je vždy dole.

---

## 2026-09-20 — Stav baterie, rychlé zadávání, statistiky hybridu

Databáze povýšena z **v5 na v6**. Migrace `MIGRATION_5_6` přidává dva sloupce
pomocí `ALTER TABLE ... ADD COLUMN`, takže data na telefonu zůstávají netknutá.

### Datový model

| Sloupec    | Typ  | Výchozí | Význam                                    |
|------------|------|---------|-------------------------------------------|
| `socStart` | REAL | `-1.0`  | Stav baterie na začátku nabíjení v %       |
| `socEnd`   | REAL | `-1.0`  | Stav baterie na konci nabíjení v %         |

Hodnota `-1.0` (`ChargingSession.SOC_UNSET`) znamená „uživatel nezadal". Nula
je platná hodnota (vybitá baterie), proto nelze použít jako příznak nezadání.
Staré záznamy tedy zůstanou bez stavu baterie a nic se u nich nedomýšlí.

### Formulář záznamu

- Nová pole **Baterie na začátku (%)** a **Baterie na konci (%)**, viditelná
  jen u elektrických typů nabíjení. Validace 0–100 %, konec musí být větší
  než začátek.
- Tlačítko v pravém okraji pole:
  - Datum → dnešek
  - Začátek / Konec → aktuální čas
  - Baterie na konci → 100 %
  Klepnutí do pole samotného nadále otevírá picker.
- Dopočet trojice **začátek / konec / doba trvání** doplňuje vždy tu hodnotu,
  která chybí:
  - začátek + doba → konec
  - konec + doba → začátek
  - jen doba (oba časy prázdné) → konec = aktuální čas, začátek = konec minus doba
- Informační řádek pod poli baterie ukazuje, kolik kWh přibylo v baterii,
  účinnost nabíjení a reálný výkon. Při účinnosti mimo rozmezí 50–105 %
  zčervená s upozorněním na překlep.

**Záměrně se nic nedopočítává mezi % a kWh.** Nabité kWh se měří na zásuvce,
procenta hlásí auto z baterie; obě hodnoty se liší o ztráty nabíjení. Přepsat
jednu druhou by tu informaci zahodilo, proto se odvozené hodnoty pouze zobrazují.

### Výpočty (`util/EvCalc.kt`)

Veškerá odvozená matematika je na jednom místě a pokrytá unit testy. Každá
funkce vrací `null`, pokud vstup chybí — volající řádek se pak skryje.

- `batteryGainKwh` — energie do baterie z rozdílu SOC a kapacity
- `efficiency` — kWh do baterie / kWh ze zásuvky
- `realPowerKw` — nabité kWh / doba trvání
- `totals` — vzdálenost (rozpětí tachometru), kWh, litry, náklady
- `drainSamples` — úseky mezi dvěma nabíjeními **bez tankování benzínu mezi nimi**
  a s poklesem baterie aspoň 15 procentních bodů; jen takový úsek popisuje jízdu
  na baterii
- `batteryConsumptionPer100Km` — medián přes tyto úseky (medián, ne průměr, aby
  jedna dálniční jízda nepřeválcovala zbytek)
- `electricRangeKm` — kapacita / spotřeba × 100
- `averageEfficiency` — medián účinnosti, nepravděpodobné hodnoty vyřazeny

Kapacita baterie se bere z Nastavení (`battery_capacity`, výchozí 18 kWh).
Do této verze byla položka v nastavení sice přítomná, ale nikde se nepoužívala.

### Přehled (dashboard)

Nové řádky, každý se skryje, pokud pro něj nejsou data:

- Najeto (tachometr)
- Elektřina na 100 km — přes **všechny** ujeté kilometry, tedy včetně jízdy
  na benzín; u hybridu nelze elektrické a benzínové km oddělit
- Reálná spotřeba baterie — z úseků jízdy na baterii, medián
- Dojezd na plnou baterii
- Průměrná účinnost nabíjení
- Náklady na 100 km s rozpadem na elektřinu a benzín

### Detail záznamu

Přibyly řádky: reálný výkon, stav baterie (začátek → konec), nabito do baterie,
účinnost nabíjení, odhad dojezdu z nabité energie.

### Grafy

Dvě nové záložky: **Stav baterie** (dvě křivky — začátek a konec nabíjení v čase)
a **Účinnost nabíjení** (v čase; podezřelé hodnoty se nekreslí, aby nezkreslily
měřítko).

### CSV

Přibyly sloupce 18 a 19 — `Baterie začátek (%)` a `Baterie konec (%)`.
Nezadaná hodnota se exportuje jako prázdná buňka, ne jako `-1`. Import starších
záloh s méně sloupci funguje dál; chybějící sloupce dostanou výchozí hodnotu.

### Testy

- `app/src/test/.../EvCalcTest.kt` — 22 unit testů výpočtů, běží na PC
- `app/src/androidTest/.../MigrationTest.kt` — migrační testy databáze,
  **vyžadují připojený telefon nebo emulátor**
- `run_tests.bat` — spustí obojí; migrační část přeskočí, pokud není zařízení

### Postup při instalaci nové verze

1. Zálohovat data z telefonu (Export CSV).
2. Připojit telefon a spustit `run_tests.bat` — migrační testy musí projít.
3. `build_release.bat`
4. Nainstalovat.

---

## 2026-09-20 (2) — Sezónní statistiky, odeslání statistik emailem, oprava duplicit

Beze změny databáze — schéma zůstává na v6.

### Statistiky podle sezóny

Meteorologické sezóny (jaro 3–5, léto 6–8, podzim 9–11, zima 12–2), sčítané
přes všechny roky. U hybridu je zima vs. léto to nejzajímavější srovnání:
v zimě klesá dojezd, roste spotřeba baterie i podíl jízdy na benzín.

Zobrazení na třech místech:
- **Přehled** — textový blok, u každé sezóny reálná spotřeba baterie a dojezd;
  když na to nejsou data, spotřeba elektřiny a benzínu na 100 km
- **Grafy** — nová záložka „Podle sezóny": skupinový sloupcový graf,
  kWh/100 km vedle l/100 km (stejné kilometry, jednotky se nesčítají)
- **E-mailový report** — plné číslo za každou sezónu

### Jak se počítá vzdálenost — změna proti předchozí verzi

Dříve se vzdálenost brala jako rozpětí tachometru (max − min). U celé historie
to sedí, ale u podmnožiny ne: „zima" sbíraná přes dva roky by zahrnula i kilometry
najeté mezi těmi zimami. Nově se historie dělí na **úseky mezi po sobě jdoucími
odečty tachometru** (`EvCalc.legs`) a sčítají se jen ty, které do období patří.

Dvě pravidla, která z toho plynou:

- **Úsek začínající v jedné sezóně a končící v jiné se nezapočítá do žádné.**
  Typicky je to několikaměsíční mezera v záznamech; připsat ho celý pozdější
  sezóně by ji zavalilo kilometry najetými jinde. Totéž platí pro měsíce v CSV.
- **Úsek jízdy na baterii mimo rozmezí 8–60 kWh/100 km se zahodí.** Pod spodní
  mezí jela většinu cesty spalovací jednotka (dlouhá cesta nebo dlouhá pauza mezi
  nabíjeními), nad horní mezí jde o chybný odečet. Takový úsek nepopisuje jízdu
  na baterii a zkreslil by medián spotřeby i odhad dojezdu.

Druhé pravidlo zpřesňuje i celkovou „Reálnou spotřebu baterie" a „Dojezd na
plnou baterii", ne jen sezónní čísla.

### Statistiky e-mailem

Nová položka v menu **Odeslat statistiky emailem**. Tělo zprávy je čitelný
přehled (celkem + každá sezóna), příloha ZIP obsahuje `byd_logger_statistiky.csv`
se stejnými čísly a navíc řádky po měsících. Obojí se počítá z týchž volání
`EvCalc`, takže se text a CSV nemohou rozejít.

Statistiky se počítají vždy z celé historie — filtr období a typu je v tomto
dialogu skrytý, protože by rozbil sezónní srovnání i výpočet spotřeby mezi
nabíjeními. Chybějící hodnota jde do CSV jako pomlčka, nikdy jako nula.

Odesílání e-mailu bylo vytaženo do sdílené metody `sendZipByEmail`, kterou teď
používá záloha i statistiky.

### Oprava: detekce duplicit fungovala jen jedním směrem

`findDuplicate` porovnávala časy přes `DateUtil.calculateDuration`, která při
záporném rozdílu přičítá 24 hodin (kvůli nabíjení přes půlnoc). Záznam o pár
minut **dřívější** než existující tak vyšel jako vzdálený 1435 minut a jako
duplicita se neoznačil; o pár minut pozdější ano.

Nová `DateUtil.absMinutesDiff` vrací symetrickou vzdálenost dvou časů a bere
kratší cestu přes půlnoc (23:55 a 00:05 jsou 10 minut od sebe).

### Ostatní

- Dialog „O aplikaci" hlásil natvrdo zapsanou verzi databáze 3. Verze je teď
  v `DbSchema.VERSION` a čte ji anotace `@Database` i dialog, takže nemůže zastarat.

### Testy

44 unit testů (z 22). Nové pokrývají úseky tachometru, sezóny, filtr
nepravděpodobných úseků, CSV statistik a práci s časy včetně opravené detekce
duplicit. Sezónní chyba s připisováním kilometrů byla odhalena právě testem.

### Dvě různá CSV — ať se nepletou

| Soubor v ZIP                  | Z čeho             | K čemu                        | Lze importovat? |
|-------------------------------|--------------------|-------------------------------|-----------------|
| `byd_logger_nabijeni.csv`     | Export CSV         | **Záloha dat**, obnova        | Ano             |
| `byd_logger_statistiky.csv`   | Odeslat statistiky | Čtení, tabulkový procesor     | Ne              |

Statistiky jsou odvozená čísla, ne data — obnovit z nich nelze nic. Importér
takový soubor odmítne (řádky neodpovídají formátu zálohy) a zobrazí
„Soubor neobsahuje platná data pro import"; žádné nesmyslné záznamy nevzniknou.
Kryto testem `CsvRoundTripTest`.

**Zálohu po instalaci nové verze obnovovat netřeba.** Migrace mění schéma na
místě a data zůstávají. Záloha je pojistka pro případ, že by se něco pokazilo.
Kdyby k tomu došlo, použij **Obnovit zálohu → Nahradit vše** (ne „Přidat k datům",
to by záznamy zdvojilo).

`CsvImporter.parseCsvText` a `CsvExporter.buildCsv` jsou veřejné, aby šlo kolečko
export–import testovat bez Androidu.

---

## 2026-09-20 (3) — INCIDENT: migrační testy smazaly ostrou aplikaci i s daty

### Co se stalo

Na připojeném telefonu (Galaxy S24 Ultra, Android 16) byl spuštěn
`run_tests.bat` → `gradle :app:connectedDebugAndroidTest`. Testy proběhly
úspěšně, ale **Gradle po jejich dokončení testovanou aplikaci odinstaloval** —
to je výchozí chování Android Gradle Pluginu. Odinstalace smaže data aplikace,
takže zmizela i databáze `charging_database` s ostrými záznamy.

Před spuštěním byla ověřena shoda podpisových certifikátů (instalace tedy
proběhla jako aktualizace na místě, data přežila) — ale následná automatická
odinstalace po testech ověřena nebyla. To byl chybný předpoklad.

Android Auto Backup byl na zařízení vypnutý (`bmgr enabled` → *Backup Manager
currently disabled*), takže data nešla obnovit z cloudu. Jediná cesta zpět je
CSV záloha z funkce Export CSV.

### Náprava v kódu

Debug build má nově vlastní `applicationId`:

```gradle
debug {
    applicationIdSuffix ".debug"
    versionNameSuffix "-debug"
}
```

Debug a instrumentované testy se tím instalují jako `com.byd.charging.debug`,
úplně oddělený balíček. `connectedAndroidTest` odinstaluje jen jeho; ostrá
aplikace `com.byd.charging` zůstává netknutá i s daty. Ověřeno opakovaným
během testů — po nich zůstal v systému jen ostatní software.

**Tento suffix se nesmí odstranit.** Bez něj testy znovu smažou ostrá data.

### Poučení pro postup

- Migrační testy na zařízení s ostrými daty jsou bezpečné jen díky odděleném
  `applicationId`. Ideálně je pouštět na emulátoru.
- Export CSV před jakoukoliv prací s telefonem není formalita — je to jediná
  záchrana, protože Auto Backup je vypnutý.

---

## 2026-09-20 (4) — Záloha do souboru, čitelný dialog O aplikaci

Beze změny databáze — schéma zůstává na v6.

### Záloha do souboru (ZIP)

Nová položka v menu **Uložit zálohu do souboru**. Otevře systémový dialog pro
výběr umístění (úložiště telefonu, Disk Google, OneDrive…) a zapíše tam ZIP
s CSV — přesně ten formát, který přijímá **Obnovit ze zálohy**. Výchozí název
souboru nese datum: `byd_logger_zaloha_2026-09-20.zip`, aby šlo v úložišti
poznat, která záloha je novější.

Zálohuje se **vždy celá historie bez filtru**. Záloha, ze které nejde obnovit
všechno, není záloha.

Do této verze vedla jediná cesta k záloze přes e-mail. Když je Auto Backup na
telefonu vypnutý (což byl tento případ), znamenalo to, že bez odeslané pošty
neexistovala žádná kopie dat — přesně to dnes způsobilo ztrátu záznamů.

### Dialog O aplikaci

Verze aplikace i verze databáze v dialogu **byly už dříve** (`tvVersion`,
`tvDbVersion`). Nebyly ale pořádně vidět: název aplikace měl natvrdo
`@color/black`, takže v tmavém motivu šlo o černý text na tmavém pozadí.

Opraveno systémově, ne jen v tom jednom místě:

- název používá `?android:attr/textColorPrimary`
- přibyl `values-night/colors.xml`, který pro tmavý motiv přepisuje
  `textSecondary` (#757575 → #B0B0B0) a `warning` (tmavě červená → světlejší)
- natvrdo zapsané oddělovače `#E0E0E0` nahrazeny barvou `@color/divider`
  s variantou pro tmavý motiv

Tím se zlepšila čitelnost i v grafech, seznamu záznamů a detailu, kde se
`textSecondary` a ty oddělovače také používaly.

### Testy

52 unit testů (z 50). Nové ověřují, že záloha zapsaná do souboru je platný ZIP,
ze kterého import obnoví záznamy včetně stavu baterie, a že název souboru
obsahuje datum.

---

## 2026-09-20 (5) — Záložky Elektřina a Benzín, automatické verzování

Beze změny databáze — schéma zůstává na v6.

### Nové záložky podle druhu energie

Přibyly dvě záložky, **Elektřina** a **Benzín**. Původní tři (Přehled, Záznamy,
Grafy) zůstaly beze změny. TabLayout je nově `scrollable`, aby se pět záložek
vešlo i na užší displej.

Obě obsluhuje jeden fragment `EnergyFragment` parametrizovaný přes
`Kind.ELECTRIC` / `Kind.GASOLINE`. Sekce jsou v obou stejné (Spotřeba, Celkem,
Tento měsíc, Podle sezóny), liší se jednotky a sada hodnot — dvě téměř shodné
třídy by se časem rozešly. Řádky se vkládají za běhu z `view_detail_row`
a vloží se jen ty, které jde z dat spočítat.

**Elektřina** — spotřeba kWh/100 km, reálná spotřeba baterie, dojezd na plnou
baterii, průměrná účinnost nabíjení, Kč/100 km, průměrná cena za kWh, členění
podle typu nabíjení (FVE / síť / garáž / veřejná).

**Benzín** — průměrná spotřeba l/100 km metodou plné nádrže, spotřeba od
minulého tankování, Kč/100 km, průměrná cena za litr.

Spotřeba se u obou počítá z **celé historie**, ne jen ze záznamů daného druhu:
vzdálenost určují odečty tachometru ze všech záznamů.

### Výpočet spotřeby benzínu přesunut do EvCalc

`gasolineConsumption` byla dosud jen v UI kódu `DashboardFragment`, bez testů.
Teď je v `EvCalc`, pokrytá testy, a používá ji dashboard i nová záložka —
nemohou se tedy rozejít. Při přesunu se opravilo, že tankování bez vyplněného
tachometru rozhazovalo řazení a tím i spočtenou vzdálenost; nově se přeskakuje.

Přibyla také `averagePricePerKwh` a průměrná cena za litr.

### Automatické zvyšování verze

`versionName` se nově při každém buildu zvyšuje v poslední (patch) části:
1.0.2 → 1.0.3. `versionCode` roste jako dosud. **Major a minor se mění ručně**
v `app/version.properties`, skript do nich nesahá.

Ověřeno: dva buildy po sobě daly 1.0.1 a 1.0.2; spuštění samotných testů verzi
neposouvá (podmínka reaguje jen na assemble/bundle/install/generate).

Při zavádění se objevily dvě chyby, obě opravené:

- `Properties.store()` přijímá jen `String`; GString z interpolace `"${...}"`
  ho shodí. Nutné `.toString()`.
- `store()` soubor nejdřív zkrátí, takže pád uprostřed nechal
  `version.properties` prázdný a build pak neměl z čeho číst. Nově se obsah
  sestaví v paměti a soubor se přepíše jedním zápisem; chybějící nebo poškozený
  soubor navíc build neshodí, jen se použijí výchozí hodnoty.
- Regulární výraz ve slashy stringu (`/…\$/`) patch nezvyšoval, protože `$`
  je v Groovy znak interpolace. Nahrazeno dělením na tečky přes `tokenize`.

### Testy

60 unit testů (z 52). Nové pokrývají spotřebu benzínu metodou plné nádrže,
přeskočení tankování bez tachometru, oddělení od nabíjení elektřinou
a průměrné ceny.

---

## 2026-09-20 (6) — Průměry za období

Beze změny databáze — schéma zůstává na v6.

### Výběr období na záložkách Elektřina a Benzín

Nahoře na obou záložkách přibyl výběr **Období**: *Celé období* a pak jednotlivé
roky z dat, od nejnovějšího. Aktuální rok je označený. Výběr ovlivní všechny
sekce pod ním. Když jsou záznamy jen z jednoho roku, výběr se skryje.

Nová sekce **Po měsících** vypíše každý měsíc zvoleného období od nejnovějšího
se spotřebou na 100 km; když ji z dat nelze určit (chybí odečty tachometru),
ukáže aspoň nabité množství.

Sekce **Podle sezóny** se při zvoleném roce počítá v rámci toho roku, aby se
zima 2025 a zima 2026 nesčítaly dohromady.

### Zobecnění výpočtu období

Sezóny, měsíce i roky teď počítá jedna funkce `EvCalc.statsByPeriod`, které se
předá jen klíčovací funkce (datum → klíč období). `seasonalStats` je nad ní
tenká obálka. Dřív mělo každé členění vlastní kód a hrozilo, že se pravidla
rozejdou.

Pravidlo pro kilometry platí pro všechna období stejně: **úsek mezi dvěma
odečty tachometru se započítá jen tehdy, když oba jeho konce padnou do téhož
období.** Totéž pro úseky jízdy na baterii.

Tím se změnilo chování měsíčních řádků v e-mailovém CSV: dřív se úsek
přiřazoval podle pozdějšího záznamu, takže leden po půlroční pauze vykázal
kilometry najeté za celé to pololetí a spotřeba vyšla nesmyslně nízká. Nově
takový úsek nepatří nikam. `StatsReport` používá `EvCalc.statsByMonth`, takže
e-mailový přehled a záložky v aplikaci ukazují tatáž čísla.

### Spotřeba benzínu podle období

Za celé období se používá přesnější **metoda plné nádrže**. Uvnitř zvoleného
roku litry natankované v období na kilometry najeté v období — metoda plné
nádrže by na výseku roku dávala zavádějící výsledek, protože nádrž nezačíná ani
nekončí na hranici roku. Popisek pod hodnotami říká, která metoda se použila.

### Testy

66 unit testů (z 60). Nové pokrývají dělení po měsících a letech, vyřazení
úseků přes hranici období a seznam dostupných roků.

---

## 2026-09-20 (7) — Vnitřní záložky Grafů, výběr měsíce, Porovnání

Beze změny databáze — schéma zůstává na v6.

### Přeskupení záložek

Nahoře zůstaly tři záložky (Přehled, Záznamy, Grafy). **Elektřina a Benzín se
přesunuly dovnitř Grafů**, kde jsou teď čtyři vnitřní záložky:

```
Přehled | Záznamy | Grafy
                    └─ Total | Elektřina | Benzín | Porovnání
```

Vnitřní ViewPager2 má vypnuté přejíždění prstem (`isUserInputEnabled = false`).
Dva vnořené ViewPagery by si vodorovná gesta přebíraly a mezi hlavními
záložkami by přestalo jít přejíždět; vnitřní se přepínají klepnutím.

### Výběr měsíce místo výpisu všech

Sekce „Po měsících" zmizela. Místo ní jsou vedle sebe dva výběry — **rok**
a **měsíc** — a všechny hodnoty se počítají za zvolené období. Měsíc nabízí jen
ty, ze kterých existují záznamy; při volbě „Celé období" se skryje. Zmizela
i sekce „Tento měsíc", kterou výběr nahradil.

Při zvoleném konkrétním měsíci se skryje členění podle sezóny — měsíc leží celý
v jedné sezóně, takže by to byl jen zopakovaný řádek.

### Nová záložka Porovnání

Čtyři režimy volené rozbalovacím seznamem:

- **Dvě období proti sobě** — vybere se období A a B (rok nebo konkrétní měsíc)
  a vedle sebe se vypíše deset metrik včetně rozdílu se znaménkem
- **Elektřina proti benzínu** — náklady na 100 km obojím a úspora
- **Typy nabíjení** — FVE, síť, garáž a veřejná nabíječka: množství, cena za
  kWh, podíl na spotřebě a účinnost tam, kde ji lze spočítat
- **Rok proti roku** — všechny roky pod sebou, kWh/100 km, l/100 km a Kč/100 km

Hodnota, kterou z dat nelze spočítat, je pomlčka, nikdy nula.

### Testy

68 unit testů (z 66). Nové pokrývají seznam dostupných měsíců roku a klíčování
období po konkrétním měsíci.

---

## 2026-09-20 (8) — Grafy ve vnitřních záložkách

Beze změny databáze — schéma zůstává na v6.

### Rychlý přehled na záložkách Elektřina a Benzín

Nad čísly je sloupcový graf za období **o úroveň níže, než je zvolený výběr**:

- *Celé období* → sloupce po letech
- *rok* → sloupce po měsících toho roku
- *konkrétní měsíc* → opět měsíce toho roku, aby bylo vidět, jak zvolený měsíc
  vychází proti ostatním; jediný sloupec by neřekl nic

Přepínač nad grafem volí, co se kreslí: **Spotřeba** (kWh/100 km nebo l/100 km),
**Množství** (kWh nebo litry) nebo **Náklady**. Sloupec, jehož hodnotu nelze
z dat spočítat, se vynechá; když nezbude žádný, graf se skryje a objeví se
hláška o chybějících datech.

### Graf v Porovnání

Nad tabulku přibyl graf, který se mění podle režimu:

| Režim | Co graf ukazuje |
|-------|-----------------|
| Dvě období | náklady na 100 km, dva sloupce |
| Elektřina proti benzínu | náklady na 100 km obojím |
| Typy nabíjení | nabité kWh po typech |
| Rok proti roku | náklady na 100 km po letech |

U porovnání dvou období kreslí graf jen náklady na 100 km — ostatní metriky
v tabulce mají různé jednotky (kWh, litry, km, %) a do jednoho grafu je míchat
nelze.

### Sdílené nastavení grafů

Barvy textu podle světlého a tmavého motivu, popisky osy a formátování hodnot
jsou u všech těchto grafů stejné. Jsou proto v `ui/charts/BarChartHelper.kt`,
ne třikrát zkopírované po fragmentech. Helper sám vyřazuje sloupce bez hodnoty
a vrací `false`, když nezbylo co kreslit.
