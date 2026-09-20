package com.byd.charging.data

/**
 * Verze schematu databaze na jednom miste.
 *
 * Pouziva ji anotace @Database i dialog "O aplikaci", takze zobrazena verze
 * nemuze zastarat. Pri zvyseni cisla MUSI pribyt odpovidajici Migration
 * v ChargingDatabase - destruktivni fallback je v projektu zakazany.
 */
object DbSchema {
    const val VERSION = 6
}
