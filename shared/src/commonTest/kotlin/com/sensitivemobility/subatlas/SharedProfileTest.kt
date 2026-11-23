package com.sensitivemobility.subatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedProfileTest {
    private val site = DiveSite(id = 1, name = "Tobia Arbaa", latitude = 24.9, longitude = 34.9)

    private fun profile(n: Int, interval: Int = 10, withTemp: Boolean = true) = DiveProfile(
        intervalSeconds = interval,
        depthDm = List(n) { (it * 3) % 250 },
        tempDc = if (withTemp) List(n) { 280 - it / 20 } else emptyList(),
    )

    @Test
    fun temperatureSurvivesTheLink() {
        val dive = Dive(id = 1, siteId = 1, date = "2026-11-01", profile = profile(300))
        val shared = DiveLink.decode(DiveLink.webLink(dive, site))!!
        val back = assertNotNull(shared.profile?.toProfile())
        assertEquals(dive.profile!!.depthDm, back.depthDm)
        assertEquals(dive.profile!!.tempDc, back.tempDc)
    }

    @Test
    fun sampledProfileKeepsTemperatureAligned() {
        // 3 000 points à la seconde : sous-échantillonné, la température suit les mêmes points que la profondeur.
        val p = profile(3000, interval = 1)
        val back = p.toShared()!!.toProfile()!!
        assertTrue(back.depthDm.size < 3000)
        assertEquals(back.depthDm.size, back.tempDc.size)
        assertEquals(p.tempDc[0], back.tempDc[0])
        assertEquals(p.tempDc[back.depthDm.lastIndex * 10], back.tempDc.last())
    }

    @Test
    fun noTemperatureMeansNoField() {
        val shared = profile(100, withTemp = false).toShared()!!
        assertNull(shared.t)
        assertTrue(shared.toProfile()!!.tempDc.isEmpty())
    }

    @Test
    fun oldLinkWithoutTemperatureStillReads() {
        val old = SharedProfile(iv = 10, d = profile(50, withTemp = false).toShared()!!.d)
        assertTrue(old.toProfile()!!.tempDc.isEmpty())
    }

    @Test
    fun mismatchedTemperatureIsDroppedNotTheCurve() {
        val ok = profile(50).toShared()!!
        val broken = SharedProfile(ok.iv, ok.d, profile(20).toShared()!!.t)
        val back = assertNotNull(broken.toProfile())
        assertTrue(back.tempDc.isEmpty())
        assertEquals(50, back.depthDm.size)
    }
}
