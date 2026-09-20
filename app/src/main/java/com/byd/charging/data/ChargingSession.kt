package com.byd.charging.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "charging_sessions")
data class ChargingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String,           // yyyy-MM-dd (pro řazení v DB)
    val startTime: String,      // HH:MM
    val endTime: String,        // HH:MM
    val powerKw: Double,
    val startMeterKwh: Double,  // stav elektroměru auta na začátku
    val chargedKwh: Double,     // nabité kWh
    val mainMeterKwh: Double,   // stav hlavního elektroměru
    val note: String = "",

    // --- v2: typ nabíjení a cena ---
    val chargingType: String = ChargingType.HOME_GRID.name,
    val pricePerKwh: Double = 0.0,
    val locationName: String = "",

    // --- v3: GPS souřadnice (0.0 = nezadáno) ---
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,

    // --- v4: Rozšířené stavy elektroměrů ---
    val mainMeterEndKwh: Double = 0.0,
    val garageMeterStartKwh: Double = 0.0,
    val garageMeterEndKwh: Double = 0.0,

    // --- v5: Tachometr ---
    val odometer: Double = 0.0,

    // --- v6: Stav baterie v procentech (SOC_UNSET = nezadano) ---
    val socStart: Double = SOC_UNSET,
    val socEnd: Double = SOC_UNSET
) {
    /** Celkové náklady (vypočítáno) */
    val totalCost: Double get() = chargedKwh * pricePerKwh

    val type: ChargingType get() = ChargingType.fromString(chargingType)

    /** True pokud jsou uloženy platné GPS souřadnice */
    val hasGpsCoordinates: Boolean get() = latitude != 0.0 || longitude != 0.0

    /** True pokud je zadany pocatecni stav baterie */
    val hasSocStart: Boolean get() = socStart >= 0.0

    /** True pokud je zadany koncovy stav baterie */
    val hasSocEnd: Boolean get() = socEnd >= 0.0

    /** True pokud jsou zadany oba stavy baterie a konec je vetsi nez zacatek */
    val hasSocRange: Boolean get() = hasSocStart && hasSocEnd && socEnd > socStart

    /** Prirustek nabiti v procentnich bodech, nebo null pokud nelze urcit */
    val socDelta: Double? get() = if (hasSocRange) socEnd - socStart else null

    companion object {
        /** Hodnota ve sloupcich socStart/socEnd znamenajici "uzivatel nezadal". */
        const val SOC_UNSET = -1.0
    }
}
