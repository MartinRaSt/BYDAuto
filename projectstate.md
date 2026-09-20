# Stav projektu — BYD Logger

Poslední aktualizace: 2026-09-20

## Co to je

Android aplikace pro evidenci nabíjení a tankování plug-in hybridu BYD.
Kotlin, XML layouty, Room + LiveData + ViewModel, MPAndroidChart.
minSdk 26, targetSdk 34. Aplikace je v denním provozu na telefonu autora.

## Stav

Funkční, používaná. Databáze na telefonu je naplněna reálnými daty — každá
změna schématu proto musí mít explicitní `Migration`; `fallbackToDestructiveMigration`
v projektu záměrně chybí.

**Databáze: verze 6.** Migrace 1→2→3→4→5→6, všechny explicitní.
Exportovaná schémata v `app/schemas/` (od verze 2; v1 export ještě neběžel).

## Struktura

```
app/src/main/java/com/byd/charging/
  data/      Room entita, DAO, repozitář, databáze s migracemi
  ui/main/   MainActivity (ViewPager2), Dashboard, seznam, detail
  ui/addedit/ formulář záznamu
  ui/charts/ grafy
  ui/settings/ nastavení (jazyk, téma, kapacita nádrže a baterie)
  util/      EvCalc (výpočty), StatsReport (přehled + CSV statistik),
             CSV export/import, DateUtil, NumberUtil, LocaleHelper
app/src/test/        unit testy výpočtů (běží na PC)
app/src/androidTest/ migrační testy databáze (vyžadují zařízení)
documentation/       changelog, doplňuje se na konec
```

## Skripty

| Skript             | Co dělá                                                    |
|--------------------|------------------------------------------------------------|
| `run_tests.bat`    | Unit testy + migrační testy (ty jen s připojeným zařízením) |
| `build_release.bat`| Podepsaný AAB pro Google Play                               |

## Zásady

- Každé číslo v UI pochází z výpočtu v `EvCalc`, který vrací `null`, když
  chybí vstup. Řádek se pak skryje — nikdy se nezobrazí odhad jako naměřená hodnota.
- Mezi procenty baterie a nabitými kWh se nic automaticky nepřepisuje; jsou to
  dvě nezávislá měření lišící se o ztráty nabíjení.
- Nezadaný stav baterie = `-1.0` (`ChargingSession.SOC_UNSET`), ne nula.
- CSV import musí zvládnout starší zálohy s méně sloupci.
- Vzdálenost za období se počítá jako součet úseků mezi odečty tachometru
  (`EvCalc.legs`), nikdy jako rozpětí max − min — to by u sezóny sbírané přes
  více let zahrnulo kilometry najeté mimo ni.
- Úsek, který začíná v jednom období a končí v jiném, se nezapočítá do žádného.
- Úsek jízdy na baterii mimo 8–60 kWh/100 km se zahazuje — nepopisuje jízdu
  na baterii a zkreslil by medián spotřeby i dojezd.

## Zálohy

Aplikace nabízí dvě cesty ke stejnému ZIP se zálohou: **Uložit zálohu do souboru**
(SAF — úložiště, Disk Google) a **Záloha e-mailem**. Obnova přes **Obnovit ze
zálohy → Nahradit vše**.

Auto Backup je na autorově telefonu vypnutý, cloudová kopie dat tedy neexistuje.
Ruční záloha je jediná záchrana — viz incident 2026-09-20 v changelogu.

## Před instalací nové verze na telefon

1. Export CSV z telefonu jako záloha.
2. `run_tests.bat` s připojeným telefonem — migrační testy musí projít.
3. `build_release.bat`, instalace.

## Co dál (nápady, nezadáno)

- Widget nebo zkratka pro rychlé přidání záznamu z plochy
- Sledování degradace baterie z dlouhodobého trendu účinnosti a dojezdu
- Srovnání stejné sezóny mezi roky (zima 2025 vs. zima 2026), ne jen souhrn
- Automatické měsíční odeslání statistik bez ručního spuštění
