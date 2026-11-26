package com.sensitivemobility.subatlas

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerArmingTest {
    @Test
    fun aComputerThatStaysInRangeIsImportedOnce() {
        val watch = ComputerArming()
        assertTrue(watch.sighting(true))
        repeat(5) { assertFalse(watch.sighting(true)) }
    }

    @Test
    fun anImportStartedElsewhereIsNotRepeated() {
        // L'association ou le bouton ont déjà importé : le Sirius, toujours annoncé, ne relance rien.
        val watch = ComputerArming()
        watch.imported()
        assertFalse(watch.sighting(true))
    }

    @Test
    fun aNewAppearanceCountsAgain() {
        val watch = ComputerArming()
        watch.imported()
        assertFalse(watch.sighting(false))
        assertTrue(watch.sighting(true))
    }
}
