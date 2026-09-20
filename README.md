# BYD Logger

Android aplikace pro evidenci nabíjení a tankování plug-in hybridu BYD.
Sleduje spotřebu elektřiny i benzínu, náklady, stav baterie a z odečtů
tachometru dopočítává reálnou spotřebu a dojezd.

Osobní nástroj v denním provozu, ne produkt pro distribuci.

---

## Co umí

**Evidence záznamů**
Nabíjení doma z FVE i ze sítě, v garáži, na veřejné nabíječce, a tankování
benzínu. U každého záznamu datum, časy, výkon, nabité kWh, cena, stav
elektroměrů, tachometr, GPS poloha místa a stav baterie v procentech.

**Odvozené hodnoty**
Kolik kWh reálně přibylo v baterii, účinnost nabíjení (kWh do baterie / kWh
ze zásuvky), skutečný průměrný výkon, spotřeba v kWh/100 km, odhad dojezdu
na plnou baterii, náklady na 100 km v rozpadu na elektřinu a benzín.

**Přehledy**
Souhrn za aktuální měsíc i celkem, členění podle typu nabíjení, porovnání
podle sezóny (zima vs. léto), grafy vývoje stavu baterie a účinnosti v čase.

Záložka **Grafy** má uvnitř čtyři pohledy — *Total*, *Elektřina*, *Benzín*
a *Porovnání*. U elektřiny i benzínu se dá zvolit **celé období, rok, nebo
konkrétní měsíc** a všechny hodnoty se přepočítají. *Porovnání* postaví proti
sobě dvě období, elektřinu proti benzínu, typy nabíjení nebo jednotlivé roky.
Každá z těchto záložek má nahoře sloupcový graf pro rychlý přehled.

**Zálohy a export**
Záloha do souboru (ZIP s CSV) nebo e-mailem, obnova ze zálohy, odeslání
statistik e-mailem s přiloženým CSV pro tabulkový procesor.

**Ostatní**
Čeština a angličtina, světlý a tmavý motiv, pinch-to-zoom.

---

## Technologie

| Vrstva     | Řešení                                  |
|------------|-----------------------------------------|
| Jazyk      | Kotlin                                  |
| UI         | XML layouty, ViewBinding, Material 1.11 |
| Data       | Room 2.6, LiveData, ViewModel           |
| Grafy      | MPAndroidChart 3.1                      |
| Build      | Gradle 8.4, AGP, JDK 17                 |
| Min. SDK   | 26 (Android 8.0), target 34             |

---

## Struktura

```
app/src/main/java/com/byd/charging/
  data/        Room entita, DAO, repozitář, databáze s migracemi
  ui/main/     MainActivity (ViewPager2), Přehled, seznam, detail,
               EnergyFragment, CompareFragment
  ui/addedit/  formulář záznamu
  ui/charts/   ChartsHostFragment (vnitřní záložky), grafy, BarChartHelper
  ui/settings/ nastavení (jazyk, motiv, kapacita nádrže a baterie)
  util/        EvCalc (výpočty), StatsReport (přehled + CSV statistik),
               CSV export/import, DateUtil, NumberUtil, LocaleHelper
app/schemas/           exportovaná schémata Room (verze 2–6)
app/src/test/          unit testy výpočtů, běží na PC
app/src/androidTest/   migrační testy databáze, vyžadují zařízení
documentation/         changelog, doplňuje se na konec
projectstate.md        stav projektu a zásady
```

---

## Sestavení a testy

```bat
run_tests.bat        :: unit testy + migrační testy (ty jen s připojeným zařízením)
build_release.bat    :: podepsaný AAB
gradlew assembleRelease   :: release APK pro ruční instalaci
```

Testy se dělí na dvě části. Unit testy ověřují výpočty a práci s CSV a běží
kdekoliv. Migrační testy ověřují, že upgrade schématu databáze nepřijde o data,
a potřebují telefon nebo emulátor.

---

## Databáze

Aktuální verze schématu je **6**, definovaná v `DbSchema.VERSION`. Používá ji
anotace `@Database` i dialog *O aplikaci*, takže zobrazená verze nemůže zastarat.

Historie: v2 typ nabíjení a cena, v3 GPS, v4 rozšířené elektroměry,
v5 tachometr, v6 stav baterie v procentech.

### Pravidla, která nelze porušit

**Každá změna schématu musí mít explicitní `Migration`.**
`fallbackToDestructiveMigration()` v projektu záměrně chybí — tiché smazání
dat je nepřijatelné, aplikace běží na telefonu s reálnou historií.

**Debug build má vlastní `applicationId`** (`com.byd.charging.debug`).
Gradle po dokončení `connectedAndroidTest` testovanou aplikaci **odinstaluje**
a odinstalace maže data. Bez odděleného `applicationId` by testy smazaly ostrou
aplikaci i s databází — 20. 9. 2026 se to přesně takhle stalo. Suffix
v `app/build.gradle` se nesmí odstranit.

**Před instalací nové verze na telefon:**
1. V aplikaci uložit zálohu (do souboru nebo e-mailem)
2. `run_tests.bat` s připojeným telefonem — migrační testy musí projít
3. Teprve pak instalovat

---

## Zásady výpočtů

Veškerá odvozená matematika je v `util/EvCalc.kt` a je krytá unit testy.

**Chybějící vstup znamená `null`, ne nulu.** Každá funkce vrací `null`, když
nemá z čeho počítat, a příslušný řádek v UI se skryje. Nikdy se nezobrazí
odhad jako naměřená hodnota.

**Mezi procenty baterie a nabitými kWh se nic nepřepisuje.** Nabité kWh se
měří na zásuvce, procenta hlásí auto z baterie; obě hodnoty se z principu liší
o ztráty nabíjení. Přepsat jednu druhou by tu informaci zahodilo, proto se
odvozené hodnoty pouze zobrazují.

**Vzdálenost za období je součet úseků mezi odečty tachometru**
(`EvCalc.legs`), nikdy rozpětí max − min. U sezóny sbírané přes více let by
rozpětí zahrnulo i kilometry najeté mimo ni. Úsek, který začíná v jednom
období a končí v jiném, se nezapočítá do žádného.

Sezóny, měsíce i roky počítá jedna funkce `EvCalc.statsByPeriod`, které se
předá jen klíčovací funkce. Nové členění se přidává tím, ne kopírováním
výpočtu — jinak by se pravidla časem rozesla.

**Úsek jízdy na baterii mimo 8–60 kWh/100 km se zahazuje.** Pod spodní mezí
jela většinu cesty spalovací jednotka, nad horní jde o chybný odečet. Takový
úsek nepopisuje jízdu na baterii a zkreslil by medián spotřeby i dojezd.

**Nezadaný stav baterie je `-1.0`** (`ChargingSession.SOC_UNSET`), ne nula —
nula je platná hodnota (vybitá baterie).

**Spotřeba elektřiny na 100 km je počítaná přes všechny ujeté kilometry**,
tedy včetně jízdy na benzín. U hybridu nelze elektrické a benzínové km oddělit,
auto to nehlásí.

---

## Verzování

`versionCode` i poslední (patch) část `versionName` se zvyšují automaticky při
každém buildu — viz blok na začátku `app/build.gradle`. Spuštění samotných testů
verzi neposouvá.

**Major a minor se mění ručně** v `app/version.properties`, když přijde větší
změna. Skript do nich nesahne.

## Podepisování — pozor

Release build se podepisuje **debug klíčem**:

```gradle
release {
    signingConfig signingConfigs.debug
}
```

Z toho plynou dvě věci:

**Klíč `~/.android/debug.keystore` je nenahraditelný.** Aplikace nainstalovaná
na telefonu je podepsaná právě jím. Když se ten soubor ztratí, nepůjde
nainstalovat žádná aktualizace — jedinou cestou by byla odinstalace, tedy
ztráta dat. Zálohovat mimo repozitář, do gitu nepatří.

**Na Google Play to takhle nahrát nejde.** Play odmítá balíčky podepsané debug
certifikátem, takže AAB z `build_release.bat` by neprošel, přestože skript
v nápovědě popisuje postup nahrání. Pro publikování by bylo potřeba přepnout na
skutečný release klíč — a tím by se rozešly podpisy s aplikací na telefonu,
která by se musela odinstalovat. Dokud aplikace slouží jen autorovi a instaluje
se ručně, je současný stav v pořádku.

Blok `signingConfigs.release` v `app/build.gradle` a práce s keystorem
v `build_release.bat` se kvůli výše uvedenému vůbec neuplatní.

---

## Zálohy dat

Aplikace nabízí dvě cesty ke stejnému ZIP se zálohou: **Uložit zálohu do
souboru** (úložiště, Disk Google) a **Záloha e-mailem**. Obnova přes
**Obnovit ze zálohy → Nahradit vše**.

Android Auto Backup je na cílovém zařízení vypnutý, cloudová kopie dat tedy
neexistuje. Ruční záloha je jediná záchrana.

V e-mailu chodí dva různé ZIPy a nesmí se zaměnit:

| Soubor v ZIP                | Z čeho             | Lze obnovit? |
|-----------------------------|--------------------|--------------|
| `byd_logger_nabijeni.csv`   | Záloha             | ano          |
| `byd_logger_statistiky.csv` | Odeslat statistiky | ne           |

Statistiky jsou odvozená čísla, ne data. Importér takový soubor odmítne
a zobrazí hlášku; kryto testem `CsvRoundTripTest`.

---

## Historie změn

`documentation/zmeny.md` — doplňuje se na konec, nejnovější změna je dole.

---

## Sestavení ze zdrojů

```bash
git clone https://github.com/MartinRaSt/BYDAuto.git
cd BYDAuto
```

Pak ještě vytvořit `local.properties` s cestou k Android SDK (v repozitáři
záměrně není, je specifický pro každý počítač):

```properties
sdk.dir=C:\Users\<jméno>\AppData\Local\Android\Sdk
```

Sestavení: `gradlew assembleRelease`, testy: `gradlew testDebugUnitTest`.

---

## Práce na více počítačích

Do repozitáře nepatří nic, co je vázané na konkrétní počítač ani co je tajné.
Dvě takové věci projekt má a na novém stroji se dořeší takto:

### 1. `local.properties` — cesta k Android SDK

Vytvoří ho Android Studio sámo při prvním otevření projektu. Ručně:

```properties
sdk.dir=C:\\Users\\<jméno>\\AppData\\Local\\Android\\Sdk
```

### 2. Podpisový klíč — musí být na obou počítačích stejný

Tohle je to podstatné. Android dovolí nainstalovat aktualizaci přes už
nainstalovanou aplikaci **jen když mají shodný podpis**. Jinak zůstává jediná
cesta odinstalovat — a tím přijít o všechna data.

Každý počítač si přitom při prvním buildu vygeneruje **vlastní** debug klíč.
Kdyby se projekt jen naklonoval a sestavil, vznikl by jinak podepsaný balíček,
který na telefon nepůjde nainstalovat.

**Řešení: přenést klíč ze starého počítače na nový.**

```
zdroj:  %USERPROFILE%\.android\debug.keystore
cíl:    %USERPROFILE%\.android\debug.keystore   (na novém počítači přepsat)
```

Přenést přes USB disk nebo správce hesel, **ne přes git**. Heslo keystoru
i klíče je `android`, alias `androiddebugkey` — to jsou výchozí hodnoty Androidu,
proto tento klíč není bezpečnostní opatření, ale jen způsob, jak udržet
instalace kompatibilní.

Po přepisu klíče stačí běžný `gradlew assembleRelease` a výsledné APK půjde
nainstalovat přes stávající aplikaci.

### 3. Vlastní release klíč (volitelné, nutné pro Google Play)

Když se v `local.properties` vyplní `keystore.path`, použije se místo debug
klíče vlastní release keystore:

```properties
keystore.path=keystore/release.keystore
keystore.alias=byd-logger
keystore.store.password=...
keystore.key.password=...
```

Klíč ani hesla se do repozitáře nedostanou — `local.properties` i `keystore/`
jsou v `.gitignore`. Na dalším počítači se soubor klíče i tyto řádky musí
doplňovat ručně, stejně jako u debug klíče.

**Pozor:** přechod z debug klíče na vlastní změní podpis aplikace. Tu, která je
na telefonu, pak půjde nahradit jen přes odinstalaci — před tím si uložit zálohu
a po instalaci ji obnovit.

### Shrnutí pro nový počítač

```bash
git clone https://github.com/MartinRaSt/BYDAuto.git
cd BYDAuto
# otevřít v Android Studiu (vytvoří local.properties)
# překopírovat debug.keystore ze starého počítače do %USERPROFILE%\.android\
gradlew testDebugUnitTest
gradlew assembleRelease
```

Změny se přenášejí přes `git pull` a `git push` jako obvykle. `version.properties`
je v repozitáři, takže se číslo verze zvyšuje dál ze společného stavu — pokud
se bude buildovat na obou strojích, je nutné před buildem `git pull`, jinak
vznikne konflikt v tomto souboru.

---

## Licence

[MIT](LICENSE) — Copyright (c) 2026 Martin Radvanský

Software je poskytován „jak stojí a leží", bez záruky. Jde o osobní nástroj,
ne produkt: výpočty spotřeby a dojezdu vycházejí z ručně zadávaných údajů
a předpokladů popsaných výše.
