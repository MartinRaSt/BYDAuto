package com.byd.charging.data

import com.byd.charging.R

enum class ChargingType {
    HOME_FVE,
    HOME_GRID,
    GARAGE,
    PUBLIC,
    GASOLINE;

    fun labelRes(): Int = when (this) {
        HOME_FVE  -> R.string.type_home_fve
        HOME_GRID -> R.string.type_home_grid
        GARAGE    -> R.string.type_garage
        PUBLIC    -> R.string.type_public
        GASOLINE  -> R.string.type_gasoline
    }

    fun colorRes(): Int = when (this) {
        HOME_FVE  -> R.color.type_fve
        HOME_GRID -> R.color.type_grid
        GARAGE    -> R.color.type_garage
        PUBLIC    -> R.color.type_public
        GASOLINE  -> R.color.type_gasoline
    }

    companion object {
        fun fromString(s: String): ChargingType =
            try { valueOf(s) } catch (_: Exception) { HOME_GRID }

        /** Seřazený seznam pro spinner (zachová pořadí výčtu) */
        val all: List<ChargingType> = listOf(HOME_FVE, HOME_GRID, GARAGE, PUBLIC, GASOLINE)
    }
}
