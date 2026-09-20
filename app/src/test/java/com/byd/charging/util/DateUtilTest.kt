package com.byd.charging.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Testy prace s casy - hlavne dopoctu doby trvani a hledani duplicit. */
class DateUtilTest {

    @Test
    fun `calculateDuration resi prechod pres pulnoc`() {
        assertEquals(120, DateUtil.calculateDuration("08:00", "10:00"))
        assertEquals(240, DateUtil.calculateDuration("23:00", "03:00"))
        assertEquals(0, DateUtil.calculateDuration("08:00", "08:00"))
    }

    @Test
    fun `addMinutes a subtractMinutes jsou navzajem inverzni`() {
        assertEquals("10:30", DateUtil.addMinutes("08:00", 150))
        assertEquals("08:00", DateUtil.subtractMinutes("10:30", 150))
        // Pres pulnoc
        assertEquals("01:00", DateUtil.addMinutes("23:00", 120))
        assertEquals("23:00", DateUtil.subtractMinutes("01:00", 120))
    }

    @Test
    fun `absMinutesDiff je symetricky`() {
        assertEquals(5, DateUtil.absMinutesDiff("10:00", "10:05"))
        assertEquals(5, DateUtil.absMinutesDiff("10:05", "10:00"))
    }

    @Test
    fun `absMinutesDiff bere kratsi cestu pres pulnoc`() {
        // 23:55 a 00:05 jsou od sebe 10 minut, ne 23 hodin a 50 minut
        assertEquals(10, DateUtil.absMinutesDiff("23:55", "00:05"))
        assertEquals(10, DateUtil.absMinutesDiff("00:05", "23:55"))
    }

    @Test
    fun `absMinutesDiff vraci maximum pri neplatnem vstupu`() {
        assertEquals(Int.MAX_VALUE, DateUtil.absMinutesDiff("nesmysl", "10:00"))
    }

    @Test
    fun `parseDuration cte HHmm i ciste minuty`() {
        assertEquals(150, DateUtil.parseDuration("02:30"))
        assertEquals(45, DateUtil.parseDuration("45"))
        assertEquals(null, DateUtil.parseDuration("nesmysl"))
    }

    @Test
    fun `prevody mezi ulozenym a zobrazovanym datem`() {
        assertEquals("15.03.2026", DateUtil.toDisplayDate("2026-03-15"))
        assertEquals("2026-03-15", DateUtil.toStorageDate("15.03.2026"))
        // Neplatny vstup se vraci beze zmeny, at se data neztichu neprepisou
        assertEquals("nesmysl", DateUtil.toDisplayDate("nesmysl"))
    }
}
